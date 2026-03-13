package com.codex.mobile

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
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
        const val EXTRA_OPEN_TARGET = "com.codex.mobile.extra.OPEN_TARGET"
        const val EXTRA_THREAD_ID = "com.codex.mobile.extra.THREAD_ID"
        const val EXTRA_SESSION_KEY = "com.codex.mobile.extra.SESSION_KEY"
        const val OPEN_TARGET_CODEX_THREAD = "codex_thread"
        const val OPEN_TARGET_OPENCLAW_SESSION = "openclaw_session"
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var permissionCenterButton: Button
    private lateinit var promptManagerButton: Button
    private lateinit var conversationManagerButton: Button
    private lateinit var modelManagerButton: Button
    private lateinit var gatewayToggleButton: Button
    private lateinit var backToCodexButton: Button
    private lateinit var openClawNewChatButton: Button
    private lateinit var serverManager: CodexServerManager
    private var shizukuBridgeServer: ShizukuShellBridgeServer? = null
    private var setupStarted = false
    private var waitingForStorageGrant = false
    private var waitingForShizukuGrant = false
    private var shizukuPermissionRequested = false
    private val gatewayStatusHandler = Handler(Looper.getMainLooper())
    private var gatewayStatusMonitorStarted = false
    private var gatewayConnected = false
    private var gatewayStatusChecking = false
    private var pendingLaunchUrl: String? = null
    private val openClawWatchdogHandler = Handler(Looper.getMainLooper())
    private var openClawWatchdogRunnable: Runnable? = null
    private var openClawRecoveryAttempts = 0
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraCaptureUri: Uri? = null
    private val gatewayStatusPollRunnable = object : Runnable {
        override fun run() {
            refreshGatewayStatusAsync(announce = false)
            val nextDelayMs = if (gatewayConnected) 7000L else 1800L
            gatewayStatusHandler.postDelayed(this, nextDelayMs)
        }
    }

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

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult
            val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            if (result.resultCode != RESULT_OK) {
                cleanupPendingCameraCapture()
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val cameraUri = pendingCameraCaptureUri
            pendingCameraCaptureUri = null
            val uris = when {
                parsed != null && parsed.isNotEmpty() -> parsed
                cameraUri != null -> arrayOf(cameraUri)
                else -> null
            }
            callback.onReceiveValue(uris)
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
        conversationManagerButton = findViewById(R.id.btnConversationManager)
        modelManagerButton = findViewById(R.id.btnModelManager)
        gatewayToggleButton = findViewById(R.id.btnGatewayToggle)
        backToCodexButton = findViewById(R.id.btnBackToCodex)
        openClawNewChatButton = findViewById(R.id.btnOpenClawNewChat)

        serverManager = CodexServerManager(this)

        permissionCenterButton.setOnClickListener {
            startActivity(Intent(this, PermissionManagerActivity::class.java))
        }
        promptManagerButton.setOnClickListener {
            startActivity(Intent(this, PromptManagerActivity::class.java))
        }
        conversationManagerButton.setOnClickListener {
            startActivity(Intent(this, ConversationManagerActivity::class.java))
        }
        modelManagerButton.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
        gatewayToggleButton.setOnClickListener {
            onGatewayTogglePressed()
        }
        backToCodexButton.setOnClickListener {
            webView.loadUrl("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/")
        }
        openClawNewChatButton.setOnClickListener {
            webView.loadUrl(buildOpenClawChatPageUrl(extractSessionFromCurrentUrl()))
        }

        requestBatteryOptimizationExemption()
        startForegroundService()
        startShizukuBridgeServer()
        setupWebView()
        pendingLaunchUrl = resolveLaunchUrlFromIntent(intent)
        ensureStorageAccessOrStartSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetUrl = resolveLaunchUrlFromIntent(intent) ?: return
        pendingLaunchUrl = targetUrl
        if (setupStarted && webView.visibility == View.VISIBLE) {
            webView.loadUrl(targetUrl)
            pendingLaunchUrl = null
        }
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
        if (setupStarted) {
            startGatewayStatusMonitor()
            val targetUrl = pendingLaunchUrl
            if (targetUrl != null && webView.visibility == View.VISIBLE) {
                webView.loadUrl(targetUrl)
                pendingLaunchUrl = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelOpenClawWatchdog()
        stopGatewayStatusMonitor()
        cleanupPendingCameraCapture()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String,
            ): Boolean = false

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!isOpenClawChatUrl(url)) {
                    openClawRecoveryAttempts = 0
                }
                cancelOpenClawWatchdog()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                scheduleOpenClawWatchdog(url)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(
                    TAG,
                    "WebView renderer gone didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}",
                )
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "页面渲染异常，正在自动恢复到可用状态",
                        Toast.LENGTH_LONG,
                    ).show()
                    pendingLaunchUrl = "http://127.0.0.1:${CodexServerManager.SERVER_PORT}/"
                    recreate()
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[WebView] ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}")
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false

                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                if (isCameraCaptureRequest(fileChooserParams)) {
                    val cameraIntent = createCameraCaptureIntent()
                    if (cameraIntent == null) {
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        Toast.makeText(
                            this@MainActivity,
                            "无法打开相机拍照",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return false
                    }
                    return try {
                        fileChooserLauncher.launch(cameraIntent)
                        true
                    } catch (error: Exception) {
                        Log.w(TAG, "Failed to launch camera chooser: ${error.message}")
                        cleanupPendingCameraCapture()
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        Toast.makeText(
                            this@MainActivity,
                            "无法启动拍照附件",
                            Toast.LENGTH_SHORT,
                        ).show()
                        false
                    }
                }

                val chooserIntent =
                    try {
                        fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Failed to create file chooser intent: ${error.message}")
                        null
                    }

                if (chooserIntent == null) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "无法打开附件选择器",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return false
                }

                return try {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to launch file chooser: ${error.message}")
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "无法启动附件选择器",
                        Toast.LENGTH_SHORT,
                    ).show()
                    false
                }
            }
        }
    }

    private fun isCameraCaptureRequest(params: WebChromeClient.FileChooserParams?): Boolean {
        if (params == null || !params.isCaptureEnabled) return false
        val accepts = params.acceptTypes
        if (accepts.isNullOrEmpty()) return true
        for (raw in accepts) {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) continue
            if (normalized == "image/*" || normalized == "*/*" || normalized.startsWith("image/")) {
                return true
            }
        }
        return false
    }

    private fun createCameraCaptureIntent(): Intent? {
        cleanupPendingCameraCapture()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pocketlobster_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketLobster")
            }
        }
        val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        pendingCameraCaptureUri = imageUri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun cleanupPendingCameraCapture() {
        val uri = pendingCameraCaptureUri ?: return
        pendingCameraCaptureUri = null
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun isOpenClawChatUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains(":${CodexServerManager.OPENCLAW_CONTROL_UI_PORT}") &&
            (url.contains("/chat") || url.contains("/index.html"))
    }

    private fun cancelOpenClawWatchdog() {
        val r = openClawWatchdogRunnable ?: return
        openClawWatchdogHandler.removeCallbacks(r)
        openClawWatchdogRunnable = null
    }

    private fun scheduleOpenClawWatchdog(url: String?) {
        cancelOpenClawWatchdog()
        if (!isOpenClawChatUrl(url)) return
        if (openClawRecoveryAttempts >= 2) return

        val runnable = Runnable {
            if (!isOpenClawChatUrl(webView.url)) return@Runnable
            val js =
                """
                (function(){
                  try{
                    var hasComposer=!!document.querySelector('textarea');
                    var bodyText=((document.body&&document.body.innerText)||'').slice(0,1200);
                    var stuck=/Loading chat|正在加载聊天/.test(bodyText);
                    return (hasComposer?'1':'0') + '|' + (stuck?'1':'0');
                  }catch(_){
                    return '0|0';
                  }
                })();
                """.trimIndent()
            webView.evaluateJavascript(js) { raw ->
                val normalized = (raw ?: "").replace("\"", "")
                val hasComposer = normalized.startsWith("1|")
                val stuck = normalized.endsWith("|1")
                if (!hasComposer && stuck) {
                    triggerOpenClawSafeRecovery(url)
                } else if (hasComposer) {
                    openClawRecoveryAttempts = 0
                }
            }
        }
        openClawWatchdogRunnable = runnable
        val delayMs = if (openClawRecoveryAttempts == 0) 9000L else 13000L
        openClawWatchdogHandler.postDelayed(runnable, delayMs)
    }

    private fun triggerOpenClawSafeRecovery(originalUrl: String?) {
        if (openClawRecoveryAttempts >= 2) return
        openClawRecoveryAttempts += 1
        Toast.makeText(this, "检测到聊天页卡住，正在自动降载恢复", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript(
            "(function(){try{localStorage.setItem('anyclaw.chat.history.limit','20');localStorage.setItem('anyclaw.chat.render.limit','20');}catch(_){}})();",
            null,
        )

        val fallback = "http://127.0.0.1:${CodexServerManager.OPENCLAW_CONTROL_UI_PORT}/chat"
        val parsed = Uri.parse(originalUrl ?: fallback)
        val builder = parsed.buildUpon().clearQuery()
        for (name in parsed.queryParameterNames) {
            if (name == "historyLimit" || name == "recovery") continue
            for (value in parsed.getQueryParameters(name)) {
                builder.appendQueryParameter(name, value)
            }
        }
        builder.appendQueryParameter("historyLimit", "20")
        builder.appendQueryParameter("recovery", openClawRecoveryAttempts.toString())
        webView.loadUrl(builder.build().toString())
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
                try {
                    serverManager.reportBootstrapFailure("core.setup", e.message ?: "unknown")
                } catch (_: Exception) {
                }
                runOnUiThread {
                    showError(e.message ?: "Unknown error")
                }
            }
        }.start()
    }

    private fun runSetup() {
        checkpoint("core.bootstrap", "started", "setup-begin")

        // Step 1: Extract bootstrap
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateStatus("Extracting environment…")
            BootstrapInstaller.install(this) { msg -> updateStatus(msg) }
        }
        updateStatus("Environment ready")
        checkpoint("core.bootstrap", "ok", "bootstrap-ready")

        // Step 2: Core runtime only (must stay small and deterministic)
        if (!serverManager.isNodeInstalled()) {
            updateStatus("Installing Node.js (first run)…", "This may take a few minutes")
            val nodeOk = serverManager.installNode { msg -> updateDetail(msg) }
            if (!nodeOk) {
                throw RuntimeException("Failed to install Node.js")
            }
        }
        updateStatus("Node.js ready")
        checkpoint("core.node", "ok", "node-ready")

        // Step 3: Install Codex CLI
        if (!serverManager.isCodexInstalled()) {
            updateStatus("Installing Codex CLI…", "This may take a few minutes")
            val codexOk = serverManager.installCodex { msg -> updateDetail(msg) }
            if (!codexOk) {
                throw RuntimeException("Failed to install Codex")
            }
        }
        checkpoint("core.codex", "ok", "codex-ready")

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
        checkpoint("core.platform", "ok", "platform-ready")

        // Step 3c: Write full-access config, create default workspace, and bridge shared storage paths
        serverManager.ensureFullAccessConfig()
        serverManager.ensureDefaultWorkspace()
        serverManager.ensureStorageBridge()
        serverManager.ensureShizukuBridgeScripts()
        PromptProfileStore.ensureSynced(this)

        // Step 4: Start CONNECT proxy (needed for native binary DNS/TLS)
        updateStatus("Starting network proxy…")
        if (!serverManager.startProxy()) {
            Log.w(TAG, "CONNECT proxy failed to start; continuing in reduced mode")
            updateStatus("Network proxy unavailable", "Continuing in reduced mode")
        }

        // Step 5: Codex auth is optional. Do not block startup for OpenClaw-only users.
        updateStatus("Checking Codex authentication…")
        val codexLoggedIn = serverManager.isLoggedIn()
        if (!codexLoggedIn) {
            updateStatus("Codex not logged in", "Continuing in OpenClaw mode")
        } else {
            // Keep startup fast and non-blocking even when Codex network is unavailable.
            updateStatus("Codex authenticated")
        }

        // Step 6: Start web server first, independent from OpenClaw enhancement path.
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
        checkpoint("core.server", "ok", "server-ready")

        // Step 7: Start enhancement asynchronously before showing UI to shave initial gateway delay.
        startEnhancedSetupFlow()

        // Step 8: Show web UI immediately (baseline available even if enhancement fails).
        runOnUiThread {
            showLoading(false)
            webView.visibility = View.VISIBLE
            permissionCenterButton.visibility = View.VISIBLE
            promptManagerButton.visibility = View.VISIBLE
            conversationManagerButton.visibility = View.VISIBLE
            modelManagerButton.visibility = View.VISIBLE
            gatewayToggleButton.visibility = View.VISIBLE
            backToCodexButton.visibility = View.VISIBLE
            openClawNewChatButton.visibility = View.VISIBLE
            applyGatewayConnectedState(false, announce = false)
            startGatewayStatusMonitor()
            webView.loadUrl(consumeLaunchUrlOrDefault())
        }
        checkpoint("core.ui", "ok", "ui-ready")
    }

    private fun startOpenClawServicesAsync() {
        if (!serverManager.isOpenClawInstalled()) return
        Thread {
            startOpenClawServicesSync()
            runOnUiThread {
                if (webView.visibility == View.VISIBLE) {
                    refreshGatewayStatusAsync(announce = true)
                }
            }
        }.start()
    }

    private fun startEnhancedSetupFlow() {
        Thread {
            checkpoint("enhanced", "started", "openclaw-and-tools")
            try {
                var gatewayOk = false
                val openClawPresentAtStart = serverManager.isOpenClawInstalled()
                val heavyNeeded = !serverManager.canSkipHeavyEnhancedBootstrap()
                checkpoint("enhanced.plan", "ok", if (heavyNeeded) "heavy-needed" else "heavy-skip-frozen-state")
                if (openClawPresentAtStart) {
                    // Existing installs should keep gateway availability fast.
                    gatewayOk = startOpenClawServicesFastPath()
                    checkpoint(
                        "enhanced.gateway.fastpath",
                        if (gatewayOk) "ok" else "failed",
                        if (gatewayOk) "gateway-ready" else "gateway-fastpath-failed",
                    )
                    if (gatewayOk) {
                        runOnUiThread {
                            if (webView.visibility == View.VISIBLE) {
                                refreshGatewayStatusAsync(announce = false)
                            }
                        }
                    }
                }

                // Keep local recovery scripts/wrappers present on every boot.
                serverManager.ensurePackageRecoveryScripts()
                serverManager.ensurePackageManagerWrappers()
                checkpoint("enhanced.pkg", "ok", "recovery-assets-ready")

                if (heavyNeeded) {
                    // Network-sensitive chain is only executed when frozen state is stale/missing.
                    serverManager.ensureAptTrustChain()
                    checkpoint("enhanced.apt", "ok", "apt-trust-chain-ready")

                    if (!serverManager.isProotInstalled()) {
                        updateStatus("Installing proot…", "Needed for package management")
                        val prootOk = serverManager.installProot { msg -> updateDetail(msg) }
                        if (!prootOk) {
                            throw RuntimeException("Failed to install proot")
                        }
                    }
                    checkpoint("enhanced.proot", "ok", "proot-ready")

                    if (!serverManager.isPythonInstalled()) {
                        updateStatus("Installing Python…")
                        val pyOk = serverManager.installPython { msg -> updateDetail(msg) }
                        if (!pyOk) {
                            Log.w(TAG, "Python install failed — continuing without it")
                        }
                    }
                    serverManager.ensureBionicCompat()
                    checkpoint("enhanced.runtime", "ok", "python-bionic-ready")
                } else {
                    checkpoint("enhanced.runtime", "ok", "reuse-frozen-enhanced-state")
                }

                if (!serverManager.isOpenClawInstalled()) {
                    updateStatus("Installing build dependencies…")
                    serverManager.installOpenClawDeps { msg -> updateDetail(msg) }

                    updateStatus("Installing OpenClaw…", "This may take several minutes")
                    val openclawOk = serverManager.installOpenClaw { msg -> updateDetail(msg) }
                    if (!openclawOk) {
                        checkpoint("enhanced.openclaw.install", "failed", "openclaw-install-failed")
                    } else {
                        updateStatus("OpenClaw installed")
                        checkpoint("enhanced.openclaw.install", "ok", "openclaw-installed")
                    }
                } else {
                    checkpoint("enhanced.openclaw.install", "ok", "already-installed")
                }

                if (!serverManager.isOpenClawInstalled()) {
                    checkpoint("enhanced.gateway", "failed", "openclaw-not-installed")
                } else {
                    if (heavyNeeded) {
                        updateStatus("Checking OpenClaw version…")
                        val versionOk = serverManager.ensureOpenClawVersion { msg -> updateDetail(msg) }
                        if (!versionOk) {
                            Log.w(TAG, "OpenClaw version alignment failed — continuing with current install")
                        }
                        checkpoint(
                            "enhanced.openclaw.version",
                            if (versionOk) "ok" else "warn",
                            if (versionOk) "version-aligned" else "version-align-failed",
                        )
                    }

                    if (!gatewayOk) {
                        gatewayOk = startOpenClawServicesSync()
                    }
                    checkpoint(
                        "enhanced.gateway",
                        if (gatewayOk) "ok" else "failed",
                        if (gatewayOk) "gateway-ready" else "gateway-start-failed",
                    )
                    serverManager.markEnhancedBootstrapReady(
                        gatewayReady = gatewayOk,
                        detail = if (heavyNeeded) "heavy-run" else "frozen-reuse",
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Enhanced setup failed", error)
                checkpoint("enhanced", "failed", error.message ?: "enhanced-setup-failed")
            } finally {
                runOnUiThread {
                    if (webView.visibility == View.VISIBLE) {
                        refreshGatewayStatusAsync(announce = true)
                    }
                }
            }
        }.start()
    }

    private fun startOpenClawServicesFastPath(): Boolean {
        if (!serverManager.isOpenClawInstalled()) return false
        return try {
            if (serverManager.isOpenClawGatewayResponsive()) {
                serverManager.startOpenClawControlUiServer()
                return true
            }

            serverManager.configureOpenClawAuth()
            var gatewayOk = serverManager.startOpenClawGateway()
            if (!gatewayOk) {
                Thread.sleep(650)
                gatewayOk = serverManager.startOpenClawGateway()
            }
            serverManager.startOpenClawControlUiServer()
            gatewayOk
        } catch (error: Exception) {
            Log.w(TAG, "OpenClaw fast path failed: ${error.message}")
            false
        }
    }

    private fun startOpenClawServicesSync(): Boolean {
        if (!serverManager.isOpenClawInstalled()) return false
        return try {
            updateStatus("Running OpenClaw preflight…")
            serverManager.runOpenClawPreflight { msg -> updateDetail(msg) }

            updateStatus("Verifying OpenClaw runtime…")
            val runtimeOk = serverManager.ensureOpenClawRuntimeReady { msg -> updateDetail(msg) }
            if (!runtimeOk) {
                Log.w(TAG, "OpenClaw runtime readiness check failed")
                return false
            }

            updateStatus("Configuring OpenClaw…")
            serverManager.configureOpenClawAuth()

            updateStatus("Starting OpenClaw gateway…")
            var gatewayOk = serverManager.startOpenClawGateway()
            if (!gatewayOk) {
                Log.w(TAG, "OpenClaw gateway did not become responsive on first attempt; retrying once")
                updateDetail("Gateway retrying once…")
                Thread.sleep(1200)
                gatewayOk = serverManager.startOpenClawGateway()
            }

            updateStatus("Starting OpenClaw Control UI…")
            serverManager.startOpenClawControlUiServer()
            gatewayOk
        } catch (error: Exception) {
            Log.e(TAG, "OpenClaw startup failed", error)
            false
        }
    }

    private fun checkpoint(stage: String, status: String, detail: String) {
        try {
            serverManager.reportBootstrapCheckpoint(stage, status, detail)
        } catch (error: Exception) {
            Log.w(TAG, "checkpoint failed for $stage/$status: ${error.message}")
        }
    }

    private fun consumeLaunchUrlOrDefault(): String {
        val target = pendingLaunchUrl
        pendingLaunchUrl = null
        return target ?: "http://127.0.0.1:${CodexServerManager.SERVER_PORT}/"
    }

    private fun resolveLaunchUrlFromIntent(intent: Intent?): String? {
        val target = intent?.getStringExtra(EXTRA_OPEN_TARGET)?.trim().orEmpty()
        if (target.isEmpty()) return null
        return when (target) {
            OPEN_TARGET_CODEX_THREAD -> {
                val threadId = intent?.getStringExtra(EXTRA_THREAD_ID)?.trim().orEmpty()
                if (threadId.isEmpty()) null
                else "http://127.0.0.1:${CodexServerManager.SERVER_PORT}/thread/${Uri.encode(threadId)}"
            }
            OPEN_TARGET_OPENCLAW_SESSION -> {
                val sessionKey = intent?.getStringExtra(EXTRA_SESSION_KEY)?.trim().orEmpty()
                buildOpenClawChatPageUrl(sessionKey)
            }
            else -> null
        }
    }

    private fun extractSessionFromCurrentUrl(): String? {
        val current = webView.url ?: return null
        return try {
            Uri.parse(current).getQueryParameter("session")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildOpenClawChatPageUrl(sessionKey: String?): String {
        val builder = Uri.parse("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/openclaw/chat").buildUpon()
        val normalized = sessionKey?.trim().orEmpty()
        if (normalized.isNotEmpty()) {
            builder.appendQueryParameter("session", normalized)
        }
        return builder.build().toString()
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
                getString(R.string.storage_permission_required_message_android_r)
            } else {
                getString(R.string.storage_permission_required_message_legacy)
            }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.storage_permission_required_title))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.storage_permission_grant)) { _, _ ->
                requestStorageAccess()
            }
            .setNegativeButton(getString(R.string.storage_permission_exit)) { _, _ ->
                finish()
            }
            .show()
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            permissionCenterButton.visibility = View.GONE
            promptManagerButton.visibility = View.GONE
            conversationManagerButton.visibility = View.GONE
            modelManagerButton.visibility = View.GONE
            gatewayToggleButton.visibility = View.GONE
            backToCodexButton.visibility = View.GONE
            openClawNewChatButton.visibility = View.GONE
        }
    }

    private fun onGatewayTogglePressed() {
        gatewayToggleButton.isEnabled = false
        Thread {
            try {
                if (gatewayConnected) {
                    val ok = serverManager.disconnectOpenClawGateway()
                    runOnUiThread {
                        val msg = if (ok) {
                            getString(R.string.gateway_toggle_disconnected_toast)
                        } else {
                            getString(R.string.gateway_toggle_disconnect_failed)
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        applyGatewayConnectedState(false, announce = true)
                        gatewayToggleButton.isEnabled = true
                        refreshGatewayStatusAsync(announce = true)
                    }
                } else {
                    val ok = serverManager.reconnectOpenClawGateway()
                    runOnUiThread {
                        val msg = if (ok) {
                            getString(R.string.gateway_toggle_connected_toast)
                        } else {
                            getString(R.string.gateway_toggle_connect_failed)
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (ok) {
                            webView.postDelayed({ webView.reload() }, 800)
                        }
                        gatewayToggleButton.isEnabled = true
                        refreshGatewayStatusAsync(announce = true)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    gatewayToggleButton.isEnabled = true
                    Toast.makeText(
                        this,
                        getString(R.string.gateway_toggle_connect_failed) + " " + (error.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun startGatewayStatusMonitor() {
        if (gatewayStatusMonitorStarted) return
        gatewayStatusMonitorStarted = true
        gatewayStatusHandler.post(gatewayStatusPollRunnable)
    }

    private fun stopGatewayStatusMonitor() {
        gatewayStatusMonitorStarted = false
        gatewayStatusHandler.removeCallbacks(gatewayStatusPollRunnable)
    }

    private fun refreshGatewayStatusAsync(announce: Boolean) {
        if (gatewayStatusChecking) return
        gatewayStatusChecking = true
        Thread {
            val connected = serverManager.isOpenClawGatewayResponsive()
            runOnUiThread {
                gatewayStatusChecking = false
                applyGatewayConnectedState(connected, announce)
            }
        }.start()
    }

    private fun applyGatewayConnectedState(connected: Boolean, announce: Boolean) {
        val changed = connected != gatewayConnected
        gatewayConnected = connected
        if (connected) {
            gatewayToggleButton.text = getString(R.string.gateway_toggle_button_connected)
            gatewayToggleButton.contentDescription = getString(R.string.gateway_toggle_button_desc_connected)
        } else {
            gatewayToggleButton.text = getString(R.string.gateway_toggle_button_disconnected)
            gatewayToggleButton.contentDescription = getString(R.string.gateway_toggle_button_desc_disconnected)
        }
        if ((changed || announce) && gatewayToggleButton.visibility == View.VISIBLE) {
            gatewayToggleButton.announceForAccessibility(gatewayToggleButton.contentDescription)
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
