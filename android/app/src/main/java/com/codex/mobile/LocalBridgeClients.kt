package com.codex.mobile

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object LocalBridgeClients {
    private const val TAG = "LocalBridgeClients"

    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        throw IllegalStateException("No JSON payload found")
    }

    fun callCodexRpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject()
            .put("method", method)
            .put("params", params)
            .toString()

        val serverPort = CodexServerManager.serverPortForPackage(BuildConfig.APPLICATION_ID)
        val url = URL("http://127.0.0.1:$serverPort/codex-api/rpc")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        conn.outputStream.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
        }

        val responseCode = conn.responseCode
        val rawResponse = try {
            val input = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            input?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }

        if (responseCode !in 200..299) {
            throw IllegalStateException("RPC HTTP $responseCode: $rawResponse")
        }

        val envelope = JSONObject(rawResponse)
        if (!envelope.has("result")) {
            throw IllegalStateException("Malformed RPC envelope: $rawResponse")
        }
        return envelope.getJSONObject("result")
    }

    class OpenClawGateway(private val serverManager: CodexServerManager) {
        fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
            val command =
                "openclaw gateway call $method --json --params ${shellQuote(params.toString())} 2>&1"
            val output = StringBuilder()
            val code = serverManager.runInPrefix(command) { line ->
                output.appendLine(line)
            }
            val raw = output.toString().trim()
            if (code != 0) {
                throw IllegalStateException(raw.ifEmpty { "Gateway call failed with code $code" })
            }

            return try {
                val json = JSONObject(extractJsonPayload(raw))
                if (json.has("error")) {
                    throw IllegalStateException(json.optString("error"))
                }
                json
            } catch (error: Exception) {
                Log.e(TAG, "Failed parsing gateway response for $method: $raw")
                throw error
            }
        }
    }
}
