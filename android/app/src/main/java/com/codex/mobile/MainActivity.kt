package com.codex.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CodexMainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var permissionCenterButton: Button
    private lateinit var promptManagerButton: Button
    private lateinit var serverManager: CodexServerManager
    private var shizukuBridgeServer: ShizukuShellBridgeServer? = null
    private var setupStarted = false
    private var waitingForStorageGrant = false
    private var waitingForShizukuGrant = false
    private var shizukuPermissionRequested = false

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            waitingForStorageGrant = false
            val granted = result.values.all { it }
            if (granted || hasStorageAccess()) {
                maybeRequestShizukuThenStartSetup()
            } else {
                showStoragePermissionDialog()
            }
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            waitingForStorageGrant = false
            if (hasStorageAccess()) {
                maybeRequestShizukuThenStartSetup()
            } else {
                showStoragePermissionDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        progressBar = findViewById(R.id.progressBar)
        permissionCenterButton = findViewById(R.id.btnPermissionCenter)
        promptManagerButton = findViewById(R.id.btnPromptManager)

        serverManager = CodexServerManager(this)

        permissionCenterButton.setOnClickListener {
            startActivity(Intent(this, PermissionManagerActivity::class.java))
        }
        promptManagerButton.setOnClickListener {
            startActivity(Intent(this, PromptManagerActivity::class.java))
        }

        requestBatteryOptimizationExemption()
        startForegroundService()
        startShizukuBridgeServer()
        setupWebView()
        ensureStorageAccessOrStartSetup()
    }

    override fun onResume() {
        super.onResume()
        PromptProfileStore.ensureSynced(this)
        if (!setupStarted && waitingForStorageGrant && hasStorageAccess()) {
            waitingForStorageGrant = false
            maybeRequestShizukuThenStartSetup()
        }
        if (!setupStarted && waitingForShizukuGrant && ShizukuController.hasPermission()) {
            waitingForShizukuGrant = false
            startSetupFlow()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shizukuBridgeServer?.stop()
        shizukuBridgeServer = null
        serverManager.stopServer()
        stopService(Intent(this, CodexForegroundService::class.java))
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, CodexForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            setSupportZoom(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String,
            ): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[WebView] ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}")
                return true
            }
        }
    }

    private fun startSetupFlow() {
        if (setupStarted) return
        setupStarted = true
        showLoading(true)
        setStatus("Initializing…")

        Thread {
            try {
                runSetup()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                runOnUiThread {
                    showError(e.message ?: "Unknown error")
                }
            }
        }.start()
    }

    private fun runSetup() {
        // Step 1: Extract bootstrap
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateStatus("Extracting environment…")
            BootstrapInstaller.install(this) { msg -> updateStatus(msg) }
        }
        updateStatus("Environment ready")

        // Step 1b: Install proot (needed for dpkg/apt-get path remapping)
        if (!serverManager.isProotInstalled()) {
            updateStatus("Installing proot…", "Needed for package management")
            val prootOk = serverManager.installProot { msg -> updateDetail(msg) }
            if (!prootOk) {
                throw RuntimeException("Failed to install proot")
            }
        }
        updateStatus("proot ready")

        // Step 2: Install Node.js
        if (!serverManager.isNodeInstalled()) {
            updateStatus("Installing Node.js (first run)…", "This may take a few minutes")
            val nodeOk = serverManager.installNode { msg -> updateDetail(msg) }
            if (!nodeOk) {
                throw RuntimeException("Failed to install Node.js")
            }
        }
        updateStatus("Node.js ready")

        // Step 2b: Install Python
        if (!serverManager.isPythonInstalled()) {
            updateStatus("Installing Python…")
            val pyOk = serverManager.installPython { msg -> updateDetail(msg) }
            if (!pyOk) {
                Log.w(TAG, "Python install failed — continuing without it")
            }
        }

        // Step 2c: Install bionic-compat.js (Android platform shim for Node.js)
        serverManager.ensureBionicCompat()

        // Step 2d: Install OpenClaw
        if (!serverManager.isOpenClawInstalled()) {
            updateStatus("Installing build dependencies…")
            serverManager.installOpenClawDeps { msg -> updateDetail(msg) }

            updateStatus("Installing OpenClaw…", "This may take several minutes")
            val openclawOk = serverManager.installOpenClaw { msg -> updateDetail(msg) }
            if (!openclawOk) {
                Log.w(TAG, "OpenClaw install failed — continuing without it")
            } else {
                updateStatus("OpenClaw installed")
            }
        }

        // Step 3: Install Codex CLI
        if (!serverManager.isCodexInstalled()) {
            updateStatus("Installing Codex CLI…", "This may take a few minutes")
            val codexOk = serverManager.installCodex { msg -> updateDetail(msg) }
            if (!codexOk) {
                throw RuntimeException("Failed to install Codex")
            }
        }

        // Ensure codex wrapper script exists
        serverManager.ensureCodexWrapperScript()

        // Step 3a: Extract web UI from APK assets (every launch)
        updateStatus("Updating web UI…")
        serverManager.installServerBundle { msg -> updateDetail(msg) }

        // Step 3b: Install native platform binary
        if (!serverManager.isPlatformBinaryInstalled()) {
            updateStatus("Installing Codex platform binary…")
            val binOk = serverManager.installPlatformBinary { msg -> updateDetail(msg) }
            if (!binOk) {
                throw RuntimeException("Failed to install Codex platform binary")
            }
        }
        updateStatus("Codex ready")

        // Step 3c: Write full-access config, create default workspace, and bridge shared storage paths
        serverManager.ensureFullAccessConfig()
        serverManager.ensureDefaultWorkspace()
        serverManager.ensureStorageBridge()
        serverManager.ensureShizukuBridgeScripts()
        PromptProfileStore.ensureSynced(this)

        // Step 4: Start CONNECT proxy (needed for native binary DNS/TLS)
        updateStatus("Starting network proxy…")
        if (!serverManager.startProxy()) {
            throw RuntimeException("Failed to start network proxy")
        }

        // Step 5: Authenticate via `codex login`
        updateStatus("Checking authentication…")
        if (!serverManager.isLoggedIn()) {
            updateStatus("Login required — opening browser…")
            val authOk = serverManager.loginWithUrl(
                onLoginUrl = { url ->
                    runOnUiThread {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                onProgress = { msg -> updateDetail(msg) },
            )
            if (!authOk && !serverManager.isLoggedIn()) {
                updateStatus("Browser login failed — enter API key manually")
                val apiKey = requestApiKey()
                if (apiKey.isBlank()) {
                    throw RuntimeException("No API key provided")
                }
                val loginOk = serverManager.loginWithApiKey(apiKey)
                if (!loginOk) {
                    throw RuntimeException("Login failed — check your API key")
                }
            }
        }
        updateStatus("Authenticated")

        // Step 6: Health check
        updateStatus("Verifying API access…", "Sending test message")
        val healthOk = serverManager.healthCheck { msg -> updateDetail(msg) }
        if (!healthOk) {
            throw RuntimeException("API health check failed — Codex could not reach OpenAI")
        }
        updateStatus("API verified")

        // Step 7: Configure and start OpenClaw
        if (serverManager.isOpenClawInstalled()) {
            updateStatus("Configuring OpenClaw…")
            serverManager.configureOpenClawAuth()

            updateStatus("Starting OpenClaw gateway…")
            serverManager.startOpenClawGateway()

            updateStatus("Starting OpenClaw Control UI…")
            serverManager.startOpenClawControlUiServer()
        }

        // Step 8: Start web server
        updateStatus("Starting server…")
        val started = serverManager.startServer()
        if (!started) {
            throw RuntimeException("Failed to start server")
        }

        // Step 9: Wait for ready
        updateStatus("Waiting for server…")
        val ready = serverManager.waitForServer(timeoutMs = 90_000)
        if (!ready) {
            throw RuntimeException("Server did not start in time")
        }

        // Step 10: Show web UI
        runOnUiThread {
            showLoading(false)
            webView.visibility = View.VISIBLE
            permissionCenterButton.visibility = View.VISIBLE
            promptManagerButton.visibility = View.VISIBLE
            webView.loadUrl("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/")
        }
    }

    /**
     * Fallback: prompt for API key if browser login fails.
     */
    private fun requestApiKey(): String {
        var result = ""
        val lock = Object()

        runOnUiThread {
            val input = EditText(this).apply {
                hint = getString(R.string.api_key_hint)
                setSingleLine(true)
            }
            val padding = (24 * resources.displayMetrics.density).toInt()
            val container = android.widget.FrameLayout(this).apply {
                setPadding(padding, padding / 2, padding, 0)
                addView(input)
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.api_key_title)
                .setMessage(R.string.api_key_message)
                .setView(container)
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { _, _ ->
                    result = input.text.toString().trim()
                    synchronized(lock) { lock.notifyAll() }
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    synchronized(lock) { lock.notifyAll() }
                }
                .show()
        }

        synchronized(lock) {
            lock.wait(300_000)
        }
        return result
    }

    // ── UI helpers ──────────────────────────────────────────────────────────

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.retry) { _, _ ->
                setupStarted = false
                ensureStorageAccessOrStartSetup()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun ensureStorageAccessOrStartSetup() {
        if (hasStorageAccess()) {
            maybeRequestShizukuThenStartSetup()
        } else {
            showStoragePermissionDialog()
        }
    }

    private fun maybeRequestShizukuThenStartSetup() {
        if (setupStarted) return

        val installed = ShizukuController.isShizukuAppInstalled(this)
        if (!installed) {
            Toast.makeText(this, getString(R.string.shizuku_not_installed), Toast.LENGTH_LONG).show()
            startSetupFlow()
            return
        }

        val running = ShizukuController.isServiceRunning()
        if (!running) {
            Toast.makeText(this, getString(R.string.shizuku_service_not_running), Toast.LENGTH_LONG).show()
            startSetupFlow()
            return
        }

        if (ShizukuController.hasPermission()) {
            startSetupFlow()
            return
        }

        if (shizukuPermissionRequested) {
            startSetupFlow()
            return
        }

        shizukuPermissionRequested = true
        waitingForShizukuGrant = true
        Toast.makeText(this, getString(R.string.shizuku_permission_requesting), Toast.LENGTH_SHORT).show()
        ShizukuController.requestPermission { granted ->
            runOnUiThread {
                waitingForShizukuGrant = false
                val msg = if (granted) {
                    getString(R.string.shizuku_permission_granted)
                } else {
                    getString(R.string.shizuku_permission_denied)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                startSetupFlow()
            }
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            readGranted && writeGranted
        }
    }

    private fun requestStorageAccess() {
        waitingForStorageGrant = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                allFilesAccessLauncher.launch(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Falling back to generic all-files settings: ${e.message}")
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                allFilesAccessLauncher.launch(fallbackIntent)
            }
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    private fun showStoragePermissionDialog() {
        val message =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "AnyClaw needs full file access to read and write your shared storage (for example /sdcard). Grant \"All files access\" to continue."
            } else {
                "AnyClaw needs storage permission to read and write your shared storage (for example /sdcard). Grant permission to continue."
            }

        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Grant") { _, _ ->
                requestStorageAccess()
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()
            }
            .show()
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            permissionCenterButton.visibility = View.GONE
            promptManagerButton.visibility = View.GONE
        }
    }

    private fun setStatus(text: String, detail: String? = null) {
        statusText.text = text
        if (detail != null) {
            statusDetail.text = detail
            statusDetail.visibility = View.VISIBLE
        } else {
            statusDetail.visibility = View.GONE
        }
    }

    private fun updateStatus(text: String, detail: String? = null) {
        runOnUiThread { setStatus(text, detail) }
    }

    private fun updateDetail(text: String) {
        runOnUiThread {
            statusDetail.text = text
            statusDetail.visibility = View.VISIBLE
        }
    }

    private fun startShizukuBridgeServer() {
        try {
            val server = ShizukuShellBridgeServer(this)
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            shizukuBridgeServer = server
            Log.i(TAG, "Shizuku bridge server started on ${ShizukuShellBridgeServer.BRIDGE_PORT}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start Shizuku bridge server: ${e.message}")
        }
    }
}
