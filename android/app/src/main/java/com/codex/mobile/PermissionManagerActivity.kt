package com.codex.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PermissionManagerActivity : AppCompatActivity() {

    private lateinit var serverManager: CodexServerManager
    private lateinit var tvStatus: TextView
    private lateinit var tvCodexAuthStatus: TextView
    private lateinit var switchBridge: Switch
    private lateinit var btnRequest: Button
    private lateinit var btnOpenShizuku: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnCodexAuthBrowser: Button
    @Volatile
    private var codexLoginRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_manager)

        serverManager = CodexServerManager(this)
        tvStatus = findViewById(R.id.tvShizukuStatus)
        tvCodexAuthStatus = findViewById(R.id.tvCodexAuthStatus)
        switchBridge = findViewById(R.id.switchShizukuBridge)
        btnRequest = findViewById(R.id.btnRequestShizukuPermission)
        btnOpenShizuku = findViewById(R.id.btnOpenShizukuApp)
        btnRefresh = findViewById(R.id.btnRefreshShizukuStatus)
        btnCodexAuthBrowser = findViewById(R.id.btnCodexAuthBrowser)

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
        btnRefresh.setOnClickListener {
            refreshStatus()
            refreshCodexAuthStatus()
        }
        btnCodexAuthBrowser.setOnClickListener { startCodexBrowserAuth() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshCodexAuthStatus()
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

    private fun refreshCodexAuthStatus() {
        tvCodexAuthStatus.text = getString(
            R.string.codex_auth_status_template,
            getString(R.string.codex_auth_status_checking),
        )
        Thread {
            val loggedIn = runCatching { serverManager.isLoggedIn() }.getOrElse { false }
            runOnUiThread {
                val text = if (loggedIn) {
                    getString(R.string.codex_auth_status_logged_in)
                } else {
                    getString(R.string.codex_auth_status_logged_out)
                }
                tvCodexAuthStatus.text = getString(R.string.codex_auth_status_template, text)
            }
        }.start()
    }

    private fun startCodexBrowserAuth() {
        if (codexLoginRunning) return
        if (!serverManager.isCodexInstalled()) {
            Toast.makeText(this, getString(R.string.codex_not_installed), Toast.LENGTH_LONG).show()
            return
        }
        codexLoginRunning = true
        btnCodexAuthBrowser.isEnabled = false
        Toast.makeText(this, getString(R.string.codex_auth_starting), Toast.LENGTH_SHORT).show()

        Thread {
            var browserOpened = false
            runCatching { serverManager.startProxy() }

            val ok = runCatching {
                serverManager.loginWithUrl(
                    onLoginUrl = { url ->
                        runOnUiThread {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                browserOpened = true
                                Toast.makeText(
                                    this,
                                    getString(R.string.codex_auth_opened_browser),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (_: Exception) {
                                Toast.makeText(
                                    this,
                                    getString(R.string.codex_auth_open_browser_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    onProgress = {},
                )
            }.getOrElse { false }

            runOnUiThread {
                codexLoginRunning = false
                btnCodexAuthBrowser.isEnabled = true
                if (ok) {
                    Toast.makeText(this, getString(R.string.codex_auth_success), Toast.LENGTH_LONG).show()
                } else {
                    val msg = if (browserOpened) {
                        getString(R.string.codex_auth_failed)
                    } else {
                        getString(R.string.codex_auth_open_browser_failed)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                refreshCodexAuthStatus()
            }
        }.start()
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
