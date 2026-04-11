package com.codex.mobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class OpenClawChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_KEY = "com.codex.mobile.extra.OPENCLAW_SESSION_KEY"
    }

    private data class DisplayMessage(
        val role: String,
        val text: String,
    )

    private lateinit var tvSession: TextView
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnRename: Button
    private lateinit var btnNewSession: Button
    private lateinit var btnReset: Button
    private lateinit var btnHeartbeat: Button
    private lateinit var btnAbort: Button
    private lateinit var btnTabOpenClaw: Button
    private lateinit var btnTabCodex: Button
    private lateinit var btnTabClaude: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val adapter = MessageAdapter()
    private val messages = mutableListOf<DisplayMessage>()
    private val sessionTitleByKey = linkedMapOf<String, String>()

    @Volatile
    private var destroyed = false
    @Volatile
    private var refreshInFlight = false
    @Volatile
    private var waitInFlight = false
    @Volatile
    private var sendingInFlight = false

    private var pollRunnable: Runnable? = null
    private var pollTick = 0
    private var sessionKey: String = ""
    private var sessionTitle: String = ""
    private var pendingRunId: String = ""
    private var pendingRunStatus: String = ""
    private var recoverDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_openclaw_chat)

        tvSession = findViewById(R.id.tvOpenClawSession)
        tvStatus = findViewById(R.id.tvOpenClawStatus)
        listView = findViewById(R.id.listOpenClawMessages)
        inputMessage = findViewById(R.id.etOpenClawInput)
        btnSend = findViewById(R.id.btnOpenClawSend)
        btnRefresh = findViewById(R.id.btnOpenClawRefresh)
        btnRename = findViewById(R.id.btnOpenClawRename)
        btnNewSession = findViewById(R.id.btnOpenClawNewSession)
        btnReset = findViewById(R.id.btnOpenClawReset)
        btnHeartbeat = findViewById(R.id.btnOpenClawHeartbeat)
        btnAbort = findViewById(R.id.btnOpenClawAbort)
        btnTabOpenClaw = findViewById(R.id.btnOpenClawTabOpenClaw)
        btnTabCodex = findViewById(R.id.btnOpenClawTabCodex)
        btnTabClaude = findViewById(R.id.btnOpenClawTabClaude)

        listView.adapter = adapter

        btnSend.setOnClickListener { onSendPressed() }
        btnRefresh.setOnClickListener { refreshNow(showErrors = true) }
        btnRename.setOnClickListener { onRenamePressed() }
        btnNewSession.setOnClickListener { onCreateSessionPressed() }
        btnReset.setOnClickListener { onResetSessionPressed() }
        btnHeartbeat.setOnClickListener { onHeartbeatPressed() }
        btnAbort.setOnClickListener { onAbortPressed() }

        btnTabOpenClaw.isEnabled = false
        btnTabCodex.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_TARGET, MainActivity.OPEN_TARGET_CODEX_HOME)
                },
            )
            finish()
        }
        btnTabClaude.setOnClickListener {
            startActivity(
                Intent(this, CliAgentChatActivity::class.java).apply {
                    putExtra(CliAgentChatActivity.EXTRA_AGENT_ID, ExternalAgentId.CLAUDE_CODE.value)
                },
            )
            finish()
        }

        renderUi()
    }

    override fun onResume() {
        super.onResume()
        val preferredSession = intent.getStringExtra(EXTRA_SESSION_KEY)?.trim().orEmpty()
        ensureSessionReady(preferredSession.ifEmpty { null })
        startPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        stopPolling()
    }

    private fun startPolling() {
        if (pollRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                if (destroyed) return
                if (!refreshInFlight) {
                    refreshNow(showErrors = false)
                }
                if (pendingRunId.isNotBlank() && !waitInFlight) {
                    waitPendingRun()
                }
                pollTick += 1
                val delayMs = if (pendingRunId.isNotBlank()) 2500L else 5000L
                uiHandler.postDelayed(this, delayMs)
            }
        }
        pollRunnable = runnable
        uiHandler.post(runnable)
    }

    private fun stopPolling() {
        val runnable = pollRunnable ?: return
        uiHandler.removeCallbacks(runnable)
        pollRunnable = null
    }

    private fun ensureSessionReady(preferredSession: String?) {
        if (refreshInFlight) return
        refreshInFlight = true
        Thread {
            try {
                val sessions = fetchSessions(limit = 200)
                if (sessions.isNotEmpty()) {
                    sessionTitleByKey.clear()
                    sessions.forEach { row -> sessionTitleByKey[row.first] = row.second }
                }

                val resolved = when {
                    !preferredSession.isNullOrBlank() -> preferredSession
                    sessionKey.isNotBlank() -> sessionKey
                    sessions.isNotEmpty() -> sessions.first().first
                    else -> createIndependentSession(currentSessionKey = null)
                }
                sessionKey = resolved
                sessionTitle = sessionTitleByKey[resolved].orEmpty().ifBlank { getString(R.string.openclaw_native_session_unknown) }
                refreshHistoryInternal(limit = 120)
                refreshSessionTitle()
                if (pendingRunId.isNotBlank()) {
                    waitPendingRun()
                }
            } catch (error: Exception) {
                val message = getErrorMessage(error)
                postErrorToast(getString(R.string.openclaw_native_refresh_failed) + message)
                maybePromptServerRecovery(message)
            } finally {
                refreshInFlight = false
                postRender()
            }
        }.start()
    }

    private fun refreshNow(showErrors: Boolean) {
        if (sessionKey.isBlank()) {
            ensureSessionReady(preferredSession = intent.getStringExtra(EXTRA_SESSION_KEY)?.trim())
            return
        }
        if (refreshInFlight) return
        refreshInFlight = true
        Thread {
            try {
                if (pollTick % 4 == 0) {
                    val sessions = fetchSessions(limit = 200)
                    sessionTitleByKey.clear()
                    sessions.forEach { row -> sessionTitleByKey[row.first] = row.second }
                    refreshSessionTitle()
                }
                refreshHistoryInternal(limit = 120)
            } catch (error: Exception) {
                if (showErrors) {
                    postErrorToast(getString(R.string.openclaw_native_refresh_failed) + getErrorMessage(error))
                }
            } finally {
                refreshInFlight = false
                postRender()
            }
        }.start()
    }

    private fun refreshSessionTitle() {
        sessionTitle = sessionTitleByKey[sessionKey].orEmpty().ifBlank { getString(R.string.openclaw_native_session_unknown) }
    }

    private fun onSendPressed() {
        val text = inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.openclaw_native_send_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (sessionKey.isBlank() || sendingInFlight) return

        sendingInFlight = true
        appendLocalMessage("您", text)
        inputMessage.setText("")
        renderUi()

        Thread {
            try {
                val response = LocalBridgeClients.callOpenClawApi(
                    path = "/openclaw-api/send",
                    method = "POST",
                    body = JSONObject()
                        .put("sessionKey", sessionKey)
                        .put("message", text)
                        .put("deliver", true),
                    readTimeoutMs = 280_000,
                )
                val runId = response.optString("runId", "").trim()
                if (runId.isNotBlank()) {
                    pendingRunId = runId
                    pendingRunStatus = "submitted"
                }
                refreshHistoryInternal(limit = 120)
            } catch (error: Exception) {
                postErrorToast(getString(R.string.openclaw_native_send_failed) + getErrorMessage(error))
            } finally {
                sendingInFlight = false
                postRender()
            }
        }.start()
    }

    private fun onCreateSessionPressed() {
        if (sendingInFlight || refreshInFlight) return
        refreshInFlight = true
        Thread {
            try {
                val created = createIndependentSession(sessionKey.ifBlank { null })
                sessionKey = created
                pendingRunId = ""
                pendingRunStatus = ""
                val sessions = fetchSessions(limit = 200)
                sessionTitleByKey.clear()
                sessions.forEach { row -> sessionTitleByKey[row.first] = row.second }
                refreshSessionTitle()
                refreshHistoryInternal(limit = 80)
            } catch (error: Exception) {
                postErrorToast(getString(R.string.openclaw_native_create_failed) + getErrorMessage(error))
            } finally {
                refreshInFlight = false
                postRender()
            }
        }.start()
    }

    private fun onResetSessionPressed() {
        if (sessionKey.isBlank() || sendingInFlight || refreshInFlight) return
        refreshInFlight = true
        Thread {
            try {
                val response = LocalBridgeClients.callOpenClawApi(
                    path = "/openclaw-api/sessions/reset",
                    method = "POST",
                    body = JSONObject().put("currentSessionKey", sessionKey),
                    readTimeoutMs = 60_000,
                )
                val next = response.optString("sessionKey", "").trim()
                if (next.isNotBlank()) {
                    sessionKey = next
                }
                pendingRunId = ""
                pendingRunStatus = ""
                val sessions = fetchSessions(limit = 200)
                sessionTitleByKey.clear()
                sessions.forEach { row -> sessionTitleByKey[row.first] = row.second }
                refreshSessionTitle()
                refreshHistoryInternal(limit = 80)
            } catch (error: Exception) {
                postErrorToast(getString(R.string.openclaw_native_reset_failed) + getErrorMessage(error))
            } finally {
                refreshInFlight = false
                postRender()
            }
        }.start()
    }

    private fun onRenamePressed() {
        if (sessionKey.isBlank()) return
        val input = EditText(this).apply {
            setText(sessionTitle.ifBlank { sessionKey })
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.openclaw_native_rename_title))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val nextTitle = input.text?.toString()?.trim().orEmpty()
                if (nextTitle.isBlank()) return@setPositiveButton
                Thread {
                    try {
                        LocalBridgeClients.callOpenClawApi(
                            path = "/openclaw-api/sessions/rename",
                            method = "POST",
                            body = JSONObject()
                                .put("sessionKey", sessionKey)
                                .put("label", nextTitle),
                            readTimeoutMs = 30_000,
                        )
                        sessionTitleByKey[sessionKey] = nextTitle
                        refreshSessionTitle()
                        postRender()
                    } catch (error: Exception) {
                        postErrorToast(getString(R.string.openclaw_native_rename_failed) + getErrorMessage(error))
                    }
                }.start()
            }
            .show()
    }

    private fun onHeartbeatPressed() {
        if (sessionKey.isBlank() || sendingInFlight) return
        Thread {
            try {
                val response = LocalBridgeClients.callOpenClawApi(
                    path = "/openclaw-api/heartbeat/trigger",
                    method = "POST",
                    body = JSONObject().put("sessionKey", sessionKey),
                    readTimeoutMs = 45_000,
                )
                val runId = response.optString("runId", "").trim()
                if (runId.isNotBlank()) {
                    pendingRunId = runId
                    pendingRunStatus = response.optString("status", "running").trim().ifBlank { "running" }
                    waitPendingRun()
                }
                postRender()
            } catch (error: Exception) {
                postErrorToast(getString(R.string.openclaw_native_heartbeat_failed) + getErrorMessage(error))
            }
        }.start()
    }

    private fun onAbortPressed() {
        if (sessionKey.isBlank() || pendingRunId.isBlank()) return
        Thread {
            try {
                LocalBridgeClients.callOpenClawApi(
                    path = "/openclaw-api/run/abort",
                    method = "POST",
                    body = JSONObject()
                        .put("sessionKey", sessionKey)
                        .put("runId", pendingRunId),
                    readTimeoutMs = 30_000,
                )
                pendingRunId = ""
                pendingRunStatus = "aborted"
                refreshHistoryInternal(limit = 120)
                postRender()
            } catch (error: Exception) {
                postErrorToast(getString(R.string.openclaw_native_abort_failed) + getErrorMessage(error))
            }
        }.start()
    }

    private fun waitPendingRun() {
        val runId = pendingRunId.trim()
        if (runId.isBlank() || waitInFlight) return
        waitInFlight = true
        Thread {
            try {
                val response = LocalBridgeClients.callOpenClawApi(
                    path = "/openclaw-api/run/wait",
                    method = "POST",
                    body = JSONObject()
                        .put("runId", runId)
                        .put("timeoutMs", 12_000),
                    readTimeoutMs = 40_000,
                )
                val status = response.optString("status", "running").trim().ifBlank { "running" }
                val completed = response.optBoolean("completed", false)
                pendingRunStatus = status
                if (completed) {
                    pendingRunId = ""
                    refreshHistoryInternal(limit = 120)
                }
            } catch (_: Exception) {
                pendingRunStatus = "reconnecting"
            } finally {
                waitInFlight = false
                postRender()
            }
        }.start()
    }

    private fun fetchSessions(limit: Int): List<Pair<String, String>> {
        val response = LocalBridgeClients.callOpenClawApi(
            path = "/openclaw-api/sessions?limit=$limit",
            method = "GET",
            body = null,
            readTimeoutMs = 40_000,
        )
        val sessions = response.optJSONArray("sessions") ?: JSONArray()
        val output = mutableListOf<Pair<String, String>>()
        for (index in 0 until sessions.length()) {
            val row = sessions.optJSONObject(index) ?: continue
            val key = row.optString("key", "").trim()
            if (key.isBlank()) continue
            val title = row.optString("displayName", "").trim()
                .ifBlank { row.optString("label", "").trim() }
                .ifBlank { key }
            output += key to title
        }
        return output
    }

    private fun createIndependentSession(currentSessionKey: String?): String {
        val payload = JSONObject()
            .put("label", "")
            .put("currentSessionKey", currentSessionKey.orEmpty())
        val response = LocalBridgeClients.callOpenClawApi(
            path = "/openclaw-api/sessions/new-independent",
            method = "POST",
            body = payload,
            readTimeoutMs = 60_000,
        )
        return response.optString("sessionKey", "").trim()
            .ifBlank { throw IllegalStateException("missing sessionKey") }
    }

    private fun refreshHistoryInternal(limit: Int) {
        if (sessionKey.isBlank()) return
        val response = LocalBridgeClients.callOpenClawApi(
            path = "/openclaw-api/history",
            method = "POST",
            body = JSONObject()
                .put("sessionKey", sessionKey)
                .put("limit", limit),
            readTimeoutMs = 80_000,
        )
        val history = response.optJSONArray("messages") ?: JSONArray()
        val next = mutableListOf<DisplayMessage>()
        for (index in 0 until history.length()) {
            val row = history.optJSONObject(index) ?: continue
            val role = row.optString("role", "").trim()
            val content = row.optJSONArray("content")
            val text = extractTextContent(content)
            if (role == "user") {
                if (text.isNotBlank()) {
                    next += DisplayMessage(role = "您", text = text)
                }
                continue
            }
            if (role == "assistant") {
                if (text.isNotBlank()) {
                    next += DisplayMessage(role = "OpenClaw", text = text)
                }
                val processLines = extractProcessContent(content)
                processLines.forEach { line ->
                    next += DisplayMessage(role = getString(R.string.cli_process_role), text = line)
                }
                continue
            }
            if (role.equals("toolResult", ignoreCase = true) || role.equals("toolUse", ignoreCase = true)) {
                val fallback = text.ifBlank { row.toString() }
                next += DisplayMessage(role = getString(R.string.cli_process_role), text = fallback)
            }
        }
        if (next.isEmpty()) {
            next += DisplayMessage(role = "OpenClaw", text = getString(R.string.openclaw_native_history_empty))
        }
        messages.clear()
        messages.addAll(next)
    }

    private fun extractTextContent(content: JSONArray?): String {
        if (content == null) return ""
        val rows = mutableListOf<String>()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            val type = item.optString("type", "").trim()
            when (type) {
                "text" -> {
                    val text = item.optString("text", "").trim()
                    if (text.isNotBlank()) rows += text
                }
                "image", "image_url" -> rows += "[图片]"
            }
        }
        return rows.joinToString("\n\n").trim()
    }

    private fun extractProcessContent(content: JSONArray?): List<String> {
        if (content == null) return emptyList()
        val rows = mutableListOf<String>()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            val type = item.optString("type", "").trim()
            when (type) {
                "thinking" -> {
                    val text = item.optString("thinking", "").trim()
                    if (text.isNotBlank()) rows += "思考: $text"
                }
                "tool_use" -> {
                    val name = item.optString("name", "").trim().ifBlank { "unknown_tool" }
                    rows += "工具调用: $name"
                }
                "tool_result" -> {
                    val text = item.optString("text", "").trim()
                    rows += if (text.isBlank()) "工具结果已返回" else "工具结果: $text"
                }
            }
        }
        return rows
    }

    private fun appendLocalMessage(role: String, text: String) {
        messages += DisplayMessage(role = role, text = text)
        postRender()
    }

    private fun postRender() {
        runOnUiThread { renderUi() }
    }

    private fun renderUi() {
        tvSession.text = getString(R.string.openclaw_native_session_prefix) + sessionTitle.ifBlank {
            if (sessionKey.isBlank()) getString(R.string.openclaw_native_session_unknown) else sessionKey
        }
        val statusText = when {
            pendingRunId.isBlank() -> getString(R.string.openclaw_native_status_ready)
            pendingRunStatus.equals("reconnecting", ignoreCase = true) -> getString(R.string.openclaw_native_status_reconnecting)
            pendingRunStatus.equals("failed", ignoreCase = true) ||
                pendingRunStatus.equals("error", ignoreCase = true) -> getString(R.string.openclaw_native_status_failed)
            pendingRunStatus.equals("completed", ignoreCase = true) ||
                pendingRunStatus.equals("ok", ignoreCase = true) -> getString(R.string.openclaw_native_status_completed)
            else -> getString(R.string.openclaw_native_status_running)
        }
        val rawStatus = pendingRunStatus.ifBlank { "idle" }
        tvStatus.text = getString(R.string.openclaw_native_run_status_prefix) + "$statusText ($rawStatus)"

        btnSend.isEnabled = !sendingInFlight && sessionKey.isNotBlank()
        inputMessage.isEnabled = !sendingInFlight && sessionKey.isNotBlank()
        btnAbort.isEnabled = pendingRunId.isNotBlank()
        btnHeartbeat.isEnabled = sessionKey.isNotBlank()
        btnRename.isEnabled = sessionKey.isNotBlank()
        btnReset.isEnabled = sessionKey.isNotBlank()

        adapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            listView.post { listView.setSelection(messages.size - 1) }
        }
    }

    private fun postErrorToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun maybePromptServerRecovery(message: String) {
        val normalized = message.lowercase()
        val needRecovery = normalized.contains("connection refused") ||
            normalized.contains("failed to connect") ||
            normalized.contains("http 503")
        if (!needRecovery || recoverDialogShown) return
        recoverDialogShown = true
        runOnUiThread {
            Toast.makeText(this, getString(R.string.openclaw_native_waiting_server), Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_TARGET, MainActivity.OPEN_TARGET_OPENCLAW_SESSION)
                    val key = sessionKey.trim()
                    if (key.isNotEmpty()) {
                        putExtra(MainActivity.EXTRA_SESSION_KEY, key)
                    }
                },
            )
            finish()
        }
    }

    private fun getErrorMessage(error: Throwable): String {
        return error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName }
    }

    private inner class MessageAdapter : BaseAdapter() {
        override fun getCount(): Int = messages.size

        override fun getItem(position: Int): Any = messages[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_agent_chat_message, parent, false)
            val item = messages[position]
            view.findViewById<TextView>(R.id.tvAgentChatRole).text = item.role
            view.findViewById<TextView>(R.id.tvAgentChatText).text = item.text
            return view
        }
    }
}
