package com.codex.mobile

import android.content.Context
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class OpenClawLocalSession(
    val key: String,
    val sessionId: String,
    val displayName: String,
    val updatedAtMs: Long,
    val preview: String,
    val sessionFilePath: String,
)

object OpenClawLocalSessionStore {
    private const val PREFS_NAME = "openclaw_local_session_aliases"
    private const val KEY_ALIASES_JSON = "aliases_json"

    private fun sessionsDir(context: Context): File {
        val homeDir = BootstrapInstaller.getPaths(context).homeDir
        return File(homeDir, ".openclaw/agents/main/sessions")
    }

    private fun sessionsIndexFile(context: Context): File {
        return File(sessionsDir(context), "sessions.json")
    }

    private fun readAliases(context: Context): MutableMap<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ALIASES_JSON, "{}").orEmpty().trim()
        if (raw.isEmpty()) return mutableMapOf()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val out = mutableMapOf<String, String>()
        val names = root.names() ?: JSONArray()
        for (index in 0 until names.length()) {
            val key = names.optString(index, "").trim()
            if (key.isEmpty()) continue
            val value = root.optString(key, "").trim()
            if (value.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun writeAliases(context: Context, aliases: Map<String, String>) {
        val root = JSONObject()
        aliases.forEach { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isNotEmpty() && normalizedValue.isNotEmpty()) {
                root.put(normalizedKey, normalizedValue)
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALIASES_JSON, root.toString())
            .apply()
    }

    fun setAlias(context: Context, sessionKey: String, alias: String) {
        val key = sessionKey.trim()
        if (key.isEmpty()) return
        val aliases = readAliases(context)
        val normalized = alias.trim()
        if (normalized.isEmpty()) {
            aliases.remove(key)
        } else {
            aliases[key] = normalized
        }
        writeAliases(context, aliases)
    }

    private fun readSessionsIndex(context: Context): JSONObject {
        val indexFile = sessionsIndexFile(context)
        if (!indexFile.exists()) return JSONObject()
        return runCatching {
            val payload = JSONTokener(indexFile.readText()).nextValue()
            if (payload is JSONObject) payload else JSONObject()
        }.getOrElse { JSONObject() }
    }

    private fun writeSessionsIndex(context: Context, root: JSONObject) {
        val indexFile = sessionsIndexFile(context)
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(root.toString(2))
    }

    fun listSessions(context: Context, limit: Int = 300): List<OpenClawLocalSession> {
        val aliases = readAliases(context)
        val root = readSessionsIndex(context)
        val names = root.names() ?: JSONArray()
        val rows = mutableListOf<OpenClawLocalSession>()
        for (index in 0 until names.length()) {
            val key = names.optString(index, "").trim()
            if (key.isEmpty()) continue
            val item = root.optJSONObject(key) ?: continue
            val sessionId = item.optString("sessionId", "").trim()
            val fallbackTitle = item.optString("displayName", "").trim()
                .ifEmpty { item.optString("label", "").trim() }
                .ifEmpty { key }
            val displayName = aliases[key]?.trim().orEmpty().ifEmpty { fallbackTitle }
            val updatedAtMs = item.optLong("updatedAt", 0L)
            rows += OpenClawLocalSession(
                key = key,
                sessionId = sessionId,
                displayName = displayName,
                updatedAtMs = updatedAtMs,
                preview = item.optString("lastMessagePreview", "").trim(),
                sessionFilePath = item.optString("sessionFile", "").trim(),
            )
        }
        return rows
            .sortedByDescending { it.updatedAtMs }
            .let { ordered ->
                if (limit < 1) ordered else ordered.take(limit)
            }
    }

    fun resolveByKey(context: Context, sessionKey: String): OpenClawLocalSession? {
        val normalized = sessionKey.trim()
        if (normalized.isEmpty()) return null
        return listSessions(context, limit = Int.MAX_VALUE)
            .firstOrNull { it.key == normalized }
    }

    fun resolveBySessionId(context: Context, sessionId: String): OpenClawLocalSession? {
        val normalized = sessionId.trim()
        if (normalized.isEmpty()) return null
        return listSessions(context, limit = Int.MAX_VALUE)
            .firstOrNull { it.sessionId == normalized }
    }

    fun createIndependentSessionKey(currentSessionKey: String?): String {
        val now = System.currentTimeMillis().toString(36)
        val randSeed = System.nanoTime() xor (Math.random() * Long.MAX_VALUE).toLong()
        val rand = randSeed.toString(36).replace("-", "").takeLast(6).ifEmpty { "mobile" }
        val normalized = currentSessionKey?.trim().orEmpty()
        if (normalized.startsWith("agent:")) {
            val parts = normalized.split(":")
            val agent = if (parts.size >= 2 && parts[1].isNotBlank()) parts[1] else "main"
            return "agent:$agent:mobile-$now-$rand"
        }
        return "agent:main:mobile-$now-$rand"
    }

    fun deleteSession(context: Context, sessionKey: String): Boolean {
        val normalized = sessionKey.trim()
        if (normalized.isEmpty()) return false
        val root = readSessionsIndex(context)
        val row = root.optJSONObject(normalized)
        if (row == null) {
            // Session already absent from index; still clear alias as best effort.
            setAlias(context, normalized, "")
            return false
        }

        val sessionFilePath = row.optString("sessionFile", "").trim()
        root.remove(normalized)
        writeSessionsIndex(context, root)
        setAlias(context, normalized, "")

        if (sessionFilePath.isNotEmpty()) {
            val file = File(sessionFilePath)
            runCatching { if (file.exists()) file.delete() }
            runCatching {
                val lockFile = File("$sessionFilePath.lock")
                if (lockFile.exists()) lockFile.delete()
            }
        }
        return true
    }

    fun loadHistoryPayload(context: Context, sessionKey: String, limit: Int = 120): JSONObject {
        val normalizedKey = sessionKey.trim()
        if (normalizedKey.isEmpty()) {
            return JSONObject()
                .put("sessionKey", "")
                .put("messages", JSONArray())
                .put("thinkingLevel", "medium")
        }

        val session = resolveByKey(context, normalizedKey)
        val messages = JSONArray()
        if (session != null && session.sessionFilePath.isNotEmpty()) {
            val file = File(session.sessionFilePath)
            if (file.exists() && file.isFile) {
                val queue = ArrayDeque<JSONObject>()
                runCatching {
                    file.useLines { lines ->
                        lines.forEach { rawLine ->
                            val line = rawLine.trim()
                            if (line.isEmpty()) return@forEach
                            val row = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                            if (!row.optString("type", "").equals("message", ignoreCase = true)) return@forEach
                            val message = row.optJSONObject("message") ?: return@forEach
                            val role = message.optString("role", "").trim()
                            if (role.isEmpty()) return@forEach
                            val item = JSONObject()
                                .put("role", role)
                                .put("content", message.optJSONArray("content") ?: JSONArray())
                                .put("timestamp", message.optLong("timestamp", 0L))
                            queue.addLast(item)
                            val cap = if (limit < 1) 1 else limit
                            while (queue.size > cap) {
                                queue.removeFirst()
                            }
                        }
                    }
                }
                queue.forEach { row -> messages.put(row) }
            }
        }

        return JSONObject()
            .put("sessionKey", normalizedKey)
            .put("messages", messages)
            .put("thinkingLevel", "medium")
    }

    fun sanitizeLabel(label: String): String {
        return label.trim().replace(Regex("\\s+"), " ").ifEmpty { "新会话" }
    }

    fun buildFallbackTitle(sessionKey: String): String {
        val normalized = sessionKey.trim()
        if (normalized.isEmpty()) return "新会话"
        val suffix = normalized.takeLast(8)
        return String.format(Locale.getDefault(), "会话 %s", suffix)
    }
}
