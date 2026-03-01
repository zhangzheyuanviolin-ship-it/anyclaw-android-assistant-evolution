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
import org.json.JSONArray
import org.json.JSONObject

class ModelManagerActivity : AppCompatActivity() {

    private data class ModelRow(
        val id: String,
        val name: String,
        val provider: String,
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
    private lateinit var tvCurrentModel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView

    private val serverManager by lazy { CodexServerManager(this) }
    private val gateway by lazy { LocalBridgeClients.OpenClawGateway(serverManager) }

    private var loading = false
    private var rows = mutableListOf<ModelRow>()
    private var currentModelId = ""
    private var configRaw = "{}"
    private var configBaseHash = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)

        btnRefresh = findViewById(R.id.btnModelRefresh)
        btnCreate = findViewById(R.id.btnModelCreate)
        btnTestConnection = findViewById(R.id.btnModelTestConnection)
        btnFetchModels = findViewById(R.id.btnModelFetchModels)
        tvCurrentModel = findViewById(R.id.tvCurrentModel)
        progressBar = findViewById(R.id.progressModel)
        tvStatus = findViewById(R.id.tvModelStatus)
        listView = findViewById(R.id.listModels)

        btnRefresh.setOnClickListener { loadModelsAndConfig() }
        btnCreate.setOnClickListener { openCreateModelDialog() }
        btnTestConnection.setOnClickListener { testCurrentProviderConnection() }
        btnFetchModels.setOnClickListener { fetchModelsFromCurrentProvider() }
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
                val configPayload = gateway.call("config.get", JSONObject())
                configRaw = configPayload.optString("raw", "{}")
                configBaseHash = configPayload.optString("hash", "")
                currentModelId = extractCurrentModel(configPayload)
                val preferredByProvider = readAgentModelOverrides()
                rows = parseConfiguredModelRows(configRaw, currentModelId, preferredByProvider).toMutableList()

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

    private fun parseConfiguredModelRows(
        rawConfig: String,
        currentModel: String,
        preferredByProvider: Map<String, Set<String>>,
    ): List<ModelRow> {
        val output = mutableListOf<ModelRow>()
        val seen = mutableSetOf<String>()
        val root = runCatching { JSONObject(rawConfig) }.getOrElse { JSONObject() }
        val providers = root
            .optJSONObject("models")
            ?.optJSONObject("providers")
            ?: JSONObject()
        val providerNames = providers.keys().asSequence().toList().sorted()

        for (providerName in providerNames) {
            val providerObj = providers.optJSONObject(providerName) ?: continue
            val apiKey = providerObj.optString("apiKey", "").trim()
            if (apiKey.isEmpty()) continue

            val preferred = preferredByProvider[providerName].orEmpty()
            val fromConfig = parseProviderModelIds(providerObj)
            val selectedIds = mutableListOf<String>()

            if (preferred.isNotEmpty()) {
                selectedIds += preferred.sorted()
            } else {
                if (fromConfig.size in 1..80) {
                    selectedIds += fromConfig
                }
                if (currentModel.startsWith("$providerName/")) {
                    selectedIds += currentModel
                }
            }

            for (modelId in selectedIds) {
                val normalized = modelId.trim()
                if (normalized.isEmpty()) continue
                if (seen.add(normalized)) {
                    output += ModelRow(
                        id = normalized,
                        name = humanizeModelName(normalized),
                        provider = providerName,
                    )
                }
            }
        }

        if (currentModel.isNotEmpty() && seen.add(currentModel)) {
            val providerName = currentModel.substringBefore('/').trim()
            val providerObj = providers.optJSONObject(providerName)
            val hasApiKey = providerObj?.optString("apiKey", "")?.trim()?.isNotEmpty() == true
            if (hasApiKey) {
                output += ModelRow(
                    id = currentModel,
                    name = humanizeModelName(currentModel),
                    provider = providerName,
                )
            }
        }

        return output.sortedBy { it.name.lowercase() }
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

    private fun humanizeModelName(modelId: String): String {
        val id = modelId.trim()
        if (id.isEmpty()) return modelId
        return id.substringAfter('/').ifEmpty { id }
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
                val currentMark = if (row.id == currentModelId) "✓ " else ""

                rowView.findViewById<TextView>(R.id.tvModelRowTitle).text = "$currentMark${row.name}"
                rowView.findViewById<TextView>(R.id.tvModelRowMeta).text =
                    "${row.id}\nprovider=${row.provider} | key=configured"
                rowView.findViewById<Button>(R.id.btnModelRowPrimary).setOnClickListener {
                    setPrimaryModel(row)
                }
                rowView.findViewById<Button>(R.id.btnModelRowConfig).setOnClickListener {
                    openProviderConfigDialog(row.provider, row.id)
                }
                rowView.setOnLongClickListener {
                    openProviderConfigDialog(row.provider, row.id)
                    true
                }
                return rowView
            }
        }

