package com.codex.mobile

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class AgentChatMessage(
    val role: String,
    val text: String,
    val atMs: Long,
)

data class AgentChatSession(
    val agentId: ExternalAgentId,
    val sessionId: String,
    val title: String,
    val updatedAtMs: Long,
    val messages: List<AgentChatMessage>,
    val nativeSessionId: String = "",
    val nativeSessionMode: String = "",
)

object AgentSessionStore {

    fun listSessions(context: Context, agentId: ExternalAgentId): List<AgentChatSession> {
        val dir = agentDir(context, agentId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { file -> runCatching { parseSession(agentId, file.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAtMs }
            .orEmpty()
    }

    fun loadSession(
        context: Context,
        agentId: ExternalAgentId,
        sessionId: String,
    ): AgentChatSession? {
        val file = sessionFile(context, agentId, sessionId)
        if (!file.exists()) return null
        return runCatching { parseSession(agentId, file.readText()) }.getOrNull()
    }

    fun createSession(
        context: Context,
        agentId: ExternalAgentId,
        title: String? = null,
    ): AgentChatSession {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val sessionId = "${agentId.value}-$stamp"
        val safeTitle = title?.trim().orEmpty().ifEmpty { "${displayAgentName(agentId)} 会话 $stamp" }
        val session = AgentChatSession(
            agentId = agentId,
            sessionId = sessionId,
            title = safeTitle,
            updatedAtMs = now,
            messages = emptyList(),
            nativeSessionId = "",
            nativeSessionMode = "",
        )
        saveSession(context, session)
        return session
    }

    fun appendMessage(
        context: Context,
        agentId: ExternalAgentId,
        sessionId: String,
        role: String,
        text: String,
    ): AgentChatSession {
        val trimmed = text.trim()
        val base = loadSession(context, agentId, sessionId)
            ?: createSession(context, agentId)
        if (trimmed.isEmpty()) return base

        val now = System.currentTimeMillis()
        val nextMessages = base.messages + AgentChatMessage(role = role.trim().ifEmpty { "assistant" }, text = trimmed, atMs = now)
        val next = base.copy(updatedAtMs = now, messages = nextMessages)
        saveSession(context, next)
        return next
    }

    fun renameSession(
        context: Context,
        agentId: ExternalAgentId,
        sessionId: String,
        newTitle: String,
    ): AgentChatSession? {
        val base = loadSession(context, agentId, sessionId) ?: return null
        val title = newTitle.trim().ifEmpty { base.title }
        val next = base.copy(title = title, updatedAtMs = System.currentTimeMillis())
        saveSession(context, next)
        return next
    }

    fun updateNativeSession(
        context: Context,
        agentId: ExternalAgentId,
        sessionId: String,
        nativeSessionId: String,
        nativeSessionMode: String,
    ): AgentChatSession? {
        val base = loadSession(context, agentId, sessionId) ?: return null
        val next = base.copy(
            updatedAtMs = System.currentTimeMillis(),
            nativeSessionId = nativeSessionId.trim(),
            nativeSessionMode = nativeSessionMode.trim(),
        )
        saveSession(context, next)
        return next
    }

    fun deleteSession(context: Context, agentId: ExternalAgentId, sessionId: String): Boolean {
        val file = sessionFile(context, agentId, sessionId)
        return file.exists() && file.delete()
    }

    fun ensureActiveSession(
        context: Context,
        agentId: ExternalAgentId,
        preferredSessionId: String? = null,
    ): AgentChatSession {
        val preferred = preferredSessionId?.trim().orEmpty()
        if (preferred.isNotEmpty()) {
            val existing = loadSession(context, agentId, preferred)
            if (existing != null) return existing
        }
        val recent = listSessions(context, agentId).firstOrNull()
        if (recent != null) return recent
        return createSession(context, agentId)
    }

    fun overwriteSession(context: Context, session: AgentChatSession): AgentChatSession {
        saveSession(context, session)
        return session
    }

    fun transcript(session: AgentChatSession): String {
        val out = StringBuilder()
        out.appendLine(session.title)
        out.appendLine("source=${session.agentId.value}")
        out.appendLine("sessionId=${session.sessionId}")
        if (session.nativeSessionId.isNotBlank()) {
            out.appendLine("nativeSessionId=${session.nativeSessionId}")
        }
        if (session.nativeSessionMode.isNotBlank()) {
            out.appendLine("nativeSessionMode=${session.nativeSessionMode}")
        }
        out.appendLine()
        session.messages.forEach { msg ->
            val role = msg.role.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            out.appendLine("$role: ${msg.text}")
            out.appendLine()
        }
        return out.toString().trim()
    }

    fun displayAgentName(agentId: ExternalAgentId): String {
        return when (agentId) {
            ExternalAgentId.CLAUDE_CODE -> "Claude Code"
        }
    }

    private fun saveSession(context: Context, session: AgentChatSession) {
        val file = sessionFile(context, session.agentId, session.sessionId)
        file.parentFile?.mkdirs()
        file.writeText(sessionToJson(session).toString(2))
    }

    private fun parseSession(agentId: ExternalAgentId, raw: String): AgentChatSession {
        val root = JSONObject(raw)
        val sessionId = root.optString("sessionId", "").trim()
        val title = root.optString("title", "").trim().ifEmpty { sessionId }
        val updatedAtMs = root.optLong("updatedAtMs", 0L)
        val nativeSessionId = root.optString("nativeSessionId", "").trim()
        val nativeSessionMode = root.optString("nativeSessionMode", "").trim()
        val messages = mutableListOf<AgentChatMessage>()
        val arr = root.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            val text = item.optString("text", "").trim()
            if (text.isEmpty()) continue
            messages += AgentChatMessage(
                role = item.optString("role", "assistant").trim().ifEmpty { "assistant" },
                text = text,
                atMs = item.optLong("atMs", 0L),
            )
        }
        return AgentChatSession(
            agentId = agentId,
            sessionId = sessionId,
            title = title,
            updatedAtMs = updatedAtMs,
            messages = messages,
            nativeSessionId = nativeSessionId,
            nativeSessionMode = nativeSessionMode,
        )
    }

    private fun sessionToJson(session: AgentChatSession): JSONObject {
        val arr = JSONArray()
        session.messages.forEach { msg ->
            arr.put(
                JSONObject()
                    .put("role", msg.role)
                    .put("text", msg.text)
                    .put("atMs", msg.atMs),
            )
        }
        return JSONObject()
            .put("agentId", session.agentId.value)
            .put("sessionId", session.sessionId)
            .put("title", session.title)
            .put("updatedAtMs", session.updatedAtMs)
            .put("nativeSessionId", session.nativeSessionId)
            .put("nativeSessionMode", session.nativeSessionMode)
            .put("messages", arr)
    }

    private fun sessionFile(context: Context, agentId: ExternalAgentId, sessionId: String): File {
        val safe = sessionId.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(agentDir(context, agentId), "$safe.json")
    }

    private fun agentDir(context: Context, agentId: ExternalAgentId): File {
        val homeDir = BootstrapInstaller.getPaths(context).homeDir
        return File(homeDir, ".pocketlobster/agent-sessions/${agentId.value}")
    }
}
