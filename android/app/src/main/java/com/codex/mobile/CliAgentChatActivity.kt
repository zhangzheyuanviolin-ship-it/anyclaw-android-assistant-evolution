package com.codex.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
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
        private const val HERMES_HARD_PROMPT_BYTES = 120 * 1024
        private const val CLAUDE_SUMMARY_CLIP_CHARS = 420
        private const val CLAUDE_SUMMARY_TOTAL_MAX_CHARS = 12_000
        private const val HERMES_PROMPT_CLIP_NOTICE = "提示：上下文过长，已自动裁剪为最近会话片段。"
        private const val CLAUDE_NATIVE_SESSION_MODE = "claude_native_v1"
        private const val NATIVE_BIND_RETRY_COOLDOWN_MS = 20_000L
        private const val MENU_ACTION_COPY = 1
        private const val MENU_ACTION_DELETE = 2
        private const val MENU_ACTION_ROLLBACK = 3
        private const val MENU_ACTION_BRANCH = 4
        private const val MENU_ACTION_SPEAK = 5
        private val CLAUDE_SESSION_ID_REGEX = Regex("""claude-code-[A-Za-z0-9._-]+""")
        private val UUID_SESSION_ID_REGEX = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        private val HERMES_SESSION_ID_LINE_REGEX = Regex("""^session_id:\s*(.+)$""", RegexOption.IGNORE_CASE)
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
    private lateinit var btnHermes: Button

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
    private val nativeBindAttempts = mutableMapOf<String, Long>()

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var abortRequested = false

    @Volatile
    private var lastLiveRenderAtMs = 0L

    @Volatile
    private var nativeBindingInProgress = false

    @Volatile
    private var nativeBindingSessionId = ""

    @Volatile
    private var lastExecutionRoute = "未执行"

    private var ttsEngine: TextToSpeech? = null
    private var ttsReady = false

    private data class AgentRuntimeOptions(
        val allowSharedStorage: Boolean,
        val dangerousAutoApprove: Boolean,
        val claudeNativeSession: Boolean,
        val hermesGatewayEnabled: Boolean,
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
        val sourceRole: String,
        val sessionMessageIndex: Int? = null,
        val isLiveProcess: Boolean = false,
    )

    private data class AgentRunResult(
        val assistantText: String,
        val processText: String,
        val nativeSessionId: String = "",
        val nativeCompactApplied: Boolean = false,
    )

    private data class ClaudeStreamParseState(
        var assistantText: String = "",
        var resultText: String = "",
        var sessionId: String = "",
        var compactApplied: Boolean = false,
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
        btnHermes = findViewById(R.id.btnCliTabHermes)

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
        btnHermes.setOnClickListener {
            if (agentId == ExternalAgentId.HERMES_AGENT) return@setOnClickListener
            restartWithAgent(ExternalAgentId.HERMES_AGENT, null)
        }
    }

    override fun onResume() {
        super.onResume()
        val preferredSession = intent.getStringExtra(EXTRA_SESSION_ID)
        activeSession = AgentSessionStore.ensureActiveSession(this, agentId, preferredSession)
        refreshIdleRouteLabel()
        renderSession()
        ensureNativeSessionBoundIfNeeded(trigger = "resume")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            activeProcess?.destroy()
        }
        ttsEngine?.stop()
        ttsEngine?.shutdown()
        ttsEngine = null
        ttsReady = false
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
            val mode = when (agentId) {
                ExternalAgentId.CLAUDE_CODE -> if (runtimeOptions.claudeNativeSession) "原生会话" else "兼容会话"
                ExternalAgentId.HERMES_AGENT -> if (runtimeOptions.hermesGatewayEnabled) "网关协同" else "直连会话"
            }
            val nativeTag = buildNativeStatusTag()
            "就绪 · 共享存储:$shared · 高权限:$danger · 会话模式:$mode · native:$nativeTag · 链路:$lastExecutionRoute"
        }
        tvAttachmentSummary.text = buildAttachmentSummary()
        renderBottomTabState()

        btnAttach.isEnabled = !sending
        btnClearAttachments.isEnabled = attachedFiles.isNotEmpty() && !sending
        btnCompact.isEnabled = !sending && agentId == ExternalAgentId.CLAUDE_CODE
        btnAbort.isEnabled = sending
        btnSend.isEnabled = !sending
        inputMessage.isEnabled = !sending

        val storedMessages = filteredMessagesForDisplayIndexed()

        val displayItems = mutableListOf<DisplayMessage>()
        storedMessages.forEach { indexed ->
            val msg = indexed.value
            val role = msg.role.lowercase(Locale.getDefault())
            val roleText = when (role) {
                "user" -> "您"
                "assistant" -> AgentSessionStore.displayAgentName(agentId)
                "process" -> getString(R.string.cli_process_role)
                else -> role
            }
            displayItems += DisplayMessage(
                roleText = roleText,
                body = msg.text,
                sourceRole = role,
                sessionMessageIndex = indexed.index,
            )
        }
        if (showProcess) {
            val live = snapshotLiveProcessLines().joinToString("\n").trim()
            if (live.isNotEmpty()) {
                displayItems += DisplayMessage(
                    roleText = getString(R.string.cli_process_role),
                    body = live,
                    sourceRole = "process",
                    isLiveProcess = true,
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

    private fun filteredMessagesForDisplayIndexed(): List<IndexedValue<AgentChatMessage>> {
        val base = activeSession.messages.withIndex().filter {
            showProcess || !it.value.role.equals("process", ignoreCase = true)
        }
        return if (base.size <= historyWindowSize) base else base.takeLast(historyWindowSize)
    }

    private fun filteredMessagesForDisplay(): List<AgentChatMessage> {
        return filteredMessagesForDisplayIndexed().map { it.value }
    }

    private fun renderBottomTabState() {
        btnClaude.isEnabled = agentId != ExternalAgentId.CLAUDE_CODE
        btnHermes.isEnabled = agentId != ExternalAgentId.HERMES_AGENT
    }

    private fun buildNativeStatusTag(): String {
        if (agentId == ExternalAgentId.HERMES_AGENT) {
            return if (runtimeOptions.hermesGatewayEnabled) "网关已启用" else "网关关闭"
        }
        if (agentId != ExternalAgentId.CLAUDE_CODE) return "n/a"
        if (!runtimeOptions.claudeNativeSession) return "关闭"
        val nativeId = activeSession.nativeSessionId.trim()
        if (nativeId.isNotBlank()) {
            return "已绑定(${nativeId.take(10)})"
        }
        if (nativeBindingInProgress && nativeBindingSessionId == activeSession.sessionId) {
            return "绑定中"
        }
        return "已启用(自动绑定)"
    }

    private fun refreshIdleRouteLabel() {
        if (sending) return
        lastExecutionRoute = when {
            agentId == ExternalAgentId.HERMES_AGENT && runtimeOptions.hermesGatewayEnabled -> "Hermes网关协同"
            agentId == ExternalAgentId.HERMES_AGENT -> "Hermes直连"
            agentId != ExternalAgentId.CLAUDE_CODE -> "兼容链路"
            runtimeOptions.claudeNativeSession && activeSession.nativeSessionId.isNotBlank() -> "原生链路"
            runtimeOptions.claudeNativeSession -> "原生链路(自动绑定)"
            else -> "兼容链路"
        }
    }

    private fun markRouteOnStart(useNativeSession: Boolean) {
        lastExecutionRoute = when {
            agentId == ExternalAgentId.HERMES_AGENT && runtimeOptions.hermesGatewayEnabled -> "Hermes网关协同(执行中)"
            agentId == ExternalAgentId.HERMES_AGENT -> "Hermes直连(执行中)"
            useNativeSession -> "原生链路(执行中)"
            else -> "兼容链路(执行中)"
        }
        clearLiveProcessLines()
    }

    private fun ensureNativeSessionBoundIfNeeded(trigger: String, force: Boolean = false) {
        if (agentId != ExternalAgentId.CLAUDE_CODE) return
        if (!runtimeOptions.claudeNativeSession) return
        if (sending) return
        val snapshot = activeSession
        if (snapshot.nativeSessionId.isNotBlank()) return
        if (nativeBindingInProgress && nativeBindingSessionId == snapshot.sessionId) return

        val now = System.currentTimeMillis()
        val allowAttempt = synchronized(nativeBindAttempts) {
            val lastAt = nativeBindAttempts[snapshot.sessionId] ?: 0L
            val ready = force || now - lastAt >= NATIVE_BIND_RETRY_COOLDOWN_MS
            if (ready) {
                nativeBindAttempts[snapshot.sessionId] = now
            }
            ready
        }
        if (!allowAttempt) return

        val modelConfig = AgentModelConfigStore.loadCurrentConfig(this, agentId) ?: return
        if (snapshot.messages.isEmpty()) return
        if (snapshot.messages.any {
                it.role.equals("assistant", ignoreCase = true) &&
                    it.text.startsWith("【会话压缩摘要】")
            }
        ) {
            // Defer native binding until the next real user turn to preserve summary bootstrap semantics.
            return
        }
        nativeBindingInProgress = true
        nativeBindingSessionId = snapshot.sessionId
        renderSession()

        Thread {
            val result = runCatching {
                runClaudePrint(
                    config = modelConfig,
                    prompt = buildNativeBindPrompt(trigger),
                    options = runtimeOptions,
                    sendPromptViaStdin = false,
                )
            }.getOrNull()
            val capturedNative = result?.nativeSessionId?.trim().orEmpty()
            var updated = snapshot
            if (capturedNative.isNotBlank()) {
                updated = AgentSessionStore.updateNativeSession(
                    context = this,
                    agentId = agentId,
                    sessionId = snapshot.sessionId,
                    nativeSessionId = capturedNative,
                    nativeSessionMode = CLAUDE_NATIVE_SESSION_MODE,
                ) ?: snapshot
            }
            runOnUiThread {
                nativeBindingInProgress = false
                nativeBindingSessionId = ""
                if (activeSession.sessionId == updated.sessionId) {
                    activeSession = updated
                }
                if (capturedNative.isNotBlank()) {
                    lastExecutionRoute = "原生链路(已绑定)"
                } else {
                    refreshIdleRouteLabel()
                }
                renderSession()
            }
        }.start()
    }

    private fun buildNativeBindPrompt(trigger: String): String {
        return buildString {
            appendLine("你现在只执行原生会话绑定握手。")
            appendLine("要求：")
            appendLine("1) 不调用任何工具。")
            appendLine("2) 不做解释。")
            appendLine("3) 仅输出一行：NATIVE_SESSION_READY")
            appendLine("trigger=$trigger")
        }.trim()
    }

    private fun createNewSession() {
        activeSession = AgentSessionStore.createSession(this, agentId)
        attachedFiles.clear()
        clearLiveProcessLines()
        refreshIdleRouteLabel()
        renderSession()
        ensureNativeSessionBoundIfNeeded(trigger = "new_session", force = true)
        Toast.makeText(this, "已新建会话", Toast.LENGTH_SHORT).show()
    }

    private fun clearAttachments() {
        attachedFiles.clear()
        renderSession()
        Toast.makeText(this, getString(R.string.cli_attachment_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun showMessageActionMenu(anchor: View, message: DisplayMessage) {
        if (message.body.isBlank()) return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, MENU_ACTION_COPY, 0, getString(R.string.cli_message_action_copy))
        popup.menu.add(Menu.NONE, MENU_ACTION_DELETE, 1, getString(R.string.cli_message_action_delete))
        popup.menu.add(Menu.NONE, MENU_ACTION_ROLLBACK, 2, getString(R.string.cli_message_action_rollback))
        popup.menu.add(Menu.NONE, MENU_ACTION_BRANCH, 3, getString(R.string.cli_message_action_branch))
        popup.menu.add(Menu.NONE, MENU_ACTION_SPEAK, 4, getString(R.string.cli_message_action_speak))

        val canMutate = message.sessionMessageIndex != null && !sending
        popup.menu.findItem(MENU_ACTION_DELETE)?.isEnabled = canMutate
        popup.menu.findItem(MENU_ACTION_ROLLBACK)?.isEnabled = canMutate
        popup.menu.findItem(MENU_ACTION_BRANCH)?.isEnabled = canMutate

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ACTION_COPY -> {
                    copyMessageText(message.body)
                    true
                }
                MENU_ACTION_DELETE -> {
                    confirmDeleteFromMessage(message)
                    true
                }
                MENU_ACTION_ROLLBACK -> {
                    confirmRollbackToMessage(message)
                    true
                }
                MENU_ACTION_BRANCH -> {
                    createBranchFromMessage(message)
                    true
                }
                MENU_ACTION_SPEAK -> {
                    speakMessageText(message.body)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun copyMessageText(text: String) {
        val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipManager == null) {
            Toast.makeText(this, getString(R.string.conversation_copy_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val clip = ClipData.newPlainText("cli_message", text)
        clipManager.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.conversation_copied_toast), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteFromMessage(message: DisplayMessage) {
        val targetIndex = message.sessionMessageIndex ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cli_message_action_delete_title))
            .setMessage(getString(R.string.cli_message_action_delete_confirm))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                deleteSingleMessage(targetIndex)
            }
            .show()
    }

    private fun deleteSingleMessage(targetIndex: Int) {
        val current = activeSession
        if (targetIndex !in current.messages.indices) return
        val nextMessages = current.messages.toMutableList()
        nextMessages.removeAt(targetIndex)
        applySessionMutation(nextMessages)
        Toast.makeText(this, getString(R.string.cli_message_action_delete_done), Toast.LENGTH_SHORT).show()
    }

    private fun confirmRollbackToMessage(message: DisplayMessage) {
        val targetIndex = message.sessionMessageIndex ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cli_message_action_rollback_title))
            .setMessage(getString(R.string.cli_message_action_rollback_confirm))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                rollbackToMessage(targetIndex)
            }
            .show()
    }

    private fun rollbackToMessage(targetIndex: Int) {
        val current = activeSession
        if (targetIndex !in current.messages.indices) return
        val nextMessages = current.messages.take(targetIndex + 1)
        applySessionMutation(nextMessages)
        Toast.makeText(this, getString(R.string.cli_message_action_rollback_done), Toast.LENGTH_SHORT).show()
    }

    private fun createBranchFromMessage(message: DisplayMessage) {
        val targetIndex = message.sessionMessageIndex ?: return
        val current = activeSession
        if (targetIndex !in current.messages.indices) return
        val branchedMessages = current.messages.take(targetIndex + 1)
        val baseTitle = current.title.trim().ifEmpty { "Claude Code 会话" }
        val branchTitle = "$baseTitle（分支）"
        var next = AgentSessionStore.createSession(this, agentId, branchTitle)
        next = next.copy(
            updatedAtMs = System.currentTimeMillis(),
            messages = branchedMessages,
            nativeSessionId = "",
            nativeSessionMode = "",
        )
        activeSession = AgentSessionStore.overwriteSession(this, next)
        attachedFiles.clear()
        clearLiveProcessLines()
        refreshIdleRouteLabel()
        renderSession()
        Toast.makeText(this, getString(R.string.cli_message_action_branch_done), Toast.LENGTH_SHORT).show()
    }

    private fun applySessionMutation(messages: List<AgentChatMessage>) {
        val current = activeSession
        val clearNative = agentId == ExternalAgentId.CLAUDE_CODE
        val next = current.copy(
            updatedAtMs = System.currentTimeMillis(),
            messages = messages,
            nativeSessionId = if (clearNative) "" else current.nativeSessionId,
            nativeSessionMode = if (clearNative) "" else current.nativeSessionMode,
        )
        activeSession = AgentSessionStore.overwriteSession(this, next)
        attachedFiles.clear()
        clearLiveProcessLines()
        refreshIdleRouteLabel()
        renderSession()
    }

    private fun speakMessageText(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        val engine = ttsEngine
        if (engine != null && ttsReady) {
            engine.stop()
            engine.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, "cli-message")
            return
        }
        ttsEngine = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Toast.makeText(this, getString(R.string.cli_message_action_speak_failed), Toast.LENGTH_SHORT).show()
                return@TextToSpeech
            }
            val tts = ttsEngine ?: return@TextToSpeech
            val zhResult = tts.setLanguage(Locale.CHINA)
            ttsReady = zhResult != TextToSpeech.LANG_MISSING_DATA && zhResult != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) {
                val fallback = tts.setLanguage(Locale.getDefault())
                ttsReady = fallback != TextToSpeech.LANG_MISSING_DATA && fallback != TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (!ttsReady) {
                Toast.makeText(this, getString(R.string.cli_message_action_speak_failed), Toast.LENGTH_SHORT).show()
                return@TextToSpeech
            }
            tts.stop()
            tts.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, "cli-message")
        }
    }

    private fun compactSessionManually() {
        if (sending) {
            Toast.makeText(this, getString(R.string.cli_compact_blocked_sending), Toast.LENGTH_SHORT).show()
            return
        }
        if (agentId == ExternalAgentId.CLAUDE_CODE && runtimeOptions.claudeNativeSession) {
            runNativeManualCompaction()
            return
        }
        runCompatibilityCompactionAndMaybeBind(trigger = "manual_button")
    }

    private fun runNativeManualCompaction() {
        if (activeSession.nativeSessionId.isBlank()) {
            runCompatibilityCompactionAndMaybeBind(trigger = "manual_button_native_no_bind")
            return
        }
        val modelConfig = AgentModelConfigStore.loadCurrentConfig(this, agentId)
        if (modelConfig == null) {
            Toast.makeText(this, "请先在模型管理中配置${AgentSessionStore.displayAgentName(agentId)}模型", Toast.LENGTH_LONG).show()
            return
        }
        sending = true
        abortRequested = false
        markRouteOnStart(useNativeSession = true)
        renderSession()
        Thread {
            val result = runCatching {
                runClaudePrint(
                    config = modelConfig,
                    prompt = "/compact",
                    options = runtimeOptions,
                    resumeSessionId = activeSession.nativeSessionId.trim(),
                    sendPromptViaStdin = false,
                )
            }.getOrElse { error ->
                AgentRunResult(
                    assistantText = "上下文压缩执行失败：${error.message ?: "unknown error"}",
                    processText = snapshotLiveProcessLines().joinToString("\n").trim(),
                )
            }
            val nativeCompactFailed = result.assistantText.startsWith("上下文压缩执行失败：") ||
                result.processText.contains("Claude 返回错误")
            if (nativeCompactFailed) {
                runOnUiThread {
                    sending = false
                    activeProcess = null
                    clearLiveProcessLines()
                    lastExecutionRoute = "原生压缩失败→兼容压缩回退"
                    runCompatibilityCompactionAndMaybeBind(trigger = "manual_button_native_fallback_after_failure")
                }
                return@Thread
            }
            var nextSession = activeSession
            if (result.nativeSessionId.isNotBlank()) {
                nextSession = AgentSessionStore.updateNativeSession(
                    context = this,
                    agentId = agentId,
                    sessionId = nextSession.sessionId,
                    nativeSessionId = result.nativeSessionId,
                    nativeSessionMode = CLAUDE_NATIVE_SESSION_MODE,
                ) ?: nextSession
            }
            nextSession = AgentSessionStore.appendMessage(
                this,
                agentId,
                nextSession.sessionId,
                "assistant",
                if (result.nativeCompactApplied) "已完成原生上下文压缩。" else "已触发原生上下文压缩。",
            )
            activeSession = nextSession
            runOnUiThread {
                sending = false
                activeProcess = null
                clearLiveProcessLines()
                lastExecutionRoute = "原生链路"
                renderSession()
                Toast.makeText(this, getString(R.string.cli_compact_done_native), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun runCompatibilityCompactionAndMaybeBind(trigger: String) {
        val compacted = maybeCompactSessionIfNeeded(trigger = trigger, force = true)
        if (compacted) {
            lastExecutionRoute = "兼容压缩链路"
            renderSession()
            Toast.makeText(this, getString(R.string.cli_compact_done), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.cli_compact_not_needed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeCompactSessionIfNeeded(trigger: String, force: Boolean = false): Boolean {
        if (agentId != ExternalAgentId.CLAUDE_CODE) return false
        if (runtimeOptions.claudeNativeSession && !force) return false
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

    private fun trimToUtf8Tail(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (promptUtf8Bytes(value) <= maxBytes) return value
        var start = 0
        var bytes = promptUtf8Bytes(value)
        while (start < value.length && bytes > maxBytes) {
            val step = if (start + 1 < value.length &&
                Character.isHighSurrogate(value[start]) &&
                Character.isLowSurrogate(value[start + 1])
            ) {
                2
            } else {
                1
            }
            bytes -= value.substring(start, start + step).toByteArray(Charsets.UTF_8).size
            start += step
        }
        return value.substring(start)
    }

    private fun isArgumentListTooLong(error: Throwable): Boolean {
        val message = error.message?.lowercase(Locale.US).orEmpty()
        return message.contains("argument list too long") || message.contains("error=7")
    }

    private fun isNativeResultUnusable(result: AgentRunResult): Boolean {
        val assistant = result.assistantText.trim()
        if (assistant.isBlank()) return true
        if (assistant == "Claude 未返回可解析内容") return true
        return false
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
        val switchNative = view.findViewById<SwitchCompat>(R.id.switchCliSettingClaudeNativeSession)
        val switchHermesGateway = view.findViewById<SwitchCompat>(R.id.switchCliSettingHermesGateway)
        val btnPermission = view.findViewById<Button>(R.id.btnCliSettingPermissionCenter)
        val btnPrompt = view.findViewById<Button>(R.id.btnCliSettingPromptManager)
        val btnConversation = view.findViewById<Button>(R.id.btnCliSettingConversationManager)
        val btnModel = view.findViewById<Button>(R.id.btnCliSettingModelManager)
        val btnLoadOlder = view.findViewById<Button>(R.id.btnCliSettingLoadOlder)
        val btnRestoreLite = view.findViewById<Button>(R.id.btnCliSettingRestoreLite)

        switchAllow.isChecked = runtimeOptions.allowSharedStorage
        switchDanger.isChecked = runtimeOptions.dangerousAutoApprove
        switchProcess.isChecked = showProcess
        switchNative.isChecked = runtimeOptions.claudeNativeSession
        switchHermesGateway.isChecked = runtimeOptions.hermesGatewayEnabled
        switchNative.isEnabled = agentId == ExternalAgentId.CLAUDE_CODE
        switchNative.visibility = if (agentId == ExternalAgentId.CLAUDE_CODE) View.VISIBLE else View.GONE
        switchHermesGateway.isEnabled = agentId == ExternalAgentId.HERMES_AGENT
        switchHermesGateway.visibility = if (agentId == ExternalAgentId.HERMES_AGENT) View.VISIBLE else View.GONE

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
        switchNative.setOnCheckedChangeListener { _, checked ->
            runtimeOptions = runtimeOptions.copy(claudeNativeSession = checked)
            saveRuntimeOptions(runtimeOptions)
            if (!checked) {
                nativeBindingInProgress = false
                nativeBindingSessionId = ""
            }
            refreshIdleRouteLabel()
            renderSession()
            if (checked) {
                ensureNativeSessionBoundIfNeeded(trigger = "switch_on", force = true)
            }
        }
        switchHermesGateway.setOnCheckedChangeListener { _, checked ->
            runtimeOptions = runtimeOptions.copy(hermesGatewayEnabled = checked)
            saveRuntimeOptions(runtimeOptions)
            refreshIdleRouteLabel()
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
        val useNativeSession = agentId == ExternalAgentId.CLAUDE_CODE && runtimeOptions.claudeNativeSession

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
        markRouteOnStart(useNativeSession)
        sending = true
        abortRequested = false
        renderSession()

        Thread {
            var usedCompatibilityFallback = false
            var capturedNativeSessionId = ""
            val result = runCatching {
                val nativeSessionId = activeSession.nativeSessionId.trim()
                val bootstrapFromHistory = useNativeSession && nativeSessionId.isBlank()
                if (nativeSessionId.isNotBlank()) {
                    capturedNativeSessionId = nativeSessionId
                }
                if (agentId == ExternalAgentId.CLAUDE_CODE &&
                    !useNativeSession &&
                    maybeCompactSessionIfNeeded(trigger = "auto_threshold")
                ) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.cli_compact_auto_triggered), Toast.LENGTH_SHORT).show()
                        renderSession()
                    }
                }

                var prompt = if (useNativeSession && !bootstrapFromHistory) {
                    buildClaudeNativeTurnPrompt(userText, runtimeOptions)
                } else {
                    buildPromptWithHistory(activeSession.messages, runtimeOptions)
                }
                if (agentId == ExternalAgentId.CLAUDE_CODE &&
                    !useNativeSession &&
                    promptUtf8Bytes(prompt) > CLAUDE_HARD_PROMPT_BYTES
                ) {
                    if (maybeCompactSessionIfNeeded(trigger = "hard_limit_guard", force = true)) {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.cli_compact_auto_triggered), Toast.LENGTH_SHORT).show()
                            renderSession()
                        }
                    }
                    prompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                }
                if (agentId == ExternalAgentId.HERMES_AGENT &&
                    promptUtf8Bytes(prompt) > HERMES_HARD_PROMPT_BYTES
                ) {
                    val reserveBytes =
                        (HERMES_HARD_PROMPT_BYTES - promptUtf8Bytes(HERMES_PROMPT_CLIP_NOTICE) - 1)
                            .coerceAtLeast(16 * 1024)
                    val tail = trimToUtf8Tail(prompt, reserveBytes)
                    prompt = "$HERMES_PROMPT_CLIP_NOTICE\n$tail"
                    appendClaudeProcessLine(liveProcessLines, "Hermes 提示词过长，已自动裁剪后继续发送")
                }
                var firstResult = when (agentId) {
                    ExternalAgentId.CLAUDE_CODE -> runClaudePrint(
                        config = modelConfig,
                        prompt = prompt,
                        options = runtimeOptions,
                        resumeSessionId = if (useNativeSession) nativeSessionId else "",
                        sendPromptViaStdin = !useNativeSession || bootstrapFromHistory,
                    )
                    ExternalAgentId.HERMES_AGENT -> runHermesChat(
                        config = modelConfig,
                        prompt = prompt,
                        options = runtimeOptions,
                        resumeSessionId = activeSession.nativeSessionId.trim(),
                    )
                }
                if (firstResult.nativeSessionId.isNotBlank()) {
                    capturedNativeSessionId = firstResult.nativeSessionId
                }
                if (useNativeSession && isNativeResultUnusable(firstResult)) {
                    usedCompatibilityFallback = true
                    appendClaudeProcessLine(liveProcessLines, "原生链路未返回可解析结果，自动回退兼容链路重试")
                    val retryPrompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                    firstResult = when (agentId) {
                        ExternalAgentId.CLAUDE_CODE -> runClaudePrint(
                            config = modelConfig,
                            prompt = retryPrompt,
                            options = runtimeOptions,
                            sendPromptViaStdin = true,
                        )
                        ExternalAgentId.HERMES_AGENT -> runHermesChat(
                            config = modelConfig,
                            prompt = retryPrompt,
                            options = runtimeOptions,
                            resumeSessionId = activeSession.nativeSessionId.trim(),
                        )
                    }
                    if (firstResult.nativeSessionId.isNotBlank()) {
                        capturedNativeSessionId = firstResult.nativeSessionId
                    }
                }
                firstResult
            }.recoverCatching { error ->
                if (agentId == ExternalAgentId.CLAUDE_CODE &&
                    !useNativeSession &&
                    isArgumentListTooLong(error)
                ) {
                    if (maybeCompactSessionIfNeeded(trigger = "error_retry", force = true)) {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.cli_compact_retry_triggered), Toast.LENGTH_SHORT).show()
                            renderSession()
                        }
                    }
                    val retryPrompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                    when (agentId) {
                        ExternalAgentId.CLAUDE_CODE -> runClaudePrint(
                            config = modelConfig,
                            prompt = retryPrompt,
                            options = runtimeOptions,
                            sendPromptViaStdin = true,
                        )
                        ExternalAgentId.HERMES_AGENT -> runHermesChat(
                            config = modelConfig,
                            prompt = retryPrompt,
                            options = runtimeOptions,
                            resumeSessionId = activeSession.nativeSessionId.trim(),
                        )
                    }
                } else if (agentId == ExternalAgentId.CLAUDE_CODE && useNativeSession) {
                    appendClaudeProcessLine(liveProcessLines, "原生链路异常，自动回退兼容链路重试")
                    val retryPrompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                    usedCompatibilityFallback = true
                    when (agentId) {
                        ExternalAgentId.CLAUDE_CODE -> runClaudePrint(
                            config = modelConfig,
                            prompt = retryPrompt,
                            options = runtimeOptions,
                            sendPromptViaStdin = true,
                        )
                        ExternalAgentId.HERMES_AGENT -> runHermesChat(
                            config = modelConfig,
                            prompt = retryPrompt,
                            options = runtimeOptions,
                            resumeSessionId = activeSession.nativeSessionId.trim(),
                        )
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
            val nativeSessionToPersist = capturedNativeSessionId.ifBlank { result.nativeSessionId }
            if (useNativeSession && nativeSessionToPersist.isNotBlank()) {
                nextSession = AgentSessionStore.updateNativeSession(
                    context = this,
                    agentId = agentId,
                    sessionId = nextSession.sessionId,
                    nativeSessionId = nativeSessionToPersist,
                    nativeSessionMode = CLAUDE_NATIVE_SESSION_MODE,
                ) ?: nextSession
            } else if (agentId == ExternalAgentId.HERMES_AGENT && nativeSessionToPersist.isNotBlank()) {
                nextSession = AgentSessionStore.updateNativeSession(
                    context = this,
                    agentId = agentId,
                    sessionId = nextSession.sessionId,
                    nativeSessionId = nativeSessionToPersist,
                    nativeSessionMode = "hermes_quiet_v1",
                ) ?: nextSession
            }
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
                lastExecutionRoute = when {
                    agentId == ExternalAgentId.HERMES_AGENT && runtimeOptions.hermesGatewayEnabled -> "Hermes网关协同"
                    agentId == ExternalAgentId.HERMES_AGENT -> "Hermes直连"
                    useNativeSession && usedCompatibilityFallback -> "原生失败→兼容回退"
                    useNativeSession -> "原生链路"
                    else -> "兼容链路"
                }
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
        if (agentId == ExternalAgentId.CLAUDE_CODE || agentId == ExternalAgentId.HERMES_AGENT) {
            out.appendLine("高优先级运行规范（必须遵守）：")
            out.appendLine("1) 先阅读“自动注入预检结果”，再决定用哪条执行链路。")
            out.appendLine("2) Android 系统级命令必须使用 system-shell <command>。")
            out.appendLine("3) Ubuntu 命令使用 ubuntu-shell <command> 或直接 Linux 命令。")
            out.appendLine("4) 先做必要验证再给结论，且不要复述整段预检文本。")
            out.appendLine("5) 若某链路失败，需明确失败原因并自动切换可用链路继续。")
            appendToolRoutingRules(out)
            if (agentId == ExternalAgentId.HERMES_AGENT) {
                out.appendLine("18) 当前为 Hermes Agent CLI 链路，必要时可使用 --resume 会话续接。")
            }
            out.appendLine()
            out.appendLine("自动注入预检结果：")
            out.appendLine(buildRuntimeProbeBlock(options))
            out.appendLine()
            out.appendLine("AnyClaw MCP注入状态：")
            out.appendLine(buildAnyClawMcpProbeBlock(options))
            out.appendLine()
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

    private fun buildClaudeNativeTurnPrompt(userText: String, options: AgentRuntimeOptions): String {
        val out = StringBuilder()
        out.appendLine("你正在继续一个已存在的 Claude 原生会话。")
        out.appendLine("高优先级运行规范（必须遵守）：")
        out.appendLine("1) Android 系统级命令必须使用 system-shell <command>。")
        out.appendLine("2) Ubuntu 命令使用 ubuntu-shell <command> 或直接 Linux 命令。")
        out.appendLine("3) 若某链路失败，明确失败原因并自动切换可用链路继续。")
        appendToolRoutingRules(out)
        out.appendLine()
        out.appendLine("自动注入预检结果：")
        out.appendLine(buildRuntimeProbeBlock(options))
        out.appendLine()
        out.appendLine("AnyClaw MCP注入状态：")
        out.appendLine(buildAnyClawMcpProbeBlock(options))
        out.appendLine()
        out.appendLine("当前用户请求：")
        out.appendLine(userText)
        return out.toString().trim()
    }

    private fun appendToolRoutingRules(out: StringBuilder) {
        out.appendLine("6) 本会话已注入 AnyClaw MCP 工具箱，调用时优先使用 mcp__anyclaw_toolbox__ 前缀工具名。")
        out.appendLine("7) 文件检索/读写/构建/测试首选 anyclaw_terminal；工作区内确定性文本改写优先 anyclaw_apply_file。")
        out.appendLine("8) anyclaw_terminal 默认 cwd 在 workspace_root，可访问工作区与共享存储绝对路径（如 /sdcard/...）。")
        out.appendLine("9) anyclaw_apply_file 仅允许 workspace_root 内路径，越界写入会失败。")
        out.appendLine("10) 大文件读取规则：若文件可能超过 25000 tokens 或约 120KB，禁止用 Read，必须改用 anyclaw_terminal + head/tail/sed -n 分段读取。")
        out.appendLine("11) 联网搜索首选 anyclaw_exa_search 系列（含 advanced/api/contents），不可用时回退 anyclaw_tavily_search。")
        out.appendLine("12) Ubuntu 命令优先使用 anyclaw_ubuntu；仅在兼容场景下再用 anyclaw_terminal + ubuntu-shell。")
        out.appendLine("13) 内置 Bash 工具存在噪声与截断风险，仅用于简单命令；复杂任务优先 anyclaw_terminal。")
        out.appendLine("14) 未经用户明确授权，禁止调用 anyclaw_device_exec / anyclaw_device_uiautomator_dump。")
        out.appendLine("15) 即使授权设备工具，也禁止将其用于工作区文件搜索、移动、编辑。")
        out.appendLine("16) 网页自动化调用请使用 mcp__anyclaw_toolbox__start_web/stop_web/web_*，避免裸 start_web/web_*。")
        out.appendLine("17) 若 anyclaw_ 工具不可见或调用失败，必须输出 MCP_TOOLBOX_STATUS=UNAVAILABLE，并给出 reason 与 step。")
    }

    private fun runClaudePrint(
        config: AgentModelConfig,
        prompt: String,
        options: AgentRuntimeOptions,
        resumeSessionId: String = "",
        sendPromptViaStdin: Boolean = true,
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
        if (resumeSessionId.isNotBlank()) {
            args += listOf("--resume", resumeSessionId.trim())
        }
        if (config.modelId.isNotBlank()) {
            args += listOf("--model", config.modelId.trim())
        }
        args += listOf("--output-format", "stream-json", "--include-partial-messages", "--include-hook-events")
        if (!sendPromptViaStdin) {
            args += prompt
        }

        val extraEnv = mutableMapOf(
            "ANTHROPIC_API_KEY" to config.apiKey.trim(),
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_ATTR_NOSYSTEM" to "1",
        )
        if (config.baseUrl.isNotBlank()) {
            extraEnv["ANTHROPIC_BASE_URL"] = config.baseUrl.trim()
        }

        val process = serverManager.startPrefixExecProcess(args, extraEnv)
        if (sendPromptViaStdin) {
            runCatching {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(prompt)
                    writer.write("\n")
                    writer.flush()
                }
            }.onFailure { error ->
                runCatching { process.destroyForcibly() }
                throw IllegalStateException("写入 Claude stdin 失败: ${error.message}", error)
            }
        } else {
            runCatching { process.outputStream.close() }
        }
        return runClaudeStreamJson(process)
    }

    private fun runHermesChat(
        config: AgentModelConfig,
        prompt: String,
        options: AgentRuntimeOptions,
        resumeSessionId: String = "",
    ): AgentRunResult {
        var runtimeReady = serverManager.isHermesRuntimeUsable()
        if (!runtimeReady) {
            serverManager.installHermesAgent { line ->
                appendClaudeProcessLine(liveProcessLines, "Hermes安装: ${clipProcessText(line)}")
            }
            runtimeReady = serverManager.isHermesRuntimeUsable()
            if (!runtimeReady) {
                throw IllegalStateException("Hermes Agent 未安装，且自动安装失败")
            }
        }

        val hermesHomePath = ensureHermesRuntimeConfig(config, options).absolutePath
        if (options.hermesGatewayEnabled) {
            val started = runCatching { serverManager.startHermesGateway() }.getOrElse { false }
            appendClaudeProcessLine(
                liveProcessLines,
                if (started) "Hermes 网关：已启动" else "Hermes 网关：启动失败，回退直连",
            )
        } else {
            runCatching { serverManager.stopHermesGateway() }
        }

        val args = mutableListOf(
            "chat",
            "-Q",
            "-q",
            prompt,
            "--source",
            "pocketlobster",
        )
        if (resumeSessionId.isNotBlank()) {
            args += listOf("--resume", resumeSessionId.trim())
        }

        val process = serverManager.startHermesExecProcess(
            args = args,
            extraEnv = mapOf(
                "HERMES_HOME" to hermesHomePath,
                "HERMES_QUIET" to "1",
            ),
        )
        return runHermesQuietOutput(process)
    }

    private fun runHermesQuietOutput(process: Process): AgentRunResult {
        activeProcess = process
        val assistantLines = mutableListOf<String>()
        val processLines = mutableListOf<String>()
        var sessionId = ""

        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val cleaned = stripAnsi(line).trim()
                if (shouldIgnoreProcessLine(cleaned)) {
                    line = reader.readLine()
                    continue
                }
                if (cleaned.isNotBlank()) {
                    val sessionMatch = HERMES_SESSION_ID_LINE_REGEX.find(cleaned)
                    if (sessionMatch != null) {
                        val parsed = sessionMatch.groupValues.getOrNull(1).orEmpty().trim()
                        if (parsed.isNotBlank()) {
                            sessionId = parsed
                        }
                    } else if (cleaned.startsWith("Hermes 网关：")) {
                        processLines += cleaned
                    } else {
                        assistantLines += cleaned
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
                processText = processLines.joinToString("\n").trim(),
                nativeSessionId = sessionId,
            )
        }
        val assistantText = assistantLines.joinToString("\n").trim()
        if (exitCode != 0) {
            val raw = (assistantText.ifBlank { processLines.joinToString("\n").trim() })
            throw IllegalStateException(raw.ifBlank { "command exit code=$exitCode" })
        }
        return AgentRunResult(
            assistantText = assistantText.ifBlank { "Hermes 未返回可解析内容" },
            processText = processLines.joinToString("\n").trim(),
            nativeSessionId = sessionId,
        )
    }

    private fun ensureHermesRuntimeConfig(config: AgentModelConfig, options: AgentRuntimeOptions): File {
        val paths = BootstrapInstaller.getPaths(this)
        val hermesHome = File(paths.homeDir, ".pocketlobster/hermes")
        if (!hermesHome.exists()) {
            hermesHome.mkdirs()
        }
        val configFile = File(hermesHome, "config.yaml")
        val toolboxAvailable = runCatching { serverManager.canHermesToolboxRunInUbuntu() }.getOrElse { false }
        val mcpConfig = if (toolboxAvailable) ensureClaudeAnyClawMcpConfig(options) else null
        val serverFile = mcpConfig?.let { File(it.parentFile, "anyclaw-toolbox-server.cjs") }

        val yaml = buildString {
            appendLine("model:")
            appendLine("  default: ${yamlQuote(config.modelId)}")
            appendLine("  provider: custom")
            appendLine("  base_url: ${yamlQuote(config.baseUrl)}")
            appendLine("  api_key: ${yamlQuote(config.apiKey)}")
            appendLine("display:")
            appendLine("  tool_progress: all")
            appendLine("terminal:")
            appendLine("  backend: local")
            appendLine("  cwd: ${yamlQuote("${paths.homeDir}/.openclaw/workspace")}")
            if (toolboxAvailable && mcpConfig != null && serverFile != null) {
                appendLine("mcp_servers:")
                appendLine("  anyclaw_toolbox:")
                appendLine("    command: ${yamlQuote("${paths.prefixDir}/bin/node")}")
                appendLine("    args:")
                appendLine("      - ${yamlQuote(serverFile.absolutePath)}")
                appendLine("    env:")
                appendLine("      HOME: ${yamlQuote(paths.homeDir)}")
                appendLine("      PREFIX: ${yamlQuote(paths.prefixDir)}")
                appendLine("      PATH: ${yamlQuote("${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin")}")
                appendLine("      ANYCLAW_WEB_BRIDGE_URL: ${yamlQuote("http://127.0.0.1:${ShizukuShellBridgeServer.BRIDGE_PORT}/web/call")}")
                appendLine("      ANYCLAW_UBUNTU_BIN: ${yamlQuote("${paths.homeDir}/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh")}")
                appendLine("      ANYCLAW_WORKSPACE_ROOT: ${yamlQuote("${paths.homeDir}/.openclaw/workspace")}")
                appendLine("      ANYCLAW_ALLOW_SHARED_STORAGE: ${yamlQuote(if (options.allowSharedStorage) "1" else "0")}")
                appendLine("      ANYCLAW_MCP_CONFIG_PATH: ${yamlQuote(mcpConfig.absolutePath)}")
            }
        }.trim() + "\n"

        writeTextIfChanged(configFile, yaml)
        return hermesHome
    }

    private fun yamlQuote(value: String): String {
        val escaped = value.replace("'", "''")
        return "'$escaped'"
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
                nativeSessionId = state.sessionId,
                nativeCompactApplied = state.compactApplied,
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
            assistantText = sanitizeAssistantText(assistantText.ifBlank { "Claude 未返回可解析内容" }),
            processText = processText,
            nativeSessionId = state.sessionId,
            nativeCompactApplied = state.compactApplied,
        )
    }

    private fun parseClaudeStreamLine(line: String, state: ClaudeStreamParseState): Boolean {
        val jsonText = if (line.startsWith("data:")) line.removePrefix("data:").trim() else line
        if (!jsonText.startsWith("{")) {
            maybeCaptureNativeSessionIdFromText(line, state)
            return false
        }
        val payload = runCatching { JSONObject(jsonText) }.getOrNull() ?: return false
        maybeCaptureNativeSessionIdFromPayload(payload, state)
        val type = payload.optString("type").trim()
        if (type.isBlank()) return false

        when (type) {
            "system" -> {
                val subtype = payload.optString("subtype").trim()
                if (subtype.equals("compact_boundary", ignoreCase = true) ||
                    subtype.equals("compacted", ignoreCase = true)
                ) {
                    state.compactApplied = true
                    appendClaudeProcessLine(state.processLines, "Claude 上下文压缩完成")
                }
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

    private fun maybeCaptureNativeSessionIdFromPayload(payload: JSONObject, state: ClaudeStreamParseState) {
        if (state.sessionId.isNotBlank()) return
        val candidates = mutableListOf<String>()
        listOf(
            payload.optString("session_id").trim(),
            payload.optString("sessionId").trim(),
            payload.optString("conversation_id").trim(),
        ).filter { it.isNotBlank() }.forEach { candidates += it }

        val sessionObj = payload.optJSONObject("session")
        if (sessionObj != null) {
            listOf(
                sessionObj.optString("id").trim(),
                sessionObj.optString("session_id").trim(),
                sessionObj.optString("sessionId").trim(),
            ).filter { it.isNotBlank() }.forEach { candidates += it }
        }

        val match = candidates.firstOrNull { looksLikeNativeSessionId(it) }
        if (!match.isNullOrBlank()) {
            state.sessionId = match.trim()
            return
        }
        val claudeStyle = CLAUDE_SESSION_ID_REGEX.find(payload.toString())?.value?.trim().orEmpty()
        if (claudeStyle.isNotBlank()) {
            state.sessionId = claudeStyle
        }
    }

    private fun maybeCaptureNativeSessionIdFromText(raw: String, state: ClaudeStreamParseState) {
        if (state.sessionId.isNotBlank()) return
        val claudeStyle = CLAUDE_SESSION_ID_REGEX.find(raw)?.value?.trim().orEmpty()
        if (claudeStyle.isNotBlank()) {
            state.sessionId = claudeStyle
            return
        }
        val normalized = raw.lowercase(Locale.US)
        if (!normalized.contains("session")) return
        val uuidStyle = UUID_SESSION_ID_REGEX.find(raw)?.value?.trim().orEmpty()
        if (uuidStyle.isNotBlank()) {
            state.sessionId = uuidStyle
        }
    }

    private fun looksLikeNativeSessionId(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) return false
        if (normalized.startsWith("claude-code-")) return true
        if (UUID_SESSION_ID_REGEX.matches(normalized)) return true
        return false
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

    private fun sanitizeAssistantText(raw: String): String {
        val lines = raw.lineSequence().map { it.trimEnd() }.toList()
        val cleaned = lines.filterNot { line ->
            val normalized = line.trim().uppercase(Locale.US)
            normalized == "NATIVE_SESSION_HANDSHAKE_COMPLETE" || normalized == "NATIVE_SESSION_READY"
        }.joinToString("\n").trim()
        return cleaned.ifBlank { raw.trim() }
    }

    private fun appendClaudeProcessLine(lines: MutableList<String>, line: String) {
        val normalized = line.trim()
        if (normalized.isBlank()) return
        if (lines === liveProcessLines) {
            val appended = synchronized(liveProcessLines) {
                if (liveProcessLines.lastOrNull() == normalized) {
                    false
                } else {
                    liveProcessLines += normalized
                    true
                }
            }
            if (appended) {
                scheduleLiveProcessRender()
            }
            return
        }
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
        if (line.startsWith("执行链路:")) return true
        if (isClaudeStdinWarningLine(line)) return true
        return false
    }

    private fun isClaudeStdinWarningLine(line: String): Boolean {
        val normalized = line.trim().lowercase(Locale.US)
        if (normalized.contains("warning: no stdin data received")) return true
        if (normalized.contains("claude code warning: no stdin data received")) return true
        if (normalized.contains("if piping from a slow command, redirect stdin explicitly")) return true
        if (normalized.contains("< /dev/null to skip, or wait longer")) return true
        return false
    }

    private fun recordLiveProcessLine(line: String) {
        synchronized(liveProcessLines) {
            liveProcessLines += line
        }
        scheduleLiveProcessRender()
    }

    private fun scheduleLiveProcessRender() {
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

    private fun buildAnyClawMcpProbeBlock(options: AgentRuntimeOptions): String {
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
            "anyclaw_ubuntu",
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
            appendLine("tool_boundary_anyclaw_terminal=local_shell_default_cwd_workspace_root_shared_storage_rw_supported")
            appendLine("tool_boundary_anyclaw_ubuntu=ubuntu_runtime_shell_for_glibc_and_root_paths")
            appendLine("tool_boundary_anyclaw_apply_file=workspace_root_only")
            appendLine("tool_boundary_anyclaw_device_exec=android_system_shell_uid2000_no_app_private_home_access")
            appendLine("tool_priority_hint=file_ops:anyclaw_terminal>anyclaw_apply_file search:anyclaw_exa_* fallback:anyclaw_tavily_search ubuntu:anyclaw_ubuntu>anyclaw_terminal+ubuntu-shell")
            appendLine("large_file_reading_hint=if_estimated_tokens_gt_25000_or_file_gt_120kb_use_anyclaw_terminal_head_tail_sed_not_Read")
            appendLine("device_tool_policy=deny_by_default_require_explicit_user_approval")
            appendLine("bash_tool_hint=built_in_bash_can_include_noise_or_truncation_prefer_anyclaw_terminal")
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
            claudeNativeSession = prefs.getBoolean(runtimeOptionKey("claude_native_session"), agentId == ExternalAgentId.CLAUDE_CODE),
            hermesGatewayEnabled = prefs.getBoolean(runtimeOptionKey("hermes_gateway_enabled"), false),
        )
    }

    private fun saveRuntimeOptions(options: AgentRuntimeOptions) {
        getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(runtimeOptionKey("allow_shared_storage"), options.allowSharedStorage)
            .putBoolean(runtimeOptionKey("dangerous_mode"), options.dangerousAutoApprove)
            .putBoolean(runtimeOptionKey("claude_native_session"), options.claudeNativeSession)
            .putBoolean(runtimeOptionKey("hermes_gateway_enabled"), options.hermesGatewayEnabled)
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
            .put("ANYCLAW_UBUNTU_BIN", "${paths.homeDir}/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh")
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
            rowView.setOnLongClickListener {
                showMessageActionMenu(it, msg)
                true
            }
            return rowView
        }
    }
}
