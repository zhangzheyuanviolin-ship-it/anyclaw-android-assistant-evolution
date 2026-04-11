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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class OpenClawChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_KEY = "com.codex.mobile.extra.OPENCLAW_SESSION_KEY"
        private const val HEARTBEAT_PROMPT =
            "Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. " +
                "Do not infer or repeat old tasks from prior chats. " +
                "If nothing needs attention, reply HEARTBEAT_OK."
    }

    private data class DisplayMessage(
        val role: String,
        val text: String,
    )

    private data class SessionRow(
        val key: String,
        val sessionId: String,
        val title: String,
        val updatedAtMs: Long,
    )

    private data class LocalRunResult(
        val success: Boolean,
        val message: String,
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
    private val sessionsByKey = linkedMapOf<String, SessionRow>()
    private val liveProcessLines = mutableListOf<String>()
    private val serverManager by lazy { CodexServerManager(this) }

    @Volatile
    private var destroyed = false

    @Volatile
    private var refreshInFlight = false

    @Volatile
    private var sendingInFlight = false

    @Volatile
    private var abortRequested = false

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var lastLiveRenderAtMs = 0L

    private var pollRunnable: Runnable? = null
    private var sessionKey: String = ""
    private var sessionId: String = ""
    private var sessionTitle: String = ""
    private var pendingRunStatus: String = "idle"

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
        runCatching { activeProcess?.destroy() }
        activeProcess = null
    }

    private fun startPolling() {
        if (pollRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                if (destroyed) return
                if (!refreshInFlight) {
                    refreshNow(showErrors = false)
                }
                val delayMs = if (sendingInFlight) 2500L else 5000L
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
                // OpenClaw native mode must not compete with gateway lock ownership.
                runCatching { serverManager.disconnectOpenClawGateway() }

                refreshSessionIndex(limit = 300)
                val resolved = when {
                    !preferredSession.isNullOrBlank() && sessionsByKey.containsKey(preferredSession) -> preferredSession
                    sessionKey.isNotBlank() && sessionsByKey.containsKey(sessionKey) -> sessionKey
                    sessionsByKey.isNotEmpty() -> sessionsByKey.values.sortedByDescending { it.updatedAtMs }.first().key
                    else -> {
                        val fallback = OpenClawLocalSessionStore.createIndependentSessionKey("agent:main:main")
                        sessionTitle = OpenClawLocalSessionStore.buildFallbackTitle(fallback)
                        fallback
                    }
                }
                sessionKey = resolved
                val row = sessionsByKey[resolved]
                sessionId = row?.sessionId.orEmpty()
                sessionTitle = row?.title.orEmpty().ifBlank {
                    OpenClawLocalSessionStore.buildFallbackTitle(resolved)
                }
                pendingRunStatus = if (sendingInFlight) "running" else "ready"
                refreshHistoryInternal(limit = 120)
            } catch (error: Exception) {
                postErrorToast(getString(R.string.openclaw_native_refresh_failed) + getErrorMessage(error))
            } finally {
                refreshInFlight = false
                postRender()
            }
        }.start()
    }

    private fun refreshNow(showErrors: Boolean) {
        if (refreshInFlight) return
        if (sessionKey.isBlank()) {
            ensureSessionReady(preferredSession = intent.getStringExtra(EXTRA_SESSION_KEY)?.trim())
            return
        }
        refreshInFlight = true
        Thread {
            try {
                refreshSessionIndex(limit = 300)
                val row = sessionsByKey[sessionKey]
                if (row != null) {
                    sessionId = row.sessionId
                    sessionTitle = row.title
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

    private fun refreshSessionIndex(limit: Int) {
        val sessions = OpenClawLocalSessionStore.listSessions(this, limit)
        sessionsByKey.clear()
        sessions.forEach { session ->
            sessionsByKey[session.key] = SessionRow(
                key = session.key,
                sessionId = session.sessionId,
                title = session.displayName,
                updatedAtMs = session.updatedAtMs,
            )
        }
    }

    private fun onSendPressed() {
        val text = inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.openclaw_native_send_empty), Toast.LENGTH_SHORT).show()
            return
        }
        submitPrompt(text = text, echoAsUser = true)
    }

    private fun onCreateSessionPressed() {
        if (sendingInFlight || refreshInFlight) return
        val base = sessionKey.ifBlank { "agent:main:main" }
        val next = OpenClawLocalSessionStore.createIndependentSessionKey(base)
        sessionKey = next
        sessionId = ""
        sessionTitle = OpenClawLocalSessionStore.buildFallbackTitle(next)
        pendingRunStatus = "ready"
        messages.clear()
        messages += DisplayMessage(
            role = "OpenClaw",
            text = "已创建本地新会话，首次发送后将自动写入会话历史。",
        )
        postRender()
    }

    private fun onResetSessionPressed() {
        if (sendingInFlight || refreshInFlight) return
        val base = sessionKey.ifBlank { "agent:main:main" }
        val next = OpenClawLocalSessionStore.createIndependentSessionKey(base)
        sessionKey = next
        sessionId = ""
        sessionTitle = OpenClawLocalSessionStore.buildFallbackTitle(next)
        pendingRunStatus = "ready"
        messages.clear()
        messages += DisplayMessage(
            role = "OpenClaw",
            text = "已重置为新会话，后续消息不会继承当前上下文。",
        )
        postRender()
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
                val nextTitle = OpenClawLocalSessionStore.sanitizeLabel(input.text?.toString().orEmpty())
                if (nextTitle.isBlank()) return@setPositiveButton
                OpenClawLocalSessionStore.setAlias(this, sessionKey, nextTitle)
                sessionTitle = nextTitle
                val existing = sessionsByKey[sessionKey]
                if (existing != null) {
                    sessionsByKey[sessionKey] = existing.copy(title = nextTitle)
                }
                postRender()
            }
            .show()
    }

    private fun onHeartbeatPressed() {
        if (sendingInFlight) {
            Toast.makeText(this, getString(R.string.openclaw_native_status_running), Toast.LENGTH_SHORT).show()
            return
        }
        submitPrompt(text = HEARTBEAT_PROMPT, echoAsUser = false)
    }

    private fun onAbortPressed() {
        val process = activeProcess
        if (!sendingInFlight || process == null) return
        abortRequested = true
        runCatching { process.destroy() }
        Thread {
            Thread.sleep(180)
            runCatching { process.destroyForcibly() }
        }.start()
        pendingRunStatus = "aborted"
        postRender()
    }

    private fun submitPrompt(text: String, echoAsUser: Boolean) {
        if (sendingInFlight) return
        if (sessionKey.isBlank()) {
            ensureSessionReady(preferredSession = intent.getStringExtra(EXTRA_SESSION_KEY)?.trim())
            return
        }

        val message = text.trim()
        if (message.isBlank()) return

        if (echoAsUser) {
            appendLocalMessage("您", message)
            inputMessage.setText("")
        }
        clearLiveProcessLines()
        sendingInFlight = true
        abortRequested = false
        pendingRunStatus = "running"
        renderUi()

        Thread {
            try {
                val result = runLocalAgentTurn(message, retryOnLock = true)
                if (!result.success) {
                    throw IllegalStateException(result.message)
                }
                refreshSessionIndex(limit = 300)
                if (sessionId.isBlank()) {
                    val latest = sessionsByKey.values.sortedByDescending { it.updatedAtMs }.firstOrNull()
                    if (latest != null) {
                        sessionKey = latest.key
                        sessionId = latest.sessionId
                        sessionTitle = latest.title
                    }
                } else {
                    val bySessionId = sessionsByKey.values.firstOrNull { it.sessionId == sessionId }
                    if (bySessionId != null) {
                        sessionKey = bySessionId.key
                        sessionTitle = bySessionId.title
                    }
                }
                refreshHistoryInternal(limit = 120)
                pendingRunStatus = if (abortRequested) "aborted" else "completed"
            } catch (error: Exception) {
                pendingRunStatus = if (abortRequested) "aborted" else "failed"
                postErrorToast(
                    (if (abortRequested) getString(R.string.cli_task_aborted) else getString(R.string.openclaw_native_send_failed)) +
                        getErrorMessage(error),
                )
            } finally {
                sendingInFlight = false
                activeProcess = null
                postRender()
            }
        }.start()
    }

    private fun runLocalAgentTurn(text: String, retryOnLock: Boolean): LocalRunResult {
        val command = buildOpenClawAgentCommand(text)
        val process = serverManager.startPrefixProcess(command)
        activeProcess = process

        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val clean = stripAnsi(line).trim()
                if (clean.isNotEmpty()) {
                    if (output.length < 240_000) {
                        output.appendLine(clean)
                    }
                    if (!isNoisyCliLine(clean)) {
                        recordLiveProcessLine(clean)
                    }
                }
                line = reader.readLine()
            }
        }

        val exitCode = process.waitFor()
        activeProcess = null
        val raw = output.toString().trim()
        if (abortRequested) {
            return LocalRunResult(success = false, message = getString(R.string.cli_task_aborted))
        }

        if (exitCode == 0) {
            return LocalRunResult(success = true, message = "ok")
        }

        if (retryOnLock && isSessionLockError(raw)) {
            runCatching { serverManager.disconnectOpenClawGateway() }
            Thread.sleep(320)
            return runLocalAgentTurn(text, retryOnLock = false)
        }

        val fallback = raw.lineSequence().lastOrNull()?.trim().orEmpty()
        return LocalRunResult(
            success = false,
            message = fallback.ifEmpty { "exit code=$exitCode" },
        )
    }

    private fun buildOpenClawAgentCommand(message: String): String {
        val quotedMessage = LocalBridgeClients.shellQuote(message)
        val normalizedSessionId = sessionId.trim().ifEmpty {
            sessionsByKey[sessionKey]?.sessionId?.trim().orEmpty()
        }
        return if (normalizedSessionId.isNotEmpty()) {
            "openclaw agent --local --session-id ${LocalBridgeClients.shellQuote(normalizedSessionId)} --message $quotedMessage --json 2>&1"
        } else {
            val pseudoTarget = "+1555" + System.currentTimeMillis().toString().takeLast(7)
            "openclaw agent --local --to ${LocalBridgeClients.shellQuote(pseudoTarget)} --message $quotedMessage --json 2>&1"
        }
    }

    private fun isSessionLockError(raw: String): Boolean {
        val normalized = raw.lowercase(Locale.getDefault())
        return normalized.contains("session file locked") || normalized.contains(".jsonl.lock")
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"), "")
    }

    private fun isNoisyCliLine(line: String): Boolean {
        val normalized = line.lowercase(Locale.getDefault())
        return normalized.startsWith("config warnings") ||
            normalized.startsWith("[plugins]") ||
            normalized.contains("stale config entry ignored")
    }

    private fun recordLiveProcessLine(line: String) {
        synchronized(liveProcessLines) {
            if (liveProcessLines.lastOrNull() == line) return
            liveProcessLines += line
            while (liveProcessLines.size > 120) {
                liveProcessLines.removeAt(0)
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastLiveRenderAtMs >= 450) {
            lastLiveRenderAtMs = now
            postRender()
        }
    }

    private fun clearLiveProcessLines() {
        synchronized(liveProcessLines) {
            liveProcessLines.clear()
        }
    }

    private fun snapshotLiveProcessLine(): String {
        synchronized(liveProcessLines) {
            return liveProcessLines.lastOrNull().orEmpty()
        }
    }

    private fun refreshHistoryInternal(limit: Int) {
        if (sessionKey.isBlank()) return
        val response = OpenClawLocalSessionStore.loadHistoryPayload(this, sessionKey, limit)
        val history = response.optJSONArray("messages") ?: JSONArray()
        val next = mutableListOf<DisplayMessage>()
        for (index in 0 until history.length()) {
            val row = history.optJSONObject(index) ?: continue
            val role = row.optString("role", "").trim()
            val content = row.optJSONArray("content")
            val text = extractTextContent(content)
            when {
                role.equals("user", ignoreCase = true) -> {
                    if (text.isNotBlank()) {
                        next += DisplayMessage(role = "您", text = text)
                    }
                }
                role.equals("assistant", ignoreCase = true) -> {
                    if (text.isNotBlank()) {
                        next += DisplayMessage(role = "OpenClaw", text = text)
                    }
                    val processLines = extractProcessContent(content)
                    processLines.forEach { line ->
                        next += DisplayMessage(role = getString(R.string.cli_process_role), text = line)
                    }
                }
                role.equals("toolResult", ignoreCase = true) || role.equals("toolUse", ignoreCase = true) -> {
                    val fallback = text.ifBlank { row.toString() }
                    next += DisplayMessage(role = getString(R.string.cli_process_role), text = fallback)
                }
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
            val type = item.optString("type", "").trim().lowercase(Locale.getDefault())
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
            val type = item.optString("type", "").trim().lowercase(Locale.getDefault())
            when (type) {
                "thinking" -> {
                    val text = item.optString("thinking", "").trim()
                    if (text.isNotBlank()) rows += "思考: $text"
                }
                "redacted_thinking" -> {
                    rows += "思考: [模型返回了加密思考片段]"
                }
                "toolcall", "tool_use" -> {
                    val name = item.optString("name", "").trim().ifBlank { "unknown_tool" }
                    rows += "工具调用: $name"
                }
                "toolresult", "tool_result" -> {
                    val text = item.optString("text", "").trim().ifBlank {
                        item.opt("content")?.toString()?.trim().orEmpty()
                    }
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

        val live = snapshotLiveProcessLine()
        val statusText = when {
            sendingInFlight && live.isNotBlank() -> getString(R.string.openclaw_native_status_running) + " · " + live
            sendingInFlight -> getString(R.string.openclaw_native_status_running)
            pendingRunStatus.equals("failed", ignoreCase = true) ||
                pendingRunStatus.equals("error", ignoreCase = true) -> getString(R.string.openclaw_native_status_failed)
            pendingRunStatus.equals("aborted", ignoreCase = true) -> getString(R.string.cli_task_aborted)
            pendingRunStatus.equals("completed", ignoreCase = true) ||
                pendingRunStatus.equals("ok", ignoreCase = true) -> getString(R.string.openclaw_native_status_completed)
            else -> getString(R.string.openclaw_native_status_ready)
        }
        val rawStatus = if (sendingInFlight) "running" else pendingRunStatus.ifBlank { "idle" }
        tvStatus.text = getString(R.string.openclaw_native_run_status_prefix) + "$statusText ($rawStatus)"

        btnSend.isEnabled = !sendingInFlight && sessionKey.isNotBlank()
        inputMessage.isEnabled = !sendingInFlight && sessionKey.isNotBlank()
        btnAbort.isEnabled = sendingInFlight
        btnHeartbeat.isEnabled = sessionKey.isNotBlank()
        btnRename.isEnabled = sessionKey.isNotBlank()
        btnReset.isEnabled = !sendingInFlight
        btnNewSession.isEnabled = !sendingInFlight

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
