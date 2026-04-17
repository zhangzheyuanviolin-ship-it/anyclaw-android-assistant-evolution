package com.codex.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class CliAgentChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AGENT_ID = "com.codex.mobile.extra.AGENT_ID"
        const val EXTRA_SESSION_ID = "com.codex.mobile.extra.AGENT_SESSION_ID"
        private const val RUNTIME_PREFS = "cli_agent_runtime_options"
        private const val UI_PREFS = "cli_agent_ui_options"
        private const val DEFAULT_VISIBLE_HISTORY = 40
        private const val HISTORY_STEP = 40
        private const val MAX_VISIBLE_HISTORY = 240
        private const val CLAUDE_AUTO_COMPACT_PROMPT_BYTES = 96 * 1024
        private const val CLAUDE_HARD_PROMPT_BYTES = 140 * 1024
        private const val CLAUDE_SUMMARY_CLIP_CHARS = 420
        private const val CLAUDE_SUMMARY_TOTAL_MAX_CHARS = 12_000
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAttachmentSummary: TextView
    private lateinit var btnSettings: Button
    private lateinit var btnNewSession: Button
    private lateinit var btnCompact: Button
    private lateinit var btnAttach: Button
    private lateinit var btnClearAttachments: Button
    private lateinit var btnAbort: Button
    private lateinit var listView: ListView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnOpenClaw: Button
    private lateinit var btnCodex: Button
    private lateinit var btnClaude: Button

    private lateinit var serverManager: CodexServerManager
    private lateinit var agentId: ExternalAgentId
    private lateinit var activeSession: AgentChatSession
    private lateinit var runtimeOptions: AgentRuntimeOptions
    private val messageAdapter = MessageAdapter()

    private var sending = false
    private var historyWindowSize = DEFAULT_VISIBLE_HISTORY
    private var showProcess = true
    private var pendingCameraUri: Uri? = null
    private var visibleMessages: List<DisplayMessage> = emptyList()
    private val attachedFiles = mutableListOf<LocalAttachment>()
    private val liveProcessLines = mutableListOf<String>()

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var abortRequested = false

    @Volatile
    private var lastLiveRenderAtMs = 0L

    private data class AgentRuntimeOptions(
        val allowSharedStorage: Boolean,
        val dangerousAutoApprove: Boolean,
    )

    private data class ProbeResult(
        val code: Int,
        val output: String,
    )

    private data class LocalAttachment(
        val displayName: String,
        val absolutePath: String,
    )

    private data class DisplayMessage(
        val roleText: String,
        val body: String,
    )

    private data class AgentRunResult(
        val assistantText: String,
        val processText: String,
    )

    private data class ClaudeStreamParseState(
        var assistantText: String = "",
        var resultText: String = "",
        val processLines: MutableList<String> = mutableListOf(),
        val fallbackLines: MutableList<String> = mutableListOf(),
        val seenToolUseIds: MutableSet<String> = mutableSetOf(),
        val seenToolResultIds: MutableSet<String> = mutableSetOf(),
    )

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePickedUris(collectSelectionUris(result.resultCode, result.data))
        }

    private val galleryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePickedUris(collectSelectionUris(result.resultCode, result.data))
        }

    private val cameraCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cameraUri = pendingCameraUri
            pendingCameraUri = null
            if (result.resultCode == RESULT_OK && cameraUri != null) {
                handlePickedUris(listOf(cameraUri))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cli_agent_chat)

        agentId = parseAgent(intent.getStringExtra(EXTRA_AGENT_ID))
        serverManager = CodexServerManager(this)
        ShizukuBridgeRuntime.ensureStarted(this)

        tvTitle = findViewById(R.id.tvCliAgentTitle)
        tvSession = findViewById(R.id.tvCliAgentSession)
        tvStatus = findViewById(R.id.tvCliAgentStatus)
        tvAttachmentSummary = findViewById(R.id.tvCliAttachmentSummary)
        btnSettings = findViewById(R.id.btnCliSettings)
        btnNewSession = findViewById(R.id.btnCliNewSession)
        btnCompact = findViewById(R.id.btnCliCompact)
        btnAttach = findViewById(R.id.btnCliAttach)
        btnClearAttachments = findViewById(R.id.btnCliClearAttachments)
        btnAbort = findViewById(R.id.btnCliAbort)
        listView = findViewById(R.id.listCliAgentMessages)
        inputMessage = findViewById(R.id.etCliAgentInput)
        btnSend = findViewById(R.id.btnCliAgentSend)
        btnOpenClaw = findViewById(R.id.btnCliTabOpenClaw)
        btnCodex = findViewById(R.id.btnCliTabCodex)
        btnClaude = findViewById(R.id.btnCliTabClaude)

        listView.adapter = messageAdapter
        tvTitle.text = AgentSessionStore.displayAgentName(agentId)

        runtimeOptions = loadRuntimeOptions()
        historyWindowSize = loadHistoryWindowSize()
        showProcess = loadShowProcess()

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }
        btnNewSession.setOnClickListener {
            createNewSession()
        }
        btnCompact.setOnClickListener {
            compactSessionManually()
        }
        btnAttach.setOnClickListener {
            showAttachmentPicker()
        }
        btnClearAttachments.setOnClickListener {
            clearAttachments()
        }
        btnAbort.setOnClickListener {
            abortRunningTask()
        }
        btnSend.setOnClickListener {
            sendMessage()
        }
        inputMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }

        btnOpenClaw.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_TARGET, MainActivity.OPEN_TARGET_OPENCLAW_SESSION)
                },
            )
            finish()
        }
        btnCodex.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_TARGET, MainActivity.OPEN_TARGET_CODEX_HOME)
                },
            )
            finish()
        }
        btnClaude.setOnClickListener {
            if (agentId == ExternalAgentId.CLAUDE_CODE) return@setOnClickListener
            restartWithAgent(ExternalAgentId.CLAUDE_CODE, null)
        }
    }

    override fun onResume() {
        super.onResume()
        val preferredSession = intent.getStringExtra(EXTRA_SESSION_ID)
        activeSession = AgentSessionStore.ensureActiveSession(this, agentId, preferredSession)
        renderSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            activeProcess?.destroy()
        }
    }

    private fun renderSession() {
        tvSession.text = "会话：${activeSession.title}"
        val latestProcess = snapshotLiveProcessLines().lastOrNull()
        tvStatus.text = if (sending) {
            if (latestProcess.isNullOrBlank()) {
                "正在执行..."
            } else {
                "正在执行... $latestProcess"
            }
        } else {
            val shared = if (runtimeOptions.allowSharedStorage) "开" else "关"
            val danger = if (runtimeOptions.dangerousAutoApprove) "开" else "关"
            "就绪 · 共享存储:$shared · 高权限:$danger"
        }
        tvAttachmentSummary.text = buildAttachmentSummary()
        renderBottomTabState()

        btnAttach.isEnabled = !sending
        btnClearAttachments.isEnabled = attachedFiles.isNotEmpty() && !sending
        btnCompact.isEnabled = !sending && agentId == ExternalAgentId.CLAUDE_CODE
        btnAbort.isEnabled = sending
        btnSend.isEnabled = !sending
        inputMessage.isEnabled = !sending

        val storedMessages = filteredMessagesForDisplay()

        val displayItems = mutableListOf<DisplayMessage>()
        storedMessages.forEach { msg ->
            val role = msg.role.lowercase(Locale.getDefault())
            val roleText = when (role) {
                "user" -> "您"
                "assistant" -> AgentSessionStore.displayAgentName(agentId)
                "process" -> getString(R.string.cli_process_role)
                else -> role
            }
            displayItems += DisplayMessage(roleText = roleText, body = msg.text)
        }
        if (showProcess) {
            val live = snapshotLiveProcessLines().joinToString("\n").trim()
            if (live.isNotEmpty()) {
                displayItems += DisplayMessage(
                    roleText = getString(R.string.cli_process_role),
                    body = live,
                )
            }
        }
        visibleMessages = displayItems
        messageAdapter.notifyDataSetChanged()
        listView.post {
            val count = messageAdapter.count
            if (count > 0) {
                listView.setSelection(count - 1)
            }
        }
    }

    private fun filteredMessagesForDisplay(): List<AgentChatMessage> {
        val base = if (showProcess) {
            activeSession.messages
        } else {
            activeSession.messages.filterNot { it.role.equals("process", ignoreCase = true) }
        }
        return if (base.size <= historyWindowSize) base else base.takeLast(historyWindowSize)
    }

    private fun renderBottomTabState() {
        btnClaude.isEnabled = agentId != ExternalAgentId.CLAUDE_CODE
    }

    private fun createNewSession() {
        activeSession = AgentSessionStore.createSession(this, agentId)
        attachedFiles.clear()
        clearLiveProcessLines()
        renderSession()
        Toast.makeText(this, "已新建会话", Toast.LENGTH_SHORT).show()
    }

    private fun clearAttachments() {
        attachedFiles.clear()
        renderSession()
        Toast.makeText(this, getString(R.string.cli_attachment_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun compactSessionManually() {
        if (sending) {
            Toast.makeText(this, getString(R.string.cli_compact_blocked_sending), Toast.LENGTH_SHORT).show()
            return
        }
        val compacted = maybeCompactSessionIfNeeded(trigger = "manual_button", force = true)
        if (compacted) {
            renderSession()
            Toast.makeText(this, getString(R.string.cli_compact_done), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.cli_compact_not_needed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeCompactSessionIfNeeded(trigger: String, force: Boolean = false): Boolean {
        if (agentId != ExternalAgentId.CLAUDE_CODE) return false
        val snapshot = activeSession
        if (snapshot.messages.isEmpty()) return false

        val promptBytes = promptUtf8Bytes(buildPromptWithHistory(snapshot.messages, runtimeOptions))
        if (!force && promptBytes < CLAUDE_AUTO_COMPACT_PROMPT_BYTES) return false

        val summary = buildCompactionSummary(snapshot, trigger, promptBytes)
        val baseTitle = snapshot.title.trim().ifEmpty { "Claude Code 会话" }
        val nextTitle = if (baseTitle.contains("续接")) baseTitle else "$baseTitle（续接）"

        var nextSession = AgentSessionStore.createSession(this, agentId, nextTitle)
        nextSession = AgentSessionStore.appendMessage(
            this,
            agentId,
            nextSession.sessionId,
            "assistant",
            summary,
        )

        val pendingUser = resolvePendingUserRequest(snapshot.messages)
        if (pendingUser.isNotEmpty()) {
            nextSession = AgentSessionStore.appendMessage(
                this,
                agentId,
                nextSession.sessionId,
                "user",
                "请基于上面的压缩摘要继续处理这个请求：\n$pendingUser",
            )
        }

        activeSession = nextSession
        clearLiveProcessLines()
        return true
    }

    private fun buildCompactionSummary(
        session: AgentChatSession,
        trigger: String,
        promptBytes: Int,
    ): String {
        val allMessages = session.messages
        val userMessages = allMessages.filter { it.role.equals("user", ignoreCase = true) }
        val assistantMessages = allMessages.filter { it.role.equals("assistant", ignoreCase = true) }
        val processMessages = allMessages.filter { it.role.equals("process", ignoreCase = true) }
        val pendingUser = resolvePendingUserRequest(allMessages)

        val requestSamples = userMessages.takeLast(10).mapIndexed { index, msg ->
            "${index + 1}. ${clipSummaryText(msg.text)}"
        }
        val responseSamples = assistantMessages
            .filterNot { isFailureLikeMessage(it.text) }
            .takeLast(8)
            .mapIndexed { index, msg ->
                "${index + 1}. ${clipSummaryText(msg.text)}"
            }
        val constraintSamples = userMessages
            .map { it.text }
            .filter { isConstraintLikeMessage(it) }
            .takeLast(6)
            .mapIndexed { index, text ->
                "${index + 1}. ${clipSummaryText(text)}"
            }
        val failureSamples = (assistantMessages + processMessages)
            .filter {
                isFailureLikeMessage(it.text)
            }
            .takeLast(6)
            .mapIndexed { index, msg ->
                "${index + 1}. ${clipSummaryText(msg.text)}"
            }

        val firstUser = userMessages.firstOrNull()?.text.orEmpty()
        val latestUser = userMessages.lastOrNull()?.text.orEmpty()
        val activeGoal = pendingUser.ifBlank { latestUser.ifBlank { firstUser } }
        val pendingDisplay = if (pendingUser.isBlank()) {
            "无（上一请求已完成或无需续接）"
        } else {
            clipSummaryText(pendingUser, maxChars = 520)
        }

        val summary = buildString {
            appendLine("【会话压缩摘要】")
            appendLine("source_session_id=${session.sessionId}")
            appendLine("trigger=$trigger")
            appendLine("original_message_count=${allMessages.size}")
            appendLine("estimated_prompt_bytes=$promptBytes")
            appendLine("summary_policy=structured_v2")
            appendLine()
            appendLine("当前主目标：")
            appendLine(clipSummaryText(activeGoal, maxChars = 520))
            appendLine()
            appendLine("初始目标：")
            appendLine(clipSummaryText(firstUser, maxChars = 420))
            appendLine()
            appendLine("最近用户请求轨迹（按时间升序）：")
            if (requestSamples.isEmpty()) {
                appendLine("无")
            } else {
                requestSamples.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("最近已完成事项（有效结论）：")
            if (responseSamples.isEmpty()) {
                appendLine("无")
            } else {
                responseSamples.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("关键约束与偏好：")
            if (constraintSamples.isEmpty()) {
                appendLine("无")
            } else {
                constraintSamples.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("异常与风险：")
            if (failureSamples.isEmpty()) {
                appendLine("无")
            } else {
                failureSamples.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("当前待续接请求：")
            appendLine(pendingDisplay)
            appendLine()
            appendLine("续接执行要求：")
            if (pendingUser.isBlank()) {
                appendLine("1. 以上述摘要为会话事实基线，继续处理用户后续新请求。")
                appendLine("2. 若需核验信息，可正常调用工具进行验证。")
            } else {
                appendLine("1. 优先完成“当前待续接请求”，并保持与已完成事项一致。")
                appendLine("2. 以上述摘要为准，必要时可调用工具核验或补充。")
            }
        }.trim()

        return enforceSummaryBudget(summary)
    }

    private fun clipSummaryText(value: String, maxChars: Int = CLAUDE_SUMMARY_CLIP_CHARS): String {
        val normalized = value
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (normalized.isBlank()) return "无"
        if (normalized.length <= maxChars) return normalized
        return normalized.take(maxChars) + "..."
    }

    private fun enforceSummaryBudget(value: String, maxChars: Int = CLAUDE_SUMMARY_TOTAL_MAX_CHARS): String {
        if (value.length <= maxChars) return value
        val keep = (maxChars - 64).coerceAtLeast(256)
        return value.take(keep).trimEnd() + "\n\n[摘要超出预算，已截断到核心字段]"
    }

    private fun resolvePendingUserRequest(messages: List<AgentChatMessage>): String {
        val lastUserIndex = messages.indexOfLast { it.role.equals("user", ignoreCase = true) && it.text.isNotBlank() }
        if (lastUserIndex < 0) return ""

        val latestUser = messages[lastUserIndex].text.trim()
        if (latestUser.isEmpty()) return ""

        val assistantAfterUser = messages
            .drop(lastUserIndex + 1)
            .filter { it.role.equals("assistant", ignoreCase = true) }

        if (assistantAfterUser.isEmpty()) return latestUser
        val hasSuccessfulAssistant = assistantAfterUser.any { !isFailureLikeMessage(it.text) }
        return if (hasSuccessfulAssistant) "" else latestUser
    }

    private fun isFailureLikeMessage(text: String): Boolean {
        val normalized = text.lowercase(Locale.US)
        return normalized.contains("执行失败") ||
            normalized.contains("argument list too long") ||
            normalized.contains("error=7") ||
            normalized.contains("error=") ||
            normalized.contains("command exit code=") ||
            normalized.contains("任务已终止") ||
            normalized.contains("claude 返回错误")
    }

    private fun isConstraintLikeMessage(text: String): Boolean {
        val normalized = text.lowercase(Locale.US)
        val markers = listOf(
            "必须",
            "默认",
            "禁止",
            "不要",
            "只能",
            "仅",
            "优先",
            "beta",
            "测试版",
            "system-shell",
            "ubuntu-shell",
            "回退",
            "风控",
            "风险",
            "通讯版",
        )
        return markers.any { normalized.contains(it) }
    }

    private fun promptUtf8Bytes(value: String): Int {
        return value.toByteArray(Charsets.UTF_8).size
    }

    private fun isArgumentListTooLong(error: Throwable): Boolean {
        val message = error.message?.lowercase(Locale.US).orEmpty()
        return message.contains("argument list too long") || message.contains("error=7")
    }

    private fun loadOlderMessages() {
        historyWindowSize = (historyWindowSize + HISTORY_STEP).coerceAtMost(MAX_VISIBLE_HISTORY)
        saveHistoryWindowSize(historyWindowSize)
        renderSession()
        Toast.makeText(this, getString(R.string.cli_history_expanded), Toast.LENGTH_SHORT).show()
    }

    private fun restoreLiteHistory() {
        historyWindowSize = DEFAULT_VISIBLE_HISTORY
        saveHistoryWindowSize(historyWindowSize)
        renderSession()
        Toast.makeText(this, getString(R.string.cli_history_restored), Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_cli_chat_settings, null)
        val switchAllow = view.findViewById<SwitchCompat>(R.id.switchCliSettingAllowSharedStorage)
        val switchDanger = view.findViewById<SwitchCompat>(R.id.switchCliSettingDangerousMode)
        val switchProcess = view.findViewById<SwitchCompat>(R.id.switchCliSettingShowProcess)
        val btnPermission = view.findViewById<Button>(R.id.btnCliSettingPermissionCenter)
        val btnPrompt = view.findViewById<Button>(R.id.btnCliSettingPromptManager)
        val btnConversation = view.findViewById<Button>(R.id.btnCliSettingConversationManager)
        val btnModel = view.findViewById<Button>(R.id.btnCliSettingModelManager)
        val btnLoadOlder = view.findViewById<Button>(R.id.btnCliSettingLoadOlder)
        val btnRestoreLite = view.findViewById<Button>(R.id.btnCliSettingRestoreLite)

        switchAllow.isChecked = runtimeOptions.allowSharedStorage
        switchDanger.isChecked = runtimeOptions.dangerousAutoApprove
        switchProcess.isChecked = showProcess

        switchAllow.setOnCheckedChangeListener { _, checked ->
            runtimeOptions = runtimeOptions.copy(allowSharedStorage = checked)
            saveRuntimeOptions(runtimeOptions)
            renderSession()
        }
        switchDanger.setOnCheckedChangeListener { _, checked ->
            runtimeOptions = runtimeOptions.copy(dangerousAutoApprove = checked)
            saveRuntimeOptions(runtimeOptions)
            renderSession()
        }
        switchProcess.setOnCheckedChangeListener { _, checked ->
            showProcess = checked
            saveShowProcess(checked)
            renderSession()
        }

        val visible = filteredMessagesForDisplay()
        btnLoadOlder.isEnabled = activeSession.messages.size > visible.size
        btnRestoreLite.isEnabled = historyWindowSize > DEFAULT_VISIBLE_HISTORY

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.cli_settings_dialog_title))
            .setView(view)
            .setPositiveButton(getString(R.string.close), null)
            .create()

        btnPermission.setOnClickListener {
            startActivity(Intent(this, PermissionManagerActivity::class.java))
            dialog.dismiss()
        }
        btnPrompt.setOnClickListener {
            val target = if (agentId == ExternalAgentId.CLAUDE_CODE) {
                PromptProfileTarget.CLAUDE.value
            } else {
                PromptProfileTarget.CODEX.value
            }
            startActivity(
                Intent(this, PromptManagerActivity::class.java).apply {
                    putExtra(PromptManagerActivity.EXTRA_PROMPT_TARGET, target)
                },
            )
            dialog.dismiss()
        }
        btnConversation.setOnClickListener {
            startActivity(Intent(this, ConversationManagerActivity::class.java))
            dialog.dismiss()
        }
        btnModel.setOnClickListener {
            startActivity(
                Intent(this, AgentModelManagerActivity::class.java).apply {
                    putExtra(AgentModelManagerActivity.EXTRA_AGENT_ID, agentId.value)
                },
            )
            dialog.dismiss()
        }
        btnLoadOlder.setOnClickListener {
            loadOlderMessages()
            dialog.dismiss()
        }
        btnRestoreLite.setOnClickListener {
            restoreLiteHistory()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun sendMessage() {
        if (sending) return
        ShizukuBridgeRuntime.ensureStarted(this)
        val input = inputMessage.text.toString().trim()
        if (input.isEmpty()) return

        val modelConfig = AgentModelConfigStore.loadCurrentConfig(this, agentId)
        if (modelConfig == null) {
            Toast.makeText(this, "请先在模型管理中配置${AgentSessionStore.displayAgentName(agentId)}模型", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, AgentModelManagerActivity::class.java).apply {
                    putExtra(AgentModelManagerActivity.EXTRA_AGENT_ID, agentId.value)
                },
            )
            return
        }

        val attachmentsSnapshot = attachedFiles.toList()
        attachedFiles.clear()
        val userText = buildUserMessageText(input, attachmentsSnapshot)
        inputMessage.setText("")
        activeSession = AgentSessionStore.appendMessage(this, agentId, activeSession.sessionId, "user", userText)
        clearLiveProcessLines()
        sending = true
        abortRequested = false
        renderSession()

        Thread {
            val result = runCatching {
                if (agentId == ExternalAgentId.CLAUDE_CODE && maybeCompactSessionIfNeeded(trigger = "auto_threshold")) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.cli_compact_auto_triggered), Toast.LENGTH_SHORT).show()
                        renderSession()
                    }
                }

                var prompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                if (agentId == ExternalAgentId.CLAUDE_CODE && promptUtf8Bytes(prompt) > CLAUDE_HARD_PROMPT_BYTES) {
                    if (maybeCompactSessionIfNeeded(trigger = "hard_limit_guard", force = true)) {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.cli_compact_auto_triggered), Toast.LENGTH_SHORT).show()
                            renderSession()
                        }
                    }
                    prompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                }
                when (agentId) {
                    ExternalAgentId.CLAUDE_CODE -> runClaudePrint(modelConfig, prompt, runtimeOptions)
                }
            }.recoverCatching { error ->
                if (agentId == ExternalAgentId.CLAUDE_CODE && isArgumentListTooLong(error)) {
                    if (maybeCompactSessionIfNeeded(trigger = "error_retry", force = true)) {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.cli_compact_retry_triggered), Toast.LENGTH_SHORT).show()
                            renderSession()
                        }
                    }
                    val retryPrompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                    when (agentId) {
                        ExternalAgentId.CLAUDE_CODE -> runClaudePrint(modelConfig, retryPrompt, runtimeOptions)
                    }
                } else {
                    throw error
                }
            }.getOrElse { error ->
                val processText = snapshotLiveProcessLines().joinToString("\n").trim()
                val message = if (abortRequested) {
                    getString(R.string.cli_task_aborted)
                } else {
                    "执行失败：${error.message ?: "unknown error"}"
                }
                AgentRunResult(assistantText = message, processText = processText)
            }

            var nextSession = activeSession
            if (result.processText.isNotBlank()) {
                nextSession = AgentSessionStore.appendMessage(
                    this,
                    agentId,
                    nextSession.sessionId,
                    "process",
                    result.processText,
                )
            }
            nextSession = AgentSessionStore.appendMessage(
                this,
                agentId,
                nextSession.sessionId,
                "assistant",
                result.assistantText,
            )
            activeSession = nextSession

            runOnUiThread {
                sending = false
                activeProcess = null
                clearLiveProcessLines()
                renderSession()
            }
        }.start()
    }

    private fun buildPromptWithHistory(
        messages: List<AgentChatMessage>,
        options: AgentRuntimeOptions,
    ): String {
        val recent = messages.takeLast(historyWindowSize.coerceIn(20, 120))
        val out = StringBuilder()
        out.appendLine("你正在继续已有会话。请结合上下文回答，并在需要时执行可用工具。")
        if (agentId == ExternalAgentId.CLAUDE_CODE) {
            out.appendLine("高优先级运行规范（必须遵守）：")
            out.appendLine("1) 先阅读“自动注入预检结果”，再决定用哪条执行链路。")
            out.appendLine("2) Android 系统级命令必须使用 system-shell <command>。")
            out.appendLine("3) Ubuntu 命令使用 ubuntu-shell <command> 或直接 Linux 命令。")
            out.appendLine("4) 先做必要验证再给结论，且不要复述整段预检文本。")
            out.appendLine("5) 若某链路失败，需明确失败原因并自动切换可用链路继续。")
            if (agentId == ExternalAgentId.CLAUDE_CODE) {
                out.appendLine("6) 本会话已注入 AnyClaw MCP 工具箱，调用时优先使用 mcp__anyclaw_toolbox__ 前缀工具名。")
                out.appendLine("7) 网页自动化调用请使用 mcp__anyclaw_toolbox__start_web/stop_web/web_*，避免裸 start_web/web_*。")
                out.appendLine("8) 若 anyclaw_ 工具不可见或调用失败，必须输出 MCP_TOOLBOX_STATUS=UNAVAILABLE，并给出 reason 与 step。")
                out.appendLine("9) 联网检索优先 mcp__anyclaw_toolbox__anyclaw_exa_search；若 Exa 超时将自动回退到 Tavily。")
                out.appendLine("10) 需要 Exa 高级能力时，优先使用 anyclaw_exa_search_advanced / anyclaw_exa_search_api / anyclaw_exa_contents_api。")
            }
            out.appendLine()
            out.appendLine("自动注入预检结果：")
            out.appendLine(buildRuntimeProbeBlock(options))
            out.appendLine()
            if (agentId == ExternalAgentId.CLAUDE_CODE) {
                out.appendLine("AnyClaw MCP注入状态：")
                out.appendLine(buildClaudeMcpProbeBlock(options))
                out.appendLine()
            }
        }
        out.appendLine("会话上下文如下：")
        recent.forEach { msg ->
            val role = msg.role.lowercase(Locale.getDefault())
            val roleText = when (role) {
                "user" -> "用户"
                "assistant" -> "助手"
                "process" -> "执行过程"
                else -> role
            }
            out.appendLine("[$roleText] ${msg.text}")
        }
        out.appendLine()
        out.appendLine("请继续完成最后一个用户请求。")
        return out.toString().trim()
    }

    private fun runClaudePrint(
        config: AgentModelConfig,
        prompt: String,
        options: AgentRuntimeOptions,
    ): AgentRunResult {
        val paths = BootstrapInstaller.getPaths(this)
        val nodePath = File(paths.prefixDir, "bin/node").absolutePath
        val claudeCliPath = File(paths.prefixDir, "lib/node_modules/@anthropic-ai/claude-code/cli.js").absolutePath
        val mcpConfigPath = ensureClaudeAnyClawMcpConfig(options).absolutePath
        val systemPromptFile = buildClaudeSystemPromptFile()

        val args = mutableListOf(
            nodePath,
            claudeCliPath,
            "-p",
            "--verbose",
        )
        if (options.dangerousAutoApprove) {
            args += "--dangerously-skip-permissions"
        }
        args += buildClaudeDirArgs(options)
        args += listOf("--mcp-config", mcpConfigPath, "--strict-mcp-config")
        if (!systemPromptFile.isNullOrBlank()) {
            args += listOf("--append-system-prompt-file", systemPromptFile)
        }
        if (config.modelId.isNotBlank()) {
            args += listOf("--model", config.modelId.trim())
        }
        args += listOf("--output-format", "stream-json", "--include-partial-messages", "--include-hook-events")
        args += prompt

        val extraEnv = mutableMapOf(
            "ANTHROPIC_API_KEY" to config.apiKey.trim(),
        )
        if (config.baseUrl.isNotBlank()) {
            extraEnv["ANTHROPIC_BASE_URL"] = config.baseUrl.trim()
        }

        val process = serverManager.startPrefixExecProcess(args, extraEnv)
        runCatching { process.outputStream.close() }
        return runClaudeStreamJson(process)
    }

    private fun buildClaudeSystemPromptFile(): String? {
        val selected = PromptProfileStore.loadSelectedProfile(this, PromptProfileTarget.CLAUDE)
        val content = selected?.content?.trim().orEmpty()
        if (content.isBlank()) return null
        val paths = BootstrapInstaller.getPaths(this)
        val mcpDir = File(paths.homeDir, ".pocketlobster/mcp")
        if (!mcpDir.exists()) {
            mcpDir.mkdirs()
        }
        val promptFile = File(mcpDir, "claude-system-prompt.txt")
        writeTextIfChanged(promptFile, content + "\n")
        return promptFile.absolutePath
    }

    private fun buildClaudeDirArgs(options: AgentRuntimeOptions): List<String> {
        val paths = BootstrapInstaller.getPaths(this)
        val dirs = linkedSetOf(paths.homeDir)
        if (options.allowSharedStorage) {
            dirs += "/sdcard"
            dirs += "/storage/emulated/0"
        }
        val args = mutableListOf<String>()
        dirs.forEach { dir ->
            args += "--add-dir"
            args += dir
        }
        return args
    }

    private fun runStreamingCommand(
        process: Process,
        finalizeAssistant: (String) -> String,
    ): AgentRunResult {
        activeProcess = process
        val rawLines = mutableListOf<String>()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val cleaned = stripAnsi(line).trim()
                if (shouldIgnoreProcessLine(cleaned)) {
                    line = reader.readLine()
                    continue
                }
                if (cleaned.isNotEmpty()) {
                    rawLines += cleaned
                    recordLiveProcessLine(cleaned)
                }
                line = reader.readLine()
            }
        }
        val exitCode = process.waitFor()
        activeProcess = null
        val raw = rawLines.joinToString("\n").trim()
        if (abortRequested) {
            return AgentRunResult(
                assistantText = getString(R.string.cli_task_aborted),
                processText = raw,
            )
        }
        if (exitCode != 0) {
            throw IllegalStateException(raw.ifBlank { "command exit code=$exitCode" })
        }
        val assistant = finalizeAssistant(raw).ifBlank { raw }
        val processText = if (assistant == raw) "" else raw
        return AgentRunResult(
            assistantText = assistant,
            processText = processText,
        )
    }

    private fun runClaudeStreamJson(process: Process): AgentRunResult {
        activeProcess = process
        val state = ClaudeStreamParseState()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val cleaned = stripAnsi(line).trim()
                if (shouldIgnoreProcessLine(cleaned)) {
                    line = reader.readLine()
                    continue
                }
                if (cleaned.isNotEmpty()) {
                    if (!parseClaudeStreamLine(cleaned, state)) {
                        state.fallbackLines += cleaned
                        appendClaudeProcessLine(state.processLines, cleaned)
                    }
                }
                line = reader.readLine()
            }
        }
        val exitCode = process.waitFor()
        activeProcess = null

        if (abortRequested) {
            return AgentRunResult(
                assistantText = getString(R.string.cli_task_aborted),
                processText = state.processLines.joinToString("\n").trim(),
            )
        }

        val processText = state.processLines.joinToString("\n").trim()
        val assistantText = chooseClaudeAssistantText(state).ifBlank {
            cleanClaudeOutput(state.fallbackLines.joinToString("\n"))
        }

        if (exitCode != 0) {
            val raw = (processText.ifBlank { state.fallbackLines.joinToString("\n").trim() })
            throw IllegalStateException(raw.ifBlank { "command exit code=$exitCode" })
        }

        return AgentRunResult(
            assistantText = assistantText.ifBlank { "Claude 未返回可解析内容" },
            processText = processText,
        )
    }

    private fun parseClaudeStreamLine(line: String, state: ClaudeStreamParseState): Boolean {
        if (!line.startsWith("{")) return false
        val payload = runCatching { JSONObject(line) }.getOrNull() ?: return false
        val type = payload.optString("type").trim()
        if (type.isBlank()) return false

        when (type) {
            "system" -> {
                val subtype = payload.optString("subtype").trim()
                if (subtype.equals("init", ignoreCase = true)) {
                    appendClaudeProcessLine(state.processLines, "Claude 会话已初始化")
                }
            }
            "assistant" -> {
                val message = payload.optJSONObject("message")
                if (message != null) {
                    parseClaudeMessageContent(message, state)
                }
                val error = payload.optString("error").trim()
                if (error.isNotBlank()) {
                    appendClaudeProcessLine(state.processLines, "Claude 错误: $error")
                }
            }
            "result" -> {
                val result = payload.optString("result").trim()
                if (result.isNotBlank()) {
                    state.resultText = result
                }
                val isError = payload.optBoolean("is_error", false)
                val terminalReason = payload.optString("terminal_reason").trim()
                if (terminalReason.isNotBlank()) {
                    appendClaudeProcessLine(state.processLines, "Claude 运行状态: $terminalReason")
                }
                if (isError && result.isNotBlank()) {
                    appendClaudeProcessLine(state.processLines, "Claude 返回错误: $result")
                }
            }
            else -> {
                val subtype = payload.optString("subtype").trim()
                val status = payload.optString("status").trim()
                val phase = listOf(type, subtype, status)
                    .filter { it.isNotBlank() }
                    .joinToString("/")
                    .trim()
                if (phase.isNotBlank() && !phase.equals("assistant", ignoreCase = true)) {
                    appendClaudeProcessLine(state.processLines, "事件: $phase")
                }
            }
        }
        return true
    }

    private fun parseClaudeMessageContent(message: JSONObject, state: ClaudeStreamParseState) {
        val role = message.optString("role").trim().lowercase(Locale.US)
        val content = message.optJSONArray("content") ?: return
        val assistantParts = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            val blockType = block.optString("type").trim().lowercase(Locale.US)
            when (blockType) {
                "text" -> {
                    val text = block.optString("text").trim()
                    if (text.isNotBlank() && role == "assistant") {
                        assistantParts += text
                    }
                }
                "thinking" -> {
                    val thinking = block.optString("thinking").trim()
                    if (thinking.isNotBlank()) {
                        appendClaudeProcessLine(state.processLines, "思考: ${clipProcessText(thinking)}")
                    }
                }
                "redacted_thinking" -> {
                    appendClaudeProcessLine(state.processLines, "思考: [模型返回了加密思考片段]")
                }
                "tool_use" -> {
                    val id = block.optString("id").trim()
                    if (id.isNotBlank() && !state.seenToolUseIds.add(id)) continue
                    val name = block.optString("name").trim().ifBlank { "unknown_tool" }
                    val inputText = block.opt("input")?.toString()?.trim().orEmpty()
                    val suffix = if (inputText.isBlank()) "" else " 参数: ${clipProcessText(inputText)}"
                    appendClaudeProcessLine(state.processLines, "工具调用: $name$suffix")
                }
                "tool_result" -> {
                    val id = block.optString("tool_use_id").trim()
                    if (id.isNotBlank() && !state.seenToolResultIds.add(id)) continue
                    val contentText = block.opt("content")?.toString()?.trim().orEmpty()
                    val suffix = if (contentText.isBlank()) "" else " 输出: ${clipProcessText(contentText)}"
                    appendClaudeProcessLine(state.processLines, "工具结果:$suffix")
                }
            }
        }
        if (assistantParts.isNotEmpty()) {
            state.assistantText = assistantParts.joinToString("\n").trim()
        }
    }

    private fun chooseClaudeAssistantText(state: ClaudeStreamParseState): String {
        if (state.assistantText.isNotBlank()) return state.assistantText
        if (state.resultText.isNotBlank()) return state.resultText
        return ""
    }

    private fun appendClaudeProcessLine(lines: MutableList<String>, line: String) {
        val normalized = line.trim()
        if (normalized.isBlank()) return
        if (lines.lastOrNull() == normalized) return
        lines += normalized
        recordLiveProcessLine(normalized)
    }

    private fun clipProcessText(value: String, maxChars: Int = 220): String {
        val singleLine = value
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (singleLine.length <= maxChars) return singleLine
        return singleLine.take(maxChars) + "..."
    }

    private fun shouldIgnoreProcessLine(line: String): Boolean {
        if (line.isBlank()) return true
        if (line == "LOGIN_SUCCESSFUL" || line == "TERMINAL_READY") return true
        if (isClaudeStdinWarningLine(line)) return true
        return false
    }

    private fun isClaudeStdinWarningLine(line: String): Boolean {
        val normalized = line.trim().lowercase(Locale.US)
        if (normalized.startsWith("warning: no stdin data received")) return true
        if (normalized.startsWith("claude code warning: no stdin data received")) return true
        if (normalized.startsWith("if piping from a slow command, redirect stdin explicitly")) return true
        return false
    }

    private fun recordLiveProcessLine(line: String) {
        synchronized(liveProcessLines) {
            liveProcessLines += line
        }
        val now = System.currentTimeMillis()
        if (now - lastLiveRenderAtMs < 350L) return
        lastLiveRenderAtMs = now
        runOnUiThread {
            if (sending) {
                renderSession()
            }
        }
    }

    private fun clearLiveProcessLines() {
        synchronized(liveProcessLines) {
            liveProcessLines.clear()
        }
    }

    private fun snapshotLiveProcessLines(): List<String> {
        return synchronized(liveProcessLines) {
            liveProcessLines.toList()
        }
    }

    private fun abortRunningTask() {
        val proc = activeProcess
        if (!sending || proc == null) {
            Toast.makeText(this, getString(R.string.cli_abort_none), Toast.LENGTH_SHORT).show()
            return
        }
        abortRequested = true
        proc.destroy()
        Thread {
            Thread.sleep(1200)
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
        }.start()
        Toast.makeText(this, getString(R.string.cli_abort_sent), Toast.LENGTH_SHORT).show()
    }

    private fun buildUserMessageText(input: String, attachments: List<LocalAttachment>): String {
        if (attachments.isEmpty()) return input
        val out = StringBuilder()
        out.appendLine(input)
        out.appendLine()
        out.appendLine("本条消息附加文件如下：")
        attachments.forEach { attachment ->
            out.appendLine("- ${attachment.displayName}")
            out.appendLine("  路径: ${attachment.absolutePath}")
        }
        return out.toString().trim()
    }

    private fun buildAttachmentSummary(): String {
        if (attachedFiles.isEmpty()) {
            return getString(R.string.cli_attachment_empty)
        }
        val names = attachedFiles.joinToString("，") { it.displayName }
        return getString(R.string.cli_attachment_count_prefix) + names
    }

    private fun showAttachmentPicker() {
        val items = arrayOf(
            getString(R.string.cli_attachment_picker_files),
            getString(R.string.cli_attachment_picker_gallery),
            getString(R.string.cli_attachment_picker_camera),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cli_attachment_picker_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchFilePicker("*/*")
                    1 -> launchGalleryPicker()
                    2 -> launchCameraCapture()
                }
            }
            .show()
    }

    private fun launchFilePicker(mimeType: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        filePickerLauncher.launch(intent)
    }

    private fun launchGalleryPicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            galleryPickerLauncher.launch(intent)
        } catch (_: Exception) {
            launchFilePicker("image/*")
        }
    }

    private fun launchCameraCapture() {
        val prepared = buildCameraCaptureIntent()
        if (prepared == null) {
            Toast.makeText(this, getString(R.string.cli_attachment_failed), Toast.LENGTH_SHORT).show()
            return
        }
        pendingCameraUri = prepared.second
        cameraCaptureLauncher.launch(prepared.first)
    }

    private fun buildCameraCaptureIntent(): Pair<Intent, Uri>? {
        return try {
            val attachmentsDir = File(cacheDir, "attachments")
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }
            val targetFile = File.createTempFile("cli_capture_", ".jpg", attachmentsDir)
            val authority = "$packageName.fileprovider"
            val captureUri = FileProvider.getUriForFile(this, authority, targetFile)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val activities = packageManager.queryIntentActivities(intent, 0)
            if (activities.isEmpty()) {
                null
            } else {
                activities.forEach { resolveInfo ->
                    grantUriPermission(
                        resolveInfo.activityInfo.packageName,
                        captureUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                intent to captureUri
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun collectSelectionUris(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != RESULT_OK) return emptyList()
        val ordered = LinkedHashSet<Uri>()
        data?.data?.let { ordered.add(it) }
        data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let { ordered.add(it) }
            }
        }
        return ordered.toList()
    }

    private fun handlePickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Thread {
            val copied = mutableListOf<LocalAttachment>()
            uris.forEach { uri ->
                runCatching { copyAttachmentToWorkspace(uri) }.getOrNull()?.let { copied += it }
            }
            runOnUiThread {
                if (copied.isEmpty()) {
                    Toast.makeText(this, getString(R.string.cli_attachment_failed), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                attachedFiles += copied
                renderSession()
                Toast.makeText(this, getString(R.string.cli_attachment_added), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun copyAttachmentToWorkspace(uri: Uri): LocalAttachment? {
        val targetDir = File(
            BootstrapInstaller.getPaths(this).homeDir,
            ".pocketlobster/attachments/${agentId.value}/${activeSession.sessionId}",
        )
        targetDir.mkdirs()
        val baseName = guessAttachmentName(uri)
        val targetFile = uniqueAttachmentFile(targetDir, sanitizeAttachmentName(baseName))
        contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        return LocalAttachment(
            displayName = targetFile.name,
            absolutePath = targetFile.absolutePath,
        )
    }

    private fun guessAttachmentName(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getString(index)?.trim().orEmpty()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        val raw = uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty()
        return raw.ifEmpty { "attachment_${System.currentTimeMillis()}" }
    }

    private fun sanitizeAttachmentName(value: String): String {
        val cleaned = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return cleaned.ifBlank { "attachment_${System.currentTimeMillis()}" }
    }

    private fun uniqueAttachmentFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val prefix = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${prefix}_$index$suffix")
            index += 1
        }
        return candidate
    }

    private fun buildRuntimeProbeBlock(options: AgentRuntimeOptions): String {
        val localCapability = runPrefixCapture("codex-capabilities --plain 2>&1 || true")
        val localChain = runPrefixCapture(
            "id 2>&1; pwd 2>&1; command -v system-shell 2>&1 || true; command -v ubuntu-shell 2>&1 || true",
        )
        val systemChain = runPrefixCapture("system-shell id 2>&1 || true")
        val ubuntuChain = runUbuntuCapture(
            "id 2>&1; pwd 2>&1; command -v system-shell 2>&1 || true; command -v ubuntu-shell 2>&1 || true; echo ANYCLAW_UBUNTU_BIN=${'$'}ANYCLAW_UBUNTU_BIN",
        )
        return buildString {
            appendLine("checked_at_ms=${System.currentTimeMillis()}")
            appendLine("allow_shared_storage=${if (options.allowSharedStorage) 1 else 0}")
            appendLine("dangerous_mode=${if (options.dangerousAutoApprove) 1 else 0}")
            appendLine(formatProbe("local_capability", localCapability))
            appendLine(formatProbe("local_chain", localChain))
            appendLine(formatProbe("system_shell", systemChain))
            appendLine(formatProbe("ubuntu_chain", ubuntuChain))
        }.trim()
    }

    private fun buildClaudeMcpProbeBlock(options: AgentRuntimeOptions): String {
        val configFile = ensureClaudeAnyClawMcpConfig(options)
        val serverFile = File(configFile.parentFile, "anyclaw-toolbox-server.cjs")
        val toolNames = if (serverFile.exists()) {
            extractAnyClawToolNames(serverFile.readText())
        } else {
            emptyList()
        }
        val required = listOf(
            "anyclaw_device_exec",
            "anyclaw_device_screen_info",
            "anyclaw_search_web",
            "anyclaw_exa_search",
            "anyclaw_exa_search_advanced",
            "anyclaw_exa_search_api",
            "anyclaw_duckduckgo_search",
            "anyclaw_github_repo",
            "anyclaw_github_repo_info",
            "start_web",
            "web_snapshot",
        )
        val missing = required.filterNot { toolNames.contains(it) }
        val statusHint = when {
            !configFile.exists() -> "CONFIG_MISSING"
            !serverFile.exists() -> "SERVER_SCRIPT_MISSING"
            missing.isNotEmpty() -> "REQUIRED_TOOLS_MISSING"
            else -> "READY"
        }
        return buildString {
            appendLine("mcp_config_path=${configFile.absolutePath}")
            appendLine("mcp_config_exists=${if (configFile.exists()) 1 else 0}")
            appendLine("mcp_server_path=${serverFile.absolutePath}")
            appendLine("mcp_server_exists=${if (serverFile.exists()) 1 else 0}")
            appendLine("anyclaw_tools_count=${toolNames.size}")
            appendLine("anyclaw_tools_declared=${if (toolNames.isEmpty()) "none" else toolNames.joinToString(",")}")
            appendLine("required_probe_tools=${required.joinToString(",")}")
            appendLine("required_probe_missing=${if (missing.isEmpty()) "none" else missing.joinToString(",")}")
            appendLine("mcp_toolbox_status_hint=$statusHint")
            appendLine("if_tools_unavailable_report=MCP_TOOLBOX_STATUS=UNAVAILABLE reason=<no_anyclaw_tools|tool_call_error|tool_timeout> step=<list|device|search|github>")
        }.trim()
    }

    private fun extractAnyClawToolNames(script: String): List<String> {
        return Regex("""[a-z0-9_]*anyclaw_[a-z0-9_]+|start_web|stop_web|web_[a-z_]+""")
            .findAll(script.lowercase(Locale.getDefault()))
            .map { it.value.trim() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun formatProbe(name: String, result: ProbeResult): String {
        val trimmed = trimProbeOutput(result.output)
        return "$name(exit=${result.code}): $trimmed"
    }

    private fun trimProbeOutput(value: String, maxChars: Int = 520): String {
        val singleLine = value
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(8)
            .joinToString(" | ")
        if (singleLine.length <= maxChars) return singleLine
        return singleLine.take(maxChars) + "..."
    }

    private fun runPrefixCapture(command: String): ProbeResult {
        val output = StringBuilder()
        val code = serverManager.runInPrefix(command) { line ->
            output.appendLine(stripAnsi(line))
        }
        return ProbeResult(code = code, output = output.toString().trim())
    }

    private fun runUbuntuCapture(command: String): ProbeResult {
        val output = StringBuilder()
        val code = serverManager.runInUbuntu(command) { line ->
            val cleaned = stripAnsi(line).trim()
            if (cleaned == "LOGIN_SUCCESSFUL" || cleaned == "TERMINAL_READY") return@runInUbuntu
            output.appendLine(cleaned)
        }
        return ProbeResult(code = code, output = output.toString().trim())
    }

    private fun cleanClaudeOutput(raw: String): String {
        val filtered = raw.lineSequence()
            .filterNot { isClaudeStdinWarningLine(it) }
            .joinToString("\n")
            .trim()
        return filtered.ifBlank { raw.trim() }
    }

    private fun stripAnsi(value: String): String {
        return value.replace(Regex("\\u001B\\[[;\\d]*[A-Za-z]"), "")
    }

    private fun runtimeOptionKey(name: String): String = "${agentId.value}_$name"

    private fun loadRuntimeOptions(): AgentRuntimeOptions {
        val prefs = getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE)
        return AgentRuntimeOptions(
            allowSharedStorage = prefs.getBoolean(runtimeOptionKey("allow_shared_storage"), true),
            dangerousAutoApprove = prefs.getBoolean(runtimeOptionKey("dangerous_mode"), false),
        )
    }

    private fun saveRuntimeOptions(options: AgentRuntimeOptions) {
        getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(runtimeOptionKey("allow_shared_storage"), options.allowSharedStorage)
            .putBoolean(runtimeOptionKey("dangerous_mode"), options.dangerousAutoApprove)
            .apply()
    }

    private fun loadHistoryWindowSize(): Int {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .getInt(runtimeOptionKey("history_window"), DEFAULT_VISIBLE_HISTORY)
            .coerceIn(DEFAULT_VISIBLE_HISTORY, MAX_VISIBLE_HISTORY)
    }

    private fun saveHistoryWindowSize(value: Int) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(runtimeOptionKey("history_window"), value)
            .apply()
    }

    private fun loadShowProcess(): Boolean {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .getBoolean(runtimeOptionKey("show_process"), true)
    }

    private fun saveShowProcess(value: Boolean) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(runtimeOptionKey("show_process"), value)
            .apply()
    }

    private fun ensureClaudeAnyClawMcpConfig(options: AgentRuntimeOptions): File {
        val paths = BootstrapInstaller.getPaths(this)
        val mcpDir = File(paths.homeDir, ".pocketlobster/mcp")
        if (!mcpDir.exists()) {
            mcpDir.mkdirs()
        }
        val configFile = File(mcpDir, "claude-anyclaw-mcp.json")

        val serverFile = File(mcpDir, "anyclaw-toolbox-server.cjs")
        val serverScript = buildAnyClawToolboxServerScript()
        writeTextIfChanged(serverFile, serverScript)
        serverFile.setExecutable(true)

        val existingRoot = readJsonObjectSafely(configFile)
        val existingServers = existingRoot?.optJSONObject("mcpServers")
        val existingServerConfig = existingServers?.optJSONObject("anyclaw_toolbox")
        val existingEnv = existingServerConfig?.optJSONObject("env")

        val systemShellPath = File(paths.prefixDir, "bin/system-shell")
        val envJson = JSONObject()
        copyJsonProperties(existingEnv, envJson)
        envJson
            .put("HOME", paths.homeDir)
            .put("PREFIX", paths.prefixDir)
            .put("PATH", "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin")
            .put("ANYCLAW_WEB_BRIDGE_URL", "http://127.0.0.1:${ShizukuShellBridgeServer.BRIDGE_PORT}/web/call")
            .put("ANYCLAW_TAVILY_BASE_URL", "https://api.tavily.com/search")
            .put("ANYCLAW_EXA_MCP_URL", "https://mcp.exa.ai/mcp")
            .put("ANYCLAW_EXA_MCP_TOOLS", "web_search_exa,web_search_advanced_exa,get_code_context_exa,company_research_exa,people_search_exa,crawling_exa,deep_researcher_start,deep_researcher_check,web_fetch_exa")
            .put("ANYCLAW_EXA_API_BASE_URL", "https://api.exa.ai")
            .put("ANYCLAW_GITHUB_API_BASE_URL", "https://api.github.com")
            .put("ANYCLAW_WORKSPACE_ROOT", "${paths.homeDir}/.openclaw/workspace")
            .put("ANYCLAW_ALLOW_SHARED_STORAGE", if (options.allowSharedStorage) "1" else "0")
            .put("ANYCLAW_MCP_CONFIG_PATH", configFile.absolutePath)
        val passThroughEnv = listOf(
            "GITHUB_TOKEN",
            "GH_TOKEN",
            "ANYCLAW_GITHUB_TOKEN",
            "TAVILY_API_KEY",
            "ANYCLAW_TAVILY_API_KEY",
            "EXA_API_KEY",
            "ANYCLAW_EXA_API_KEY",
        )
        passThroughEnv.forEach { key ->
            val value = System.getenv(key)?.trim()
            if (!value.isNullOrBlank()) {
                envJson.put(key, value)
            }
        }
        if (systemShellPath.exists()) {
            envJson.put("ANYCLAW_SYSTEM_SHELL_BIN", systemShellPath.absolutePath)
        }

        val serverConfig = JSONObject()
        copyJsonProperties(existingServerConfig, serverConfig)
        serverConfig
            .put("command", "${paths.prefixDir}/bin/node")
            .put("args", JSONArray().put(serverFile.absolutePath))
            .put("env", envJson)

        val mcpServers = JSONObject()
        copyJsonProperties(existingServers, mcpServers)
        mcpServers.put("anyclaw_toolbox", serverConfig)
        val root = JSONObject()
        copyJsonProperties(existingRoot, root)
        root.put("mcpServers", mcpServers)

        writeTextIfChanged(configFile, root.toString(2) + "\n")
        return configFile
    }

    private fun writeTextIfChanged(target: File, content: String) {
        val current = if (target.exists()) target.readText() else null
        if (current == content) return
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    private fun readJsonObjectSafely(file: File): JSONObject? {
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun copyJsonProperties(from: JSONObject?, to: JSONObject) {
        if (from == null) return
        val keys = from.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            to.put(key, from.opt(key))
        }
    }

    private fun buildAnyClawToolboxServerScript(): String {
        return try {
            assets.open("anyclaw/claude-toolbox-server.js")
                .bufferedReader()
                .use { it.readText() }
                .trimEnd() + "\n"
        } catch (_: Exception) {
            "#!/usr/bin/env node\nconsole.error(\"Failed to load anyclaw/claude-toolbox-server.js\");\nprocess.exit(1);\n"
        }
    }

    private fun restartWithAgent(next: ExternalAgentId, sessionId: String?) {
        startActivity(
            Intent(this, CliAgentChatActivity::class.java).apply {
                putExtra(EXTRA_AGENT_ID, next.value)
                if (!sessionId.isNullOrBlank()) {
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            },
        )
        finish()
    }

    private fun parseAgent(raw: String?): ExternalAgentId {
        return ExternalAgentId.entries.firstOrNull { it.value == raw?.trim() } ?: ExternalAgentId.CLAUDE_CODE
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("返回智能体入口")
            .setMessage("要返回智能体入口页吗？")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                startActivity(Intent(this, AgentHubActivity::class.java))
                finish()
            }
            .show()
    }

    private inner class MessageAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleMessages.size

        override fun getItem(position: Int): Any = visibleMessages[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val rowView = convertView ?: LayoutInflater.from(this@CliAgentChatActivity)
                .inflate(R.layout.item_agent_chat_message, parent, false)
            val msg = visibleMessages[position]
            rowView.findViewById<TextView>(R.id.tvAgentChatRole).text = msg.roleText
            rowView.findViewById<TextView>(R.id.tvAgentChatText).text = msg.body
            return rowView
        }
    }
}
