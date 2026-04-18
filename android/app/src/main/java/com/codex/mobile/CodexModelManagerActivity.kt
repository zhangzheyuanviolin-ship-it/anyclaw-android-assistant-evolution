package com.codex.mobile

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class CodexModelManagerActivity : AppCompatActivity() {

    companion object {
        private const val TOKEN_CATALOG_PREFIX = "codex-token-runtime-"
        private const val TOKEN_PROVIDER_ID = "openai_token_catalog"
        private const val TOKEN_PROVIDER_NAME = "OpenAI 登录模型"
    }

    private data class AuthModeOption(
        val mode: CodexAuthMode,
        val label: String,
    )

    private data class RuntimeModelOption(
        val modelId: String,
        val displayName: String,
        val isDefault: Boolean,
    )

    private data class RuntimeSnapshot(
        val loggedIn: Boolean,
        val accountEmail: String,
        val models: List<RuntimeModelOption>,
        val runtimeModel: String,
        val runtimeProvider: String,
        val runtimeBaseUrl: String,
    )

    private data class ApplyVerification(
        val matched: Boolean?,
        val actualModel: String,
        val actualProvider: String,
        val source: String,
    )

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnCreate: Button
    private lateinit var listView: ListView
    private lateinit var tvStatus: TextView

    private val serverManager by lazy { CodexServerManager(this) }
    private var rows: List<CodexModelConfig> = emptyList()
    private var runtimeSnapshot: RuntimeSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_model_manager)

        tvTitle = findViewById(R.id.tvAgentModelManagerTitle)
        tvSubtitle = findViewById(R.id.tvAgentModelManagerSubtitle)
        btnRefresh = findViewById(R.id.btnAgentModelRefresh)
        btnCreate = findViewById(R.id.btnAgentModelCreate)
        listView = findViewById(R.id.listAgentModels)
        tvStatus = findViewById(R.id.tvAgentModelStatus)

        tvTitle.text = getString(R.string.codex_model_manager_title)
        tvSubtitle.text = getString(R.string.codex_model_manager_subtitle)

        btnRefresh.setOnClickListener { refresh() }
        btnCreate.setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        btnRefresh.isEnabled = false
        tvStatus.text = "正在刷新 Codex 模型配置与运行态…"
        Thread {
            val snapshot = loadRuntimeSnapshot()
            runtimeSnapshot = snapshot
            runCatching { syncRuntimeTokenCatalog(snapshot) }
            val updatedRows = CodexModelConfigStore.loadConfigs(this)
            val statusText = buildStatusText(updatedRows, snapshot)
            runOnUiThread {
                rows = updatedRows
                btnRefresh.isEnabled = true
                renderRows(statusText)
            }
        }.start()
    }

    private fun renderRows(statusText: String) {
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size

            override fun getItem(position: Int): Any = rows[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val rowView = convertView ?: LayoutInflater.from(this@CodexModelManagerActivity)
                    .inflate(R.layout.item_agent_model_row, parent, false)
                val row = rows[position]
                val runtimeCatalog = isRuntimeCatalogRow(row)
                val checkedPrefix = if (row.isDefault) "✓ " else ""
                rowView.findViewById<TextView>(R.id.tvAgentModelRowTitle).text = "$checkedPrefix${row.displayName}"
                rowView.findViewById<TextView>(R.id.tvAgentModelRowMeta).text =
                    "model=${row.modelId} | provider=${row.providerName}\nauth=${authLabel(row.authMode)} | source=${if (runtimeCatalog) "runtime_catalog" else "manual"}\n${row.baseUrl.ifBlank { "baseUrl=默认" }}"

                rowView.findViewById<Button>(R.id.btnAgentModelRowSelect).setOnClickListener {
                    CodexModelConfigStore.setDefault(this@CodexModelManagerActivity, row.id)
                    applySelectedConfigInBackground(showToast = true, expected = row)
                }
                val btnEdit = rowView.findViewById<Button>(R.id.btnAgentModelRowEdit)
                val btnDelete = rowView.findViewById<Button>(R.id.btnAgentModelRowDelete)
                btnEdit.isEnabled = !runtimeCatalog
                btnDelete.isEnabled = !runtimeCatalog
                btnEdit.alpha = if (runtimeCatalog) 0.55f else 1f
                btnDelete.alpha = if (runtimeCatalog) 0.55f else 1f

                btnEdit.setOnClickListener {
                    if (runtimeCatalog) {
                        Toast.makeText(
                            this@CodexModelManagerActivity,
                            "登录态模型由系统自动维护，不能直接编辑。",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        showEditDialog(row)
                    }
                }
                btnDelete.setOnClickListener {
                    if (runtimeCatalog) {
                        Toast.makeText(
                            this@CodexModelManagerActivity,
                            "登录态模型由系统自动维护，不能直接删除。",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        confirmDelete(row)
                    }
                }
                rowView.setOnClickListener {
                    if (!runtimeCatalog) {
                        showEditDialog(row)
                    }
                }
                return rowView
            }
        }

        tvStatus.text = statusText
    }

    private fun confirmDelete(config: CodexModelConfig) {
        if (isRuntimeCatalogRow(config)) {
            Toast.makeText(this, "登录态模型由系统自动维护，不能删除。", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除模型")
            .setMessage("确定删除 ${config.displayName} 吗？")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_delete)) { _, _ ->
                CodexModelConfigStore.deleteConfig(this, config.id)
                val expected = CodexModelConfigStore.loadCurrentConfig(this)
                applySelectedConfigInBackground(showToast = false, expected = expected)
                Toast.makeText(this, "模型已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showEditDialog(existing: CodexModelConfig?) {
        val presets = CodexModelConfigStore.presets()
        val authOptions = listOf(
            AuthModeOption(CodexAuthMode.TOKEN, "令牌鉴权（ChatGPT 登录）"),
            AuthModeOption(CodexAuthMode.API, "API Key 鉴权（兼容端点）"),
        )

        val nameInput = EditText(this).apply {
            hint = "显示名称"
            setText(existing?.displayName.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val modelIdInput = EditText(this).apply {
            hint = "模型 ID（必填）"
            setText(existing?.modelId.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val modelIdHelp = TextView(this).apply {
            text = "模型ID默认填写模型名（如 gpt-5.4）；若服务商要求 provider/model（如 deepseek/deepseek-chat）也可直接填写，系统会原样保存。"
            textSize = 12f
        }

        val baseUrlInput = EditText(this).apply {
            hint = "Base URL（API 模式必填；Token 模式可选）"
            setText(existing?.baseUrl.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val apiKeyInput = EditText(this).apply {
            hint = "API Key（仅 API 模式必填）"
            setText(existing?.apiKey.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val authSpinner = Spinner(this)
        authSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            authOptions.map { it.label },
        )

        val presetSpinner = Spinner(this)
        val presetLabels = presets.map { p -> if (p.note.isBlank()) p.name else "${p.name}（${p.note}）" }
        presetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            presetLabels,
        )

        val setDefault = CheckBox(this).apply {
            text = "设为当前模型"
            isChecked = existing?.isDefault ?: true
        }

        fun selectedAuthMode(): CodexAuthMode {
            return authOptions.getOrNull(authSpinner.selectedItemPosition)?.mode ?: CodexAuthMode.TOKEN
        }

        fun selectedPreset(): CodexProviderPreset? {
            return presets.getOrNull(presetSpinner.selectedItemPosition)
        }

        fun applyPreset(forceOverwrite: Boolean) {
            val preset = selectedPreset() ?: return
            if (forceOverwrite || baseUrlInput.text.toString().trim().isEmpty()) {
                baseUrlInput.setText(preset.baseUrl)
            }
        }

        fun syncAuthUi() {
            val apiMode = selectedAuthMode() == CodexAuthMode.API
            apiKeyInput.isEnabled = apiMode
            apiKeyInput.alpha = if (apiMode) 1f else 0.6f
            if (apiMode) {
                apiKeyInput.hint = "API Key（必填）"
                baseUrlInput.hint = "Base URL（必填）"
            } else {
                apiKeyInput.hint = "Token 模式无需 API Key"
                baseUrlInput.hint = "Base URL（可选，留空使用默认 OpenAI 端点）"
            }
        }

        val authIndex = authOptions.indexOfFirst { it.mode == (existing?.authMode ?: CodexAuthMode.TOKEN) }
        authSpinner.setSelection(if (authIndex >= 0) authIndex else 0)
        val presetIndex = existing?.providerId?.let { id -> presets.indexOfFirst { it.id == id } } ?: -1
        presetSpinner.setSelection(if (presetIndex >= 0) presetIndex else 0)
        applyPreset(forceOverwrite = false)
        syncAuthUi()

        authSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                syncAuthUi()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                applyPreset(forceOverwrite = true)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@CodexModelManagerActivity).apply { text = "鉴权模式" })
            addView(authSpinner)
            addView(TextView(this@CodexModelManagerActivity).apply { text = "提供商预设（可自定义 Base URL）" })
            addView(presetSpinner)
            addView(nameInput)
            addView(modelIdInput)
            addView(modelIdHelp)
            addView(baseUrlInput)
            addView(apiKeyInput)
            addView(setDefault)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建 Codex 模型" else "编辑 Codex 模型")
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setNeutralButton("测试连接", null)
            .setPositiveButton(getString(R.string.prompt_save_text), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val draft = buildDraftConfig(
                    existing = existing,
                    authMode = selectedAuthMode(),
                    preset = selectedPreset(),
                    nameInput = nameInput,
                    modelIdInput = modelIdInput,
                    baseUrlInput = baseUrlInput,
                    apiKeyInput = apiKeyInput,
                    setDefault = setDefault,
                    validate = false,
                )
                if (draft == null) {
                    Toast.makeText(this, "请先补充模型ID", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                testConnection(draft)
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val config = buildDraftConfig(
                    existing = existing,
                    authMode = selectedAuthMode(),
                    preset = selectedPreset(),
                    nameInput = nameInput,
                    modelIdInput = modelIdInput,
                    baseUrlInput = baseUrlInput,
                    apiKeyInput = apiKeyInput,
                    setDefault = setDefault,
                    validate = true,
                ) ?: return@setOnClickListener

                CodexModelConfigStore.saveConfig(this, config)
                val expected = if (config.isDefault) config else CodexModelConfigStore.loadCurrentConfig(this)
                applySelectedConfigInBackground(showToast = true, expected = expected)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun buildDraftConfig(
        existing: CodexModelConfig?,
        authMode: CodexAuthMode,
        preset: CodexProviderPreset?,
        nameInput: EditText,
        modelIdInput: EditText,
        baseUrlInput: EditText,
        apiKeyInput: EditText,
        setDefault: CheckBox,
        validate: Boolean,
    ): CodexModelConfig? {
        val modelIdRaw = modelIdInput.text.toString().trim()
        val modelId = normalizeModelId(modelIdRaw)
        if (validate && modelId.isBlank()) {
            modelIdInput.error = "模型ID必填"
            return null
        }

        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
            .ifEmpty { preset?.baseUrl.orEmpty().trim().trimEnd('/') }
        val apiKey = apiKeyInput.text.toString().trim()

        if (validate && authMode == CodexAuthMode.API) {
            if (baseUrl.isBlank()) {
                baseUrlInput.error = "API 模式下 Base URL 必填"
                return null
            }
            val baseUrlError = validateApiBaseUrl(baseUrl)
            if (baseUrlError != null) {
                baseUrlInput.error = baseUrlError
                return null
            }
            if (apiKey.isBlank()) {
                apiKeyInput.error = "API 模式下 API Key 必填"
                return null
            }
        }

        val providerId = preset?.id?.trim().orEmpty().ifBlank { "custom_openai_compatible" }
        val providerName = preset?.name?.trim().orEmpty().ifBlank { "自定义兼容端点" }
        val displayName = nameInput.text.toString().trim().ifEmpty { "$providerName / $modelId" }

        return CodexModelConfig(
            id = existing?.id ?: "codex-${System.currentTimeMillis()}",
            displayName = displayName,
            providerId = providerId,
            providerName = providerName,
            baseUrl = baseUrl,
            modelId = modelId,
            authMode = authMode,
            apiKey = apiKey,
            isDefault = setDefault.isChecked,
        )
    }

    private fun normalizeModelId(raw: String): String {
        return raw.trim()
    }

    private fun authLabel(mode: CodexAuthMode): String {
        return when (mode) {
            CodexAuthMode.TOKEN -> "token"
            CodexAuthMode.API -> "api"
        }
    }

    private fun testConnection(config: CodexModelConfig) {
        Toast.makeText(this, "正在测试连接…", Toast.LENGTH_SHORT).show()
        Thread {
            val message = when (config.authMode) {
                CodexAuthMode.TOKEN -> {
                    val ok = runCatching { serverManager.isLoggedIn() }.getOrElse { false }
                    if (!ok) {
                        "连接测试失败：当前未登录 Codex，请先在权限中心完成登录。"
                    } else {
                        val runtimeIds = runCatching { loadRuntimeModelIds() }.getOrElse { emptySet() }
                        if (runtimeIds.isEmpty()) {
                            "连接测试通过：Token 模式已登录 Codex（未取到模型目录，建议稍后刷新重试）。"
                        } else if (runtimeIds.contains(config.modelId.trim())) {
                            "连接测试通过：Token 模式已登录，且模型 ${config.modelId} 可用。"
                        } else {
                            "连接测试提示：已登录，但当前账号模型目录未包含 ${config.modelId}。"
                        }
                    }
                }

                CodexAuthMode.API -> {
                    val result = probeApiProvider(config.baseUrl, config.apiKey)
                    if (result.first) {
                        "连接测试通过：HTTP ${result.second}"
                    } else {
                        "连接测试失败：${result.second}"
                    }
                }
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun validateApiBaseUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return "Base URL 不能为空"
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return "Base URL 格式无效"
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (scheme == "https") return null
        if (scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")) return null
        return "仅允许 https 端点；若为本机调试可使用 http://localhost"
    }

    private fun probeApiProvider(baseUrl: String, apiKey: String): Pair<Boolean, String> {
        if (baseUrl.isBlank()) return false to "Base URL 为空"
        if (apiKey.isBlank()) return false to "API Key 为空"

        val normalized = baseUrl.trim().trimEnd('/')
        val candidates = linkedSetOf<String>()
        if (normalized.endsWith("/v1")) {
            candidates += "$normalized/models"
        } else {
            candidates += "$normalized/models"
            candidates += "$normalized/v1/models"
        }

        var lastError = "unknown"
        for (url in candidates) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                conn.disconnect()
                return if (code in 200..299) {
                    true to code.toString()
                } else {
                    false to "HTTP $code"
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return false to lastError
    }

    private fun applySelectedConfigInBackground(
        showToast: Boolean,
        expected: CodexModelConfig?,
    ) {
        Thread {
            val ok = runCatching { serverManager.applySelectedCodexModelConfig(force = true) }.getOrElse { false }
            val verify = if (ok) verifyAppliedConfig(expected) else null
            runOnUiThread {
                if (showToast) {
                    if (ok) {
                        val verifyMsg = when (verify?.matched) {
                            true -> "已验证生效：${verify.actualProvider.ifBlank { "unknown" }}/${verify.actualModel.ifBlank { "unknown" }} (${verify.source})"
                            false -> "应用完成，但运行态与预期不一致：实际 ${verify.actualProvider.ifBlank { "unknown" }}/${verify.actualModel.ifBlank { "unknown" }} (${verify.source})"
                            null -> "模型配置已应用（运行态暂不可验证）"
                        }
                        Toast.makeText(this, verifyMsg, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "模型配置已保存，但应用配置失败，请重试", Toast.LENGTH_LONG).show()
                    }
                }
                refresh()
            }
        }.start()
    }

    private fun verifyAppliedConfig(expected: CodexModelConfig?): ApplyVerification {
        val expectedModel = normalizeModelId(expected?.modelId.orEmpty())
        val expectedProvider = expected?.let { expectedProviderFor(it) }.orEmpty()

        val rpcResult = runCatching { readRuntimeEffectiveModelFromRpc() }.getOrNull()
        if (rpcResult != null) {
            val actualModel = normalizeModelId(rpcResult.first)
            val actualProvider = rpcResult.second.trim()
            if (actualModel.isNotBlank()) {
                val matched = expectedModel.isNotBlank() &&
                    expectedModel.equals(actualModel, ignoreCase = true) &&
                    (expectedProvider.isBlank() || expectedProvider.equals(actualProvider, ignoreCase = true))
                return ApplyVerification(
                    matched = matched,
                    actualModel = actualModel,
                    actualProvider = actualProvider,
                    source = "rpc",
                )
            }
        }

        val fileResult = runCatching { readManagedModelFromConfigFile() }.getOrNull()
        if (fileResult != null) {
            val actualModel = normalizeModelId(fileResult.first)
            val actualProvider = fileResult.second.trim()
            if (actualModel.isNotBlank()) {
                val matched = expectedModel.isNotBlank() &&
                    expectedModel.equals(actualModel, ignoreCase = true) &&
                    (expectedProvider.isBlank() || expectedProvider.equals(actualProvider, ignoreCase = true))
                return ApplyVerification(
                    matched = matched,
                    actualModel = actualModel,
                    actualProvider = actualProvider,
                    source = "config.toml",
                )
            }
        }

        return ApplyVerification(
            matched = null,
            actualModel = "",
            actualProvider = "",
            source = "unavailable",
        )
    }

    private fun expectedProviderFor(config: CodexModelConfig): String {
        return if (config.authMode == CodexAuthMode.TOKEN) {
            "openai"
        } else {
            config.providerId.trim().ifBlank { "custom_openai_compatible" }
                .replace(Regex("[^a-zA-Z0-9_]+"), "_")
                .trim('_')
                .ifBlank { "custom_openai_compatible" }
                .let { key ->
                    when (key) {
                        "openai", "ollama", "lmstudio" -> "${key}_proxy"
                        else -> key
                    }
                }
        }
    }

    private fun readRuntimeEffectiveModelFromRpc(): Pair<String, String> {
        val result = LocalBridgeClients.callCodexRpc("config/read")
        val config = result.optJSONObject("config") ?: JSONObject()
        val model = normalizeModelId(config.optString("model", ""))
        val provider = config.optString("model_provider", "").trim()
        return model to provider
    }

    private fun readManagedModelFromConfigFile(): Pair<String, String> {
        val paths = BootstrapInstaller.getPaths(this)
        val configFile = File(paths.homeDir, ".codex/config.toml")
        if (!configFile.exists()) return "" to ""
        val text = configFile.readText()
        val startMarker = "# >>> pocketlobster-codex-model >>>"
        val endMarker = "# <<< pocketlobster-codex-model <<<"
        val start = text.indexOf(startMarker)
        val end = text.indexOf(endMarker)
        if (start < 0 || end <= start) return "" to ""
        val block = text.substring(start + startMarker.length, end)
        val model = Regex("(?m)^\\s*model\\s*=\\s*\"([^\"]+)\"")
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
            .trim()
        val provider = Regex("(?m)^\\s*model_provider\\s*=\\s*\"([^\"]+)\"")
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
            .trim()
        return model to provider
    }

    private fun isRuntimeCatalogRow(config: CodexModelConfig): Boolean {
        return config.id.startsWith(TOKEN_CATALOG_PREFIX)
    }

    private fun loadRuntimeSnapshot(): RuntimeSnapshot {
        val loggedIn = runCatching { serverManager.isLoggedIn() }.getOrElse { false }
        val accountEmail = runCatching {
            LocalBridgeClients.callCodexRpc("account/read")
                .optJSONObject("account")
                ?.optString("email", "")
                .orEmpty()
                .trim()
        }.getOrElse { "" }

        val models = mutableListOf<RuntimeModelOption>()
        runCatching {
            val payload = LocalBridgeClients.callCodexRpc("model/list")
            val data = payload.optJSONArray("data") ?: JSONArray()
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val modelId = normalizeModelId(item.optString("id", ""))
                if (modelId.isBlank()) continue
                models += RuntimeModelOption(
                    modelId = modelId,
                    displayName = item.optString("displayName", modelId).trim().ifBlank { modelId },
                    isDefault = item.optBoolean("isDefault", false),
                )
            }
        }

        var runtimeModel = ""
        var runtimeProvider = ""
        var runtimeBaseUrl = ""
        runCatching {
            val config = LocalBridgeClients.callCodexRpc("config/read").optJSONObject("config") ?: JSONObject()
            runtimeModel = normalizeModelId(config.optString("model", ""))
            runtimeProvider = config.optString("model_provider", "").trim()
            runtimeBaseUrl = config.optString("openai_base_url", "").trim().trimEnd('/')
        }

        if (runtimeModel.isBlank()) {
            runtimeModel = models.firstOrNull { it.isDefault }?.modelId.orEmpty()
        }
        if (runtimeProvider.isBlank() && runtimeModel.isNotBlank()) {
            runtimeProvider = "openai"
        }

        return RuntimeSnapshot(
            loggedIn = loggedIn,
            accountEmail = accountEmail,
            models = models.distinctBy { it.modelId },
            runtimeModel = runtimeModel,
            runtimeProvider = runtimeProvider,
            runtimeBaseUrl = runtimeBaseUrl,
        )
    }

    private fun loadRuntimeModelIds(): Set<String> {
        val snapshot = loadRuntimeSnapshot()
        return snapshot.models.map { it.modelId }.filter { it.isNotBlank() }.toSet()
    }

    private fun syncRuntimeTokenCatalog(snapshot: RuntimeSnapshot) {
        if (snapshot.models.isEmpty()) return
        val existing = CodexModelConfigStore.loadConfigs(this)
        val existingById = existing.associateBy { it.id }
        val existingByModel = existing
            .filter { isRuntimeCatalogRow(it) }
            .associateBy { normalizeModelId(it.modelId) }
        var changed = false

        snapshot.models.forEach { runtimeModel ->
            val modelId = normalizeModelId(runtimeModel.modelId)
            if (modelId.isBlank()) return@forEach
            val rowId = runtimeTokenConfigId(modelId)
            val existingRow = existingById[rowId] ?: existingByModel[modelId]
            val desired = CodexModelConfig(
                id = rowId,
                displayName = runtimeModel.displayName.ifBlank { modelId },
                providerId = TOKEN_PROVIDER_ID,
                providerName = TOKEN_PROVIDER_NAME,
                baseUrl = snapshot.runtimeBaseUrl,
                modelId = modelId,
                authMode = CodexAuthMode.TOKEN,
                apiKey = "",
                isDefault = existingRow?.isDefault ?: false,
            )
            if (existingRow == null || existingRow != desired) {
                CodexModelConfigStore.saveConfig(this, desired)
                changed = true
            }
        }

        val updated = CodexModelConfigStore.loadConfigs(this)
        if (updated.none { it.isDefault }) {
            val targetModel = normalizeModelId(snapshot.runtimeModel)
            val target = updated.firstOrNull {
                it.authMode == CodexAuthMode.TOKEN && normalizeModelId(it.modelId).equals(targetModel, ignoreCase = true)
            } ?: updated.firstOrNull()
            if (target != null) {
                CodexModelConfigStore.setDefault(this, target.id)
                changed = true
            }
        }

        if (changed) {
            runCatching { serverManager.applySelectedCodexModelConfig(force = false) }
        }
    }

    private fun runtimeTokenConfigId(modelId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(modelId.toByteArray(Charsets.UTF_8))
        val hex = digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
        return "$TOKEN_CATALOG_PREFIX$hex"
    }

    private fun buildStatusText(
        rowList: List<CodexModelConfig>,
        snapshot: RuntimeSnapshot?,
    ): String {
        val selected = rowList.firstOrNull { it.isDefault }
        val tokenCount = rowList.count { it.authMode == CodexAuthMode.TOKEN }
        val apiCount = rowList.count { it.authMode == CodexAuthMode.API }
        val runtimeModel = snapshot?.runtimeModel?.ifBlank { "未返回" } ?: "未返回"
        val runtimeProvider = snapshot?.runtimeProvider?.ifBlank { "未返回" } ?: "未返回"
        val loginState = if (snapshot?.loggedIn == true) {
            "已登录${snapshot.accountEmail.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""}"
        } else {
            "未登录"
        }
        val cliVersion = serverManager.getInstalledCodexVersion().ifBlank { "unknown" }
        val nativeVersion = serverManager.getInstalledCodexNativeVersion().ifBlank { "unknown" }
        val versionState = if (cliVersion != "unknown" && nativeVersion != "unknown" && cliVersion == nativeVersion) {
            "一致"
        } else {
            "存在差异"
        }
        val configPath = File(BootstrapInstaller.getPaths(this).homeDir, ".codex/config.toml").absolutePath
        val selectedText = selected?.let {
            "${it.modelId} (${authLabel(it.authMode)})"
        } ?: "未选择"
        return "共 ${rowList.size} 条（Token=$tokenCount，API=$apiCount） | 当前选中=$selectedText | 运行态=$runtimeProvider/$runtimeModel | 登录=$loginState | CLI=$cliVersion Native=$nativeVersion($versionState)\n配置文件：$configPath"
    }
}
