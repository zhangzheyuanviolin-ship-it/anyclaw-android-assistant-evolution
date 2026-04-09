package com.codex.mobile

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.InetSocketAddress
import java.net.Socket

object ShizukuBridgeRuntime {
    private const val TAG = "ShizukuBridgeRuntime"

    @Volatile
    private var server: ShizukuShellBridgeServer? = null

    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        if (isBridgeReachable()) return true
        return try {
            val newServer = ShizukuShellBridgeServer(context.applicationContext)
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = newServer
            Log.i(TAG, "Bridge server started on ${ShizukuShellBridgeServer.BRIDGE_PORT}")
            true
        } catch (error: Exception) {
            if (isBridgeReachable()) {
                Log.i(TAG, "Bridge already running on ${ShizukuShellBridgeServer.BRIDGE_PORT}")
                true
            } else {
                Log.w(TAG, "Failed to start bridge server: ${error.message}")
                false
            }
        }
    }

    fun isBridgeReachable(timeoutMs: Int = 350): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", ShizukuShellBridgeServer.BRIDGE_PORT),
                    timeoutMs,
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

