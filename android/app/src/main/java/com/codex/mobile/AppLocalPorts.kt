package com.codex.mobile

object AppLocalPorts {
    // This branch currently builds the temporary test package only.
    // Keep the port offset explicit here so the test app never collides
    // with the original package when both are installed side by side.
    private const val isTestPackage: Boolean = true
    private const val TEST_OFFSET = 10_000
    private val offset: Int = if (isTestPackage) TEST_OFFSET else 0

    val codexServerPort: Int = 18923 + offset
    val codexProxyPort: Int = 18924 + offset
    val openClawGatewayPort: Int = 18789 + offset
    val openClawControlUiPort: Int = 19001 + offset
    val shizukuBridgePort: Int = 18926 + offset
}
