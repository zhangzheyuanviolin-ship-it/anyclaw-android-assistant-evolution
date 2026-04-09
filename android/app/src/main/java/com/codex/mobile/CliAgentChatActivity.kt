package com.codex.mobile

import android.content.Intent
import android.os.Bundle
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
import androidx.appcompat.widget.SwitchCompat
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class CliAgentChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AGENT_ID = "com.codex.mobile.extra.AGENT_ID"
        const val EXTRA_SESSION_ID = "com.codex.mobile.extra.AGENT_SESSION_ID"
        private const val RUNTIME_PREFS = "cli_agent_runtime_options"
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPermissionCenter: Button
    private lateinit var btnPromptManager: Button
    private lateinit var btnConversationManager: Button
    private lateinit var btnModelManager: Button
    private lateinit var btnNewSession: Button
    private lateinit var listView: ListView
    private lateinit var inputMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnOpenClaw: Button
    private lateinit var btnCodex: Button
    private lateinit var btnClaude: Button
    private lateinit var btnOpenCode: Button
    private lateinit var switchAllowSharedStorage: SwitchCompat
    private lateinit var switchDangerousMode: SwitchCompat

    private lateinit var serverManager: CodexServerManager
    private lateinit var agentId: ExternalAgentId
    private lateinit var activeSession: AgentChatSession
    private lateinit var runtimeOptions: AgentRuntimeOptions
    private var sending = false

    private data class AgentRuntimeOptions(
        val allowSharedStorage: Boolean,
        val dangerousAutoApprove: Boolean,
    )

    private data class ProbeResult(
        val code: Int,
        val output: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cli_agent_chat)

        agentId = parseAgent(intent.getStringExtra(EXTRA_AGENT_ID))
        serverManager = CodexServerManager(this)
        ShizukuBridgeRuntime.ensureStarted(this)

        tvTitle = findViewById(R.id.tvCliAgentTitle)
        tvSession = findViewById(R.id.tvCliAgentSession)
        tvStatus = findViewById(R.id.tvCliAgentStatus)
        btnPermissionCenter = findViewById(R.id.btnCliPermissionCenter)
        btnPromptManager = findViewById(R.id.btnCliPromptManager)
        btnConversationManager = findViewById(R.id.btnCliConversationManager)
        btnModelManager = findViewById(R.id.btnCliModelManager)
        btnNewSession = findViewById(R.id.btnCliNewSession)
        listView = findViewById(R.id.listCliAgentMessages)
        inputMessage = findViewById(R.id.etCliAgentInput)
        btnSend = findViewById(R.id.btnCliAgentSend)
        btnOpenClaw = findViewById(R.id.btnCliTabOpenClaw)
        btnCodex = findViewById(R.id.btnCliTabCodex)
        btnClaude = findViewById(R.id.btnCliTabClaude)
        btnOpenCode = findViewById(R.id.btnCliTabOpenCode)
        switchAllowSharedStorage = findViewById(R.id.switchCliAllowSharedStorage)
        switchDangerousMode = findViewById(R.id.switchCliDangerousMode)

        tvTitle.text = AgentSessionStore.displayAgentName(agentId)
        runtimeOptions = loadRuntimeOptions()
        switchAllowSharedStorage.isChecked = runtimeOptions.allowSharedStorage
        switchDangerousMode.isChecked = runtimeOptions.dangerousAutoApprove
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

    private fun renderSession() {
        tvSession.text = "会话：${activeSession.title}"
        tvStatus.text = if (sending) {
            "正在执行..."
        } else {
            val shared = if (runtimeOptions.allowSharedStorage) "开" else "关"
            val danger = if (runtimeOptions.dangerousAutoApprove) "开" else "关"
            "就绪 · 共享存储:$shared · 高权限:$danger"
        }
        renderBottomTabState()

        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = activeSession.messages.size

            override fun getItem(position: Int): Any = activeSession.messages[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val rowView = convertView ?: LayoutInflater.from(this@CliAgentChatActivity)
                    .inflate(R.layout.item_agent_chat_message, parent, false)
                val msg = activeSession.messages[position]
                val role = msg.role.lowercase(Locale.getDefault())
                val roleText = when (role) {
                    "user" -> "您"
                    "assistant" -> AgentSessionStore.displayAgentName(agentId)
                    else -> role
                }
                rowView.findViewById<TextView>(R.id.tvAgentChatRole).text = roleText
                rowView.findViewById<TextView>(R.id.tvAgentChatText).text = msg.text
                return rowView
            }
        }
        listView.post { listView.setSelection((listView.adapter?.count ?: 1) - 1) }
    }

    private fun renderBottomTabState() {
        btnClaude.isEnabled = agentId != ExternalAgentId.CLAUDE_CODE
        btnOpenCode.isEnabled = agentId != ExternalAgentId.OPEN_CODE
    }

    private fun createNewSession() {
        activeSession = AgentSessionStore.createSession(this, agentId)
        renderSession()
        Toast.makeText(this, "已新建会话", Toast.LENGTH_SHORT).show()
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

        inputMessage.setText("")
        activeSession = AgentSessionStore.appendMessage(this, agentId, activeSession.sessionId, "user", input)
        renderSession()

        sending = true
        renderSession()
        Thread {
            val assistantText = runCatching {
                val prompt = buildPromptWithHistory(activeSession.messages, runtimeOptions)
                when (agentId) {
                    ExternalAgentId.CLAUDE_CODE -> runClaudePrint(modelConfig, prompt, runtimeOptions)
                    ExternalAgentId.OPEN_CODE -> runOpenCode(modelConfig, prompt, runtimeOptions)
                }
            }.getOrElse { error ->
                "执行失败：${error.message ?: "unknown error"}"
            }

            activeSession = AgentSessionStore.appendMessage(
                this,
                agentId,
                activeSession.sessionId,
                "assistant",
                assistantText,
            )

            runOnUiThread {
                sending = false
                renderSession()
            }
        }.start()
    }

    private fun buildPromptWithHistory(
        messages: List<AgentChatMessage>,
        options: AgentRuntimeOptions,
    ): String {
        val recent = messages.takeLast(12)
        val out = StringBuilder()
        out.appendLine("你正在继续已有会话。请结合上下文回答，并在需要时执行可用工具。")
        if (agentId == ExternalAgentId.OPEN_CODE || agentId == ExternalAgentId.CLAUDE_CODE) {
            out.appendLine("高优先级运行规范（必须遵守）：")
            out.appendLine("1) 先阅读“自动注入预检结果”，再决定用哪条执行链路。")
            out.appendLine("2) Android 系统级命令必须使用 system-shell <command>。")
            out.appendLine("3) Ubuntu 命令使用 ubuntu-shell <command> 或直接 Linux 命令。")
            out.appendLine("4) 先做必要验证再给结论，且不要复述整段预检文本。")
            out.appendLine("5) 若某链路失败，需明确失败原因并自动切换可用链路继续。")
            out.appendLine()
            out.appendLine("自动注入预检结果：")
            out.appendLine(buildRuntimeProbeBlock(options))
            out.appendLine()
        }
        out.appendLine("会话上下文如下：")
        recent.forEach { msg ->
            val role = msg.role.lowercase(Locale.getDefault())
            val roleText = if (role == "user") "用户" else "助手"
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
    ): String {
        val modelArg = if (config.modelId.isBlank()) "" else "--model ${LocalBridgeClients.shellQuote(config.modelId)} "
        val baseEnv = if (config.baseUrl.isBlank()) "" else "ANTHROPIC_BASE_URL=${LocalBridgeClients.shellQuote(config.baseUrl)} "
        val keyEnv = "ANTHROPIC_API_KEY=${LocalBridgeClients.shellQuote(config.apiKey)} "
        val paths = BootstrapInstaller.getPaths(this)
        val dirs = mutableListOf(paths.homeDir)
        if (options.allowSharedStorage) {
            dirs += "/sdcard"
            dirs += "/storage/emulated/0"
        }
        val addDirArg = dirs.joinToString(" ") { "--add-dir ${LocalBridgeClients.shellQuote(it)}" } + " "
        val dangerArg = if (options.dangerousAutoApprove) "--dangerously-skip-permissions " else ""
        val cmd =
            "${baseEnv}${keyEnv}claude -p ${dangerArg}${addDirArg}${modelArg}--output-format text ${LocalBridgeClients.shellQuote(prompt)} < /dev/null 2>&1"
        val raw = runPrefixCommandOrThrow(cmd).trim()
        return cleanClaudeOutput(raw)
    }

    private fun runOpenCode(
        config: AgentModelConfig,
        prompt: String,
        options: AgentRuntimeOptions,
    ): String {
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
        val modelName = normalizeOpenCodeModelName(config.modelId)
        val modelRef = "$providerKey/$modelName"
        val workDir = if (options.allowSharedStorage) "/sdcard" else homeDir
        val dangerArg = if (options.dangerousAutoApprove) "--dangerously-skip-permissions " else ""
        val cmd =
            "export OPENCODE_CONFIG=${LocalBridgeClients.shellQuote(configFile.absolutePath)}; " +
                "export PATH=${LocalBridgeClients.shellQuote("$bridgeDir:${paths.prefixDir}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")}; " +
                "cd ${LocalBridgeClients.shellQuote(workDir)} 2>/dev/null || cd ${LocalBridgeClients.shellQuote(homeDir)}; " +
                "${LocalBridgeClients.shellQuote(nativeBin)} run --model ${LocalBridgeClients.shellQuote(modelRef)} " +
                "--dir ${LocalBridgeClients.shellQuote(workDir)} ${dangerArg}--format json ${LocalBridgeClients.shellQuote(prompt)} 2>&1"

        return try {
            val raw = runUbuntuCommandOrThrow(
                cmd,
                timeoutMillis = 240_000L,
                idleTimeoutMillis = 60_000L,
            ).trim()
            parseOpenCodeJsonOutput(raw)
        } catch (error: Exception) {
            val tail = fetchOpenCodeLogTail()
            if (tail.isBlank()) throw error
            throw IllegalStateException("${error.message ?: "OpenCode 执行失败"}；日志尾部：$tail")
        }
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
        val modelName = normalizeOpenCodeModelName(config.modelId)
        val npmPackage = when (config.protocol) {
            ProviderProtocol.ANTHROPIC -> "@ai-sdk/anthropic"
            ProviderProtocol.OPENAI_COMPATIBLE -> "@ai-sdk/openai-compatible"
        }
        val modelsJson = JSONObject()
            .put(
                modelName,
                JSONObject().put("name", modelName),
            )
        val rawModel = config.modelId.trim()
        if (rawModel.isNotEmpty() && rawModel != modelName) {
            modelsJson.put(
                rawModel,
                JSONObject().put("name", modelName),
            )
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
            .put("models", modelsJson)

        return JSONObject()
            .put("\$schema", "https://opencode.ai/config.json")
            .put(
                "provider",
                JSONObject().put(providerKey, providerJson),
            )
    }

    private fun runPrefixCommandOrThrow(command: String): String {
        val output = StringBuilder()
        val code = serverManager.runInPrefix(command) { line ->
            output.appendLine(stripAnsi(line))
        }
        val raw = output.toString().trim()
        if (code != 0) {
            throw IllegalStateException(raw.ifBlank { "command exit code=$code" })
        }
        return raw
    }

    private fun runUbuntuCommandOrThrow(
        command: String,
        timeoutMillis: Long? = null,
        idleTimeoutMillis: Long? = null,
    ): String {
        val output = StringBuilder()
        val code = serverManager.runInUbuntu(
            command = command,
            timeoutMillis = timeoutMillis,
            idleTimeoutMillis = idleTimeoutMillis,
        ) { line ->
            val cleaned = stripAnsi(line).trim()
            if (cleaned == "LOGIN_SUCCESSFUL" || cleaned == "TERMINAL_READY") return@runInUbuntu
            output.appendLine(cleaned)
        }
        val raw = output.toString().trim()
        if (code != 0) {
            if (code == 124) {
                throw IllegalStateException(raw.ifBlank { "ubuntu command timeout" })
            }
            if (code == 125) {
                throw IllegalStateException(raw.ifBlank { "ubuntu command idle-timeout" })
            }
            throw IllegalStateException(raw.ifBlank { "ubuntu command exit code=$code" })
        }
        return raw
    }

    private fun parseOpenCodeJsonOutput(raw: String): String {
        if (raw.isBlank()) {
            throw IllegalStateException("OpenCode 未返回任何输出")
        }

        val events = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .toList()

        if (events.isEmpty()) {
            val lowered = raw.lowercase(Locale.US)
            if (lowered.contains("error") || lowered.contains("exception")) {
                throw IllegalStateException(raw.take(500))
            }
            return raw
        }

        val errorMessage = extractOpenCodeError(events)
        if (!errorMessage.isNullOrBlank()) {
            throw IllegalStateException(errorMessage)
        }

        val assistantText = extractOpenCodeAssistantText(events)
        if (assistantText.isBlank()) {
            throw IllegalStateException("OpenCode 已执行但未返回可显示内容")
        }
        return assistantText
    }

    private fun extractOpenCodeError(events: List<JSONObject>): String? {
        for (event in events) {
            val type = event.optString("type", "")
            val errorObj = event.optJSONObject("error")
            if (type.equals("error", ignoreCase = true) || errorObj != null) {
                val data = errorObj?.optJSONObject("data")
                val nested = data?.optString("message").orEmpty().trim()
                val direct = errorObj?.optString("message").orEmpty().trim()
                val top = event.optString("message", "").trim()
                return nested.ifBlank { direct.ifBlank { top.ifBlank { event.toString() } } }
            }
        }
        return null
    }

    private fun extractOpenCodeAssistantText(events: List<JSONObject>): String {
        val candidates = linkedSetOf<String>()
        events.forEach { event ->
            collectOpenCodeTextCandidates(event, null, candidates, 0)
        }
        return candidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
            .trim()
    }

    private fun collectOpenCodeTextCandidates(
        value: Any?,
        key: String?,
        sink: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > 8 || value == null) return
        when (value) {
            is JSONObject -> {
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val childKey = iterator.next()
                    collectOpenCodeTextCandidates(value.opt(childKey), childKey, sink, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectOpenCodeTextCandidates(value.opt(index), key, sink, depth + 1)
                }
            }
            is String -> {
                val normalized = key?.lowercase(Locale.US).orEmpty()
                if (normalized in setOf("text", "content", "output", "answer", "message")) {
                    val text = value.trim()
                    if (text.isNotEmpty()) sink += text
                }
            }
        }
    }

    private fun fetchOpenCodeLogTail(): String {
        val probe = runUbuntuCapture(
            "latest=$(ls -1t /root/.local/share/opencode/log/*.log 2>/dev/null | head -n 1); " +
                "if [ -n \"${'$'}latest\" ]; then tail -n 30 \"${'$'}latest\"; fi",
        )
        return trimProbeOutput(probe.output, maxChars = 700)
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

    private fun normalizeOpenCodeModelName(raw: String): String {
        val trimmed = raw.trim()
        val model = if (trimmed.contains("/")) trimmed.substringAfterLast("/") else trimmed
        if (model.isBlank()) throw IllegalStateException("模型ID为空")
        return model
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
}
