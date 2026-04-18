package com.codex.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class CodexAuthMode(val value: String) {
    TOKEN("token"),
    API("api"),
}

data class CodexProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val note: String = "",
)

data class CodexModelConfig(
    val id: String,
    val displayName: String,
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val modelId: String,
    val authMode: CodexAuthMode,
    val apiKey: String,
    val isDefault: Boolean,
)

object CodexModelConfigStore {
    private const val PREFS_NAME = "codex_model_configs"
    private const val KEY_CONFIGS_JSON = "configs_json"

    fun presets(): List<CodexProviderPreset> {
        return listOf(
            CodexProviderPreset(
                id = "openai_official",
                name = "OpenAI 官方",
                baseUrl = "https://api.openai.com/v1",
                note = "默认官方端点",
            ),
            CodexProviderPreset(
                id = "openai_data_residency_us",
                name = "OpenAI 数据驻留（US）",
                baseUrl = "https://us.api.openai.com/v1",
                note = "官方数据驻留域名前缀示例",
            ),
            CodexProviderPreset(
                id = "openrouter",
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                note = "常见 OpenAI 兼容路由",
            ),
            CodexProviderPreset(
                id = "groq",
                name = "Groq",
                baseUrl = "https://api.groq.com/openai/v1",
                note = "OpenAI 兼容接口",
            ),
            CodexProviderPreset(
                id = "together",
                name = "Together AI",
                baseUrl = "https://api.together.xyz/v1",
                note = "OpenAI 兼容接口",
            ),
            CodexProviderPreset(
                id = "deepseek",
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                note = "中国常用 OpenAI 兼容接口",
            ),
            CodexProviderPreset(
                id = "moonshot",
                name = "Moonshot / Kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                note = "中国常用 OpenAI 兼容接口",
            ),
            CodexProviderPreset(
                id = "siliconflow",
                name = "硅基流动 SiliconFlow",
                baseUrl = "https://api.siliconflow.cn/v1",
                note = "中国常用 OpenAI 兼容接口",
            ),
            CodexProviderPreset(
                id = "dashscope_compatible",
                name = "阿里云百炼（兼容模式）",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                note = "中国常用 OpenAI 兼容接口",
            ),
            CodexProviderPreset(
                id = "volcengine_ark",
                name = "火山引擎方舟",
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                note = "中国常用 OpenAI 兼容接口",
            ),
            CodexProviderPreset(
                id = "zhipu_glm",
                name = "智谱 GLM",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                note = "中国常用兼容接口（按官方文档核对路径）",
            ),
            CodexProviderPreset(
                id = "custom_openai_compatible",
                name = "自定义 OpenAI 兼容端点",
                baseUrl = "",
                note = "可手动填写任意兼容端点",
            ),
        )
    }

    fun loadConfigs(context: Context): List<CodexModelConfig> {
        return readAll(context)
            .sortedWith(
                compareByDescending<CodexModelConfig> { it.isDefault }
                    .thenBy { it.displayName.lowercase() },
            )
    }

    fun loadCurrentConfig(context: Context): CodexModelConfig? {
        val rows = loadConfigs(context)
        return rows.firstOrNull { it.isDefault } ?: rows.firstOrNull()
    }

    fun saveConfig(context: Context, config: CodexModelConfig) {
        val all = readAll(context).toMutableList()
        val replacedIndex = all.indexOfFirst { it.id == config.id }

        if (config.isDefault) {
            for (index in all.indices) {
                val row = all[index]
                if (row.id != config.id && row.isDefault) {
                    all[index] = row.copy(isDefault = false)
                }
            }
        }

        if (replacedIndex >= 0) {
            all[replacedIndex] = config
        } else {
            all += config
        }

        if (all.none { it.isDefault } && all.isNotEmpty()) {
            all[0] = all[0].copy(isDefault = true)
        }
        writeAll(context, all)
    }

    fun setDefault(context: Context, configId: String) {
        val all = readAll(context).map { it.copy(isDefault = it.id == configId) }
        writeAll(context, all)
    }

    fun deleteConfig(context: Context, configId: String) {
        val before = readAll(context)
        val after = before.filterNot { it.id == configId }.toMutableList()
        if (after.isNotEmpty() && after.none { it.isDefault }) {
            after[0] = after[0].copy(isDefault = true)
        }
        writeAll(context, after)
    }

    private fun readAll(context: Context): List<CodexModelConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIGS_JSON, "[]").orEmpty()
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = mutableListOf<CodexModelConfig>()
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            val id = item.optString("id", "").trim()
            if (id.isBlank()) continue
            val authModeRaw = item.optString("authMode", CodexAuthMode.TOKEN.value).trim()
            val authMode = CodexAuthMode.entries.firstOrNull { it.value == authModeRaw } ?: CodexAuthMode.TOKEN
            out += CodexModelConfig(
                id = id,
                displayName = item.optString("displayName", "").trim().ifEmpty { id },
                providerId = item.optString("providerId", "").trim(),
                providerName = item.optString("providerName", "").trim(),
                baseUrl = item.optString("baseUrl", "").trim().trimEnd('/'),
                modelId = item.optString("modelId", "").trim(),
                authMode = authMode,
                apiKey = item.optString("apiKey", "").trim(),
                isDefault = item.optBoolean("isDefault", false),
            )
        }
        return out
    }

    private fun writeAll(context: Context, rows: List<CodexModelConfig>) {
        val arr = JSONArray()
        rows.forEach { row ->
            arr.put(
                JSONObject()
                    .put("id", row.id)
                    .put("displayName", row.displayName)
                    .put("providerId", row.providerId)
                    .put("providerName", row.providerName)
                    .put("baseUrl", row.baseUrl.trim().trimEnd('/'))
                    .put("modelId", row.modelId)
                    .put("authMode", row.authMode.value)
                    .put("apiKey", row.apiKey)
                    .put("isDefault", row.isDefault),
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIGS_JSON, arr.toString())
            .apply()
    }
}
