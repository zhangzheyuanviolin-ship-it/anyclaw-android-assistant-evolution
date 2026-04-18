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
import java.net.HttpURLConnection
import java.net.URL

class CodexModelManagerActivity : AppCompatActivity() {

    private data class AuthModeOption(
        val mode: CodexAuthMode,
        val label: String,
    )

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnCreate: Button
    private lateinit var listView: ListView
    private lateinit var tvStatus: TextView

    private val serverManager by lazy { CodexServerManager(this) }
    private var rows: List<CodexModelConfig> = emptyList()

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
        rows = CodexModelConfigStore.loadConfigs(this)
        renderRows()
    }

    private fun renderRows() {
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size

            override fun getItem(position: Int): Any = rows[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val rowView = convertView ?: LayoutInflater.from(this@CodexModelManagerActivity)
                    .inflate(R.layout.item_agent_model_row, parent, false)
                val row = rows[position]
                val checkedPrefix = if (row.isDefault) "✓ " else ""
                rowView.findViewById<TextView>(R.id.tvAgentModelRowTitle).text = "$checkedPrefix${row.displayName}"
                rowView.findViewById<TextView>(R.id.tvAgentModelRowMeta).text =
                    "model=${row.modelId} | provider=${row.providerName}\nauth=${authLabel(row.authMode)}\n${row.baseUrl.ifBlank { "baseUrl=默认" }}"

                rowView.findViewById<Button>(R.id.btnAgentModelRowSelect).setOnClickListener {
                    CodexModelConfigStore.setDefault(this@CodexModelManagerActivity, row.id)
                    applySelectedConfigInBackground(showToast = true)
                    refresh()
                }
                rowView.findViewById<Button>(R.id.btnAgentModelRowEdit).setOnClickListener {
                    showEditDialog(row)
                }
                rowView.findViewById<Button>(R.id.btnAgentModelRowDelete).setOnClickListener {
                    confirmDelete(row)
                }
                rowView.setOnClickListener { showEditDialog(row) }
                return rowView
            }
        }

        tvStatus.text = if (rows.isEmpty()) "暂无 Codex 模型配置" else "共 ${rows.size} 条 Codex 模型配置"
    }

    private fun confirmDelete(config: CodexModelConfig) {
        AlertDialog.Builder(this)
            .setTitle("删除模型")
            .setMessage("确定删除 ${config.displayName} 吗？")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_delete)) { _, _ ->
                CodexModelConfigStore.deleteConfig(this, config.id)
                applySelectedConfigInBackground(showToast = false)
                refresh()
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
            text = "模型ID支持两种写法：`gpt-5.4` 或 `provider/gpt-5.4`。已选预设时推荐只填模型名。"
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
                applySelectedConfigInBackground(showToast = true)
                refresh()
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
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.contains('/')) trimmed.substringAfterLast('/').trim() else trimmed
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
                    if (ok) {
                        "连接测试通过：Token 模式已登录 Codex。"
                    } else {
                        "连接测试失败：当前未登录 Codex，请先在权限中心完成登录。"
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

    private fun applySelectedConfigInBackground(showToast: Boolean) {
        Thread {
            val ok = runCatching { serverManager.applySelectedCodexModelConfig(force = true) }.getOrElse { false }
            runOnUiThread {
                if (showToast) {
                    if (ok) {
                        Toast.makeText(this, "模型配置已保存并生效", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "模型配置已保存，但应用配置失败，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }
}
