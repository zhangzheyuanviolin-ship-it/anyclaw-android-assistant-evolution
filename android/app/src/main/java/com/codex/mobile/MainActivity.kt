package com.codex.mobile

import android.Manifest
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
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CodexMainActivity"
        const val EXTRA_OPEN_TARGET = "com.codex.mobile.extra.OPEN_TARGET"
        const val EXTRA_THREAD_ID = "com.codex.mobile.extra.THREAD_ID"
        const val EXTRA_SESSION_KEY = "com.codex.mobile.extra.SESSION_KEY"
        const val OPEN_TARGET_CODEX_HOME = "codex_home"
        const val OPEN_TARGET_CODEX_THREAD = "codex_thread"
        const val OPEN_TARGET_OPENCLAW_SESSION = "openclaw_session"
        const val OPEN_TARGET_CLAUDE_SESSION = "claude_session"
        const val OPEN_TARGET_HERMES_SESSION = "hermes_session"
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
    private lateinit var bottomAgentTabs: View
    private lateinit var tabOpenClawButton: Button
    private lateinit var tabCodexButton: Button
    private lateinit var tabClaudeButton: Button
    private lateinit var tabHermesButton: Button
    private lateinit var serverManager: CodexServerManager
    private var setupStarted = false
    private var waitingForStorageGrant = false
    private var waitingForShizukuGrant = false
    private var shizukuPermissionRequested = false
    private val gatewayStatusHandler = Handler(Looper.getMainLooper())
    private var gatewayStatusMonitorStarted = false
    private var gatewayConnected = false
    private var gatewayStatusChecking = false
    private var currentUiTarget: String = OPEN_TARGET_CODEX_HOME
    private var pendingLaunchUrl: String? = null
    private val openClawWatchdogHandler = Handler(Looper.getMainLooper())
    private var openClawWatchdogRunnable: Runnable? = null
    private var openClawRecoveryAttempts = 0
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraImageUri: Uri? = null
    private val gatewayStatusPollRunnable = object : Runnable {
        override fun run() {
            refreshGatewayStatusAsync(announce = false)
            gatewayStatusHandler.postDelayed(this, 7000)
        }
    }

    private fun isOpenClawLightweightOnlyMode(): Boolean {
        return false
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
            val cameraUri = pendingCameraImageUri
            pendingCameraImageUri = null
            if (cameraUri != null) {
                if (result.resultCode == RESULT_OK) {
                    callback.onReceiveValue(arrayOf(cameraUri))
                } else {
                    callback.onReceiveValue(null)
                }
                return@registerForActivityResult
            }
            val uris = collectFileChooserUris(result.resultCode, result.data)
            callback.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasExplicitTargetIntent(intent) && BootstrapInstaller.isBootstrapInstalled(this)) {
            startActivity(Intent(this, AgentHubActivity::class.java))
            finish()
            return
        }
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
        bottomAgentTabs = findViewById(R.id.bottomAgentTabs)
        tabOpenClawButton = findViewById(R.id.btnTabOpenClaw)
        tabCodexButton = findViewById(R.id.btnTabCodex)
        tabClaudeButton = findViewById(R.id.btnTabClaude)
        tabHermesButton = findViewById(R.id.btnTabHermes)

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
            currentUiTarget = OPEN_TARGET_CODEX_HOME
            webView.loadUrl("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/")
            updateUiForCurrentTarget()
        }
        openClawNewChatButton.setOnClickListener {
            currentUiTarget = OPEN_TARGET_OPENCLAW_SESSION
            webView.loadUrl(buildOpenClawChatPageUrl(null))
            updateUiForCurrentTarget()
        }
        tabOpenClawButton.setOnClickListener {
            currentUiTarget = OPEN_TARGET_OPENCLAW_SESSION
            val sessionKey = extractSessionFromCurrentUrl()
            webView.loadUrl(buildOpenClawChatPageUrl(sessionKey))
            updateUiForCurrentTarget()
        }
        tabCodexButton.setOnClickListener {
            currentUiTarget = OPEN_TARGET_CODEX_HOME
            webView.loadUrl("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/")
            updateUiForCurrentTarget()
        }
        tabClaudeButton.setOnClickListener {
            startActivity(
                Intent(this, CliAgentChatActivity::class.java).apply {
                    putExtra(CliAgentChatActivity.EXTRA_AGENT_ID, ExternalAgentId.CLAUDE_CODE.value)
                },
            )
        }
        tabHermesButton.setOnClickListener {
            startActivity(
                Intent(this, CliAgentChatActivity::class.java).apply {
                    putExtra(CliAgentChatActivity.EXTRA_AGENT_ID, ExternalAgentId.HERMES_AGENT.value)
                },
            )
        }

        requestBatteryOptimizationExemption()
        startForegroundService()
        startShizukuBridgeServer()
        setupWebView()
        if (redirectToExternalAgentIfNeeded(intent)) {
            return
        }
        pendingLaunchUrl = resolveLaunchUrlFromIntent(intent)
        ensureStorageAccessOrStartSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (redirectToExternalAgentIfNeeded(intent)) {
            return
        }
        val targetUrl = resolveLaunchUrlFromIntent(intent) ?: return
        pendingLaunchUrl = targetUrl
        currentUiTarget = when {
            isOpenClawChatUrl(targetUrl) -> OPEN_TARGET_OPENCLAW_SESSION
            isClaudeChatUrl(targetUrl) -> OPEN_TARGET_CLAUDE_SESSION
            else -> OPEN_TARGET_CODEX_HOME
        }
        if (setupStarted && webView.visibility == View.VISIBLE) {
            webView.loadUrl(targetUrl)
            pendingLaunchUrl = null
        }
    }

    override fun onResume() {
        super.onResume()
        startShizukuBridgeServer()
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
            updateUiForCurrentTarget()
            val targetUrl = pendingLaunchUrl
            if (targetUrl != null && webView.visibility == View.VISIBLE) {
                currentUiTarget = when {
                    isOpenClawChatUrl(targetUrl) -> OPEN_TARGET_OPENCLAW_SESSION
                    isClaudeChatUrl(targetUrl) -> OPEN_TARGET_CLAUDE_SESSION
                    else -> OPEN_TARGET_CODEX_HOME
                }
                webView.loadUrl(targetUrl)
                pendingLaunchUrl = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelOpenClawWatchdog()
        stopGatewayStatusMonitor()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
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
                currentUiTarget = if (isOpenClawChatUrl(url)) {
                    OPEN_TARGET_OPENCLAW_SESSION
                } else if (isClaudeChatUrl(url)) {
                    OPEN_TARGET_CLAUDE_SESSION
                } else {
                    OPEN_TARGET_CODEX_HOME
                }
                updateUiForCurrentTarget()
                if (!isOpenClawChatUrl(url)) {
                    openClawRecoveryAttempts = 0
                }
                cancelOpenClawWatchdog()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                updateUiForCurrentTarget()
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
                pendingCameraImageUri = null

                if (shouldUseCameraCapture(fileChooserParams)) {
                    val cameraPair = buildCameraCaptureIntent()
                    if (cameraPair != null) {
                        pendingCameraImageUri = cameraPair.second
                        return try {
                            fileChooserLauncher.launch(cameraPair.first)
                            true
                        } catch (error: Exception) {
                            Log.w(TAG, "Failed to launch camera chooser: ${error.message}")
                            pendingCameraImageUri = null
                            this@MainActivity.filePathCallback?.onReceiveValue(null)
                            this@MainActivity.filePathCallback = null
                            Toast.makeText(
                                this@MainActivity,
                                "无法启动相机",
                                Toast.LENGTH_SHORT,
                            ).show()
                            false
                        }
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

                val allowMultiple =
                    fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                chooserIntent?.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                chooserIntent?.addCategory(Intent.CATEGORY_OPENABLE)
                if (chooserIntent != null && chooserIntent.type.isNullOrBlank()) {
                    chooserIntent.type = "*/*"
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

    private fun collectFileChooserUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK) return null
        val ordered = LinkedHashSet<Uri>()
        val parsed = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        parsed?.forEach { uri ->
            if (uri != null) ordered.add(uri)
        }
        data?.data?.let { ordered.add(it) }
        data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let { ordered.add(it) }
            }
        }
        return if (ordered.isEmpty()) null else ordered.toTypedArray()
    }

    private fun shouldUseCameraCapture(fileChooserParams: WebChromeClient.FileChooserParams?): Boolean {
        if (fileChooserParams == null) return false
        if (!fileChooserParams.isCaptureEnabled) return false
        val acceptsImage = fileChooserParams.acceptTypes
            ?.mapNotNull { it?.trim()?.lowercase() }
            ?.any { value ->
                value == "image/*" || value.startsWith("image/")
            } == true
        return acceptsImage
    }

    private fun buildCameraCaptureIntent(): Pair<Intent, Uri>? {
        return try {
            val attachmentsDir = File(cacheDir, "attachments")
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }
            val targetFile = File.createTempFile("openclaw_capture_", ".jpg", attachmentsDir)
            val authority = "$packageName.fileprovider"
            val captureUri = FileProvider.getUriForFile(this, authority, targetFile)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (activities.isEmpty()) {
                null
            } else {
                for (resolveInfo in activities) {
                    grantUriPermission(
                        resolveInfo.activityInfo.packageName,
                        captureUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                intent to captureUri
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to prepare camera capture intent: ${error.message}")
            null
        }
    }

    private fun isOpenClawChatUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val isLegacyControlUi = isLegacyOpenClawControlUiUrl(url)
        val isNewChatPage =
            url.contains(":${CodexServerManager.SERVER_PORT}") &&
                url.contains("/openclaw/chat")
        return isLegacyControlUi || isNewChatPage
    }

    private fun isLegacyOpenClawControlUiUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val isLegacyControlUi =
            url.contains(":${CodexServerManager.OPENCLAW_CONTROL_UI_PORT}") &&
                (url.contains("/chat") || url.contains("/index.html"))
        return isLegacyControlUi
    }

    private fun cancelOpenClawWatchdog() {
        val r = openClawWatchdogRunnable ?: return
        openClawWatchdogHandler.removeCallbacks(r)
        openClawWatchdogRunnable = null
    }

    private fun scheduleOpenClawWatchdog(url: String?) {
        cancelOpenClawWatchdog()
        if (!isLegacyOpenClawControlUiUrl(url)) return
        if (openClawRecoveryAttempts >= 2) return

        val runnable = Runnable {
            if (!isLegacyOpenClawControlUiUrl(webView.url)) return@Runnable
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
                runOnUiThread {
                    showError(e.message ?: "Unknown error")
                }
            }
        }.start()
    }

    private fun runSetup() {
        val explicitTarget = hasExplicitTargetIntent(intent)
        val explicitOpenClawTarget = isOpenClawTargetIntent(intent)
        if (explicitTarget) {
            updateStatus("Checking local server…")
            val warmReady = serverManager.waitForServer(timeoutMs = 3500)
            if (warmReady) {
                runOnUiThread {
                    showReadyUi(explicitTarget = true)
                }
                return
            }
            if (explicitOpenClawTarget && isOpenClawLightweightOnlyMode()) {
                val quickStarted = runOpenClawLightweightQuickStart()
                if (quickStarted) {
                    runOnUiThread {
                        showReadyUi(explicitTarget = true)
                    }
                    return
                }
                throw RuntimeException("OpenClaw local service did not start in lightweight mode")
            }
        }

        val hadOpenClawAtStart = serverManager.isOpenClawInstalled()

        // Step 1: Extract bootstrap
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateStatus("Extracting environment…")
            BootstrapInstaller.install(this) { msg -> updateStatus(msg) }
        }
        updateStatus("Environment ready")

        // Step 1a: Ubuntu-first runtime bootstrap (primary terminal baseline).
        // Keep this early so all later OpenClaw/plugin operations can assume the
        // bundled Linux runtime exists and avoid late-stage environment drift.
        updateStatus("Installing Linux runtime…", "Preparing bundled Ubuntu terminal")
        val linuxRuntimeOk = OfflineLinuxRuntimeInstaller.install(this) { msg -> updateDetail(msg) }
        if (!linuxRuntimeOk) {
            throw RuntimeException("Failed to install bundled Linux runtime")
        }
        updateStatus("Linux runtime ready")

        // Step 1b: Install proot (needed for dpkg/apt-get path remapping)
        if (!serverManager.isProotInstalled()) {
            updateStatus("Installing proot…", "Needed for package management")
            val prootOk = serverManager.installProot { msg -> updateDetail(msg) }
            if (!prootOk) {
                throw RuntimeException("Failed to install proot")
            }
        }
        updateStatus("proot ready")

        // Step 1c: Repair apt trust chain, then prepare resilient package manager scripts/wrappers.
        updateStatus("Repairing package manager…")
        serverManager.ensureAptTrustChain()
        updateStatus("Preparing package recovery…")
        serverManager.ensurePackageRecoveryScripts()
        updateStatus("Preparing package wrappers…")
        serverManager.ensurePackageManagerWrappers()
        updateStatus("Package manager ready")

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

        // Step 2d: Install OpenClaw from offline runtime assets
        var openClawAvailable = serverManager.isOpenClawInstalled()
        if (!openClawAvailable) {
            updateStatus("Installing OpenClaw (offline runtime)…", "Using bundled runtime assets")
            val offlineOk = OfflineOpenClawRuntimeInstaller.install(this) { msg -> updateDetail(msg) }
            if (!offlineOk) {
                throw RuntimeException("Failed to install bundled OpenClaw runtime")
            }

            updateStatus("Preparing OpenClaw runtime…")
            val prepared = serverManager.prepareOfflineOpenClawRuntime { msg -> updateDetail(msg) }
            if (!prepared) {
                throw RuntimeException("Failed to prepare bundled OpenClaw runtime")
            }
            openClawAvailable = serverManager.isOpenClawInstalled()
        }
        updateStatus("OpenClaw runtime ready")

        if (openClawAvailable) {
            updateStatus("Checking OpenClaw version…")
            val versionOk = serverManager.ensureOpenClawVersion { msg -> updateDetail(msg) }
            if (!versionOk) {
                throw RuntimeException("Failed to align bundled OpenClaw runtime")
            }
            if (openClawAvailable) {
                updateStatus("Validating OpenClaw runtime…")
                val runtimeReady = serverManager.ensureOpenClawRuntimeReady { msg -> updateDetail(msg) }
                if (!runtimeReady) {
                    throw RuntimeException("Bundled OpenClaw runtime failed validation")
                } else {
                    updateStatus("OpenClaw runtime ready")
                }
            }
        }

        // Step 3: Codex is optional on first run. Do not block OpenClaw-only users.
        val codexCliInstalled = serverManager.isCodexInstalled()
        if (codexCliInstalled) {
            serverManager.ensureCodexWrapperScript()
        } else {
            updateStatus("Skipping optional Codex CLI install", "You can install it later in Permission Center")
        }

        // Step 3a: Extract web UI from APK assets (every launch)
        updateStatus("Updating web UI…")
        serverManager.installServerBundle { msg -> updateDetail(msg) }

        // Step 3b: Codex platform binary is also optional and can be installed later.
        val codexBinaryInstalled = serverManager.isPlatformBinaryInstalled()
        when {
            codexCliInstalled && codexBinaryInstalled -> updateStatus("Codex ready")
            codexCliInstalled -> updateStatus("Codex native binary not installed", "You can complete it later in Permission Center")
            else -> updateStatus("Codex optional install skipped", "OpenClaw mode is ready")
        }

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
        if (codexCliInstalled && codexBinaryInstalled) {
            updateStatus("Checking Codex authentication…")
            val codexLoggedIn = serverManager.isLoggedIn()
            if (!codexLoggedIn) {
                updateStatus("Codex not logged in", "Continuing in OpenClaw mode")
            } else {
                // Keep startup fast and non-blocking even when Codex network is unavailable.
                updateStatus("Codex authenticated")
            }
        } else {
            updateStatus("Codex not installed", "Continuing in OpenClaw mode")
        }

        // Step 7: Prepare OpenClaw local runtime and force-disconnect gateway to
        // avoid session lock contention in native chat mode.
        if (openClawAvailable && !isOpenClawLightweightOnlyMode()) {
            val isFreshOpenClawInstall = !hadOpenClawAtStart
            if (isFreshOpenClawInstall) {
                updateStatus("Finalizing OpenClaw…", "Preparing gateway runtime")
            } else {
                updateStatus("Refreshing OpenClaw runtime…", "Switching to gateway mode")
            }
            startOpenClawServicesSync()
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
        runOnUiThread { showReadyUi(explicitTarget) }
    }

    private fun showReadyUi(explicitTarget: Boolean) {
        showLoading(false)
        webView.visibility = View.VISIBLE
        permissionCenterButton.visibility = View.VISIBLE
        promptManagerButton.visibility = View.VISIBLE
        conversationManagerButton.visibility = View.VISIBLE
        modelManagerButton.visibility = View.VISIBLE
        bottomAgentTabs.visibility = View.VISIBLE
        applyGatewayConnectedState(false, announce = false)
        if (!explicitTarget) {
            startActivity(Intent(this, AgentHubActivity::class.java))
            finish()
            return
        }
        val launchUrl = consumeLaunchUrlOrDefault()
        currentUiTarget = when {
            isOpenClawChatUrl(launchUrl) -> OPEN_TARGET_OPENCLAW_SESSION
            isClaudeChatUrl(launchUrl) -> OPEN_TARGET_CLAUDE_SESSION
            else -> OPEN_TARGET_CODEX_HOME
        }
        updateUiForCurrentTarget()
        webView.loadUrl(launchUrl)
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

    private fun startOpenClawServicesSync(): Boolean {
        if (!serverManager.isOpenClawInstalled()) return false
        return try {
            updateStatus("Running OpenClaw preflight…")
            serverManager.runOpenClawPreflight { msg -> updateDetail(msg) }

            updateStatus("Configuring OpenClaw…")
            serverManager.configureOpenClawAuth()

            updateStatus("Starting OpenClaw gateway…", "Using gateway + Web chat mode")
            serverManager.reconnectOpenClawGateway()
        } catch (error: Exception) {
            Log.e(TAG, "OpenClaw startup failed", error)
            false
        }
    }

    private fun runOpenClawLightweightQuickStart(): Boolean {
        return try {
            updateStatus("Updating web UI…")
            serverManager.installServerBundle { msg -> updateDetail(msg) }

            for (attempt in 0 until 2) {
                updateStatus("Starting server…")
                val started = serverManager.startServer()
                if (!started) {
                    updateDetail("Server start attempt ${attempt + 1} failed")
                    Thread.sleep(350L + attempt * 250L)
                    continue
                }

                updateStatus("Waiting for server…")
                val ready = serverManager.waitForServer(
                    timeoutMs = if (attempt == 0) 30_000 else 45_000,
                )
                if (ready) {
                    return true
                }
                updateDetail("Local server not ready after attempt ${attempt + 1}, retrying…")
                Thread.sleep(400L + attempt * 350L)
            }
            false
        } catch (error: Exception) {
            Log.w(TAG, "OpenClaw lightweight quick start failed: ${error.message}")
            false
        }
    }

    private fun isOpenClawTargetIntent(intent: Intent?): Boolean {
        val target = intent?.getStringExtra(EXTRA_OPEN_TARGET)?.trim().orEmpty()
        return target == OPEN_TARGET_OPENCLAW_SESSION
    }

    private fun redirectToExternalAgentIfNeeded(intent: Intent?): Boolean {
        val target = intent?.getStringExtra(EXTRA_OPEN_TARGET)?.trim().orEmpty()
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_KEY)?.trim().orEmpty()
        when (target) {
            OPEN_TARGET_CLAUDE_SESSION -> {
                startActivity(
                    Intent(this, CliAgentChatActivity::class.java).apply {
                        putExtra(CliAgentChatActivity.EXTRA_AGENT_ID, ExternalAgentId.CLAUDE_CODE.value)
                        if (sessionId.isNotEmpty()) {
                            putExtra(CliAgentChatActivity.EXTRA_SESSION_ID, sessionId)
                        }
                    },
                )
            }
            OPEN_TARGET_HERMES_SESSION -> {
                startActivity(
                    Intent(this, CliAgentChatActivity::class.java).apply {
                        putExtra(CliAgentChatActivity.EXTRA_AGENT_ID, ExternalAgentId.HERMES_AGENT.value)
                        if (sessionId.isNotEmpty()) {
                            putExtra(CliAgentChatActivity.EXTRA_SESSION_ID, sessionId)
                        }
                    },
                )
            }
            else -> return false
        }
        finish()
        return true
    }

    private fun extractSessionFromUrl(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            Uri.parse(raw).getQueryParameter("session")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasExplicitTargetIntent(intent: Intent?): Boolean {
        val target = intent?.getStringExtra(EXTRA_OPEN_TARGET)?.trim().orEmpty()
        return target.isNotEmpty()
    }

    private fun updateUiForCurrentTarget() {
        val openClawActive = currentUiTarget == OPEN_TARGET_OPENCLAW_SESSION
        if (openClawActive) {
            gatewayToggleButton.visibility = if (isOpenClawLightweightOnlyMode()) View.GONE else View.VISIBLE
            backToCodexButton.visibility = View.VISIBLE
            openClawNewChatButton.visibility = View.VISIBLE
            if (isOpenClawLightweightOnlyMode()) {
                stopGatewayStatusMonitor()
            } else {
                startGatewayStatusMonitor()
                refreshGatewayStatusAsync(announce = false)
            }
        } else {
            gatewayToggleButton.visibility = View.GONE
            backToCodexButton.visibility = View.GONE
            openClawNewChatButton.visibility = View.GONE
            stopGatewayStatusMonitor()
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
            OPEN_TARGET_CODEX_HOME -> "http://127.0.0.1:${CodexServerManager.SERVER_PORT}/"
            OPEN_TARGET_CODEX_THREAD -> {
                val threadId = intent?.getStringExtra(EXTRA_THREAD_ID)?.trim().orEmpty()
                if (threadId.isEmpty()) null
                else "http://127.0.0.1:${CodexServerManager.SERVER_PORT}/thread/${Uri.encode(threadId)}"
            }
            OPEN_TARGET_OPENCLAW_SESSION -> {
                val sessionKey = intent?.getStringExtra(EXTRA_SESSION_KEY)?.trim().orEmpty()
                buildOpenClawChatPageUrl(sessionKey)
            }
            OPEN_TARGET_CLAUDE_SESSION -> {
                val sessionKey = intent?.getStringExtra(EXTRA_SESSION_KEY)?.trim().orEmpty()
                buildClaudeChatPageUrl(sessionKey)
            }
            OPEN_TARGET_HERMES_SESSION -> {
                val sessionKey = intent?.getStringExtra(EXTRA_SESSION_KEY)?.trim().orEmpty()
                buildClaudeChatPageUrl(sessionKey)
            }
            else -> null
        }
    }

    private fun resolveAgentSessionKey(agentId: String): String = "agent:$agentId:node-main"

    private fun extractSessionFromCurrentUrl(): String? {
        val current = webView.url ?: return null
        return try {
            Uri.parse(current).getQueryParameter("session")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildOpenClawChatPageUrl(sessionKey: String?): String {
        val builder = Uri.parse("http://127.0.0.1:${CodexServerManager.OPENCLAW_CONTROL_UI_PORT}/chat").buildUpon()
        val normalized = sessionKey?.trim().orEmpty()
        if (normalized.isNotEmpty()) {
            builder.appendQueryParameter("session", normalized)
        }
        return builder.build().toString()
    }

    private fun isClaudeChatUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        return raw.contains("/claude/chat")
    }

    private fun buildClaudeChatPageUrl(sessionKey: String?): String {
        val builder = Uri.parse("http://127.0.0.1:${CodexServerManager.SERVER_PORT}/claude/chat").buildUpon()
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
            bottomAgentTabs.visibility = View.GONE
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
        val started = ShizukuBridgeRuntime.ensureStarted(this)
        if (!started) {
            Log.w(TAG, "Shizuku bridge ensureStarted failed")
        }
    }
}
