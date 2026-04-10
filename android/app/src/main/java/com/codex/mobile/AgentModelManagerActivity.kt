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

class AgentModelManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AGENT_ID = "com.codex.mobile.extra.AGENT_ID"
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnCreate: Button
    private lateinit var listView: ListView
    private lateinit var tvStatus: TextView

    private lateinit var agentId: ExternalAgentId
    private var rows: List<AgentModelConfig> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_model_manager)

        agentId = parseAgent(intent.getStringExtra(EXTRA_AGENT_ID))

        tvTitle = findViewById(R.id.tvAgentModelManagerTitle)
        tvSubtitle = findViewById(R.id.tvAgentModelManagerSubtitle)
        btnRefresh = findViewById(R.id.btnAgentModelRefresh)
        btnCreate = findViewById(R.id.btnAgentModelCreate)
        listView = findViewById(R.id.listAgentModels)
        tvStatus = findViewById(R.id.tvAgentModelStatus)

        val title = "${AgentSessionStore.displayAgentName(agentId)} 模型管理"
        tvTitle.text = title
        tvSubtitle.text = "支持添加、编辑、删除和切换当前模型。可预置常见提供商端点。"

        btnRefresh.setOnClickListener { refresh() }
        btnCreate.setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        rows = AgentModelConfigStore.loadConfigs(this, agentId)
        renderRows()
    }

    private fun renderRows() {
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size

            override fun getItem(position: Int): Any = rows[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val rowView = convertView ?: LayoutInflater.from(this@AgentModelManagerActivity)
                    .inflate(R.layout.item_agent_model_row, parent, false)
                val row = rows[position]
                val checkedPrefix = if (row.isDefault) "✓ " else ""
                rowView.findViewById<TextView>(R.id.tvAgentModelRowTitle).text = "$checkedPrefix${row.displayName}"
                rowView.findViewById<TextView>(R.id.tvAgentModelRowMeta).text =
                    "${row.modelId} | ${row.providerName} | ${row.protocol.value}\n${row.baseUrl}"

                rowView.findViewById<Button>(R.id.btnAgentModelRowSelect).setOnClickListener {
                    AgentModelConfigStore.setDefault(this@AgentModelManagerActivity, agentId, row.id)
                    refresh()
                    Toast.makeText(this@AgentModelManagerActivity, "已切换当前模型", Toast.LENGTH_SHORT).show()
                }
                rowView.findViewById<Button>(R.id.btnAgentModelRowEdit).setOnClickListener {
                    showEditDialog(row)
                }
                rowView.findViewById<Button>(R.id.btnAgentModelRowDelete).setOnClickListener {
                    confirmDelete(row)
                }
                rowView.setOnClickListener {
                    showEditDialog(row)
                }
                return rowView
            }
        }

        tvStatus.text = if (rows.isEmpty()) {
            "暂无模型配置"
        } else {
            "共 ${rows.size} 条模型配置"
        }
    }

    private fun confirmDelete(config: AgentModelConfig) {
        AlertDialog.Builder(this)
            .setTitle("删除模型")
            .setMessage("确定删除 ${config.displayName} 吗？")
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_delete)) { _, _ ->
                AgentModelConfigStore.deleteConfig(this, config.id)
                refresh()
                Toast.makeText(this, "模型已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showEditDialog(existing: AgentModelConfig?) {
        val presets = AgentModelConfigStore.presetsFor(agentId)

        val nameInput = EditText(this).apply {
            hint = "显示名称"
            setText(existing?.displayName.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val modelIdInput = EditText(this).apply {
            hint = "模型ID（必填）"
            setText(existing?.modelId.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val baseUrlInput = EditText(this).apply {
            hint = "Base URL（必填）"
            setText(existing?.baseUrl.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val apiKeyInput = EditText(this).apply {
            hint = "API Key（必填）"
            setText(existing?.apiKey.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val presetSpinner = Spinner(this)
        val presetLabels = presets.map { preset ->
            if (preset.note.isBlank()) preset.name else "${preset.name}（${preset.note}）"
        }
        presetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presetLabels)

        val protocolText = TextView(this).apply {
            textSize = 13f
        }

        val setDefault = CheckBox(this).apply {
            text = "设为当前模型"
            isChecked = existing?.isDefault ?: true
        }

        fun applyPreset(index: Int, forceOverwrite: Boolean) {
            val preset = presets.getOrNull(index) ?: return
            protocolText.text = "协议：${preset.protocol.value}"
            if (forceOverwrite || baseUrlInput.text.toString().trim().isEmpty()) {
                baseUrlInput.setText(preset.baseUrl)
            }
        }

        val existingPresetIndex = existing?.providerId?.let { pid ->
            presets.indexOfFirst { it.id == pid }
        } ?: -1
        presetSpinner.setSelection(if (existingPresetIndex >= 0) existingPresetIndex else 0)
        applyPreset(if (existingPresetIndex >= 0) existingPresetIndex else 0, forceOverwrite = false)

        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                applyPreset(position, forceOverwrite = true)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@AgentModelManagerActivity).apply { text = "提供商预设" })
            addView(presetSpinner)
            addView(protocolText)
            addView(nameInput)
            addView(modelIdInput)
            addView(baseUrlInput)
            addView(apiKeyInput)
            addView(setDefault)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建模型" else "编辑模型")
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_save_text), null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val selectedPreset = presets.getOrNull(presetSpinner.selectedItemPosition)
                        if (selectedPreset == null) {
                            Toast.makeText(this, "请选择提供商", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val modelId = modelIdInput.text.toString().trim()
                        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
                        val apiKey = apiKeyInput.text.toString().trim()
                        val displayName = nameInput.text.toString().trim().ifEmpty {
                            "${selectedPreset.name} / $modelId"
                        }

                        when {
                            modelId.isEmpty() -> {
                                modelIdInput.error = "模型ID必填"
                                return@setOnClickListener
                            }
                            baseUrl.isEmpty() -> {
                                baseUrlInput.error = "Base URL必填"
                                return@setOnClickListener
                            }
                            apiKey.isEmpty() -> {
                                apiKeyInput.error = "API Key必填"
                                return@setOnClickListener
                            }
                        }

                        val config = AgentModelConfig(
                            id = existing?.id ?: "${agentId.value}-${System.currentTimeMillis()}",
                            agentId = agentId,
                            displayName = displayName,
                            providerId = selectedPreset.id,
                            providerName = selectedPreset.name,
                            protocol = selectedPreset.protocol,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            modelId = modelId,
                            isDefault = setDefault.isChecked,
                        )
                        AgentModelConfigStore.saveConfig(this, config)
                        refresh()
                        dialog.dismiss()
                        Toast.makeText(this, "模型配置已保存", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun parseAgent(raw: String?): ExternalAgentId {
        return ExternalAgentId.entries.firstOrNull { it.value == raw?.trim() } ?: ExternalAgentId.CLAUDE_CODE
    }
}
