package com.codex.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ExternalAgentId(val value: String) {
    CLAUDE_CODE("claude-code"),
    OPEN_CODE("open-code"),
}

enum class ProviderProtocol(val value: String) {
    ANTHROPIC("anthropic"),
    OPENAI_COMPATIBLE("openai-compatible"),
}

data class ProviderPreset(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val note: String = "",
)

data class AgentModelConfig(
    val id: String,
    val agentId: ExternalAgentId,
    val displayName: String,
    val providerId: String,
    val providerName: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val isDefault: Boolean,
)

object AgentModelConfigStore {
    private const val PREFS_NAME = "agent_model_configs"
    private const val KEY_CONFIGS_JSON = "configs_json"

    fun presetsFor(agentId: ExternalAgentId): List<ProviderPreset> {
        return when (agentId) {
            ExternalAgentId.CLAUDE_CODE -> listOf(
                ProviderPreset(
                    id = "anthropic_official",
                    name = "Anthropic 官方",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "https://api.anthropic.com",
                    note = "Claude Code 官方 Anthropic 接口",
                ),
                ProviderPreset(
                    id = "aliyun_coding_plan_cn",
                    name = "阿里云 Coding Plan（中国）",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "https://coding.dashscope.aliyuncs.com/apps/anthropic",
                    note = "官方文档给出的 Claude Code Coding Plan 地址",
                ),
                ProviderPreset(
                    id = "aliyun_coding_plan_sg",
                    name = "阿里云 Coding Plan（新加坡）",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "https://coding-intl.dashscope.aliyuncs.com/apps/anthropic",
                    note = "根据阿里云地域规则与域名连通性验证推断",
                ),
                ProviderPreset(
                    id = "custom_anthropic",
                    name = "自定义 Anthropic 兼容",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "",
                    note = "可接入任意 Anthropic 兼容网关",
                ),
            )
            ExternalAgentId.OPEN_CODE -> listOf(
                ProviderPreset(
                    id = "openai",
                    name = "OpenAI",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "https://api.openai.com/v1",
                ),
                ProviderPreset(
                    id = "openrouter",
                    name = "OpenRouter",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "https://openrouter.ai/api/v1",
                ),
                ProviderPreset(
                    id = "deepseek",
                    name = "DeepSeek",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "https://api.deepseek.com/v1",
                ),
                ProviderPreset(
                    id = "xai",
                    name = "xAI",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "https://api.x.ai/v1",
                ),
                ProviderPreset(
                    id = "google_ai_studio",
                    name = "Google AI Studio",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                ),
                ProviderPreset(
                    id = "aliyun_dashscope_cn",
                    name = "阿里云百炼（中国）",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    note = "阿里云官方 OpenAI 兼容入口（中国）",
                ),
                ProviderPreset(
                    id = "aliyun_dashscope_sg",
                    name = "阿里云百炼（新加坡）",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                    note = "阿里云官方 OpenAI 兼容入口（新加坡）",
                ),
                ProviderPreset(
                    id = "aliyun_coding_plan_cn",
                    name = "阿里云 Coding Plan（中国）",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "https://coding.dashscope.aliyuncs.com/apps/anthropic/v1",
                    note = "阿里云 FAQ 给出的 OpenCode Coding Plan 地址",
                ),
                ProviderPreset(
                    id = "aliyun_coding_plan_sg",
                    name = "阿里云 Coding Plan（新加坡）",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "https://coding-intl.dashscope.aliyuncs.com/apps/anthropic/v1",
                    note = "根据阿里云地域规则与域名连通性验证推断",
                ),
                ProviderPreset(
                    id = "custom_openai",
                    name = "自定义 OpenAI 兼容",
                    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "",
                ),
                ProviderPreset(
                    id = "custom_anthropic",
                    name = "自定义 Anthropic 兼容",
                    protocol = ProviderProtocol.ANTHROPIC,
                    baseUrl = "",
                ),
            )
        }
    }

