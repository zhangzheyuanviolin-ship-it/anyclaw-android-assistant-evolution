package com.codex.mobile

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class ModelManagerActivity : AppCompatActivity() {

    private data class ModelRow(
        val id: String,
        val name: String,
        val provider: String,
        val contextWindow: Int,
        val reasoning: Boolean,
    )

    private lateinit var btnRefresh: Button
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
        tvCurrentModel = findViewById(R.id.tvCurrentModel)
        progressBar = findViewById(R.id.progressModel)
        tvStatus = findViewById(R.id.tvModelStatus)
        listView = findViewById(R.id.listModels)

        btnRefresh.setOnClickListener {
            loadModelsAndConfig()
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
                val modelPayload = gateway.call("models.list", JSONObject())
                val configPayload = gateway.call("config.get", JSONObject())

                rows = parseModelRows(modelPayload.optJSONArray("models")).toMutableList()
                configRaw = configPayload.optString("raw", "{}")
                configBaseHash = configPayload.optString("hash", "")
                currentModelId = extractCurrentModel(configPayload)

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

    private fun parseModelRows(array: JSONArray?): List<ModelRow> {
        if (array == null) return emptyList()
        val output = mutableListOf<ModelRow>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id", "").trim()
            if (id.isEmpty()) continue
            output += ModelRow(
                id = id,
                name = item.optString("name", id),
                provider = item.optString("provider", ""),
                contextWindow = item.optInt("contextWindow", 0),
                reasoning = item.optBoolean("reasoning", false),
            )
        }
        return output.sortedBy { it.name.lowercase() }
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

    private fun renderModelList() {
        listView.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = rows.size

            override fun getItem(position: Int): Any = rows[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val rowView = convertView ?: LayoutInflater.from(this@ModelManagerActivity)
                    .inflate(R.layout.item_model_row, parent, false)
                val row = rows[position]
                val currentMark = if (row.id == currentModelId) "✓ " else ""
                val reasoning = if (row.reasoning) "reasoning" else "no-reasoning"
                val window = if (row.contextWindow > 0) row.contextWindow.toString() else getString(R.string.status_na)

                rowView.findViewById<TextView>(R.id.tvModelRowTitle).text = "$currentMark${row.name}"
                rowView.findViewById<TextView>(R.id.tvModelRowMeta).text =
                    "${row.id}\nprovider=${row.provider} | ctx=$window | $reasoning"
                rowView.findViewById<Button>(R.id.btnModelRowPrimary).setOnClickListener {
                    setPrimaryModel(row)
                }
                rowView.findViewById<Button>(R.id.btnModelRowConfig).setOnClickListener {
                    openProviderConfigDialog(row)
                }
                rowView.setOnLongClickListener {
                    openProviderConfigDialog(row)
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

    private fun openProviderConfigDialog(row: ModelRow) {
        val providerName = row.provider.ifEmpty { row.id.substringBefore('/') }
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
                saveProviderConfig(row, providerName, baseUrl, apiKey)
            }
            .show()
    }

    private fun saveProviderConfig(row: ModelRow, providerName: String, baseUrl: String, apiKey: String) {
        Thread {
            try {
                val root = JSONObject(configRaw)
                val models = ensureObject(root, "models")
                val providers = ensureObject(models, "providers")
                val provider = if (providers.has(providerName) && providers.optJSONObject(providerName) != null) {
                    providers.getJSONObject(providerName)
                } else {
                    JSONObject().also { providers.put(providerName, it) }
                }

                if (baseUrl.isEmpty()) provider.remove("baseUrl") else provider.put("baseUrl", baseUrl)
                if (apiKey.isEmpty()) provider.remove("apiKey") else provider.put("apiKey", apiKey)
                if (!provider.has("auth")) {
                    provider.put("auth", "api-key")
                }
                if (!provider.has("models")) {
                    provider.put("models", JSONArray())
                }
                if (!provider.has("api")) {
                    provider.put("api", inferApiForProvider(providerName))
                }

                applyConfig(root, "update provider config: $providerName")
                runCatching {
                    gateway.call(
                        "sessions.patch",
                        JSONObject()
                            .put("key", "agent:main:main")
                            .put("model", row.id),
                    )
                }

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

    private fun inferApiForProvider(providerName: String): String {
        return when {
            providerName.contains("openai", ignoreCase = true) -> "openai-responses"
            providerName.contains("codex", ignoreCase = true) -> "openai-codex-responses"
            providerName.contains("anthropic", ignoreCase = true) -> "anthropic-messages"
            providerName.contains("ollama", ignoreCase = true) -> "ollama"
            else -> "openai-responses"
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
