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
import java.io.File
import java.util.Locale
import org.json.JSONObject

class CliAgentChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AGENT_ID = "com.codex.mobile.extra.AGENT_ID"
        const val EXTRA_SESSION_ID = "com.codex.mobile.extra.AGENT_SESSION_ID"
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

    private lateinit var serverManager: CodexServerManager
    private lateinit var agentId: ExternalAgentId
    private lateinit var activeSession: AgentChatSession
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cli_agent_chat)

        agentId = parseAgent(intent.getStringExtra(EXTRA_AGENT_ID))
        serverManager = CodexServerManager(this)

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

        tvTitle.text = AgentSessionStore.displayAgentName(agentId)

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
        tvStatus.text = if (sending) "正在执行..." else "就绪"
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
                val prompt = buildPromptWithHistory(activeSession.messages)
                when (agentId) {
                    ExternalAgentId.CLAUDE_CODE -> runClaudePrint(modelConfig, prompt)
                    ExternalAgentId.OPEN_CODE -> runOpenCode(modelConfig, prompt)
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

    private fun buildPromptWithHistory(messages: List<AgentChatMessage>): String {
        val recent = messages.takeLast(12)
        val out = StringBuilder()
        out.appendLine("你正在继续已有会话。请结合上下文回答，并在需要时执行可用工具。")
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

    private fun runClaudePrint(config: AgentModelConfig, prompt: String): String {
        val modelArg = if (config.modelId.isBlank()) "" else "--model ${LocalBridgeClients.shellQuote(config.modelId)} "
        val baseEnv = if (config.baseUrl.isBlank()) "" else "ANTHROPIC_BASE_URL=${LocalBridgeClients.shellQuote(config.baseUrl)} "
        val keyEnv = "ANTHROPIC_API_KEY=${LocalBridgeClients.shellQuote(config.apiKey)} "
        val cmd =
            "${baseEnv}${keyEnv}claude -p ${modelArg}--output-format text ${LocalBridgeClients.shellQuote(prompt)} 2>&1"
        return runPrefixCommandOrThrow(cmd).trim()
    }

    private fun runOpenCode(config: AgentModelConfig, prompt: String): String {
        val homeDir = BootstrapInstaller.getPaths(this).homeDir
        val configFile = File(homeDir, ".pocketlobster/opencode/opencode-${activeSession.sessionId}.json")
        configFile.parentFile?.mkdirs()
        configFile.writeText(buildOpenCodeConfig(config).toString(2))

        val providerKey = normalizeProviderKey(config.providerId.ifBlank { "lobster" })
        val modelRef = if (config.modelId.contains("/")) config.modelId else "$providerKey/${config.modelId}"
        val cmd =
            "OPENCODE_CONFIG=${LocalBridgeClients.shellQuote(configFile.absolutePath)} " +
                "opencode run --model ${LocalBridgeClients.shellQuote(modelRef)} " +
                "--format default ${LocalBridgeClients.shellQuote(prompt)} 2>&1"
        return runPrefixCommandOrThrow(cmd).trim()
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

    private fun stripAnsi(value: String): String {
        return value.replace(Regex("\\u001B\\[[;\\d]*m"), "")
    }

    private fun normalizeProviderKey(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "-")
        return cleaned.ifBlank { "lobster" }
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
