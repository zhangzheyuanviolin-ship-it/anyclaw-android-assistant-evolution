package com.codex.mobile

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class PromptManagerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var btnAddPrompt: Button
    private lateinit var btnClearSelectedPrompt: Button
    private var profiles = mutableListOf<PromptProfile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prompt_manager)

        listView = findViewById(R.id.listPromptProfiles)
        btnAddPrompt = findViewById(R.id.btnAddPrompt)
        btnClearSelectedPrompt = findViewById(R.id.btnClearSelectedPrompt)

        btnAddPrompt.setOnClickListener { openEditDialog(null) }
        btnClearSelectedPrompt.setOnClickListener { clearSelectedProfile() }

        listView.setOnItemClickListener { _, _, position, _ ->
            val row = profiles.getOrNull(position) ?: return@setOnItemClickListener
            toggleSelection(row.id)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val row = profiles.getOrNull(position) ?: return@setOnItemLongClickListener true
            showItemMenu(row)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        profiles = PromptProfileStore.loadProfiles(this)
        val displayRows = if (profiles.isEmpty()) {
            listOf(getString(R.string.prompt_empty_text))
        } else {
            profiles.map { row ->
                val status = if (row.selected) {
                    getString(R.string.prompt_status_active)
                } else {
                    getString(R.string.prompt_status_inactive)
                }
                val preview = row.content.replace('\n', ' ').trim()
                val shortPreview = if (preview.length > 80) preview.take(80) + "..." else preview
                "$status | ${row.name}\n$shortPreview"
            }
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayRows)
    }

    private fun toggleSelection(profileId: String) {
        profiles = profiles.map { row ->
            if (row.id == profileId) {
                row.copy(selected = !row.selected, updatedAtMs = System.currentTimeMillis())
            } else {
                row.copy(selected = false)
            }
        }.toMutableList()
        PromptProfileStore.saveProfiles(this, profiles)
        val selected = profiles.firstOrNull { it.id == profileId }?.selected == true
        val toast = if (selected) {
            getString(R.string.prompt_selected_toast)
        } else {
            getString(R.string.prompt_unselected_toast)
        }
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun clearSelectedProfile() {
        var changed = false
        profiles = profiles.map { row ->
            if (row.selected) {
                changed = true
                row.copy(selected = false, updatedAtMs = System.currentTimeMillis())
            } else {
                row
            }
        }.toMutableList()
        if (!changed) return
        PromptProfileStore.saveProfiles(this, profiles)
        Toast.makeText(this, getString(R.string.prompt_selection_cleared_toast), Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun showItemMenu(profile: PromptProfile) {
        val options = arrayOf(
            getString(R.string.prompt_edit_text),
            getString(R.string.prompt_delete_text),
        )
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openEditDialog(profile)
                    1 -> confirmDelete(profile.id)
                }
            }
            .show()
    }

    private fun confirmDelete(profileId: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.prompt_delete_confirm_title))
            .setMessage(getString(R.string.prompt_delete_confirm_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_delete_text)) { _, _ ->
                profiles = profiles.filterNot { it.id == profileId }.toMutableList()
                PromptProfileStore.saveProfiles(this, profiles)
                Toast.makeText(this, getString(R.string.prompt_deleted_toast), Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .show()
    }

    private fun openEditDialog(existing: PromptProfile?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val nameInput = EditText(this).apply {
            hint = getString(R.string.prompt_name_hint)
            setText(existing?.name ?: "")
            setSingleLine(true)
        }

        val contentInput = EditText(this).apply {
            hint = getString(R.string.prompt_content_hint)
            setText(existing?.content ?: "")
            minLines = 5
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        container.addView(nameInput)
        container.addView(contentInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.prompt_manager_title))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.prompt_save_text), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val content = contentInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.prompt_invalid_name_toast), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (content.isEmpty()) {
                    Toast.makeText(this, getString(R.string.prompt_invalid_content_toast), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val now = System.currentTimeMillis()
                profiles = if (existing == null) {
                    (listOf(
                        PromptProfile(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            content = content,
                            selected = false,
                            updatedAtMs = now,
                        ),
                    ) + profiles).toMutableList()
                } else {
                    profiles.map { row ->
                        if (row.id == existing.id) {
                            row.copy(
                                name = name,
                                content = content,
                                updatedAtMs = now,
                            )
                        } else {
                            row
                        }
                    }.toMutableList()
                }

                PromptProfileStore.saveProfiles(this, profiles)
                Toast.makeText(this, getString(R.string.prompt_saved_toast), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                refreshList()
            }
        }

        dialog.show()
    }
}
