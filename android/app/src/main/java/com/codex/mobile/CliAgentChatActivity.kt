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
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAttachmentSummary: TextView
    private lateinit var btnPermissionCenter: Button
    private lateinit var btnPromptManager: Button
    private lateinit var btnConversationManager: Button
    private lateinit var btnModelManager: Button
    private lateinit var btnNewSession: Button
    private lateinit var btnAttach: Button
    private lateinit var btnClearAttachments: Button
    private lateinit var btnAbort: Button
    private lateinit var btnLoadOlder: Button
    private lateinit var btnRestoreLite: Button
    private lateinit var listView: ListView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnOpenClaw: Button
    private lateinit var btnCodex: Button
    private lateinit var btnClaude: Button
    private lateinit var btnOpenCode: Button
    private lateinit var switchAllowSharedStorage: SwitchCompat
    private lateinit var switchDangerousMode: SwitchCompat
    private lateinit var switchShowProcess: SwitchCompat

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
        btnPermissionCenter = findViewById(R.id.btnCliPermissionCenter)
        btnPromptManager = findViewById(R.id.btnCliPromptManager)
        btnConversationManager = findViewById(R.id.btnCliConversationManager)
        btnModelManager = findViewById(R.id.btnCliModelManager)
        btnNewSession = findViewById(R.id.btnCliNewSession)
        btnAttach = findViewById(R.id.btnCliAttach)
        btnClearAttachments = findViewById(R.id.btnCliClearAttachments)
        btnAbort = findViewById(R.id.btnCliAbort)
        btnLoadOlder = findViewById(R.id.btnCliLoadOlder)
        btnRestoreLite = findViewById(R.id.btnCliRestoreLite)
        listView = findViewById(R.id.listCliAgentMessages)
        inputMessage = findViewById(R.id.etCliAgentInput)
        btnSend = findViewById(R.id.btnCliAgentSend)
        btnOpenClaw = findViewById(R.id.btnCliTabOpenClaw)
        btnCodex = findViewById(R.id.btnCliTabCodex)
        btnClaude = findViewById(R.id.btnCliTabClaude)
        btnOpenCode = findViewById(R.id.btnCliTabOpenCode)
        switchAllowSharedStorage = findViewById(R.id.switchCliAllowSharedStorage)
        switchDangerousMode = findViewById(R.id.switchCliDangerousMode)
        switchShowProcess = findViewById(R.id.switchCliShowProcess)

        listView.adapter = messageAdapter
        tvTitle.text = AgentSessionStore.displayAgentName(agentId)

        runtimeOptions = loadRuntimeOptions()
        historyWindowSize = loadHistoryWindowSize()
        showProcess = loadShowProcess()

        switchAllowSharedStorage.isChecked = runtimeOptions.allowSharedStorage
        switchDangerousMode.isChecked = runtimeOptions.dangerousAutoApprove
        switchShowProcess.isChecked = showProcess

        switchAllowSharedStorage.setOnCheckedChangeListener { _, checked ->
            runtimeOptions = runtimeOptions.copy(allowSharedStorage = checked)
            saveRuntimeOptions(runtimeOptions)
            renderSession()
        }
        switchDangerousMode.setOnCheckedChangeListener { _, checked ->
            runtimeOptions = runtimeOptions.copy(dangerousAutoApprove = checked)
            saveRuntimeOptions(runtimeOptions)
            renderSession()
        }
        switchShowProcess.setOnCheckedChangeListener { _, checked ->
            showProcess = checked
            saveShowProcess(checked)
            renderSession()
        }

        btnPermissionCenter.setOnClickListener {
            startActivity(Intent(this, PermissionManagerActivity::class.java))
        }
        btnPromptManager.setOnClickListener {
            startActivity(Intent(this, PromptManagerActivity::class.java))
        }
        btnConversationManager.setOnClickListener {
            startActivity(Intent(this, ConversationManagerActivity::class.java))
        }
        btnModelManager.setOnClickListener {
            startActivity(
                Intent(this, AgentModelManagerActivity::class.java).apply {
                    putExtra(AgentModelManagerActivity.EXTRA_AGENT_ID, agentId.value)
                },
            )
        }
        btnNewSession.setOnClickListener {
            createNewSession()
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
        btnLoadOlder.setOnClickListener {
            loadOlderMessages()
        }
        btnRestoreLite.setOnClickListener {
            restoreLiteHistory()
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
        btnOpenCode.setOnClickListener {
            if (agentId == ExternalAgentId.OPEN_CODE) return@setOnClickListener
            restartWithAgent(ExternalAgentId.OPEN_CODE, null)
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
        btnAbort.isEnabled = sending
        btnSend.isEnabled = !sending
        inputMessage.isEnabled = !sending

        val storedMessages = filteredMessagesForDisplay()
        btnLoadOlder.isEnabled = activeSession.messages.size > storedMessages.size
        btnRestoreLite.isEnabled = historyWindowSize > DEFAULT_VISIBLE_HISTORY

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
        btnOpenCode.isEnabled = agentId != ExternalAgentId.OPEN_CODE
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
                val prompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                when (agentId) {
                    ExternalAgentId.CLAUDE_CODE -> runClaudePrint(modelConfig, prompt, runtimeOptions)
                    ExternalAgentId.OPEN_CODE -> runOpenCode(modelConfig, prompt, runtimeOptions)
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
        if (agentId == ExternalAgentId.OPEN_CODE || agentId == ExternalAgentId.CLAUDE_CODE) {
            out.appendLine("高优先级运行规范（必须遵守）：")
            out.appendLine("1) 先阅读“自动注入预检结果”，再决定用哪条执行链路。")
            out.appendLine("2) Android 系统级命令必须使用 system-shell <command>。")
            out.appendLine("3) Ubuntu 命令使用 ubuntu-shell <command> 或直接 Linux 命令。")
            out.appendLine("4) 先做必要验证再给结论，且不要复述整段预检文本。")
            out.appendLine("5) 若某链路失败，需明确失败原因并自动切换可用链路继续。")
            if (agentId == ExternalAgentId.CLAUDE_CODE) {
                out.appendLine("6) 本会话已注入 AnyClaw MCP 工具箱，工具名前缀为 anyclaw_。")
                out.appendLine("7) 用户要求验证工具时，先尝试列出或调用 anyclaw_ 工具，不得只依据内置工具列表给结论。")
                out.appendLine("8) 若 anyclaw_ 工具不可见或调用失败，必须输出 MCP_TOOLBOX_STATUS=UNAVAILABLE，并给出 reason 与 step。")
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
        val mcpConfigPath = ensureClaudeAnyClawMcpConfig(options).absolutePath
        val modelArg = if (config.modelId.isBlank()) "" else "--model ${LocalBridgeClients.shellQuote(config.modelId)} "
        val baseEnv = if (config.baseUrl.isBlank()) "" else "ANTHROPIC_BASE_URL=${LocalBridgeClients.shellQuote(config.baseUrl)} "
        val keyEnv = "ANTHROPIC_API_KEY=${LocalBridgeClients.shellQuote(config.apiKey)} "
        val addDirArg = buildClaudeDirArgs(options)
        val dangerArg = if (options.dangerousAutoApprove) "--dangerously-skip-permissions " else ""
        val mcpArg =
            "--mcp-config ${LocalBridgeClients.shellQuote(mcpConfigPath)} --strict-mcp-config "
        val cmd =
            "${baseEnv}${keyEnv}claude -p --verbose ${dangerArg}${addDirArg}${mcpArg}${modelArg}" +
                "--output-format stream-json --include-partial-messages --include-hook-events " +
                "${LocalBridgeClients.shellQuote(prompt)} < /dev/null 2>&1"
        return runClaudeStreamJson(serverManager.startPrefixProcess(cmd))
    }

    private fun buildClaudeDirArgs(options: AgentRuntimeOptions): String {
        val paths = BootstrapInstaller.getPaths(this)
        val dirs = linkedSetOf(paths.homeDir)
        if (options.allowSharedStorage) {
            dirs += "/sdcard"
            dirs += "/storage/emulated/0"
        }
        return dirs.joinToString(" ") { "--add-dir ${LocalBridgeClients.shellQuote(it)}" } + " "
    }

    private fun runOpenCode(
        config: AgentModelConfig,
        prompt: String,
        options: AgentRuntimeOptions,
    ): AgentRunResult {
        val paths = BootstrapInstaller.getPaths(this)
        val homeDir = paths.homeDir
        val nativeBin = "${paths.prefixDir}/lib/node_modules/opencode-ai/bin/.opencode"
        if (!File(nativeBin).exists()) {
            throw IllegalStateException("OpenCode 组件未安装或损坏：缺少 .opencode 二进制")
        }
        val configFile = File(homeDir, ".pocketlobster/opencode/opencode-${activeSession.sessionId}.json")
        configFile.parentFile?.mkdirs()
        configFile.writeText(buildOpenCodeConfig(config).toString(2))
        val bridgeDir = ensureOpenCodeBridgeScripts(homeDir)

        val providerKey = normalizeProviderKey(config.providerId.ifBlank { "lobster" })
        val modelRef = if (config.modelId.contains("/")) config.modelId else "$providerKey/${config.modelId}"
        val workDir = if (options.allowSharedStorage) "/sdcard" else homeDir
        val dangerArg = if (options.dangerousAutoApprove) "--dangerously-skip-permissions " else ""
        val cmd =
            "export OPENCODE_CONFIG=${LocalBridgeClients.shellQuote(configFile.absolutePath)}; " +
                "export PATH=${LocalBridgeClients.shellQuote("$bridgeDir:${paths.prefixDir}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")}; " +
                "cd ${LocalBridgeClients.shellQuote(workDir)} 2>/dev/null || cd ${LocalBridgeClients.shellQuote(homeDir)}; " +
                "${LocalBridgeClients.shellQuote(nativeBin)} run --model ${LocalBridgeClients.shellQuote(modelRef)} " +
                "--dir ${LocalBridgeClients.shellQuote(workDir)} ${dangerArg}--format default ${LocalBridgeClients.shellQuote(prompt)} 2>&1"
        return runStreamingCommand(serverManager.startUbuntuProcess(cmd)) { raw -> raw.trim() }
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
        if (line.startsWith("Claude Code Warning: no stdin data received")) return true
        if (line.startsWith("If piping from a slow command, redirect stdin explicitly")) return true
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
                    1 -> launchFilePicker("image/*", galleryOnly = true)
                    2 -> launchCameraCapture()
                }
            }
            .show()
    }

    private fun launchFilePicker(mimeType: String, galleryOnly: Boolean = false) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (galleryOnly) {
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*"))
            }
        }
        if (galleryOnly) {
            galleryPickerLauncher.launch(intent)
        } else {
            filePickerLauncher.launch(intent)
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
        val paths = BootstrapInstaller.getPaths(this)
        val openCodeBin = "${paths.prefixDir}/lib/node_modules/opencode-ai/bin/.opencode"
        val localCapability = runPrefixCapture("codex-capabilities --plain 2>&1 || true")
        val localChain = runPrefixCapture(
            "id 2>&1; pwd 2>&1; command -v system-shell 2>&1 || true; command -v ubuntu-shell 2>&1 || true",
        )
        val systemChain = runPrefixCapture("system-shell id 2>&1 || true")
        val ubuntuChain = runUbuntuCapture(
            "id 2>&1; pwd 2>&1; command -v system-shell 2>&1 || true; command -v ubuntu-shell 2>&1 || true; echo ANYCLAW_UBUNTU_BIN=${'$'}ANYCLAW_UBUNTU_BIN",
        )
        val openCodeHealth = if (agentId == ExternalAgentId.OPEN_CODE) {
            runUbuntuCapture("${LocalBridgeClients.shellQuote(openCodeBin)} --version 2>&1 || true")
        } else {
            ProbeResult(0, "skip")
        }

        return buildString {
            appendLine("checked_at_ms=${System.currentTimeMillis()}")
            appendLine("allow_shared_storage=${if (options.allowSharedStorage) 1 else 0}")
            appendLine("dangerous_mode=${if (options.dangerousAutoApprove) 1 else 0}")
            appendLine(formatProbe("local_capability", localCapability))
            appendLine(formatProbe("local_chain", localChain))
            appendLine(formatProbe("system_shell", systemChain))
            appendLine(formatProbe("ubuntu_chain", ubuntuChain))
            if (agentId == ExternalAgentId.OPEN_CODE) {
                appendLine(formatProbe("opencode_binary", openCodeHealth))
            }
        }.trim()
    }

    private fun buildClaudeMcpProbeBlock(options: AgentRuntimeOptions): String {
        val configFile = ensureClaudeAnyClawMcpConfig(options)
        val serverFile = File(configFile.parentFile, "anyclaw-toolbox-server.js")
        val toolNames = if (serverFile.exists()) {
            extractAnyClawToolNames(serverFile.readText())
        } else {
            emptyList()
        }
        val required = listOf("anyclaw_device_exec", "anyclaw_search_web", "anyclaw_github_repo")
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
        return Regex("""name:\s*"([^"]+)"""")
            .findAll(script)
            .map { it.groupValues.getOrElse(1) { "" }.trim() }
            .filter { it.startsWith("anyclaw_") }
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

    private fun buildOpenCodeConfig(config: AgentModelConfig): JSONObject {
        val providerKey = normalizeProviderKey(config.providerId.ifBlank { "lobster" })
        val npmPackage = when (config.protocol) {
            ProviderProtocol.ANTHROPIC -> "@ai-sdk/anthropic"
            ProviderProtocol.OPENAI_COMPATIBLE -> "@ai-sdk/openai-compatible"
        }
        val providerJson = JSONObject()
            .put("npm", npmPackage)
            .put("name", config.providerName.ifBlank { providerKey })
            .put(
                "options",
                JSONObject()
                    .put("baseURL", config.baseUrl)
                    .put("apiKey", config.apiKey),
            )
            .put(
                "models",
                JSONObject().put(
                    config.modelId,
                    JSONObject().put("name", config.modelId),
                ),
            )

        return JSONObject()
            .put("\$schema", "https://opencode.ai/config.json")
            .put(
                "provider",
                JSONObject().put(providerKey, providerJson),
            )
    }

    private fun cleanClaudeOutput(raw: String): String {
        val filtered = raw.lineSequence()
            .filterNot { it.startsWith("Claude Code Warning: no stdin data received") }
            .filterNot { it.startsWith("If piping from a slow command, redirect stdin explicitly") }
            .joinToString("\n")
            .trim()
        return filtered.ifBlank { raw.trim() }
    }

    private fun stripAnsi(value: String): String {
        return value.replace(Regex("\\u001B\\[[;\\d]*[A-Za-z]"), "")
    }

    private fun normalizeProviderKey(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "-")
        return cleaned.ifBlank { "lobster" }
    }

    private fun runtimeOptionKey(name: String): String = "${agentId.value}_$name"

    private fun loadRuntimeOptions(): AgentRuntimeOptions {
        val prefs = getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE)
        val defaultDangerous = agentId == ExternalAgentId.OPEN_CODE
        return AgentRuntimeOptions(
            allowSharedStorage = prefs.getBoolean(runtimeOptionKey("allow_shared_storage"), true),
            dangerousAutoApprove = prefs.getBoolean(runtimeOptionKey("dangerous_mode"), defaultDangerous),
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

    private fun ensureOpenCodeBridgeScripts(homeDir: String): String {
        val bridgeDir = File(homeDir, ".pocketlobster/bridges")
        if (!bridgeDir.exists()) bridgeDir.mkdirs()
        val systemShellScript = File(bridgeDir, "system-shell")
        val systemShellAliasScript = File(bridgeDir, "system_shell")
        val ubuntuShellScript = File(bridgeDir, "ubuntu-shell")
        val ubuntuShellAliasScript = File(bridgeDir, "ubuntu_shell")

        val systemShellContent =
            """
            #!/usr/bin/env bash
            set -euo pipefail
            if [ "${'$'}#" -eq 0 ]; then
              echo "Usage: system-shell <command>" >&2
              exit 2
            fi
            cmd="${'$'}*"
            payload=${'$'}(python3 - "${'$'}cmd" <<'PY'
            import json,sys
            print(json.dumps({"command": sys.argv[1]}))
            PY
            )
            resp=${'$'}(curl -fsS --max-time 120 -H "Content-Type: application/json" -d "${'$'}payload" http://127.0.0.1:18926/exec || true)
            if [ -z "${'$'}resp" ]; then
              echo "Shizuku bridge unreachable" >&2
              exit 3
            fi
            python3 - "${'$'}resp" <<'PY'
            import json,sys
            data={}
            try:
                data=json.loads(sys.argv[1])
            except Exception:
                sys.stderr.write("Invalid bridge response\n")
                sys.exit(1)
            out=data.get("stdout","")
            err=data.get("stderr","")
            if out:
                sys.stdout.write(out)
            if err:
                sys.stderr.write(err)
            code=data.get("exitCode", 0 if data.get("ok") else 1)
            if (not data.get("ok")) and data.get("error"):
                sys.stderr.write(str(data["error"]) + "\n")
            if (not data.get("ok")) and data.get("error_code"):
                sys.stderr.write("error_code=" + str(data["error_code"]) + "\n")
            sys.exit(int(code))
            PY
            """.trimIndent() + "\n"
        if (!systemShellScript.exists() || systemShellScript.readText() != systemShellContent) {
            systemShellScript.writeText(systemShellContent)
            systemShellScript.setExecutable(true)
        }
        val systemShellAliasContent = "#!/usr/bin/env bash\nexec \"${systemShellScript.absolutePath}\" \"\$@\"\n"
        if (!systemShellAliasScript.exists() || systemShellAliasScript.readText() != systemShellAliasContent) {
            systemShellAliasScript.writeText(systemShellAliasContent)
            systemShellAliasScript.setExecutable(true)
        }

        val ubuntuShellContent =
            """
            #!/usr/bin/env bash
            if [ "${'$'}#" -eq 0 ]; then
              exec /bin/bash -il
            fi
            exec /bin/bash -lc "${'$'}*"
            """.trimIndent() + "\n"
        if (!ubuntuShellScript.exists() || ubuntuShellScript.readText() != ubuntuShellContent) {
            ubuntuShellScript.writeText(ubuntuShellContent)
            ubuntuShellScript.setExecutable(true)
        }
        val ubuntuShellAliasContent = "#!/usr/bin/env bash\nexec \"${ubuntuShellScript.absolutePath}\" \"\$@\"\n"
        if (!ubuntuShellAliasScript.exists() || ubuntuShellAliasScript.readText() != ubuntuShellAliasContent) {
            ubuntuShellAliasScript.writeText(ubuntuShellAliasContent)
            ubuntuShellAliasScript.setExecutable(true)
        }

        return bridgeDir.absolutePath
    }

    private fun ensureClaudeAnyClawMcpConfig(options: AgentRuntimeOptions): File {
        val paths = BootstrapInstaller.getPaths(this)
        val mcpDir = File(paths.homeDir, ".pocketlobster/mcp")
        if (!mcpDir.exists()) {
            mcpDir.mkdirs()
        }

        val serverFile = File(mcpDir, "anyclaw-toolbox-server.js")
        val serverScript = buildAnyClawToolboxServerScript()
        writeTextIfChanged(serverFile, serverScript)
        serverFile.setExecutable(true)

        val systemShellPath = File(paths.prefixDir, "bin/system-shell")
        val envJson = JSONObject()
            .put("HOME", paths.homeDir)
            .put("PREFIX", paths.prefixDir)
            .put("PATH", "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin")
            .put("ANYCLAW_ALLOW_SHARED_STORAGE", if (options.allowSharedStorage) "1" else "0")
        if (systemShellPath.exists()) {
            envJson.put("ANYCLAW_SYSTEM_SHELL_BIN", systemShellPath.absolutePath)
        }

        val serverConfig = JSONObject()
            .put("command", "${paths.prefixDir}/bin/node")
            .put("args", JSONArray().put(serverFile.absolutePath))
            .put("env", envJson)

        val root = JSONObject()
            .put("mcpServers", JSONObject().put("anyclaw_toolbox", serverConfig))

        val configFile = File(mcpDir, "claude-anyclaw-mcp.json")
        writeTextIfChanged(configFile, root.toString(2) + "\n")
        return configFile
    }

    private fun writeTextIfChanged(target: File, content: String) {
        val current = if (target.exists()) target.readText() else null
        if (current == content) return
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    private fun buildAnyClawToolboxServerScript(): String {
        return """
            #!/usr/bin/env node
            const https = require("https");
            const http = require("http");
            const { spawnSync } = require("child_process");

            const SERVER_INFO = { name: "anyclaw-toolbox", version: "1.0.0" };
            const MAX_STDIO_BYTES = 1024 * 1024;
            const DEFAULT_TIMEOUT = 30000;

            const TOOL_DEFS = [
              {
                name: "anyclaw_device_exec",
                description: "Execute Android system command via Shizuku-backed system-shell.",
                inputSchema: {
                  type: "object",
                  properties: {
                    command: { type: "string", minLength: 1 },
                    timeoutMs: { type: "integer", minimum: 1000, maximum: 120000 }
                  },
                  required: ["command"],
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_device_screenshot",
                description: "Capture Android screenshot using system-shell screencap and save to shared storage.",
                inputSchema: {
                  type: "object",
                  properties: {
                    outputPath: { type: "string" }
                  },
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_device_uiautomator_dump",
                description: "Dump Android UI hierarchy xml into shared storage.",
                inputSchema: {
                  type: "object",
                  properties: {
                    outputPath: { type: "string" }
                  },
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_search_web",
                description: "Web search using DuckDuckGo instant answer and related topics API.",
                inputSchema: {
                  type: "object",
                  properties: {
                    query: { type: "string", minLength: 1 },
                    maxResults: { type: "integer", minimum: 1, maximum: 10 }
                  },
                  required: ["query"],
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_search_wikipedia",
                description: "Search Wikipedia summaries by keyword.",
                inputSchema: {
                  type: "object",
                  properties: {
                    query: { type: "string", minLength: 1 },
                    maxResults: { type: "integer", minimum: 1, maximum: 10 }
                  },
                  required: ["query"],
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_fetch_url",
                description: "Fetch URL content over HTTP/HTTPS and return text snippet.",
                inputSchema: {
                  type: "object",
                  properties: {
                    url: { type: "string", minLength: 8 },
                    maxChars: { type: "integer", minimum: 200, maximum: 50000 }
                  },
                  required: ["url"],
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_github_repo",
                description: "Read GitHub repository metadata.",
                inputSchema: {
                  type: "object",
                  properties: {
                    owner: { type: "string", minLength: 1 },
                    repo: { type: "string", minLength: 1 }
                  },
                  required: ["owner", "repo"],
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_github_search_repositories",
                description: "Search GitHub repositories.",
                inputSchema: {
                  type: "object",
                  properties: {
                    query: { type: "string", minLength: 1 },
                    perPage: { type: "integer", minimum: 1, maximum: 30 }
                  },
                  required: ["query"],
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_github_search_code",
                description: "Search GitHub code snippets.",
                inputSchema: {
                  type: "object",
                  properties: {
                    query: { type: "string", minLength: 1 },
                    perPage: { type: "integer", minimum: 1, maximum: 20 }
                  },
                  required: ["query"],
                  additionalProperties: false
                }
              },
              {
                name: "anyclaw_github_list_issues",
                description: "List GitHub issues for a repository.",
                inputSchema: {
                  type: "object",
                  properties: {
                    owner: { type: "string", minLength: 1 },
                    repo: { type: "string", minLength: 1 },
                    state: { type: "string", enum: ["open", "closed", "all"] },
                    perPage: { type: "integer", minimum: 1, maximum: 30 }
                  },
                  required: ["owner", "repo"],
                  additionalProperties: false
                }
              }
            ];

            let lineBuffer = "";
            process.stdin.setEncoding("utf8");
            process.stdin.on("data", (chunk) => {
              lineBuffer += String(chunk || "");
              processLines();
            });
            process.stdin.on("end", () => process.exit(0));

            function writeMessage(payload) {
              process.stdout.write(JSON.stringify(payload) + "\n");
            }

            function makeResponse(id, result) {
              return { jsonrpc: "2.0", id, result };
            }

            function makeError(id, code, message) {
              return { jsonrpc: "2.0", id, error: { code, message } };
            }

            function processLines() {
              while (true) {
                const idx = lineBuffer.indexOf("\n");
                if (idx === -1) return;
                const rawLine = lineBuffer.slice(0, idx);
                lineBuffer = lineBuffer.slice(idx + 1);
                const body = rawLine.trim();
                if (!body) continue;
                let message;
                try {
                  message = JSON.parse(body);
                } catch {
                  continue;
                }
                handleMessage(message).catch((error) => {
                  if (typeof message?.id !== "undefined") {
                    writeMessage(makeError(message.id, -32603, String(error?.message || error)));
                  }
                });
              }
            }

            async function handleMessage(message) {
              if (!message || typeof message !== "object") return;
              const id = message.id;
              const method = message.method;
              if (!method) return;

              if (method === "initialize") {
                writeMessage(makeResponse(id, {
                  protocolVersion: message?.params?.protocolVersion || "2025-11-25",
                  capabilities: { tools: {} },
                  serverInfo: SERVER_INFO
                }));
                return;
              }
              if (method === "notifications/initialized") return;
              if (method === "ping") {
                writeMessage(makeResponse(id, {}));
                return;
              }
              if (method === "tools/list") {
                writeMessage(makeResponse(id, { tools: TOOL_DEFS }));
                return;
              }
              if (method === "tools/call") {
                const toolName = message?.params?.name;
                const args = message?.params?.arguments || {};
                try {
                  const text = await callTool(toolName, args);
                  writeMessage(makeResponse(id, { content: [{ type: "text", text }], isError: false }));
                } catch (error) {
                  writeMessage(makeResponse(id, {
                    content: [{ type: "text", text: "Tool error: " + String(error?.message || error) }],
                    isError: true
                  }));
                }
                return;
              }

              if (typeof id !== "undefined") {
                writeMessage(makeError(id, -32601, "Method not found: " + method));
              }
            }

            function shellQuote(value) {
              return "'" + String(value).replace(/'/g, "'\"'\"'") + "'";
            }

            function runSystemShell(command, timeoutMs) {
              const bin = process.env.ANYCLAW_SYSTEM_SHELL_BIN || "system-shell";
              const result = spawnSync(bin, [command], {
                encoding: "utf8",
                timeout: timeoutMs || DEFAULT_TIMEOUT,
                maxBuffer: MAX_STDIO_BYTES
              });
              const stdout = (result.stdout || "").trim();
              const stderr = (result.stderr || "").trim();
              const status = typeof result.status === "number" ? result.status : 1;
              if (result.error) {
                throw new Error("system-shell invoke failed: " + String(result.error.message || result.error));
              }
              if (status !== 0) {
                throw new Error((stderr || stdout || ("system-shell exit " + status)).trim());
              }
              return stdout || "(no output)";
            }

            function requestJson(url, headers = {}) {
              return new Promise((resolve, reject) => {
                let current = String(url);
                let redirects = 0;
                const doRequest = () => {
                  const parsed = new URL(current);
                  const mod = parsed.protocol === "https:" ? https : http;
                  const req = mod.request({
                    protocol: parsed.protocol,
                    hostname: parsed.hostname,
                    port: parsed.port || undefined,
                    path: parsed.pathname + parsed.search,
                    method: "GET",
                    headers: {
                      "User-Agent": "AnyClawClaudeToolbox/1.0",
                      "Accept": "application/json,text/plain,*/*",
                      ...headers
                    },
                    timeout: 25000
                  }, (res) => {
                    const chunks = [];
                    res.on("data", (d) => chunks.push(Buffer.isBuffer(d) ? d : Buffer.from(d)));
                    res.on("end", () => {
                      const body = Buffer.concat(chunks).toString("utf8");
                      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 3) {
                        redirects += 1;
                        current = new URL(res.headers.location, current).toString();
                        doRequest();
                        return;
                      }
                      if (res.statusCode < 200 || res.statusCode >= 300) {
                        reject(new Error("HTTP " + res.statusCode + " " + body.slice(0, 600)));
                        return;
                      }
                      try {
                        resolve(JSON.parse(body));
                      } catch {
                        resolve({ raw: body });
                      }
                    });
                  });
                  req.on("timeout", () => req.destroy(new Error("request timeout")));
                  req.on("error", reject);
                  req.end();
                };
                doRequest();
              });
            }

            function requestText(url) {
              return new Promise((resolve, reject) => {
                const parsed = new URL(String(url));
                const mod = parsed.protocol === "https:" ? https : http;
                const req = mod.request({
                  protocol: parsed.protocol,
                  hostname: parsed.hostname,
                  port: parsed.port || undefined,
                  path: parsed.pathname + parsed.search,
                  method: "GET",
                  headers: { "User-Agent": "AnyClawClaudeToolbox/1.0", "Accept": "text/plain,text/html,*/*" },
                  timeout: 25000
                }, (res) => {
                  const chunks = [];
                  res.on("data", (d) => chunks.push(Buffer.isBuffer(d) ? d : Buffer.from(d)));
                  res.on("end", () => {
                    const body = Buffer.concat(chunks).toString("utf8");
                    if (res.statusCode < 200 || res.statusCode >= 300) {
                      reject(new Error("HTTP " + res.statusCode + " " + body.slice(0, 600)));
                      return;
                    }
                    resolve(body);
                  });
                });
                req.on("timeout", () => req.destroy(new Error("request timeout")));
                req.on("error", reject);
                req.end();
              });
            }

            function flattenDuckRelated(related, out) {
              if (!Array.isArray(related)) return;
              for (const item of related) {
                if (!item || typeof item !== "object") continue;
                if (Array.isArray(item.Topics)) {
                  flattenDuckRelated(item.Topics, out);
                } else if (item.Text) {
                  out.push({ text: String(item.Text), url: String(item.FirstURL || "") });
                }
              }
            }

            function ghHeaders() {
              const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN || "";
              const headers = {
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28"
              };
              if (token.trim()) headers.Authorization = "Bearer " + token.trim();
              return headers;
            }

            async function callTool(name, args) {
              switch (name) {
                case "anyclaw_device_exec": {
                  const command = String(args.command || "").trim();
                  if (!command) throw new Error("command is required");
                  const timeoutMs = Number(args.timeoutMs || DEFAULT_TIMEOUT);
                  const output = runSystemShell(command, timeoutMs);
                  return "system-shell success\n" + output;
                }
                case "anyclaw_device_screenshot": {
                  const outputPath = String(args.outputPath || ("/sdcard/Download/AnyClawShots/claude_capture_" + Date.now() + ".png"));
                  const cmd = "mkdir -p " + shellQuote(outputPath.replace(/\/[^/]*$/, "")) + " && screencap -p " + shellQuote(outputPath);
                  runSystemShell(cmd, 40000);
                  return "screenshot saved: " + outputPath;
                }
                case "anyclaw_device_uiautomator_dump": {
                  const outputPath = String(args.outputPath || ("/sdcard/Download/AnyClawShots/ui_dump_" + Date.now() + ".xml"));
                  const cmd = "mkdir -p " + shellQuote(outputPath.replace(/\/[^/]*$/, "")) + " && uiautomator dump " + shellQuote(outputPath) + " >/dev/null && cat " + shellQuote(outputPath);
                  const xml = runSystemShell(cmd, 45000);
                  return "ui dump saved: " + outputPath + "\n" + xml.slice(0, 5000);
                }
                case "anyclaw_search_web": {
                  const query = String(args.query || "").trim();
                  if (!query) throw new Error("query is required");
                  const maxResults = Math.max(1, Math.min(10, Number(args.maxResults || 5)));
                  const url = "https://api.duckduckgo.com/?q=" + encodeURIComponent(query) + "&format=json&no_html=1&no_redirect=1&skip_disambig=0";
                  const data = await requestJson(url);
                  const lines = [];
                  if (data?.Heading) lines.push("Heading: " + data.Heading);
                  if (data?.AbstractText) {
                    lines.push("Abstract: " + String(data.AbstractText));
                    if (data?.AbstractURL) lines.push("AbstractURL: " + String(data.AbstractURL));
                  }
                  const related = [];
                  flattenDuckRelated(data?.RelatedTopics, related);
                  related.slice(0, maxResults).forEach((x, idx) => {
                    lines.push((idx + 1) + ". " + x.text + (x.url ? " | " + x.url : ""));
                  });
                  if (lines.length === 0) lines.push("No result");
                  return lines.join("\n");
                }
                case "anyclaw_search_wikipedia": {
                  const query = String(args.query || "").trim();
                  if (!query) throw new Error("query is required");
                  const maxResults = Math.max(1, Math.min(10, Number(args.maxResults || 5)));
                  const url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + encodeURIComponent(query) + "&format=json&srlimit=" + maxResults;
                  const data = await requestJson(url);
                  const items = Array.isArray(data?.query?.search) ? data.query.search : [];
                  if (!items.length) return "No result";
                  return items.map((item, idx) => {
                    const title = String(item?.title || "");
                    const snippet = String(item?.snippet || "").replace(/<[^>]+>/g, "");
                    return (idx + 1) + ". " + title + " | " + snippet;
                  }).join("\n");
                }
                case "anyclaw_fetch_url": {
                  const url = String(args.url || "").trim();
                  if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw new Error("url must start with http:// or https://");
                  }
                  const maxChars = Math.max(200, Math.min(50000, Number(args.maxChars || 8000)));
                  const body = await requestText(url);
                  const normalized = body.replace(/\r/g, "").replace(/\t/g, " ").replace(/\x00/g, "");
                  return normalized.slice(0, maxChars);
                }
                case "anyclaw_github_repo": {
                  const owner = String(args.owner || "").trim();
                  const repo = String(args.repo || "").trim();
                  if (!owner || !repo) throw new Error("owner and repo are required");
                  const data = await requestJson("https://api.github.com/repos/" + encodeURIComponent(owner) + "/" + encodeURIComponent(repo), ghHeaders());
                  return JSON.stringify({
                    full_name: data.full_name,
                    description: data.description,
                    stargazers_count: data.stargazers_count,
                    forks_count: data.forks_count,
                    open_issues_count: data.open_issues_count,
                    default_branch: data.default_branch,
                    html_url: data.html_url,
                    updated_at: data.updated_at
                  }, null, 2);
                }
                case "anyclaw_github_search_repositories": {
                  const query = String(args.query || "").trim();
                  if (!query) throw new Error("query is required");
                  const perPage = Math.max(1, Math.min(30, Number(args.perPage || 8)));
                  const data = await requestJson("https://api.github.com/search/repositories?q=" + encodeURIComponent(query) + "&per_page=" + perPage, ghHeaders());
                  const items = Array.isArray(data.items) ? data.items : [];
                  if (!items.length) return "No result";
                  return items.map((item, idx) =>
                    (idx + 1) + ". " + item.full_name + " | stars=" + item.stargazers_count + " | " + (item.description || "")
                  ).join("\n");
                }
                case "anyclaw_github_search_code": {
                  const query = String(args.query || "").trim();
                  if (!query) throw new Error("query is required");
                  const perPage = Math.max(1, Math.min(20, Number(args.perPage || 8)));
                  const data = await requestJson("https://api.github.com/search/code?q=" + encodeURIComponent(query) + "&per_page=" + perPage, ghHeaders());
                  const items = Array.isArray(data.items) ? data.items : [];
                  if (!items.length) return "No result";
                  return items.map((item, idx) =>
                    (idx + 1) + ". " + item.repository?.full_name + " | " + item.path + " | " + item.html_url
                  ).join("\n");
                }
                case "anyclaw_github_list_issues": {
                  const owner = String(args.owner || "").trim();
                  const repo = String(args.repo || "").trim();
                  if (!owner || !repo) throw new Error("owner and repo are required");
                  const state = String(args.state || "open");
                  const perPage = Math.max(1, Math.min(30, Number(args.perPage || 10)));
                  const url = "https://api.github.com/repos/" + encodeURIComponent(owner) + "/" + encodeURIComponent(repo) +
                    "/issues?state=" + encodeURIComponent(state) + "&per_page=" + perPage;
                  const data = await requestJson(url, ghHeaders());
                  const items = Array.isArray(data) ? data.filter((x) => !x.pull_request) : [];
                  if (!items.length) return "No result";
                  return items.map((item) => "#" + item.number + " [" + item.state + "] " + item.title + " | " + item.html_url).join("\n");
                }
                default:
                  throw new Error("unknown tool: " + name);
              }
            }
            """.trimIndent() + "\n"
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
        return ExternalAgentId.entries.firstOrNull { it.value == raw?.trim() } ?: ExternalAgentId.OPEN_CODE
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
