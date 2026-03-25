package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class PromptProfile(
    val id: String,
    val name: String,
    val content: String,
    val selected: Boolean,
    val updatedAtMs: Long,
)

object PromptProfileStore {
    private const val TAG = "PromptProfileStore"
    private const val PREFS = "anyclaw_prompt_profiles"
    private const val KEY_PROFILES_JSON = "profiles_json"
    private const val PROMPT_INJECTION_FILE = ".openclaw-android/state/prompt-injection.json"
    private const val MAX_CONTENT_PREVIEW = 120

    private val capabilityBootstrapInstructions = """
        You are running in AnyClaw on Android.
        Always detect capability state first with: codex-capabilities --plain
        For system-level shell commands, always use: system-shell <command>
        For Ubuntu runtime commands (ubuntu-shell, ubuntu-status, ANYCLAW_UBUNTU_BIN), use local shell directly and do not wrap with system-shell.
        Do not guess command names like shizuku or rish.
        If a command fails, read error_code and degrade or retry accordingly.
        Verify the bundled Ubuntu runtime before concluding it is unavailable.
    """.trimIndent()

    fun loadProfiles(context: Context): MutableList<PromptProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES_JSON, "[]")
            ?: "[]"
        return parseProfiles(raw)
    }

    fun saveProfiles(context: Context, profiles: List<PromptProfile>) {
        val normalized = normalizeSelection(profiles)
        val json = JSONArray()
        for (profile in normalized) {
            json.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("content", profile.content)
                    .put("selected", profile.selected)
                    .put("updated_at_ms", profile.updatedAtMs),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES_JSON, json.toString())
            .apply()
        syncPromptInjectionFile(context, normalized)
    }

    fun ensureSynced(context: Context) {
        val profiles = loadProfiles(context)
        syncPromptInjectionFile(context, normalizeSelection(profiles))
    }

    private fun parseProfiles(raw: String): MutableList<PromptProfile> {
        val result = mutableListOf<PromptProfile>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val id = row.optString("id", "").trim()
                val name = row.optString("name", "").trim()
                val content = row.optString("content", "").trim()
                if (id.isEmpty() || name.isEmpty() || content.isEmpty()) continue
                result.add(
                    PromptProfile(
                        id = id,
                        name = name,
                        content = content,
                        selected = row.optBoolean("selected", false),
                        updatedAtMs = row.optLong("updated_at_ms", System.currentTimeMillis()),
                    ),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing prompt profiles: ${e.message}")
        }
        return normalizeSelection(result).toMutableList()
    }

    private fun normalizeSelection(profiles: List<PromptProfile>): List<PromptProfile> {
        var selectedSeen = false
        return profiles.map { profile ->
            if (profile.selected && !selectedSeen) {
                selectedSeen = true
                profile
            } else if (profile.selected) {
                profile.copy(selected = false)
            } else {
                profile
            }
        }
    }

    private fun syncPromptInjectionFile(context: Context, profiles: List<PromptProfile>) {
        try {
            val selected = profiles.firstOrNull { it.selected }
            val payload = JSONObject()
                .put("version", 1)
                .put("updated_at", Instant.now().toString())
                .put("active_profile_id", selected?.id ?: JSONObject.NULL)
                .put("active_profile_name", selected?.name ?: JSONObject.NULL)
                .put("developer_instructions", buildDeveloperInstructions(context, selected))
                .put("profiles_count", profiles.size)
            if (selected != null) {
                payload.put("active_profile_content_preview", previewText(selected.content))
            }

            val paths = BootstrapInstaller.getPaths(context)
            val outFile = File(paths.homeDir, PROMPT_INJECTION_FILE)
            outFile.parentFile?.mkdirs()
            outFile.writeText(payload.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing prompt injection file: ${e.message}")
        }
    }

    private fun buildDeveloperInstructions(context: Context, selected: PromptProfile?): String {
        val parts = mutableListOf(capabilityBootstrapInstructions.trim(), buildRuntimeSnapshotInstructions(context))
        if (selected != null) {
            parts.add(
                """
                User selected prompt profile name: ${selected.name}
                User selected prompt profile content:
                ${selected.content.trim()}
                """.trimIndent(),
            )
        }
        return parts.joinToString("\n\n").trim()
    }

    private fun buildRuntimeSnapshotInstructions(context: Context): String {
        val paths = BootstrapInstaller.getPaths(context)
        val runtimeBinDir = "${paths.homeDir}/.openclaw-android/linux-runtime/bin"
        val ubuntuBin = "$runtimeBinDir/ubuntu-shell.sh"
        val runtimeMarker = File(paths.homeDir, ".openclaw-android/state/offline-linux-runtime.json")
        val runtimeHealth = File(paths.homeDir, ".openclaw-android/state/runtime-health.json")
        val runtimeVersion = readJsonString(runtimeMarker, "version").ifEmpty { "missing" }
        val runtimeInstalled = if (runtimeMarker.exists()) "1" else "0"
        val runtimeHealthOk = when {
            !runtimeHealth.exists() -> "0"
            readJsonBoolean(runtimeHealth, "ok") -> "1"
            else -> "0"
        }
        val runtimeCheckedAt = readJsonString(runtimeHealth, "checked_at")
        return """
            You are running inside the app-private environment for package ${context.packageName}.
            Default app environment snapshot: HOME=${paths.homeDir} PREFIX=${paths.prefixDir} TMPDIR=${paths.tmpDir} ANYCLAW_UBUNTU_BIN=$ubuntuBin
            Default execution chains in this app: local app shell, Ubuntu runtime shell, and system-shell.
            Bundled Ubuntu runtime snapshot: installed=$runtimeInstalled version=$runtimeVersion runtime_health_ok=$runtimeHealthOk${if (runtimeCheckedAt.isNotEmpty()) " checked_at=$runtimeCheckedAt" else ""}
            When checking the environment, verify ubuntu-status, echo ${'$'}ANYCLAW_UBUNTU_BIN, and list ${'$'}HOME/.openclaw-android/linux-runtime/bin before concluding the Ubuntu runtime is unavailable.
        """.trimIndent()
    }

    private fun readJsonString(file: File, key: String): String {
        if (!file.exists()) return ""
        return runCatching {
            JSONObject(file.readText()).optString(key, "").trim()
        }.getOrDefault("")
    }

    private fun readJsonBoolean(file: File, key: String): Boolean {
        if (!file.exists()) return false
        return runCatching {
            JSONObject(file.readText()).optBoolean(key, false)
        }.getOrDefault(false)
    }

    private fun previewText(content: String): String {
        val normalized = content.replace('\n', ' ').trim()
        if (normalized.length <= MAX_CONTENT_PREVIEW) return normalized
        return normalized.take(MAX_CONTENT_PREVIEW) + "..."
    }
}