    fun loadConfigs(context: Context, agentId: ExternalAgentId): List<AgentModelConfig> {
        val all = readAllConfigs(context)
        return all.filter { it.agentId == agentId }
            .sortedWith(
                compareByDescending<AgentModelConfig> { it.isDefault }
                    .thenBy { it.displayName.lowercase() },
            )
    }

    fun loadCurrentConfig(context: Context, agentId: ExternalAgentId): AgentModelConfig? {
        val list = loadConfigs(context, agentId)
        return list.firstOrNull { it.isDefault } ?: list.firstOrNull()
    }

    fun saveConfig(context: Context, config: AgentModelConfig) {
        val all = readAllConfigs(context).toMutableList()
        val replaced = all.indexOfFirst { it.id == config.id }

        if (config.isDefault) {
            for (index in all.indices) {
                val row = all[index]
                if (row.agentId == config.agentId && row.id != config.id && row.isDefault) {
                    all[index] = row.copy(isDefault = false)
                }
            }
        }

        if (replaced >= 0) {
            all[replaced] = config
        } else {
            all += config
        }

        val hasDefault = all.any { it.agentId == config.agentId && it.isDefault }
        if (!hasDefault) {
            val first = all.indexOfFirst { it.agentId == config.agentId }
            if (first >= 0) {
                all[first] = all[first].copy(isDefault = true)
            }
        }

        writeAllConfigs(context, all)
    }

    fun setDefault(context: Context, agentId: ExternalAgentId, configId: String) {
        val all = readAllConfigs(context).map { row ->
            if (row.agentId != agentId) return@map row
            row.copy(isDefault = row.id == configId)
        }
        writeAllConfigs(context, all)
    }

    fun deleteConfig(context: Context, configId: String) {
        val before = readAllConfigs(context)
        val target = before.firstOrNull { it.id == configId } ?: return
        val after = before.filterNot { it.id == configId }.toMutableList()

        val hasDefault = after.any { it.agentId == target.agentId && it.isDefault }
        if (!hasDefault) {
            val first = after.indexOfFirst { it.agentId == target.agentId }
            if (first >= 0) {
                after[first] = after[first].copy(isDefault = true)
            }
        }

        writeAllConfigs(context, after)
    }

    private fun readAllConfigs(context: Context): List<AgentModelConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIGS_JSON, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val output = mutableListOf<AgentModelConfig>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val agentRaw = item.optString("agentId", "").trim()
            val agentId = ExternalAgentId.entries.firstOrNull { it.value == agentRaw } ?: continue
            val protocolRaw = item.optString("protocol", "").trim()
            val protocol = ProviderProtocol.entries.firstOrNull { it.value == protocolRaw }
                ?: ProviderProtocol.OPENAI_COMPATIBLE
            val id = item.optString("id", "").trim()
            if (id.isEmpty()) continue
            output += AgentModelConfig(
                id = id,
                agentId = agentId,
                displayName = item.optString("displayName", "").trim().ifEmpty { id },
                providerId = item.optString("providerId", "").trim(),
                providerName = item.optString("providerName", "").trim(),
                protocol = protocol,
                baseUrl = item.optString("baseUrl", "").trim(),
                apiKey = item.optString("apiKey", "").trim(),
                modelId = item.optString("modelId", "").trim(),
                isDefault = item.optBoolean("isDefault", false),
            )
        }
        return output
    }

    private fun writeAllConfigs(context: Context, configs: List<AgentModelConfig>) {
        val arr = JSONArray()
        configs.forEach { cfg ->
            arr.put(
                JSONObject()
                    .put("id", cfg.id)
                    .put("agentId", cfg.agentId.value)
                    .put("displayName", cfg.displayName)
                    .put("providerId", cfg.providerId)
                    .put("providerName", cfg.providerName)
                    .put("protocol", cfg.protocol.value)
                    .put("baseUrl", cfg.baseUrl)
                    .put("apiKey", cfg.apiKey)
                    .put("modelId", cfg.modelId)
                    .put("isDefault", cfg.isDefault),
            )
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIGS_JSON, arr.toString())
            .apply()
    }
}
