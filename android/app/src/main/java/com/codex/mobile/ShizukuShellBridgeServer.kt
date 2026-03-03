package com.codex.mobile

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.time.Instant
import org.json.JSONObject

class ShizukuShellBridgeServer(
    private val context: Context,
    port: Int = BRIDGE_PORT,
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val TAG = "ShizukuBridgeServer"
        const val BRIDGE_PORT = 18926
    }

    @Volatile
    private var lastErrorCode: String? = null

    @Volatile
    private var lastErrorMessage: String? = null

    init {
        persistStatusSnapshot(currentStatusPayload())
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/status" -> handleStatus()
                session.method == Method.POST && session.uri == "/enable" -> handleEnable(true)
                session.method == Method.POST && session.uri == "/disable" -> handleEnable(false)
                session.method == Method.POST && session.uri == "/exec" -> handleExec(session)
                else -> jsonResponse(
                    Response.Status.NOT_FOUND,
                    JSONObject().put("ok", false).put("error", "Not found"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve failed", e)
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("ok", false).put("error", e.message ?: "Internal error"),
            )
        }
    }

    private fun handleStatus(): Response {
        val body = currentStatusPayload()
        persistStatusSnapshot(body)
        return jsonResponse(Response.Status.OK, body)
    }

    private fun handleEnable(enabled: Boolean): Response {
        ShizukuController.setBridgeEnabled(context, enabled)
        if (!enabled) {
            setLastError("bridge_disabled", "Shizuku bridge is disabled in permission center")
        } else {
            clearLastError()
        }
        val body = currentStatusPayload().put("enabled", enabled)
        persistStatusSnapshot(body)
        return jsonResponse(Response.Status.OK, body)
    }

    private fun handleExec(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: ""
        val payload = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val command = payload.optString("command", "").trim()
        if (command.isEmpty()) {
            setLastError("invalid_command", "Missing command")
            val body = currentStatusPayload()
                .put("ok", false)
                .put("error_code", "invalid_command")
                .put("error", "Missing command")
            persistStatusSnapshot(currentStatusPayload())
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                body,
            )
        }

        if (!ShizukuController.isBridgeEnabled(context)) {
            setLastError("bridge_disabled", "Shizuku bridge is disabled in permission center")
            val body = currentStatusPayload()
                .put("ok", false)
                .put("error_code", "bridge_disabled")
                .put("error", "Shizuku bridge is disabled in permission center")
            persistStatusSnapshot(currentStatusPayload())
            return jsonResponse(
                Response.Status.FORBIDDEN,
                body,
            )
        }

        val result = ShizukuController.executeShellCommand(command)
        if (result.success) {
            clearLastError()
        } else {
            setLastError(
                result.errorCode ?: "executor_missing",
                result.error ?: "Command execution failed",
            )
        }

        val body = JSONObject()
            .put("ok", result.success)
            .put("success", result.success)
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("error_code", result.errorCode ?: JSONObject.NULL)

        if (result.error != null) {
            body.put("error", result.error)
        }

        persistStatusSnapshot(currentStatusPayload())
        return jsonResponse(Response.Status.OK, body)
    }

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString())
    }

    private fun currentStatusPayload(): JSONObject {
        val installed = ShizukuController.isShizukuAppInstalled(context)
        val running = ShizukuController.isServiceRunning()
        val granted = ShizukuController.hasPermission()
        val enabled = ShizukuController.isBridgeEnabled(context)
        return JSONObject()
            .put("ok", true)
            .put("installed", installed)
            .put("running", running)
            .put("granted", granted)
            .put("enabled", enabled)
            .put("executor", "system-shell")
            .put("bridge_port", BRIDGE_PORT)
            .put("last_error_code", lastErrorCode ?: JSONObject.NULL)
            .put("last_error", lastErrorMessage ?: JSONObject.NULL)
            .put("checked_at", Instant.now().toString())
    }

    private fun persistStatusSnapshot(payload: JSONObject) {
        try {
            val paths = BootstrapInstaller.getPaths(context)
            val statusFile = File(paths.homeDir, ".openclaw-android/capabilities/shizuku.json")
            statusFile.parentFile?.mkdirs()
            statusFile.writeText(payload.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing Shizuku status snapshot: ${e.message}")
        }
    }

    private fun setLastError(code: String, message: String) {
        lastErrorCode = code
        lastErrorMessage = message
    }

    private fun clearLastError() {
        lastErrorCode = null
        lastErrorMessage = null
    }
}
