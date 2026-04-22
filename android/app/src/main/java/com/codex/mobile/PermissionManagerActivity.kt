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
    private lateinit var tvClaudeInstallStatus: TextView
    private lateinit var tvHermesInstallStatus: TextView
    private lateinit var switchBridge: Switch
    private lateinit var btnRequest: Button
    private lateinit var btnOpenShizuku: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnCodexAuthBrowser: Button
    private lateinit var btnCodexInstall: Button
    private lateinit var btnClaudeInstall: Button
    private lateinit var btnHermesInstall: Button
    @Volatile
    private var codexLoginRunning = false
    @Volatile
    private var codexInstallRunning = false
    @Volatile
    private var claudeInstallRunning = false
    @Volatile
    private var hermesInstallRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_manager)

        serverManager = CodexServerManager(this)
        ShizukuBridgeRuntime.ensureStarted(this)
        tvStatus = findViewById(R.id.tvShizukuStatus)
        tvCodexAuthStatus = findViewById(R.id.tvCodexAuthStatus)
        tvClaudeInstallStatus = findViewById(R.id.tvClaudeInstallStatus)
        tvHermesInstallStatus = findViewById(R.id.tvHermesInstallStatus)
        switchBridge = findViewById(R.id.switchShizukuBridge)
        btnRequest = findViewById(R.id.btnRequestShizukuPermission)
        btnOpenShizuku = findViewById(R.id.btnOpenShizukuApp)
        btnRefresh = findViewById(R.id.btnRefreshShizukuStatus)
        btnCodexAuthBrowser = findViewById(R.id.btnCodexAuthBrowser)
        btnCodexInstall = findViewById(R.id.btnCodexInstall)
        btnClaudeInstall = findViewById(R.id.btnClaudeInstall)
        btnHermesInstall = findViewById(R.id.btnHermesInstall)

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
            refreshOptionalAgentInstallStatus()
        }
        btnCodexAuthBrowser.setOnClickListener { startCodexBrowserAuth() }
        btnCodexInstall.setOnClickListener { startCodexInstallRepair() }
        btnClaudeInstall.setOnClickListener { startClaudeInstallRepair() }
        btnHermesInstall.setOnClickListener { startHermesInstallRepair() }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridgeRuntime.ensureStarted(this)
        refreshStatus()
        refreshCodexAuthStatus()
        refreshOptionalAgentInstallStatus()
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
            val cliInstalled = runCatching { serverManager.isCodexInstalled() }.getOrElse { false }
            val binaryInstalled = runCatching { serverManager.isPlatformBinaryInstalled() }.getOrElse { false }
            val codexVersion = if (cliInstalled) {
                runCatching { serverManager.getInstalledCodexVersion() }.getOrElse { "" }
            } else {
                ""
            }
            val loggedIn = if (cliInstalled && binaryInstalled) {
                runCatching { serverManager.isLoggedIn() }.getOrElse { false }
            } else {
                false
            }
            runOnUiThread {
                val text = when {
                    !cliInstalled -> getString(R.string.codex_install_status_missing)
                    !binaryInstalled -> getString(R.string.codex_install_status_binary_missing)
                    loggedIn -> getString(R.string.codex_auth_status_logged_in)
                    else -> getString(R.string.codex_auth_status_logged_out)
                }
                val textWithVersion = if (codexVersion.isBlank()) text else "$text · CLI $codexVersion"
                tvCodexAuthStatus.text = getString(R.string.codex_auth_status_template, textWithVersion)
                btnCodexAuthBrowser.isEnabled = !codexLoginRunning && cliInstalled && binaryInstalled
                btnCodexInstall.isEnabled = !codexInstallRunning
                btnCodexInstall.text = if (cliInstalled && binaryInstalled) {
                    getString(R.string.codex_install_repair_text)
                } else {
                    getString(R.string.codex_install_button_text)
                }
            }
        }.start()
    }

    private fun startCodexBrowserAuth() {
        if (codexLoginRunning) return
        if (!serverManager.isCodexInstalled() || !serverManager.isPlatformBinaryInstalled()) {
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

    private fun startCodexInstallRepair() {
        if (codexInstallRunning) return
        codexInstallRunning = true
        btnCodexInstall.isEnabled = false
        btnCodexAuthBrowser.isEnabled = false
        Toast.makeText(this, getString(R.string.codex_install_starting), Toast.LENGTH_SHORT).show()

        Thread {
            val cliInstalled =
                if (serverManager.isCodexInstalled()) {
                    true
                } else {
                    serverManager.installCodex { }
                }

            val binaryInstalled =
                if (cliInstalled && serverManager.isPlatformBinaryInstalled()) {
                    true
                } else if (cliInstalled) {
                    serverManager.installPlatformBinary { }
                } else {
                    false
                }

            if (cliInstalled) {
                runCatching { serverManager.ensureCodexWrapperScript() }
            }

            runOnUiThread {
                codexInstallRunning = false
                btnCodexInstall.isEnabled = true
                val message = if (cliInstalled && binaryInstalled) {
                    getString(R.string.codex_install_success)
                } else {
                    getString(R.string.codex_install_failed)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshCodexAuthStatus()
                refreshOptionalAgentInstallStatus()
            }
        }.start()
    }

    private fun refreshOptionalAgentInstallStatus() {
        tvClaudeInstallStatus.text = getString(
            R.string.optional_agent_status_template,
            getString(R.string.codex_auth_status_checking),
        )
        tvHermesInstallStatus.text = getString(
            R.string.optional_agent_status_template,
            getString(R.string.codex_auth_status_checking),
        )
        Thread {
            val claudeInstalled = runCatching { serverManager.isClaudeCodeInstalled() }.getOrElse { false }
            val claudeVersion = if (claudeInstalled) {
                runCatching { serverManager.getInstalledClaudeCodeVersion() }.getOrElse { "" }
            } else {
                ""
            }
            val hermesInstalled = runCatching { serverManager.isHermesAgentInstalled() }.getOrElse { false }
            val hermesVersion = if (hermesInstalled) {
                runCatching { serverManager.getInstalledHermesAgentVersion() }.getOrElse { "" }
            } else {
                ""
            }
            runOnUiThread {
                val claudeStatusText = if (claudeInstalled) {
                    if (claudeVersion.isBlank()) {
                        getString(R.string.optional_agent_installed)
                    } else {
                        "${getString(R.string.optional_agent_installed)} · CLI $claudeVersion"
                    }
                } else {
                    getString(R.string.optional_agent_not_installed)
                }
                tvClaudeInstallStatus.text = getString(
                    R.string.optional_agent_status_template,
                    claudeStatusText,
                )
                btnClaudeInstall.isEnabled = !claudeInstallRunning

                val hermesStatusText = if (hermesInstalled) {
                    if (hermesVersion.isBlank()) {
                        getString(R.string.optional_agent_installed)
                    } else {
                        "${getString(R.string.optional_agent_installed)} · CLI $hermesVersion"
                    }
                } else {
                    getString(R.string.optional_agent_not_installed)
                }
                tvHermesInstallStatus.text = getString(
                    R.string.optional_agent_status_template,
                    hermesStatusText,
                )
                btnHermesInstall.isEnabled = !hermesInstallRunning
            }
        }.start()
    }

    private fun startClaudeInstallRepair() {
        if (claudeInstallRunning) return
        claudeInstallRunning = true
        btnClaudeInstall.isEnabled = false
        Toast.makeText(this, getString(R.string.claude_install_starting), Toast.LENGTH_SHORT).show()

        Thread {
            val installed = serverManager.installClaudeCode { }
            runOnUiThread {
                claudeInstallRunning = false
                val message = if (installed) {
                    getString(R.string.claude_install_success)
                } else {
                    getString(R.string.claude_install_failed)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshOptionalAgentInstallStatus()
            }
        }.start()
    }

    private fun startHermesInstallRepair() {
        if (hermesInstallRunning) return
        hermesInstallRunning = true
        btnHermesInstall.isEnabled = false
        Toast.makeText(this, getString(R.string.hermes_install_starting), Toast.LENGTH_SHORT).show()

        Thread {
            val progressLines = mutableListOf<String>()
            val installed = serverManager.installHermesAgent { line ->
                synchronized(progressLines) {
                    progressLines.add(line)
                    if (progressLines.size > 60) {
                        progressLines.removeAt(0)
                    }
                }
            }
            val failureDetail = synchronized(progressLines) {
                extractHermesInstallFailureDetail(progressLines)
            }
            runOnUiThread {
                hermesInstallRunning = false
                val message = if (installed) {
                    getString(R.string.hermes_install_success)
                } else {
                    val base = getString(R.string.hermes_install_failed)
                    if (failureDetail.isNotBlank()) "$base 原因：$failureDetail" else base
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshOptionalAgentInstallStatus()
            }
        }.start()
    }

    private fun extractHermesInstallFailureDetail(lines: List<String>): String {
        val ignoredPrefixes = listOf(
            "Installing",
            "Preparing",
            "Collecting",
            "Using",
            "Requirement already satisfied",
            "Successfully installed",
            "Installing collected packages",
            "Building wheels for collected packages",
            "Installing build dependencies",
            "Checking if build backend",
            "Getting requirements to build editable",
            "Preparing editable metadata",
            "Created wheel for",
            "Stored in directory:",
            "Obtaining file://",
            "Hit:",
            "Reading package lists",
            "Building dependency tree",
            "Reading state information",
            "LOGIN_SUCCESSFUL",
            "TERMINAL_READY",
            "hermes-install-verify-ok",
        )
        return lines
            .asReversed()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() && ignoredPrefixes.none { prefix -> line.startsWith(prefix) }
            }
            .orEmpty()
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