        tvCurrentModel.text = getString(R.string.model_manager_current_prefix) + currentModelId.ifEmpty {
            getString(R.string.status_na)
        }
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
                val root = JSONObject(configRaw)
                val agents = ensureObject(root, "agents")
                val defaults = ensureObject(agents, "defaults")
                val model = ensureObject(defaults, "model")
                model.put("primary", row.id)

                ensureAgentModelOverride(row.provider, row.id)
                applyConfig(root, "set primary model: ${row.id}")
                runCatching {
                    gateway.call(
                        "sessions.patch",
                        JSONObject()
                            .put("key", "agent:main:main")
                            .put("model", row.id),
                    )
                }

                currentModelId = row.id
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

    private fun openProviderConfigDialog(providerName: String, modelId: String) {
        val providerConfig = try {
            val root = JSONObject(configRaw)
            root.optJSONObject("models")
                ?.optJSONObject("providers")
                ?.optJSONObject(providerName)
        } catch (_: Exception) {
            null
        }
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
                saveProviderConfig(providerName, modelId, baseUrl, apiKey)
            }
            .show()
    }

    private fun openCreateModelDialog() {
        val defaultProvider = currentModelId.substringBefore('/').trim().ifEmpty { "openai-codex" }
        val modelIdInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.model_manager_config_model_id_hint)
            setText(currentModelId.ifEmpty { "$defaultProvider/" })
        }
        val providerInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.model_manager_config_provider_hint)
            setText(defaultProvider)
        }
        val baseUrlInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.model_manager_config_url_hint)
            setText(inferBaseUrlForProvider(defaultProvider))
        }
        val apiKeyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.model_manager_config_key_hint)
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
            addView(providerInput)
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
                val providerName = providerInput.text.toString().trim()
                val baseUrl = baseUrlInput.text.toString().trim()
                val apiKey = apiKeyInput.text.toString().trim()
                saveCreatedModel(modelId, providerName, baseUrl, apiKey, setPrimary.isChecked)
            }
            .show()
    }

    private fun saveCreatedModel(
        modelId: String,
        providerName: String,
        baseUrl: String,
        apiKey: String,
        setAsPrimary: Boolean,
    ) {
        Thread {
            try {
                if (modelId.isBlank()) {
                    throw IllegalArgumentException(getString(R.string.model_manager_config_model_id_hint))
                }
                if (providerName.isBlank()) {
                    throw IllegalArgumentException(getString(R.string.model_manager_config_provider_hint))
                }
                if (apiKey.isBlank()) {
                    throw IllegalArgumentException(getString(R.string.model_manager_config_key_hint))
                }

                val root = JSONObject(configRaw)
                val models = ensureObject(root, "models")
                val providers = ensureObject(models, "providers")
                val provider = ensureObject(providers, providerName)

                provider.put("baseUrl", if (baseUrl.isBlank()) inferBaseUrlForProvider(providerName) else baseUrl)
                provider.put("apiKey", apiKey)
                if (!provider.has("auth")) {
                    provider.put("auth", "api-key")
                }
                if (!provider.has("api")) {
                    provider.put("api", inferApiForProvider(providerName))
                }
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
                        gateway.call(
                            "sessions.patch",
                            JSONObject()
                                .put("key", "agent:main:main")
                                .put("model", modelId),
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

    private fun saveProviderConfig(providerName: String, modelId: String, baseUrl: String, apiKey: String) {
        Thread {
            try {
                val root = JSONObject(configRaw)
                val models = ensureObject(root, "models")
                val providers = ensureObject(models, "providers")
                val provider = ensureObject(providers, providerName)

                if (baseUrl.isEmpty()) {
                    provider.remove("baseUrl")
                } else {
                    provider.put("baseUrl", baseUrl)
                }
                if (apiKey.isEmpty()) {
                    provider.remove("apiKey")
                } else {
                    provider.put("apiKey", apiKey)
                }
                if (!provider.has("auth")) {
                    provider.put("auth", "api-key")
                }
                if (!provider.has("api")) {
                    provider.put("api", inferApiForProvider(providerName))
                }
                if (modelId.isNotBlank()) {
                    ensureProviderModel(provider, modelId)
                    ensureAgentModelOverride(providerName, modelId)
                }

                applyConfig(root, "update provider config: $providerName")

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

    private fun testCurrentProviderConnection() {
        Thread {
            try {
                val root = JSONObject(configRaw)
                val provider = resolveCurrentProvider(root)
                    ?: throw IllegalStateException(getString(R.string.model_manager_provider_missing))
                val baseUrl = provider.config.optString("baseUrl", "").trim()
                    .ifEmpty { inferBaseUrlForProvider(provider.name) }
                val apiKey = provider.config.optString("apiKey", "").trim()
                if (apiKey.isEmpty()) {
                    throw IllegalStateException(getString(R.string.model_manager_provider_missing))
                }
                val probe = runConnectionProbe(baseUrl, apiKey)
                runOnUiThread {
                    val prefix = if (probe.ok) {
                        getString(R.string.model_manager_connection_success)
                    } else {
                        getString(R.string.model_manager_connection_failed)
                    }
                    Toast.makeText(
                        this,
                        "$prefix HTTP ${probe.code} ${probe.detail}",
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

    private fun fetchModelsFromCurrentProvider() {
        Thread {
            try {
                val root = JSONObject(configRaw)
                val provider = resolveCurrentProvider(root)
                    ?: throw IllegalStateException(getString(R.string.model_manager_provider_missing))
                val baseUrl = provider.config.optString("baseUrl", "").trim()
                    .ifEmpty { inferBaseUrlForProvider(provider.name) }
                val apiKey = provider.config.optString("apiKey", "").trim()
                if (apiKey.isEmpty()) {
                    throw IllegalStateException(getString(R.string.model_manager_provider_missing))
                }

                val modelIds = fetchProviderModelIds(baseUrl, apiKey)
                if (modelIds.isEmpty()) {
                    throw IllegalStateException(getString(R.string.model_manager_fetch_empty))
                }

                val limitedIds = modelIds.take(120)
                var added = 0
                for (modelId in limitedIds) {
                    if (ensureProviderModel(provider.config, modelId)) {
                        added += 1
                    }
                    ensureAgentModelOverride(provider.name, modelId)
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
            if (existingId == modelId) {
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

    private fun resolveCurrentProvider(root: JSONObject): ProviderEntry? {
        val providers = root
            .optJSONObject("models")
            ?.optJSONObject("providers")
            ?: return null

        val preferredName = currentModelId.substringBefore('/').trim()
        if (preferredName.isNotEmpty()) {
            val preferred = providers.optJSONObject(preferredName)
            val hasKey = preferred?.optString("apiKey", "")?.trim()?.isNotEmpty() == true
            if (preferred != null && hasKey) {
                return ProviderEntry(preferredName, preferred)
            }
        }

        val names = providers.keys().asSequence().toList().sorted()
        for (name in names) {
            val item = providers.optJSONObject(name) ?: continue
            val hasKey = item.optString("apiKey", "").trim().isNotEmpty()
            if (hasKey) {
                return ProviderEntry(name, item)
            }
        }
        return null
    }

    private fun inferApiForProvider(providerName: String): String {
        return when {
            providerName.contains("openai", ignoreCase = true) -> "openai-responses"
            providerName.contains("codex", ignoreCase = true) -> "openai-codex-responses"
            providerName.contains("anthropic", ignoreCase = true) -> "anthropic-messages"
            providerName.contains("ollama", ignoreCase = true) -> "ollama"
            else -> "openai-responses"
        }
    }

    private fun inferBaseUrlForProvider(providerName: String): String {
        return when {
            providerName.contains("openrouter", ignoreCase = true) -> "https://openrouter.ai/api/v1"
            providerName.contains("anthropic", ignoreCase = true) -> "https://api.anthropic.com/v1"
            providerName.contains("ollama", ignoreCase = true) -> "http://127.0.0.1:11434/v1"
            else -> "https://api.openai.com/v1"
        }
    }

    private fun applyConfig(root: JSONObject, note: String) {
        val params = JSONObject()
            .put("raw", root.toString(2))
            .put("note", note)
        if (configBaseHash.isNotEmpty()) {
            params.put("baseHash", configBaseHash)
        }
        val applyResult = gateway.call("config.apply", params)
        configRaw = root.toString(2)
        configBaseHash = applyResult.optString("hash", configBaseHash)
    }

    private fun ensureObject(parent: JSONObject, key: String): JSONObject {
        val existing = parent.optJSONObject(key)
        if (existing != null) return existing
        val created = JSONObject()
        parent.put(key, created)
        return created
    }
}
