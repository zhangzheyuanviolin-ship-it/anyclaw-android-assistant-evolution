package com.codex.mobile

object AppLocalPorts {
    private val isTestPackage: Boolean =
        BuildConfig.APPLICATION_ID == "com.codex.mobile.pocketlobster.test"

    private const val TEST_OFFSET = 10_000
    private val offset: Int = if (isTestPackage) TEST_OFFSET else 0

    val codexServerPort: Int = 18923 + offset
    val codexProxyPort: Int = 18924 + offset
    val openClawGatewayPort: Int = 18789 + offset
    val openClawControlUiPort: Int = 19001 + offset
    val shizukuBridgePort: Int = 18926 + offset
}
