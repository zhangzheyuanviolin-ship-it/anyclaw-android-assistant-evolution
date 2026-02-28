package com.codex.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PermissionManagerActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var switchBridge: Switch
    private lateinit var btnRequest: Button
    private lateinit var btnOpenShizuku: Button
    private lateinit var btnRefresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_manager)

        tvStatus = findViewById(R.id.tvShizukuStatus)
        switchBridge = findViewById(R.id.switchShizukuBridge)
        btnRequest = findViewById(R.id.btnRequestShizukuPermission)
        btnOpenShizuku = findViewById(R.id.btnOpenShizukuApp)
        btnRefresh = findViewById(R.id.btnRefreshShizukuStatus)

        switchBridge.isChecked = ShizukuController.isBridgeEnabled(this)
        switchBridge.setOnCheckedChangeListener { _, isChecked ->
            ShizukuController.setBridgeEnabled(this, isChecked)
            val toast = if (isChecked) {
                getString(R.string.shizuku_bridge_enabled_toast)
            } else {
                getString(R.string.shizuku_bridge_disabled_toast)
            }
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        btnRequest.setOnClickListener {
            if (!ShizukuController.isServiceRunning()) {
                Toast.makeText(this, getString(R.string.shizuku_service_not_running), Toast.LENGTH_LONG).show()
                refreshStatus()
                return@setOnClickListener
            }
            Toast.makeText(this, getString(R.string.shizuku_permission_requesting), Toast.LENGTH_SHORT).show()
            ShizukuController.requestPermission { granted ->
                runOnUiThread {
                    val msg = if (granted) {
                        getString(R.string.shizuku_permission_granted)
                    } else {
                        getString(R.string.shizuku_permission_denied)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
        }

        btnOpenShizuku.setOnClickListener { openShizukuApp() }
        btnRefresh.setOnClickListener { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val installed = ShizukuController.isShizukuAppInstalled(this)
        val running = ShizukuController.isServiceRunning()
        val granted = ShizukuController.hasPermission()
        val enabled = ShizukuController.isBridgeEnabled(this)

        val statusText = getString(
            R.string.shizuku_status_template,
            if (installed) getString(R.string.status_yes) else getString(R.string.status_no),
            if (running) getString(R.string.status_yes) else getString(R.string.status_no),
            if (granted) getString(R.string.status_granted) else getString(R.string.status_not_granted),
            if (enabled) getString(R.string.status_enabled) else getString(R.string.status_disabled),
        )
        tvStatus.text = statusText

        switchBridge.setOnCheckedChangeListener(null)
        switchBridge.isChecked = enabled
        switchBridge.setOnCheckedChangeListener { _, isChecked ->
            ShizukuController.setBridgeEnabled(this, isChecked)
            val toast = if (isChecked) {
                getString(R.string.shizuku_bridge_enabled_toast)
            } else {
                getString(R.string.shizuku_bridge_disabled_toast)
            }
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        btnRequest.isEnabled = installed && running && !granted
    }

    private fun openShizukuApp() {
        val intent: Intent? = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent == null) {
            Toast.makeText(this, getString(R.string.shizuku_app_not_found), Toast.LENGTH_LONG).show()
            return
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.shizuku_app_open_failed), Toast.LENGTH_LONG).show()
        }
    }
}
