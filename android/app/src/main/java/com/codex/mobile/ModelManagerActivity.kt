package com.codex.mobile

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class ModelManagerActivity : AppCompatActivity() {
    companion object {
        private const val REDACTED_SENTINEL = "__OPENCLAW_REDACTED__"
    }

    private enum class AuthMode {
        TOKEN,
        API,
    }

    private data class ModelRow(
        val id: String,
        val name: String,
        val provider: String,
        val mode: AuthMode,
        val baseUrl: String,
        val apiKeyPresent: Boolean,
    )

    private data class ProviderConnectionProbe(
        val ok: Boolean,
        val code: Int,
        val detail: String,
    )

    private data class ProviderEntry(
        val name: String,
        val config: JSONObject,
    )

    private lateinit var btnRefresh: Button
    private lateinit var btnCreate: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnFetchModels: Button
    private lateinit var btnClaudeModelManager: Button
    private lateinit var tvCurrentModel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView

    private val serverManager by lazy { CodexServerManager(this) }
    private val gateway by lazy { LocalBridgeClients.OpenClawGateway(serverManager) }

    private var loading = false
    private var rows = mutableListOf<ModelRow>()
    private var currentModelId = ""
    private var currentModelMode: AuthMode? = null
    private var configRaw = "{}"
    private var configBaseHash = ""
    private var tokenProviders = emptySet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)

        btnRefresh = findViewById(R.id.btnModelRefresh)
        btnCreate = findViewById(R.id.btnModelCreate)
        btnTestConnection = findViewById(R.id.btnModelTestConnection)
        btnFetchModels = findViewById(R.id.btnModelFetchModels)
        btnClaudeModelManager = findViewById(R.id.btnClaudeModelManager)
        tvCurrentModel = findViewById(R.id.tvCurrentModel)
        progressBar = findViewById(R.id.progressModel)
        tvStatus = findViewById(R.id.tvModelStatus)
        listView = findViewById(R.id.listModels)

        btnRefresh.setOnClickListener { loadModelsAndConfig() }
        btnCreate.setOnClickListener { openCreateModelDialog() }
        btnTestConnection.setOnClickListener { testCurrentModelConnection() }
        btnFetchModels.setOnClickListener { fetchCurrentModelList() }
        btnClaudeModelManager.setOnClickListener {
            startActivity(
                android.content.Intent(this, AgentModelManagerActivity::class.java).apply {
                    putExtra(AgentModelManagerActivity.EXTRA_AGENT_ID, ExternalAgentId.CLAUDE_CODE.value)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        loadModelsAndConfig()
    }

    private fun loadModelsAndConfig() {
        if (loading) return
        loading = true
        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.model_manager_loading)

        Thread {
            try {
                val configPayload = runCatching {
                    callGatewayWithRetry("config.get", JSONObject(), attempts = 8, baseDelayMs = 250)
                }.getOrNull() ?: JSONObject()
                configRaw = configPayload.optString("raw", "{}")
                configBaseHash = configPayload.optString("hash", "")
                val writableRoot = readWritableConfigRoot(configPayload)
                val writableRaw = writableRoot.toString()

                val defaultProvider = runCatching { loadDefaultModelProvider() }.getOrDefault("")
                val configuredPrimary = readPrimaryModelId(writableRoot)
                currentModelId = normalizeCurrentModelId(
                    configuredPrimary.ifEmpty { extractCurrentModel(configPayload) },
                    defaultProvider,
                )
                tokenProviders = readTokenProviders()
                rows = buildModelRows(writableRaw, currentModelId, tokenProviders).toMutableList()
                currentModelMode = resolveCurrentModelMode(writableRoot, currentModelId, tokenProviders)

                runOnUiThread {
                    loading = false
                    progressBar.visibility = View.GONE
                    renderModelList()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loading = false
                    progressBar.visibility = View.GONE
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = getString(R.string.model_manager_error_prefix) + (error.message ?: "unknown")
                    Toast.makeText(
                        this,
                        getString(R.string.model_manager_error_prefix) + (error.message ?: "unknown"),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun loadDefaultModelProvider(): String {
        return try {
            val payload = callGatewayWithRetry(
                "sessions.list",
                JSONObject()
                    .put("limit", 1)
                    .put("includeDerivedTitles", false)
                    .put("includeLastMessage", false)
                    .put("includeGlobal", true)
                    .put("includeUnknown", true),
                attempts = 4,
                baseDelayMs = 200,
            )
            payload.optJSONObject("defaults")?.optString("modelProvider", "").orEmpty().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractCurrentModel(configPayload: JSONObject): String {
        val resolved = configPayload.optJSONObject("resolved")
            ?: configPayload.optJSONObject("config")
            ?: JSONObject()
        return resolved
            .optJSONObject("agents")
            ?.optJSONObject("defaults")
            ?.optJSONObject("model")
            ?.optString("primary", "")
            .orEmpty()
            .trim()
    }

    private fun normalizeCurrentModelId(raw: String, defaultProvider: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.contains("/")) return trimmed
        if (defaultProvider.isNotEmpty()) {
            return "$defaultProvider/$trimmed"
        }
        if (trimmed.startsWith("gpt-", ignoreCase = true)) {
            return "openai-codex/$trimmed"
        }
        return trimmed
    }

    private fun buildModelRows(
        rawConfig: String,
        currentModel: String,
        tokenProvidersSet: Set<String>,
    ): List<ModelRow> {
        val output = linkedMapOf<String, ModelRow>()
        val root = runCatching { JSONObject(rawConfig) }.getOrElse { JSONObject() }
        val providers = root
            .optJSONObject("models")
            ?.optJSONObject("providers")
            ?: JSONObject()

        fun upsertRow(row: ModelRow) {
            val existing = output[row.id]
            if (existing == null || (existing.mode == AuthMode.TOKEN && row.mode == AuthMode.API)) {
                output[row.id] = row
            }
        }

        val orderedTokenProviders = tokenProvidersSet.toList().sorted()
        for (providerName in orderedTokenProviders) {
            val hasRow = output.values.any { it.provider == providerName }
            if (hasRow) continue
            val fallbackId = defaultTokenModelId(providerName) ?: continue
            output[fallbackId] = ModelRow(
                id = fallbackId,
                name = humanizeModelName(fallbackId),
                provider = providerName,
                mode = AuthMode.TOKEN,
                baseUrl = inferBaseUrlForProvider(providerName),
                apiKeyPresent = false,
            )
        }

        val providerNames = providers.keys().asSequence().toList().sorted()
        for (providerName in providerNames) {
            val providerObj = providers.optJSONObject(providerName) ?: continue
            val apiKey = normalizeApiKey(providerObj.optString("apiKey", ""))
            val baseUrl = providerObj.optString("baseUrl", "").trim().ifEmpty { inferBaseUrlForProvider(providerName) }
            val mode = when {
                apiKey.isNotEmpty() -> AuthMode.API
                tokenProvidersSet.contains(providerName) -> AuthMode.TOKEN
                else -> null
            } ?: continue

            val modelIds = parseProviderModelIds(providerObj)
            for (rawModelId in modelIds) {
                val modelId = normalizeModelId(providerName, rawModelId)
                if (modelId.isEmpty()) continue
                upsertRow(
                    ModelRow(
                        id = modelId,
                        name = humanizeModelName(modelId),
                        provider = providerName,
                        mode = mode,
                        baseUrl = baseUrl,
                        apiKeyPresent = apiKey.isNotEmpty(),
                    ),
                )
            }
        }

        val overrides = readAgentModelOverrides()
        for ((providerName, ids) in overrides) {
            val providerObj = providers.optJSONObject(providerName)
            val apiKey = providerObj?.optString("apiKey", "")?.let { normalizeApiKey(it) }.orEmpty()
            val baseUrl = providerObj?.optString("baseUrl", "")?.trim().orEmpty().ifEmpty {
                inferBaseUrlForProvider(providerName)
            }
            val mode = when {
                apiKey.isNotEmpty() -> AuthMode.API
                tokenProvidersSet.contains(providerName) -> AuthMode.TOKEN
                else -> null
            } ?: continue

            for (rawId in ids) {
                val modelId = normalizeModelId(providerName, rawId)
                if (modelId.isEmpty()) continue
                upsertRow(
                    ModelRow(
                        id = modelId,
                        name = humanizeModelName(modelId),
                        provider = providerName,
                        mode = mode,
                        baseUrl = baseUrl,
                        apiKeyPresent = apiKey.isNotEmpty(),
                    ),
                )
            }
        }

        if (currentModel.isNotEmpty()) {
            val providerName = inferProviderName(currentModel)
            val providerObj = if (providerName.isNotEmpty()) providers.optJSONObject(providerName) else null
            val apiKey = providerObj?.optString("apiKey", "")?.let { normalizeApiKey(it) }.orEmpty()
            val mode = when {
                apiKey.isNotEmpty() -> AuthMode.API
                providerName.isNotEmpty() && tokenProvidersSet.contains(providerName) -> AuthMode.TOKEN
                else -> AuthMode.TOKEN
            }
            val normalized = if (providerName.isNotEmpty()) normalizeModelId(providerName, currentModel) else currentModel
            if (normalized.isNotEmpty() && !output.containsKey(normalized)) {
                output[normalized] = ModelRow(
                    id = normalized,
                    name = humanizeModelName(normalized),
                    provider = providerName.ifEmpty { "openai-codex" },
                    mode = mode,
                    baseUrl = providerObj?.optString("baseUrl", "")?.trim().orEmpty(),
                    apiKeyPresent = apiKey.isNotEmpty(),
                )
            }
        }

        return output.values
            .sortedWith(
                compareBy<ModelRow> {
                    if (idsEquivalent(it.id, currentModel)) 0 else 1
                }.thenBy { it.name.lowercase(Locale.getDefault()) },
            )
    }

    private fun parseProviderModelIds(provider: JSONObject): List<String> {
        val modelsArray = provider.optJSONArray("models") ?: return emptyList()
        val ids = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (index in 0 until modelsArray.length()) {
            val rawItem = modelsArray.opt(index)
            val modelId = when (rawItem) {
                is String -> rawItem.trim()
                is JSONObject -> rawItem.optString("id", "").trim()
                    .ifEmpty { rawItem.optString("model", "").trim() }
                    .ifEmpty { rawItem.optString("name", "").trim() }
                else -> ""
            }
            if (modelId.isNotEmpty() && seen.add(modelId)) {
                ids += modelId
            }
        }
        return ids
    }

    private fun normalizeModelId(providerName: String, rawModelId: String): String {
        val modelId = rawModelId.trim()
        if (modelId.isEmpty()) return ""
        return if (modelId.contains("/")) modelId else "$providerName/$modelId"
    }

    private fun inferProviderName(modelId: String): String {
        val trimmed = modelId.trim()
        if (trimmed.contains("/")) return trimmed.substringBefore('/').trim()
        if (trimmed.startsWith("gpt-", ignoreCase = true)) return "openai-codex"
        return ""
    }

    private fun humanizeModelName(modelId: String): String {
        val id = modelId.trim()
        if (id.isEmpty()) return modelId
        return id.substringAfter('/').ifEmpty { id }
    }

    private fun idsEquivalent(left: String, right: String): Boolean {
        val a = left.trim()
        val b = right.trim()
        if (a == b) return true
        if (a.contains("/") && !b.contains("/")) return a.substringAfter('/') == b
        if (!a.contains("/") && b.contains("/")) return b.substringAfter('/') == a
        return false
    }

    private fun resolveCurrentModelMode(root: JSONObject, currentModel: String, tokenProviderSet: Set<String>): AuthMode? {
        val providerName = inferProviderName(currentModel)
        if (providerName.isEmpty()) return null
        val providers = root
            .optJSONObject("models")
            ?.optJSONObject("providers")
            ?: JSONObject()
        val providerObj = providers.optJSONObject(providerName)
        val apiKey = providerObj?.optString("apiKey", "")?.let { normalizeApiKey(it) }.orEmpty()
        return when {
            apiKey.isNotEmpty() -> AuthMode.API
            tokenProviderSet.contains(providerName) -> AuthMode.TOKEN
            else -> null
        }
    }

    private fun resolveCurrentModelRow(): ModelRow? {
        val byExact = rows.firstOrNull { idsEquivalent(it.id, currentModelId) }
        if (byExact != null) return byExact
        val currentProvider = inferProviderName(currentModelId)
        if (currentProvider.isNotEmpty()) {
            return rows.firstOrNull { it.provider == currentProvider }
        }
        return null
    }

    private fun modeLabel(mode: AuthMode?): String {
        return when (mode) {
            AuthMode.TOKEN -> getString(R.string.model_manager_mode_token)
            AuthMode.API -> getString(R.string.model_manager_mode_api)
            null -> getString(R.string.model_manager_mode_unknown)
        }
    }

    private fun renderModelList() {
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size

            override fun getItem(position: Int): Any = rows[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val rowView = convertView ?: LayoutInflater.from(this@ModelManagerActivity)
                    .inflate(R.layout.item_model_row, parent, false)
                val row = rows[position]
                val selected = idsEquivalent(row.id, currentModelId)

                val titlePrefix = if (selected) "✓ " else ""
                rowView.findViewById<TextView>(R.id.tvModelRowTitle).text = "$titlePrefix${row.name}"
                rowView.findViewById<TextView>(R.id.tvModelRowMeta).text =
                    "${row.id}\nprovider=${row.provider} | ${modeLabel(row.mode)}"

                val btnSelect = rowView.findViewById<Button>(R.id.btnModelRowSelect)
                val btnConfig = rowView.findViewById<Button>(R.id.btnModelRowConfig)
                val btnFetch = rowView.findViewById<Button>(R.id.btnModelRowFetch)
                val btnDelete = rowView.findViewById<Button>(R.id.btnModelRowDelete)

                btnSelect.text = if (selected) {
                    getString(R.string.model_manager_row_selected)
                } else {
                    getString(R.string.model_manager_row_select)
                }
                btnSelect.setOnClickListener {
                    if (selected) {
                        Toast.makeText(this@ModelManagerActivity, getString(R.string.model_manager_row_selected), Toast.LENGTH_SHORT).show()
                    } else {
                        setPrimaryModel(row)
                    }
                }

                btnFetch.setOnClickListener { fetchModelsForRow(row) }

                if (row.mode == AuthMode.API) {
                    btnConfig.visibility = View.VISIBLE
                    btnDelete.visibility = View.VISIBLE
                    btnConfig.setOnClickListener { openProviderConfigDialog(row) }
                    btnDelete.setOnClickListener { confirmDeleteModel(row) }
                    rowView.setOnLongClickListener {
                        openProviderConfigDialog(row)
                        true
                    }
                } else {
                    btnConfig.visibility = View.GONE
                    btnDelete.visibility = View.GONE
                    rowView.setOnLongClickListener {
                        setPrimaryModel(row)
                        true
                    }
                }
                return rowView
            }
        }

        val mode = currentModelMode ?: resolveCurrentModelRow()?.mode
        val currentText = currentModelId.ifEmpty { getString(R.string.status_na) }
        tvCurrentModel.text = getString(R.string.model_manager_current_prefix) + "$currentText（${modeLabel(mode)}）"
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = if (rows.isEmpty()) {
            getString(R.string.model_manager_empty)
        } else {
            "${rows.size} ${getString(R.string.model_manager_title)}"
        }
    }

    private fun setPrimaryModel(row: ModelRow) {
        Thread {
            try {
                val root = readLatestConfigRoot()
                val agents = ensureObject(root, "agents")
                val defaults = ensureObject(agents, "defaults")
                val model = ensureObject(defaults, "model")
                model.put("primary", row.id)

                if (row.mode == AuthMode.API) {
                    val models = ensureObject(root, "models")
                    val providers = ensureObject(models, "providers")
                    val provider = ensureObject(providers, row.provider)
                    if (!provider.has("baseUrl")) {
                        provider.put("baseUrl", inferBaseUrlForProvider(row.provider))
                    }
                    if (!provider.has("auth")) {
                        provider.put("auth", "api-key")
                    }
                    provider.put(
                        "api",
                        resolveProviderApi(
                            row.provider,
                            provider.optString("api", "").trim(),
                        ),
                    )
                    ensureProviderModel(provider, row.id)
                }

                ensureAgentModelOverride(row.provider, row.id)
                applyConfig(root, "set primary model: ${row.id}")
                runCatching {
                    callGatewayWithRetry(
                        "sessions.patch",
                        JSONObject()
                            .put("key", "agent:main:main")
                            .put("model", row.id),
                        attempts = 6,
                        baseDelayMs = 200,
                    )
                }

                currentModelId = row.id
                currentModelMode = row.mode
                runOnUiThread {
                    renderModelList()
                    Toast.makeText(this, getString(R.string.model_manager_select_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.model_manager_save_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun openProviderConfigDialog(row: ModelRow) {
        if (row.mode != AuthMode.API) return

        val providerConfig = readProviderConfigFromLocalFile(row.provider)

        val baseUrlInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.model_manager_config_url_hint)
            setText(providerConfig?.optString("baseUrl", "").orEmpty())
        }
        val apiKeyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.model_manager_config_key_hint)
            setText(providerConfig?.optString("apiKey", "").orEmpty())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(baseUrlInput)
            addView(apiKeyInput)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.model_manager_config_title))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_save_text)) { _, _ ->
                val baseUrl = baseUrlInput.text.toString().trim()
                val apiKey = apiKeyInput.text.toString().trim()
                saveProviderConfig(row, baseUrl, apiKey)
            }
            .show()
    }

    private fun openCreateModelDialog() {
        val modelIdInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.model_manager_config_model_id_hint)
            setText("")
        }
        val baseUrlInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.model_manager_config_url_hint)
            setText("")
        }
        val apiKeyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.model_manager_config_key_hint)
            setText("")
        }
        val setPrimary = CheckBox(this).apply {
            text = getString(R.string.model_manager_set_primary)
            isChecked = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(modelIdInput)
            addView(baseUrlInput)
            addView(apiKeyInput)
            addView(setPrimary)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.model_manager_create_button))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_save_text)) { _, _ ->
                val modelId = modelIdInput.text.toString().trim()
                val baseUrl = baseUrlInput.text.toString().trim()
                val apiKey = apiKeyInput.text.toString().trim()
                saveCreatedApiModel(modelId, baseUrl, apiKey, setPrimary.isChecked)
            }
            .show()
    }

    private fun saveCreatedApiModel(
        modelId: String,
        baseUrl: String,
        apiKey: String,
        setAsPrimary: Boolean,
    ) {
        Thread {
            try {
                if (modelId.isBlank() || !modelId.contains('/')) {
                    throw IllegalArgumentException(getString(R.string.model_manager_config_model_id_hint))
                }
                val normalizedApiKey = normalizeApiKey(apiKey)
                if (normalizedApiKey.isBlank()) {
                    throw IllegalArgumentException(getString(R.string.model_manager_config_key_hint))
                }

                val providerName = modelId.substringBefore('/').trim()
                if (providerName.isBlank()) {
                    throw IllegalArgumentException(getString(R.string.model_manager_config_model_id_hint))
                }

                val root = readLatestConfigRoot()
                val models = ensureObject(root, "models")
                val providers = ensureObject(models, "providers")
                val provider = ensureObject(providers, providerName)

                val normalizedBaseUrl = normalizeProviderBaseUrl(providerName, baseUrl)
                provider.put(
                    "baseUrl",
                    if (normalizedBaseUrl.isBlank()) inferBaseUrlForProvider(providerName) else normalizedBaseUrl,
                )
                provider.put("apiKey", normalizedApiKey)
                if (!provider.has("auth")) {
                    provider.put("auth", "api-key")
                }
                provider.put(
                    "api",
                    resolveProviderApi(
                        providerName,
                        provider.optString("api", "").trim(),
                    ),
                )

                ensureProviderModel(provider, modelId)
                ensureAgentModelOverride(providerName, modelId)

                if (setAsPrimary) {
                    val agents = ensureObject(root, "agents")
                    val defaults = ensureObject(agents, "defaults")
                    val model = ensureObject(defaults, "model")
                    model.put("primary", modelId)
                }

                applyConfig(root, "create model: $modelId")
                if (setAsPrimary) {
                    runCatching {
                        callGatewayWithRetry(
                            "sessions.patch",
                            JSONObject()
                                .put("key", "agent:main:main")
                                .put("model", modelId),
                            attempts = 6,
                            baseDelayMs = 200,
                        )
                    }
                }

                runOnUiThread {
                    Toast.makeText(this, getString(R.string.model_manager_created_toast), Toast.LENGTH_SHORT).show()
                    loadModelsAndConfig()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.model_manager_save_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun saveProviderConfig(row: ModelRow, baseUrl: String, apiKey: String) {
        Thread {
            try {
                val root = readLatestConfigRoot()
                val models = ensureObject(root, "models")
                val providers = ensureObject(models, "providers")
                val provider = ensureObject(providers, row.provider)
                val normalizedApiKey = normalizeApiKey(apiKey)

                val normalizedBaseUrl = normalizeProviderBaseUrl(row.provider, baseUrl)
                if (normalizedBaseUrl.isEmpty()) {
                    provider.remove("baseUrl")
                } else {
                    provider.put("baseUrl", normalizedBaseUrl)
                }
                if (normalizedApiKey.isEmpty()) {
                    provider.remove("apiKey")
                } else {
                    provider.put("apiKey", normalizedApiKey)
                }
                if (!provider.has("auth")) {
                    provider.put("auth", "api-key")
                }
                provider.put(
                    "api",
                    resolveProviderApi(
                        row.provider,
                        provider.optString("api", "").trim(),
                    ),
                )

                ensureProviderModel(provider, row.id)
                ensureAgentModelOverride(row.provider, row.id)

                applyConfig(root, "update provider config: ${row.provider}")
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.model_manager_config_saved), Toast.LENGTH_SHORT).show()
                    loadModelsAndConfig()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.model_manager_save_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun testCurrentModelConnection() {
        Thread {
            try {
                val root = readLatestConfigRoot()
                val latestModelId = readPrimaryModelId(root).ifEmpty { currentModelId }
                val latestProviderName = inferProviderName(latestModelId)
                var debugInfo = ""
                val namedProvider = resolveNamedApiProvider(root, latestProviderName)
                val probe = if (namedProvider != null) {
                    val baseUrl = normalizeProviderBaseUrl(
                        namedProvider.name,
                        namedProvider.config.optString("baseUrl", "").trim(),
                    )
                        .ifEmpty { inferBaseUrlForProvider(namedProvider.name) }
                    val apiKey = normalizeApiKey(namedProvider.config.optString("apiKey", ""))
                    if (apiKey.isEmpty()) {
                        throw IllegalStateException(getString(R.string.model_manager_provider_missing))
                    }
                    debugInfo = " provider=${namedProvider.name} keyTail=${apiKeyTail(apiKey)}"
                    runConnectionProbe(baseUrl, apiKey)
                } else {
                    val tokenModelId = latestModelId.ifEmpty { resolveCurrentModelRow()?.id.orEmpty() }
                    if (tokenModelId.isEmpty()) {
                        throw IllegalStateException(getString(R.string.model_manager_provider_missing))
                    }
                    debugInfo = " provider=${latestProviderName.ifEmpty { "token" }} mode=token"
                    runTokenProbe(tokenModelId)
                }

                runOnUiThread {
                    val prefix = if (probe.ok) {
                        getString(R.string.model_manager_connection_success)
                    } else {
                        getString(R.string.model_manager_connection_failed)
                    }
                    Toast.makeText(
                        this,
                        "$prefix$debugInfo HTTP ${probe.code} ${probe.detail}",
                        if (probe.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.model_manager_connection_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun fetchCurrentModelList() {
        Thread {
            try {
                val root = readLatestConfigRoot()
                val latestModelId = readPrimaryModelId(root).ifEmpty { currentModelId }
                val latestProviderName = inferProviderName(latestModelId)
                val namedProvider = resolveNamedApiProvider(root, latestProviderName)
                if (namedProvider != null) {
                    fetchModelsFromApiProvider(namedProvider.name)
                } else {
                    val providerName = latestProviderName.ifEmpty { resolveCurrentModelRow()?.provider.orEmpty() }
                    if (providerName.isEmpty()) {
                        throw IllegalStateException(getString(R.string.model_manager_provider_missing))
                    }
                    fetchModelsFromTokenProvider(providerName)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.model_manager_save_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun fetchModelsForRow(row: ModelRow) {
        Thread {
            try {
                if (row.mode == AuthMode.API) {
                    fetchModelsFromApiProvider(row.provider)
                } else {
                    fetchModelsFromTokenProvider(row.provider)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.model_manager_save_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun fetchModelsFromApiProvider(providerName: String) {
        val root = readLatestConfigRoot()
        val provider = resolveNamedApiProvider(root, providerName)
            ?: throw IllegalStateException(getString(R.string.model_manager_provider_missing))

        val baseUrl = normalizeProviderBaseUrl(
            provider.name,
            provider.config.optString("baseUrl", "").trim(),
        )
            .ifEmpty { inferBaseUrlForProvider(provider.name) }
        val apiKey = normalizeApiKey(provider.config.optString("apiKey", ""))
        if (apiKey.isEmpty()) {
            throw IllegalStateException(getString(R.string.model_manager_provider_missing))
        }

        val modelIds = fetchProviderModelIds(baseUrl, apiKey)
        if (modelIds.isEmpty()) {
            throw IllegalStateException(getString(R.string.model_manager_fetch_empty))
        }

        var added = 0
        for (id in modelIds.take(200)) {
            val normalized = normalizeModelId(provider.name, id)
            if (normalized.isEmpty()) continue
            if (ensureProviderModel(provider.config, normalized)) {
                added += 1
            }
            ensureAgentModelOverride(provider.name, normalized)
        }

        applyConfig(root, "fetch models from provider: ${provider.name}")
        runOnUiThread {
            Toast.makeText(
                this,
                "${getString(R.string.model_manager_fetch_models)} +$added",
                Toast.LENGTH_SHORT,
            ).show()
            loadModelsAndConfig()
        }
    }

    private fun fetchModelsFromTokenProvider(providerName: String) {
        val payload = callGatewayWithRetry("models.list", JSONObject(), attempts = 6, baseDelayMs = 200)
        val models = payload.optJSONArray("models") ?: JSONArray()

        val collected = linkedSetOf<String>()
        for (index in 0 until models.length()) {
            val item = models.optJSONObject(index) ?: continue
            val provider = item.optString("provider", "").trim()
            if (provider != providerName) continue
            val id = item.optString("id", "").trim()
            if (id.isEmpty()) continue
            collected += normalizeModelId(providerName, id)
        }

        if (collected.isEmpty()) {
            throw IllegalStateException(getString(R.string.model_manager_fetch_empty))
        }

        var added = 0
        for (modelId in collected) {
            if (ensureAgentModelOverride(providerName, modelId)) {
                added += 1
            }
        }

        runOnUiThread {
            Toast.makeText(
                this,
                "${getString(R.string.model_manager_fetch_models)} +$added",
                Toast.LENGTH_SHORT,
            ).show()
            loadModelsAndConfig()
        }
    }

    private fun runTokenProbe(modelId: String): ProviderConnectionProbe {
        val sessionKey = "agent:main:model-probe-${System.currentTimeMillis()}"
        val idempotency = "model-probe-${System.currentTimeMillis()}"
        return try {
            callGatewayWithRetry(
                "sessions.patch",
                JSONObject()
                    .put("key", sessionKey)
                    .put("model", modelId),
                attempts = 6,
                baseDelayMs = 200,
            )

            val sendPayload = callGatewayWithRetry(
                "chat.send",
                JSONObject()
                    .put("sessionKey", sessionKey)
                    .put("message", "ping")
                    .put("deliver", false)
                    .put("timeoutMs", 12000)
                    .put("idempotencyKey", idempotency),
                attempts = 6,
                baseDelayMs = 200,
            )
            val runId = sendPayload.optString("runId", "").trim()
            if (runId.isEmpty()) {
                return ProviderConnectionProbe(false, 502, "chat.send missing runId")
            }

            val waitPayload = callGatewayWithRetry(
                "agent.wait",
                JSONObject()
                    .put("runId", runId)
                    .put("timeoutMs", 25000),
                attempts = 4,
                baseDelayMs = 250,
            )
            val status = waitPayload.optString("status", "").trim().lowercase(Locale.getDefault())
            val ok = status == "ok" || status == "completed"
            ProviderConnectionProbe(ok, if (ok) 200 else 502, "agent.wait=$status")
        } finally {
            runCatching {
                callGatewayWithRetry(
                    "sessions.delete",
                    JSONObject()
                        .put("key", sessionKey)
                        .put("deleteTranscript", true)
                        .put("emitLifecycleHooks", false),
                    attempts = 3,
                    baseDelayMs = 150,
                )
            }
        }
    }

    private fun confirmDeleteModel(row: ModelRow) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.model_manager_delete_confirm_title))
            .setMessage(getString(R.string.model_manager_delete_confirm_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_delete)) { _, _ ->
                deleteApiModel(row)
            }
            .show()
    }

    private fun deleteApiModel(row: ModelRow) {
        if (row.mode != AuthMode.API) return
        Thread {
            try {
                val root = readLatestConfigRoot()
                val providers = root
                    .optJSONObject("models")
                    ?.optJSONObject("providers")
                    ?: throw IllegalStateException(getString(R.string.model_manager_provider_missing))
                val provider = providers.optJSONObject(row.provider)
                    ?: throw IllegalStateException(getString(R.string.model_manager_provider_missing))

                val removed = removeProviderModel(provider, row.id)
                if (!removed) {
                    throw IllegalStateException(getString(R.string.model_manager_delete_failed))
                }
                removeAgentModelOverride(row.provider, row.id)

                if (idsEquivalent(row.id, currentModelId)) {
                    val replacement = rows.firstOrNull {
                        !idsEquivalent(it.id, row.id)
                    } ?: throw IllegalStateException(getString(R.string.model_manager_cannot_delete_last))

                    val agents = ensureObject(root, "agents")
                    val defaults = ensureObject(agents, "defaults")
                    val model = ensureObject(defaults, "model")
                    model.put("primary", replacement.id)
                    runCatching {
                        callGatewayWithRetry(
                            "sessions.patch",
                            JSONObject()
                                .put("key", "agent:main:main")
                                .put("model", replacement.id),
                            attempts = 6,
                            baseDelayMs = 200,
                        )
                    }
                }

                applyConfig(root, "delete model: ${row.id}")
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.model_manager_delete_done), Toast.LENGTH_SHORT).show()
                    loadModelsAndConfig()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.model_manager_delete_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun removeProviderModel(provider: JSONObject, modelId: String): Boolean {
        val modelsArray = provider.optJSONArray("models") ?: return false
        val kept = JSONArray()
        var removed = false
        for (index in 0 until modelsArray.length()) {
            val item = modelsArray.opt(index)
            val existingId = when (item) {
                is String -> item.trim()
                is JSONObject -> item.optString("id", "").trim()
                    .ifEmpty { item.optString("model", "").trim() }
                    .ifEmpty { item.optString("name", "").trim() }
                else -> ""
            }
            if (idsEquivalent(existingId, modelId)) {
                removed = true
                continue
            }
            kept.put(item)
        }
        provider.put("models", kept)
        return removed
    }

    private fun runConnectionProbe(baseUrl: String, apiKey: String): ProviderConnectionProbe {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val url = URL("$normalizedBase/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val code = conn.responseCode
        val body = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
        val detail = body.replace('\n', ' ').replace('\r', ' ').take(120)
        return ProviderConnectionProbe(code in 200..299, code, detail)
    }

    private fun fetchProviderModelIds(baseUrl: String, apiKey: String): List<String> {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val url = URL("$normalizedBase/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val code = conn.responseCode
        val body = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code ${body.take(160)}")
        }

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val ids = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val modelId = item.optString("id", "").trim()
            if (modelId.isNotEmpty() && seen.add(modelId)) {
                ids += modelId
            }
        }
        return ids
    }

    private fun ensureProviderModel(provider: JSONObject, modelId: String): Boolean {
        if (modelId.isBlank()) return false
        val modelsArray = if (provider.has("models") && provider.optJSONArray("models") != null) {
            provider.getJSONArray("models")
        } else {
            JSONArray().also { provider.put("models", it) }
        }
        for (index in 0 until modelsArray.length()) {
            val existing = modelsArray.opt(index)
            val existingId = when (existing) {
                is String -> existing.trim()
                is JSONObject -> existing.optString("id", "").trim()
                    .ifEmpty { existing.optString("model", "").trim() }
                else -> ""
            }
            if (idsEquivalent(existingId, modelId)) {
                return false
            }
        }
        modelsArray.put(
            JSONObject()
                .put("id", modelId)
                .put("name", humanizeModelName(modelId)),
        )
        return true
    }

    private fun readTokenProviders(): Set<String> {
        val result = linkedSetOf<String>()

        fun collectFrom(path: File) {
            if (!path.exists()) return
            val root = runCatching { JSONObject(path.readText()) }.getOrNull() ?: return
            val profiles = root.optJSONObject("profiles") ?: return
            val keys = profiles.keys().asSequence().toList()
            for (key in keys) {
                val profile = profiles.optJSONObject(key) ?: continue
                if (!profile.optString("type", "").contains("token", ignoreCase = true)) continue
                val provider = profile.optString("provider", "").trim()
                if (provider.isNotEmpty()) {
                    result += provider
                }
            }
        }

        val homeDir = BootstrapInstaller.getPaths(this).homeDir
        collectFrom(File(homeDir, ".openclaw/auth-profiles.json"))
        collectFrom(File(homeDir, ".openclaw/agents/main/agent/auth-profiles.json"))

        if (result.isEmpty() && File(homeDir, ".codex/auth.json").exists()) {
            result += "openai-codex"
        }

        return result
    }

    private fun readAgentModelOverrides(): Map<String, Set<String>> {
        val file = File(BootstrapInstaller.getPaths(this).homeDir, ".openclaw/agents/main/agent/models.json")
        if (!file.exists()) return emptyMap()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        val providers = root.optJSONObject("providers") ?: return emptyMap()
        val names = providers.keys().asSequence().toList()
        val out = linkedMapOf<String, Set<String>>()
        for (name in names) {
            val provider = providers.optJSONObject(name) ?: continue
            val overrides = provider.optJSONObject("modelOverrides") ?: continue
            val ids = overrides.keys().asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (ids.isNotEmpty()) {
                out[name] = ids
            }
        }
        return out
    }

    private fun ensureAgentModelOverride(providerName: String, modelId: String): Boolean {
        if (providerName.isBlank() || modelId.isBlank()) return false
        val file = File(BootstrapInstaller.getPaths(this).homeDir, ".openclaw/agents/main/agent/models.json")
        file.parentFile?.mkdirs()

        val root = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }

        val providers = ensureObject(root, "providers")
        val provider = ensureObject(providers, providerName)
        val overrides = ensureObject(provider, "modelOverrides")
        if (overrides.has(modelId)) {
            return false
        }

        overrides.put(
            modelId,
            JSONObject().put("name", humanizeModelName(modelId)),
        )
        file.writeText(root.toString(2))
        return true
    }

    private fun removeAgentModelOverride(providerName: String, modelId: String): Boolean {
        val file = File(BootstrapInstaller.getPaths(this).homeDir, ".openclaw/agents/main/agent/models.json")
        if (!file.exists()) return false
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return false
        val providers = root.optJSONObject("providers") ?: return false
        val provider = providers.optJSONObject(providerName) ?: return false
        val overrides = provider.optJSONObject("modelOverrides") ?: return false

        var removed = false
        val keys = overrides.keys().asSequence().toList()
        for (key in keys) {
            if (idsEquivalent(key, modelId)) {
                overrides.remove(key)
                removed = true
            }
        }

        if (removed) {
            file.writeText(root.toString(2))
        }
        return removed
    }

    private fun resolveApiProvider(root: JSONObject, preferredProvider: String): ProviderEntry? {
        val providers = root
            .optJSONObject("models")
            ?.optJSONObject("providers")
            ?: return null

        if (preferredProvider.isNotBlank()) {
            val preferred = providers.optJSONObject(preferredProvider)
            val apiKey = preferred?.optString("apiKey", "")?.let { normalizeApiKey(it) }.orEmpty()
            if (preferred != null && apiKey.isNotEmpty()) {
                return ProviderEntry(preferredProvider, preferred)
            }
        }

        val names = providers.keys().asSequence().toList().sorted()
        for (name in names) {
            val item = providers.optJSONObject(name) ?: continue
            val hasKey = normalizeApiKey(item.optString("apiKey", "")).isNotEmpty()
            if (hasKey) {
                return ProviderEntry(name, item)
            }
        }
        return null
    }

    private fun resolveNamedApiProvider(root: JSONObject, providerName: String): ProviderEntry? {
        if (providerName.isBlank()) return null
        val providers = root
            .optJSONObject("models")
            ?.optJSONObject("providers")
            ?: return null
        val provider = providers.optJSONObject(providerName) ?: return null
        val apiKey = normalizeApiKey(provider.optString("apiKey", ""))
        if (apiKey.isEmpty()) return null
        return ProviderEntry(providerName, provider)
    }

    private fun defaultTokenModelId(providerName: String): String? {
        return when {
            providerName.equals("openai-codex", ignoreCase = true) -> "openai-codex/gpt-5.3-codex"
            else -> null
        }
    }

    private fun readPrimaryModelId(root: JSONObject): String {
        return root
            .optJSONObject("agents")
            ?.optJSONObject("defaults")
            ?.optJSONObject("model")
            ?.optString("primary", "")
            .orEmpty()
            .trim()
    }

    private fun inferApiForProvider(providerName: String): String {
        return when {
            providerName.contains("deepseek", ignoreCase = true) -> "openai-completions"
            providerName.contains("openai", ignoreCase = true) -> "openai-responses"
            providerName.contains("codex", ignoreCase = true) -> "openai-codex-responses"
            providerName.contains("anthropic", ignoreCase = true) -> "anthropic-messages"
            providerName.contains("ollama", ignoreCase = true) -> "ollama"
            else -> "openai-responses"
        }
    }

    private fun resolveProviderApi(providerName: String, currentApi: String): String {
        if (providerName.contains("deepseek", ignoreCase = true)) {
            // DeepSeek OpenAI-compatible endpoints use chat.completions, not responses.
            return "openai-completions"
        }
        if (currentApi.isNotBlank()) {
            return currentApi
        }
        return inferApiForProvider(providerName)
    }

    private fun inferBaseUrlForProvider(providerName: String): String {
        return when {
            providerName.contains("openrouter", ignoreCase = true) -> "https://openrouter.ai/api/v1"
            providerName.contains("anthropic", ignoreCase = true) -> "https://api.anthropic.com/v1"
            providerName.contains("ollama", ignoreCase = true) -> "http://127.0.0.1:11434/v1"
            providerName.contains("deepseek", ignoreCase = true) -> "https://api.deepseek.com/v1"
            else -> "https://api.openai.com/v1"
        }
    }

    private fun normalizeProviderBaseUrl(providerName: String, rawBaseUrl: String): String {
        var normalized = rawBaseUrl.trim()
        if (normalized.isEmpty()) return normalized

        val lower = normalized.lowercase(Locale.getDefault())
        when {
            lower.endsWith("/chat/completions") -> {
                normalized = normalized.dropLast("/chat/completions".length)
            }
            lower.endsWith("/responses") -> {
                normalized = normalized.dropLast("/responses".length)
            }
        }
        normalized = normalized.trimEnd('/')

        if (providerName.contains("deepseek", ignoreCase = true) &&
            !normalized.contains("/v1", ignoreCase = true)
        ) {
            normalized += "/v1"
        }
        return normalized
    }

    private fun normalizeApiKey(rawApiKey: String): String {
        val normalized = rawApiKey.filterNot { it.isWhitespace() }.trim()
        if (normalized.isEmpty()) return normalized
        if (normalized.equals(REDACTED_SENTINEL, ignoreCase = true)) return ""
        if (normalized.startsWith("__OPENCLAW_REDACTED", ignoreCase = true)) return ""
        return normalized
    }

    private fun apiKeyTail(apiKey: String): String {
        val normalized = normalizeApiKey(apiKey)
        if (normalized.length <= 4) return normalized
        return normalized.takeLast(4)
    }

    private fun readLatestConfigRoot(): JSONObject {
        val payload = runCatching {
            callGatewayWithRetry("config.get", JSONObject(), attempts = 8, baseDelayMs = 250)
        }.getOrNull()
        if (payload != null) {
            configRaw = payload.optString("raw", configRaw.ifBlank { "{}" })
            configBaseHash = payload.optString("hash", configBaseHash)
            return readWritableConfigRoot(payload)
        }

        val fallback = File(BootstrapInstaller.getPaths(this).homeDir, ".openclaw/openclaw.json")
        if (fallback.exists()) {
            val parsed = runCatching { JSONObject(fallback.readText()) }.getOrNull()
            if (parsed != null) {
                configRaw = parsed.toString(2)
                configBaseHash = ""
                return parsed
            }
        }
        val created = JSONObject(configRaw.ifBlank { "{}" })
        return created
    }

    private fun readWritableConfigRoot(payload: JSONObject): JSONObject {
        val fromFile = runCatching {
            val path = payload.optString("path", "").trim()
            if (path.isNotEmpty()) {
                val file = File(path)
                if (file.exists()) {
                    JSONObject(file.readText())
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
        if (fromFile != null) return fromFile

        val fallback = File(BootstrapInstaller.getPaths(this).homeDir, ".openclaw/openclaw.json")
        if (fallback.exists()) {
            runCatching { JSONObject(fallback.readText()) }.getOrNull()?.let { return it }
        }

        return JSONObject(configRaw.ifBlank { "{}" })
    }

    private fun readProviderConfigFromLocalFile(providerName: String): JSONObject? {
        val root = runCatching {
            val configFile = File(BootstrapInstaller.getPaths(this).homeDir, ".openclaw/openclaw.json")
            if (!configFile.exists()) return@runCatching null
            JSONObject(configFile.readText())
        }.getOrNull() ?: return null
        return root
            .optJSONObject("models")
            ?.optJSONObject("providers")
            ?.optJSONObject(providerName)
    }

    private fun applyConfig(root: JSONObject, note: String) {
        val params = JSONObject()
            .put("raw", root.toString(2))
            .put("note", note)
        if (configBaseHash.isNotEmpty()) {
            params.put("baseHash", configBaseHash)
        }
        val applyResult = runCatching {
            callGatewayWithRetry("config.apply", params, attempts = 4, baseDelayMs = 200)
        }.getOrNull()
        if (applyResult != null) {
            configRaw = root.toString(2)
            configBaseHash = applyResult.optString("hash", configBaseHash)
            return
        }

        val fallback = File(BootstrapInstaller.getPaths(this).homeDir, ".openclaw/openclaw.json")
        fallback.parentFile?.mkdirs()
        fallback.writeText(root.toString(2))
        configRaw = root.toString(2)
        configBaseHash = ""
    }

    private fun callGatewayWithRetry(
        method: String,
        params: JSONObject,
        attempts: Int = 3,
        baseDelayMs: Long = 200L,
    ): JSONObject {
        var lastError: Exception? = null
        repeat(attempts.coerceAtLeast(1)) { index ->
            try {
                return gateway.call(method, params)
            } catch (error: Exception) {
                lastError = error
                val message = (error.message ?: "").lowercase(Locale.getDefault())
                val retryable = message.contains("gateway closed") ||
                    message.contains("abnormal closure") ||
                    message.contains("gateway timeout") ||
                    message.contains("connection refused")
                if (!retryable || index == attempts - 1) {
                    throw error
                }
                Thread.sleep(baseDelayMs * (index + 1))
            }
        }
        throw lastError ?: IllegalStateException("Gateway call failed: $method")
    }

    private fun ensureObject(parent: JSONObject, key: String): JSONObject {
        val existing = parent.optJSONObject(key)
        if (existing != null) return existing
        val created = JSONObject()
        parent.put(key, created)
        return created
    }
}
