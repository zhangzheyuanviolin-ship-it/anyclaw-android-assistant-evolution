package com.codex.mobile

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

data class ShizukuExecResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val errorCode: String? = null,
    val error: String? = null,
)

object ShizukuController {
    private const val TAG = "ShizukuController"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PREFS = "anyclaw_shizuku"
    private const val KEY_BRIDGE_ENABLED = "bridge_enabled"
    private const val REQUEST_CODE = 62041

    @Volatile
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isBridgeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BRIDGE_ENABLED, true)
    }

    fun setBridgeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).apply()
    }

    fun isShizukuAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isServiceRunning(): Boolean {
        return try {
            if (Shizuku.pingBinder()) {
                true
            } else {
                val binder = Shizuku.getBinder()
                binder != null && binder.isBinderAlive
            }
        } catch (_: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            isServiceRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isServiceRunning()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }

        val existing = pendingPermissionCallback
        if (existing != null) {
            onResult(false)
            return
        }
        pendingPermissionCallback = onResult

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                try {
                    Shizuku.removeRequestPermissionResultListener(this)
                } catch (_: Exception) {
                }
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                val callback = pendingPermissionCallback
                pendingPermissionCallback = null
                callback?.invoke(granted)
            }
        }

        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (_: Exception) {
            }
            pendingPermissionCallback = null
            onResult(false)
        }
    }

    fun executeShellCommand(command: String): ShizukuExecResult {
        if (!isServiceRunning()) {
            return ShizukuExecResult(
                success = false,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errorCode = "service_stopped",
                error = "Shizuku service not running",
            )
        }
        if (!hasPermission()) {
            return ShizukuExecResult(
                success = false,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errorCode = "permission_revoked",
                error = "Shizuku permission not granted",
            )
        }

        val service = getService()
            ?: return ShizukuExecResult(
                success = false,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errorCode = "bridge_unreachable",
                error = "Shizuku service interface unavailable",
            )

        return try {
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
                ?: return ShizukuExecResult(
                    success = false,
                    stdout = "",
                    stderr = "",
                    exitCode = -1,
                    errorCode = "executor_missing",
                    error = "Failed to start Shizuku process",
                )

            val processClass = process.javaClass
            val stdoutPfd = processClass.getMethod("getInputStream").invoke(process) as ParcelFileDescriptor?
            val stderrPfd = processClass.getMethod("getErrorStream").invoke(process) as ParcelFileDescriptor?

            val stdoutHolder = StringBuilder()
            val stderrHolder = StringBuilder()
            val latch = CountDownLatch(2)

            val stdoutThread = Thread {
                try {
                    stdoutHolder.append(readAllFromPfd(stdoutPfd))
                } catch (e: Exception) {
                    Log.w(TAG, "stdout read failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
            val stderrThread = Thread {
                try {
                    stderrHolder.append(readAllFromPfd(stderrPfd))
                } catch (e: Exception) {
                    Log.w(TAG, "stderr read failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = processClass.getMethod("waitFor").invoke(process) as Int
            val drained = latch.await(15, TimeUnit.SECONDS)

            try {
                stdoutPfd?.close()
            } catch (_: Exception) {
            }
            try {
                stderrPfd?.close()
            } catch (_: Exception) {
            }

            if (!drained) {
                ShizukuExecResult(
                    success = false,
                    stdout = stdoutHolder.toString(),
                    stderr = stderrHolder.toString(),
                    exitCode = -1,
                    errorCode = "timeout",
                    error = "Timed out while reading Shizuku process output",
                )
            } else {
                ShizukuExecResult(
                    success = exitCode == 0,
                    stdout = stdoutHolder.toString(),
                    stderr = stderrHolder.toString(),
                    exitCode = exitCode,
                )
            }
        } catch (e: Exception) {
            ShizukuExecResult(
                success = false,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errorCode = "bridge_unreachable",
                error = e.message ?: "Unknown error",
            )
        }
    }

    private fun getService(): IShizukuService? {
        return try {
            val binder = Shizuku.getBinder() ?: return null
            if (!binder.isBinderAlive) return null
            IShizukuService.Stub.asInterface(binder)
        } catch (e: Exception) {
            Log.e(TAG, "getService failed", e)
            null
        }
    }

    private fun readAllFromPfd(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return ""
        FileInputStream(pfd.fileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                return reader.readText()
            }
        }
    }
}
