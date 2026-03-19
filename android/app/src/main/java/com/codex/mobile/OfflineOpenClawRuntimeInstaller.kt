package com.codex.mobile

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

object OfflineOpenClawRuntimeInstaller {

    private const val TAG = "OfflineOpenClawRuntime"
    private const val RUNTIME_VERSION = "2026.3.2-offline-r1"
    private const val RUNTIME_DIR = "runtime"
    private const val RUNTIME_ARCHIVE = "openclaw-runtime-2026.3.2.tar.gz"
    private const val RUNTIME_PART_PREFIX = "openclaw-runtime-2026.3.2.part"
    private const val DAVEY_ASSET = "runtime/davey.android-arm64.node"

    fun install(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val stateDir = File(paths.homeDir, ".openclaw-android/state").apply { mkdirs() }
        val markerFile = File(stateDir, "offline-openclaw-runtime.json")
        val targetDir = File(paths.prefixDir, "lib/node_modules/openclaw")
        val nativeFile = File(paths.homeDir, ".openclaw-android/native/davey/davey.android-arm64.node")

        if (isCurrentInstallValid(markerFile, targetDir, nativeFile)) {
            Log.i(TAG, "Offline OpenClaw runtime already installed")
            return true
        }

        val runtimeAssets = resolveRuntimeAssets(context)
        if (runtimeAssets == null) {
            Log.e(TAG, "Offline OpenClaw runtime archive is missing from APK assets")
            return false
        }

        val stagingRoot = File(paths.filesDir, "offline-openclaw-runtime-staging")
        val stagingDir = File(stagingRoot, "openclaw")
        if (stagingRoot.exists()) {
            stagingRoot.deleteRecursively()
        }
        stagingRoot.mkdirs()

        return try {
            onProgress("Installing OpenClaw offline runtime…")
            extractRuntimeArchive(runtimeAssets, stagingRoot, onProgress)

            if (!stagingDir.exists()) {
                Log.e(TAG, "Offline runtime staging directory missing after extraction")
                stagingRoot.deleteRecursively()
                return false
            }

            onProgress("Activating offline OpenClaw runtime…")
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.parentFile?.mkdirs()
            if (!stagingDir.renameTo(targetDir)) {
                stagingDir.copyRecursively(targetDir, overwrite = true)
                stagingRoot.deleteRecursively()
            }

            installBundledDavey(context, nativeFile, onProgress)
            writeMarker(markerFile, runtimeAssets.assetCount)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Offline runtime install failed", error)
            stagingRoot.deleteRecursively()
            false
        }
    }

    private fun resolveRuntimeAssets(context: Context): RuntimeAssets? {
        val assetNames = context.assets.list(RUNTIME_DIR) ?: emptyArray()
        if (assetNames.contains(RUNTIME_ARCHIVE)) {
            return RuntimeAssets(
                assetCount = 1,
                openStream = { context.assets.open("$RUNTIME_DIR/$RUNTIME_ARCHIVE") },
            )
        }

        val partNames =
            assetNames
                .filter { it.startsWith(RUNTIME_PART_PREFIX) }
                .sorted()
        if (partNames.isEmpty()) {
            return null
        }

        return RuntimeAssets(
            assetCount = partNames.size,
            openStream = {
                val streams = partNames.map { part -> context.assets.open("$RUNTIME_DIR/$part") }
                SequenceInputStream(Collections.enumeration(streams))
            },
        )
    }

    private fun isCurrentInstallValid(
        markerFile: File,
        targetDir: File,
        nativeFile: File,
    ): Boolean {
        if (!targetDir.exists() || !File(targetDir, "package.json").exists() || !File(targetDir, "openclaw.mjs").exists()) {
            return false
        }
        if (!nativeFile.exists()) {
            return false
        }
        return try {
            val marker = JSONObject(markerFile.takeIf { it.exists() }?.readText().orEmpty())
            marker.optString("version") == RUNTIME_VERSION
        } catch (_: Exception) {
            false
        }
    }

    private fun extractRuntimeArchive(
        runtimeAssets: RuntimeAssets,
        targetRoot: File,
        onProgress: (String) -> Unit,
    ) {
        runtimeAssets.openStream().use { archiveStream ->
        GzipCompressorInputStream(archiveStream).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val name = entry.name.removePrefix("./")
                    if (name.isBlank()) {
                        entry = tar.nextTarEntry
                        continue
                    }
                    val output = File(targetRoot, name)
                    val normalizedRoot = targetRoot.canonicalFile
                    val normalizedOutput = output.canonicalFile
                    if (!normalizedOutput.path.startsWith(normalizedRoot.path + File.separator) &&
                        normalizedOutput != normalizedRoot
                    ) {
                        throw IllegalStateException("Unsafe runtime entry: $name")
                    }

                    when {
                        entry.isDirectory -> {
                            output.mkdirs()
                        }
                        entry.isSymbolicLink -> {
                            output.parentFile?.mkdirs()
                            if (output.exists() || output.isDirectory) {
                                output.deleteRecursively()
                            }
                            Os.symlink(entry.linkName, output.absolutePath)
                        }
                        entry.isFile -> {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { fos ->
                                tar.copyTo(fos)
                            }
                            val mode = entry.mode
                            if ((mode and 0b001_001_001) != 0) {
                                Os.chmod(output.absolutePath, 0b111_000_000)
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        }
        onProgress("Offline OpenClaw runtime extracted")
    }

    private fun installBundledDavey(
        context: Context,
        target: File,
        onProgress: (String) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        context.assets.open(DAVEY_ASSET).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        Os.chmod(target.absolutePath, 0b111_000_000)
        onProgress("Bundled native binding installed")
    }

    private fun writeMarker(
        markerFile: File,
        partCount: Int,
    ) {
        markerFile.parentFile?.mkdirs()
        val payload =
            JSONObject()
                .put("version", RUNTIME_VERSION)
                .put("parts", partCount)
                .put("installedAt", java.time.Instant.now().toString())
        markerFile.writeText(payload.toString(2))
    }

    private data class RuntimeAssets(
        val assetCount: Int,
        val openStream: () -> InputStream,
    )
}
