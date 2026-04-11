package com.codex.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class ConversationManagerActivity : AppCompatActivity() {

    private data class ConversationRow(
        val source: SourceType,
        val id: String,
        var title: String,
        val updatedAtMs: Long,
        val preview: String,
        val path: String = "",
        val archived: Boolean = false,
    )

    private enum class SourceType {
        ALL,
        CODEX,
        OPENCLAW,
        CLAUDE_CODE,
    }

    private lateinit var spinnerSource: Spinner
    private lateinit var btnRefresh: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val serverManager by lazy { CodexServerManager(this) }
    private val gateway by lazy { LocalBridgeClients.OpenClawGateway(serverManager) }

    private var allRows = mutableListOf<ConversationRow>()
    private var visibleRows = mutableListOf<ConversationRow>()
    private var loadWarnings = mutableListOf<String>()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_manager)

        spinnerSource = findViewById(R.id.spinnerConversationSource)
        btnRefresh = findViewById(R.id.btnConversationRefresh)
        progressBar = findViewById(R.id.progressConversation)
        tvStatus = findViewById(R.id.tvConversationStatus)
        listView = findViewById(R.id.listConversations)

        val sourceOptions = listOf(
            getString(R.string.conversation_source_all),
            getString(R.string.conversation_source_codex),
            getString(R.string.conversation_source_openclaw),
            getString(R.string.conversation_source_claude),
        )
        spinnerSource.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sourceOptions,
        )
        spinnerSource.setSelection(0)
        spinnerSource.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyFilterAndRender()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        btnRefresh.setOnClickListener {
            loadConversations()
        }

    }

    override fun onResume() {
        super.onResume()
        loadConversations()
    }

    private fun sourceFromSelection(): SourceType {
        return when (spinnerSource.selectedItemPosition) {
            1 -> SourceType.CODEX
            2 -> SourceType.OPENCLAW
            3 -> SourceType.CLAUDE_CODE
            else -> SourceType.ALL
        }
    }

    private fun loadConversations() {
        if (loading) return
        loading = true
        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.conversation_loading)

        Thread {
            val warnings = mutableListOf<String>()
            val codexRows = runCatching { loadCodexRows() }.getOrElse { error ->
                warnings += "Codex: ${normalizeLoadError(error)}"
                emptyList()
            }
            val openClawRows = runCatching { loadOpenClawRows() }.getOrElse { error ->
                warnings += "大龙虾: ${normalizeLoadError(error)}"
                emptyList()
            }
            val claudeRows = runCatching { loadCliAgentRows(ExternalAgentId.CLAUDE_CODE, SourceType.CLAUDE_CODE) }
                .getOrElse { error ->
                    warnings += "Claude Code: ${normalizeLoadError(error)}"
                    emptyList()
                }

            allRows = (codexRows + openClawRows + claudeRows)
                .distinctBy { "${it.source}:${it.id}" }
                .sortedByDescending { it.updatedAtMs }
                .toMutableList()

            runOnUiThread {
                loading = false
                progressBar.visibility = View.GONE
                loadWarnings = warnings.toMutableList()
                applyFilterAndRender()
                if (warnings.isNotEmpty()) {
                    Toast.makeText(
                        this,
                        getString(R.string.conversation_error_prefix) + warnings.joinToString(" | "),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun normalizeLoadError(error: Throwable): String {
        val firstLine = error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (firstLine.isNotEmpty()) return firstLine
        return error.javaClass.simpleName.ifEmpty { "unknown" }
    }

    private fun loadCodexRows(): List<ConversationRow> {
        val active = LocalBridgeClients.callCodexRpc(
            "thread/list",
            JSONObject()
                .put("archived", false)
                .put("limit", 200)
                .put("sortKey", "updated_at"),
        )
        val archived = LocalBridgeClients.callCodexRpc(
            "thread/list",
            JSONObject()
                .put("archived", true)
                .put("limit", 200)
                .put("sortKey", "updated_at"),
        )

        val rows = mutableListOf<ConversationRow>()
        rows += parseCodexList(active.optJSONArray("data"), archived = false)
        rows += parseCodexList(archived.optJSONArray("data"), archived = true)
        return rows
    }

    private fun parseCodexList(data: JSONArray?, archived: Boolean): List<ConversationRow> {
        if (data == null) return emptyList()
        val rows = mutableListOf<ConversationRow>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val id = item.optString("id", "").trim()
            if (id.isEmpty()) continue
            val preview = item.optString("preview", "").trim()
            val updatedAtSeconds = item.optLong("updatedAt", 0L)
            val titleCandidate = preview.lineSequence().firstOrNull()?.trim().orEmpty()
            rows += ConversationRow(
                source = SourceType.CODEX,
                id = id,
                title = if (titleCandidate.isEmpty()) getString(R.string.conversation_unknown_title) else titleCandidate,
                updatedAtMs = updatedAtSeconds * 1000L,
                preview = preview,
                path = item.optString("path", "").trim()
                    .ifEmpty { item.optString("rolloutPath", "").trim() }
                    .ifEmpty { item.optString("transcriptPath", "").trim() },
                archived = archived,
            )
        }
        return rows
    }

    private fun loadOpenClawRows(): List<ConversationRow> {
        var gatewayError: Exception? = null
        var apiError: Exception? = null
        val merged = LinkedHashMap<String, ConversationRow>()

        try {
            mergeOpenClawRows(merged, loadOpenClawRowsFromGateway())
        } catch (error: Exception) {
            gatewayError = error
        }

        try {
            mergeOpenClawRows(merged, loadOpenClawRowsFromApi())
        } catch (error: Exception) {
            apiError = error
        }

        if (merged.isEmpty() && gatewayError != null && apiError != null) {
            throw IllegalStateException(
                "Gateway unavailable: ${gatewayError.message ?: "unknown"}; " +
                    "Lightweight API unavailable: ${apiError.message ?: "unknown"}",
            )
        }
        return merged.values.toList()
    }

    private fun loadCliAgentRows(
        agentId: ExternalAgentId,
        source: SourceType,
    ): List<ConversationRow> {
        return AgentSessionStore.listSessions(this, agentId).map { session ->
            ConversationRow(
                source = source,
                id = session.sessionId,
                title = session.title,
                updatedAtMs = session.updatedAtMs,
                preview = session.messages.lastOrNull()?.text.orEmpty(),
            )
        }
    }

    private fun loadOpenClawRowsFromGateway(): List<ConversationRow> {
        val payload = gateway.call(
            "sessions.list",
            JSONObject()
                .put("limit", 300)
                .put("includeDerivedTitles", true)
                .put("includeLastMessage", true)
                .put("includeGlobal", true)
                .put("includeUnknown", true),
        )
        return parseOpenClawRows(payload.optJSONArray("sessions"))
    }

    private fun loadOpenClawRowsFromApi(): List<ConversationRow> {
        val payload = LocalBridgeClients.callOpenClawApi(
            "/openclaw-api/sessions?limit=300",
            method = "GET",
            connectTimeoutMs = 9_000,
            readTimeoutMs = 20_000,
        )
        return parseOpenClawRows(payload.optJSONArray("sessions"))
    }

    private fun parseOpenClawRows(sessions: JSONArray?): List<ConversationRow> {
        if (sessions == null) return emptyList()
        val rows = mutableListOf<ConversationRow>()
        for (index in 0 until sessions.length()) {
            val item = sessions.optJSONObject(index) ?: continue
            val key = item.optString("key", "").trim()
            if (key.isEmpty()) continue
            val title = item.optString("displayName", "").trim()
                .ifEmpty { item.optString("label", "").trim() }
                .ifEmpty { item.optString("derivedTitle", "").trim() }
                .ifEmpty { key }
            rows += ConversationRow(
                source = SourceType.OPENCLAW,
                id = key,
                title = title,
                updatedAtMs = item.optLong("updatedAt", 0L),
                preview = item.optString("lastMessagePreview", "").trim(),
            )
        }
        return rows
    }

    private fun mergeOpenClawRows(target: LinkedHashMap<String, ConversationRow>, rows: List<ConversationRow>) {
        for (row in rows) {
            val existing = target[row.id]
            if (existing == null || row.updatedAtMs >= existing.updatedAtMs) {
                target[row.id] = row
            }
        }
    }

    private fun applyFilterAndRender() {
        val selected = sourceFromSelection()
        visibleRows = allRows.filter { row ->
            selected == SourceType.ALL || row.source == selected
        }.toMutableList()

        listView.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = visibleRows.size

            override fun getItem(position: Int): Any = visibleRows[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val rowView = convertView ?: LayoutInflater.from(this@ConversationManagerActivity)
                    .inflate(R.layout.item_conversation_row, parent, false)
                val row = visibleRows[position]

                val sourceName = when (row.source) {
                    SourceType.CODEX -> getString(R.string.conversation_source_codex)
                    SourceType.OPENCLAW -> getString(R.string.conversation_source_openclaw)
                    SourceType.CLAUDE_CODE -> getString(R.string.conversation_source_claude)
                    SourceType.ALL -> getString(R.string.conversation_source_all)
                }
                val stateTag = if (row.source == SourceType.CODEX && row.archived) {
                    getString(R.string.conversation_state_archived)
                } else {
                    getString(R.string.conversation_state_active)
                }
                val updated = if (row.updatedAtMs > 0L) {
                    dateFormat.format(Date(row.updatedAtMs))
                } else {
                    getString(R.string.status_no)
                }
                val preview = row.preview.lineSequence().firstOrNull()?.trim().orEmpty()

                rowView.findViewById<TextView>(R.id.tvConversationRowTitle).text = row.title
                rowView.findViewById<TextView>(R.id.tvConversationRowMeta).text = "$sourceName | $stateTag | $updated | ${row.id}"
                rowView.findViewById<TextView>(R.id.tvConversationRowPreview).text = preview

                rowView.findViewById<Button>(R.id.btnConversationRowRename).setOnClickListener {
                    openRenameDialog(row)
                }
                rowView.findViewById<Button>(R.id.btnConversationRowDelete).setOnClickListener {
                    confirmDelete(row)
                }
                rowView.findViewById<Button>(R.id.btnConversationRowCopy).setOnClickListener {
                    copyConversation(row)
                }
                rowView.findViewById<Button>(R.id.btnConversationRowExport).setOnClickListener {
                    exportConversation(row)
                }
                rowView.setOnClickListener {
                    openConversation(row)
                }
                rowView.setOnLongClickListener {
                    openRenameDialog(row)
                    true
                }
                return rowView
            }
        }
        tvStatus.visibility = View.VISIBLE
        val baseStatus = if (visibleRows.isEmpty()) {
            getString(R.string.conversation_empty)
        } else {
            "${visibleRows.size} ${getString(R.string.conversation_source_all)}"
        }
        if (loadWarnings.isEmpty()) {
            tvStatus.text = baseStatus
        } else {
            val brief = loadWarnings.take(2).joinToString(" | ")
            val moreSuffix = if (loadWarnings.size > 2) " | +${loadWarnings.size - 2}项" else ""
            tvStatus.text = "$baseStatus\n部分来源加载失败：$brief$moreSuffix"
        }
    }

    private fun openRenameDialog(row: ConversationRow) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(row.title)
            setSelection(text.length)
            hint = getString(R.string.conversation_title_hint)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.conversation_action_rename))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok), null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val nextTitle = input.text.toString().trim()
                        if (nextTitle.isEmpty()) {
                            input.error = getString(R.string.conversation_title_hint)
                            return@setOnClickListener
                        }
                        renameConversation(row, nextTitle) {
                            dialog.dismiss()
                        }
                    }
                }
            }
            .show()
    }

    private fun openConversation(row: ConversationRow) {
        if (row.source == SourceType.OPENCLAW) {
            startActivity(
                Intent(this, OpenClawChatActivity::class.java).apply {
                    putExtra(OpenClawChatActivity.EXTRA_SESSION_KEY, row.id)
                },
            )
            Toast.makeText(this, getString(R.string.conversation_opening_toast), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            when (row.source) {
                SourceType.CODEX -> {
                    putExtra(MainActivity.EXTRA_OPEN_TARGET, MainActivity.OPEN_TARGET_CODEX_THREAD)
                    putExtra(MainActivity.EXTRA_THREAD_ID, row.id)
                }
                SourceType.OPENCLAW -> Unit
                SourceType.CLAUDE_CODE -> Unit
                SourceType.ALL -> Unit
            }
        }
        if (row.source == SourceType.CLAUDE_CODE) {
            startActivity(
                Intent(this, CliAgentChatActivity::class.java).apply {
                    putExtra(
                        CliAgentChatActivity.EXTRA_AGENT_ID,
                        ExternalAgentId.CLAUDE_CODE.value,
                    )
                    putExtra(CliAgentChatActivity.EXTRA_SESSION_ID, row.id)
                },
            )
            Toast.makeText(this, getString(R.string.conversation_opening_toast), Toast.LENGTH_SHORT).show()
            return
        }
        if (!intent.hasExtra(MainActivity.EXTRA_OPEN_TARGET)) {
            return
        }
        startActivity(intent)
        Toast.makeText(this, getString(R.string.conversation_opening_toast), Toast.LENGTH_SHORT).show()
    }

    private fun renameConversation(row: ConversationRow, newTitle: String, onDone: () -> Unit) {
        Thread {
            try {
                when (row.source) {
                    SourceType.CODEX -> {
                        LocalBridgeClients.callCodexRpc(
                            "thread/name/set",
                            JSONObject()
                                .put("threadId", row.id)
                                .put("name", newTitle),
                        )
                    }
                    SourceType.OPENCLAW -> {
                        renameOpenClawSession(row.id, newTitle)
                    }
                    SourceType.CLAUDE_CODE -> {
                        AgentSessionStore.renameSession(this, ExternalAgentId.CLAUDE_CODE, row.id, newTitle)
                    }
                    SourceType.ALL -> Unit
                }
                row.title = newTitle
                runOnUiThread {
                    applyFilterAndRender()
                    Toast.makeText(this, getString(R.string.conversation_renamed_toast), Toast.LENGTH_SHORT).show()
                    onDone()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.conversation_update_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun confirmDelete(row: ConversationRow) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.conversation_delete_confirm_title))
            .setMessage(getString(R.string.conversation_delete_confirm_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.conversation_action_delete)) { _, _ ->
                deleteConversation(row)
            }
            .show()
    }

    private fun deleteConversation(row: ConversationRow) {
        Thread {
            try {
                when (row.source) {
                    SourceType.CODEX -> {
                        if (!row.archived) {
                            try {
                                LocalBridgeClients.callCodexRpc(
                                    "thread/archive",
                                    JSONObject().put("threadId", row.id),
                                )
                            } catch (archiveError: Exception) {
                                if (!isMissingCodexRollout(archiveError)) {
                                    throw archiveError
                                }
                            }
                        }
                        deleteCodexTranscriptFileIfPossible(row)
                    }
                    SourceType.OPENCLAW -> {
                        deleteOpenClawSession(row.id)
                    }
                    SourceType.CLAUDE_CODE -> {
                        AgentSessionStore.deleteSession(this, ExternalAgentId.CLAUDE_CODE, row.id)
                    }
                    SourceType.ALL -> Unit
                }
                allRows.removeAll { it.source == row.source && it.id == row.id }
                runOnUiThread {
                    applyFilterAndRender()
                    Toast.makeText(this, getString(R.string.conversation_deleted_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.conversation_delete_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun isMissingCodexRollout(error: Exception): Boolean {
        val raw = (error.message ?: "").lowercase(Locale.getDefault())
        return raw.contains("no rollout found") ||
            raw.contains("failed to locate rollout") ||
            raw.contains("rollout not found")
    }

    private fun deleteCodexTranscriptFileIfPossible(row: ConversationRow) {
        val target = resolveCodexTranscriptFile(row) ?: return
        target.deleteRecursively()
    }

    private fun resolveCodexTranscriptFile(row: ConversationRow): File? {
        val baseHome = BootstrapInstaller.getPaths(this).homeDir
        val canonicalHome = runCatching { File(baseHome).canonicalFile }.getOrNull() ?: return null

        fun normalize(pathValue: String): File? {
            val trimmed = pathValue.trim()
            if (trimmed.isEmpty()) return null
            val raw = if (trimmed.startsWith("/")) File(trimmed) else File(baseHome, trimmed)
            val canonical = runCatching { raw.canonicalFile }.getOrNull() ?: return null
            if (!canonical.path.startsWith(canonicalHome.path)) return null
            if (!canonical.exists() || !canonical.isFile) return null
            return canonical
        }

        normalize(row.path)?.let { return it }

        val probeDirs = listOf(
            File(baseHome, ".codex/archived_sessions"),
            File(baseHome, ".codex/sessions"),
        )
        for (dir in probeDirs) {
            val candidates = dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".jsonl") && it.name.contains(row.id) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            for (candidate in candidates) {
                normalize(candidate.absolutePath)?.let { return it }
            }
        }
        return null
    }

    private fun copyConversation(row: ConversationRow) {
        Thread {
            try {
                val text = loadConversationTranscript(row)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(row.title, text)
                clipboard.setPrimaryClip(clip)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.conversation_copied_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.conversation_copy_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun exportConversation(row: ConversationRow) {
        Thread {
            try {
                val text = loadConversationTranscript(row)
                val exportDir = File("/sdcard/Download/AnyClaw/exports")
                exportDir.mkdirs()
                val safeName = row.title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(48).ifEmpty { "conversation" }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(exportDir, "${safeName}_${stamp}.txt")
                file.writeText(text)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.conversation_exported_toast), Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.conversation_export_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun loadConversationTranscript(row: ConversationRow): String {
        return when (row.source) {
            SourceType.CODEX -> loadCodexTranscript(row)
            SourceType.OPENCLAW -> loadOpenClawTranscript(row)
            SourceType.CLAUDE_CODE -> loadCliAgentTranscript(ExternalAgentId.CLAUDE_CODE, row.id)
            SourceType.ALL -> ""
        }
    }

    private fun loadCodexTranscript(row: ConversationRow): String {
        return try {
            val payload = LocalBridgeClients.callCodexRpc(
                "thread/read",
                JSONObject()
                    .put("threadId", row.id)
                    .put("includeTurns", true),
            )
            val thread = payload.optJSONObject("thread") ?: JSONObject()
            val turns = thread.optJSONArray("turns") ?: JSONArray()
            val out = StringBuilder()
            out.appendLine(row.title)
            out.appendLine("source=codex")
            out.appendLine("id=${row.id}")
            out.appendLine()
            for (turnIndex in 0 until turns.length()) {
                val turn = turns.optJSONObject(turnIndex) ?: continue
                val items = turn.optJSONArray("items") ?: continue
                for (itemIndex in 0 until items.length()) {
                    val item = items.optJSONObject(itemIndex) ?: continue
                    when (item.optString("type", "")) {
                        "userMessage" -> {
                            val text = parseCodexUserText(item.optJSONArray("content"))
                            if (text.isNotEmpty()) {
                                out.appendLine("User: $text")
                            }
                        }
                        "agentMessage" -> {
                            val text = item.optString("text", "").trim()
                            if (text.isNotEmpty()) {
                                out.appendLine("Assistant: $text")
                            }
                        }
                    }
                }
                out.appendLine()
            }
            out.toString().trim()
        } catch (_: Exception) {
            loadCodexTranscriptFromJsonl(row)
        }
    }

    private fun loadCodexTranscriptFromJsonl(row: ConversationRow): String {
        val canonicalTarget = resolveCodexTranscriptFile(row)
            ?: throw IOException("Failed to locate local transcript file")

        val out = StringBuilder()
        out.appendLine(row.title)
        out.appendLine("source=codex")
        out.appendLine("id=${row.id}")
        out.appendLine("archived=${row.archived}")
        out.appendLine()

        canonicalTarget.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine
            val item = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            when (item.optString("type", "")) {
                "event_msg" -> {
                    val payload = item.optJSONObject("payload") ?: return@forEachLine
                    if (payload.optString("type", "") == "user_message") {
                        val message = payload.opt("message")
                        val text = when (message) {
                            is String -> message.trim()
                            is JSONObject -> message.optString("text", "").trim()
                                .ifEmpty { parseCodexUserText(message.optJSONArray("content")) }
                            else -> ""
                        }
                        if (text.isNotEmpty()) {
                            out.appendLine("User: $text")
                            out.appendLine()
                        }
                    }
                }
                "response_item" -> {
                    val payload = item.optJSONObject("payload") ?: return@forEachLine
                    if (payload.optString("type", "") != "message") return@forEachLine
                    if (payload.optString("role", "") != "assistant") return@forEachLine
                    val content = payload.optJSONArray("content") ?: return@forEachLine
                    for (index in 0 until content.length()) {
                        val block = content.optJSONObject(index) ?: continue
                        val type = block.optString("type", "")
                        val text = when (type) {
                            "output_text", "text" -> block.optString("text", "").trim()
                            else -> ""
                        }
                        if (text.isNotEmpty()) {
                            out.appendLine("Assistant: $text")
                            out.appendLine()
                        }
                    }
                }
            }
        }
        return out.toString().trim()
    }

    private fun parseCodexUserText(content: JSONArray?): String {
        if (content == null) return ""
        val chunks = mutableListOf<String>()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type", "") == "text") {
                val text = block.optString("text", "").trim()
                if (text.isNotEmpty()) chunks += text
            }
        }
        return chunks.joinToString("\n").trim()
    }

    private fun loadOpenClawTranscript(row: ConversationRow): String {
        val payload = loadOpenClawHistoryPayload(row.id)
        val messages = payload.optJSONArray("messages") ?: JSONArray()
        val out = StringBuilder()
        out.appendLine(row.title)
        out.appendLine("source=openclaw")
        out.appendLine("sessionKey=${row.id}")
        out.appendLine()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role", "unknown")
            val text = parseOpenClawMessageText(message.optJSONArray("content"))
            if (text.isNotEmpty()) {
                out.appendLine("${role.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}: $text")
            }
            out.appendLine()
        }
        return out.toString().trim()
    }

    private fun loadCliAgentTranscript(agentId: ExternalAgentId, sessionId: String): String {
        val session = AgentSessionStore.loadSession(this, agentId, sessionId)
            ?: throw IOException("session not found")
        return AgentSessionStore.transcript(session)
    }

    private fun renameOpenClawSession(sessionKey: String, label: String) {
        val gatewayError = runCatching {
            gateway.call(
                "sessions.patch",
                JSONObject()
                    .put("key", sessionKey)
                    .put("label", label),
            )
        }.exceptionOrNull()
        if (gatewayError == null) return

        LocalBridgeClients.callOpenClawApi(
            "/openclaw-api/sessions/rename",
            method = "POST",
            body = JSONObject()
                .put("sessionKey", sessionKey)
                .put("label", label),
            connectTimeoutMs = 9_000,
            readTimeoutMs = 20_000,
        )
    }

    private fun deleteOpenClawSession(sessionKey: String) {
        val gatewayError = runCatching {
            gateway.call(
                "sessions.delete",
                JSONObject()
                    .put("key", sessionKey)
                    .put("deleteTranscript", true)
                    .put("emitLifecycleHooks", false),
            )
        }.exceptionOrNull()
        if (gatewayError == null) return

        LocalBridgeClients.callOpenClawApi(
            "/openclaw-api/sessions/delete",
            method = "POST",
            body = JSONObject().put("sessionKey", sessionKey),
            connectTimeoutMs = 9_000,
            readTimeoutMs = 20_000,
        )
    }

    private fun loadOpenClawHistoryPayload(sessionKey: String): JSONObject {
        val gatewayResult = runCatching {
            gateway.call(
                "chat.history",
                JSONObject()
                    .put("sessionKey", sessionKey)
                    .put("limit", 400),
            )
        }
        if (gatewayResult.isSuccess) return gatewayResult.getOrThrow()

        return LocalBridgeClients.callOpenClawApi(
            "/openclaw-api/history",
            method = "POST",
            body = JSONObject()
                .put("sessionKey", sessionKey)
                .put("limit", 400),
            connectTimeoutMs = 9_000,
            readTimeoutMs = 30_000,
        )
    }

    private fun parseOpenClawMessageText(content: JSONArray?): String {
        if (content == null) return ""
        val chunks = mutableListOf<String>()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            when (block.optString("type", "")) {
                "text" -> {
                    val text = block.optString("text", "").trim()
                    if (text.isNotEmpty()) chunks += text
                }
                "thinking" -> {
                    val text = block.optString("thinking", "").trim()
                    if (text.isNotEmpty()) chunks += "[thinking] $text"
                }
                "toolCall" -> {
                    val toolName = block.optString("name", "").trim()
                    if (toolName.isNotEmpty()) chunks += "[toolCall] $toolName"
                }
                "toolResult" -> {
                    val toolName = block.optString("toolName", "").trim()
                    if (toolName.isNotEmpty()) chunks += "[toolResult] $toolName"
                }
            }
        }
        return chunks.joinToString("\n").trim()
    }
}
