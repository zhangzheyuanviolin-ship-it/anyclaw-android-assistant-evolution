package com.codex.mobile

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Extracts the Termux bootstrap archive from APK assets into the app's private
 * data directory, creating a usable Linux-like prefix environment without root.
 *
 * Follows the same extraction logic as Termux's TermuxInstaller.java:
 *   1. Extract all zip entries into a staging directory
 *   2. Parse SYMLINKS.txt and create symlinks
 *   3. Set execute permissions on bin/, libexec/, lib/apt/methods/
 *   4. Atomically rename staging -> final prefix
 */
object BootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    data class Paths(
        val filesDir: String,
        val prefixDir: String,
        val homeDir: String,
        val tmpDir: String,
    )

    fun getPaths(context: Context): Paths {
        val filesDir = context.filesDir.absolutePath
        return Paths(
            filesDir = filesDir,
            prefixDir = "$filesDir/usr",
            homeDir = "$filesDir/home",
            tmpDir = "$filesDir/usr/tmp",
        )
    }

    fun isBootstrapInstalled(context: Context): Boolean {
        val paths = getPaths(context)
        return File(paths.prefixDir).isDirectory && File(paths.prefixDir, "bin/sh").exists()
    }

    /**
     * Extract bootstrap-aarch64.zip from assets into the prefix directory.
     * This is idempotent: if the prefix already exists, it returns immediately.
     */
    fun install(
        context: Context,
        onProgress: (String) -> Unit = {},
    ) {
        val paths = getPaths(context)
        val prefixFile = File(paths.prefixDir)

        if (prefixFile.isDirectory && File(prefixFile, "bin/sh").exists()) {
            Log.i(TAG, "Bootstrap already installed at ${paths.prefixDir}")
            return
        }

        onProgress("Extracting environment…")

        val stagingPath = "${paths.filesDir}/usr-staging"
        val stagingFile = File(stagingPath)

        if (stagingFile.exists()) {
            deleteRecursive(stagingFile)
        }

        val archName = determineArchName()
        val assetName = "bootstrap-$archName.zip"
        val termuxPrefix = "/data/data/com.termux/files/usr"

        Log.i(TAG, "Extracting $assetName to $stagingPath")

        val buffer = ByteArray(8192)
        val symlinks = mutableListOf<Pair<String, String>>()

        context.assets.open(assetName).use { assetStream ->
            ZipInputStream(assetStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "SYMLINKS.txt") {
                        val reader = BufferedReader(InputStreamReader(zip))
                        var line = reader.readLine()
                        while (line != null) {
                            val parts = line.split("←")
                            if (parts.size == 2) {
                                var target = parts[0]
                                // Remap absolute Termux paths to our actual prefix
                                if (target.startsWith(termuxPrefix)) {
                                    target = target.replace(termuxPrefix, paths.prefixDir)
                                }
                                val linkPath = "$stagingPath/${parts[1]}"
                                symlinks.add(target to linkPath)
                                val parentFile = File(linkPath).parentFile
                                if (parentFile != null) {
                                    ensureParentDir(parentFile)
                                }
                            }
                            line = reader.readLine()
                        }
                    } else {
                        val targetFile = File(stagingPath, entry.name)
                        val isDir = entry.isDirectory

                        ensureParentDir(if (isDir) targetFile else targetFile.parentFile!!)
                        if (isDir) {
                            targetFile.mkdirs()
                        } else {
                            FileOutputStream(targetFile).use { out ->
                                var len = zip.read(buffer)
                                while (len != -1) {
                                    out.write(buffer, 0, len)
                                    len = zip.read(buffer)
                                }
                            }
                            if (shouldBeExecutable(entry.name)) {
                                Os.chmod(targetFile.absolutePath, 0b111_000_000) // 0700
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }

        if (symlinks.isEmpty()) {
            throw RuntimeException("No SYMLINKS.txt found in bootstrap archive")
        }

        onProgress("Creating symlinks…")
        for ((target, linkPath) in symlinks) {
            try {
                val linkFile = File(linkPath)
                // Remove any existing file/directory at the symlink location
                if (linkFile.exists() || linkFile.isDirectory) {
                    deleteRecursive(linkFile)
                }
                Os.symlink(target, linkPath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink $linkPath -> $target: ${e.message}")
            }
        }

        // Atomically move staging to final prefix
        if (prefixFile.exists()) {
            deleteRecursive(prefixFile)
        }
        if (!stagingFile.renameTo(prefixFile)) {
            throw RuntimeException("Failed to rename $stagingPath to ${paths.prefixDir}")
        }

        // Create home and tmp directories
        File(paths.homeDir).mkdirs()
        File(paths.tmpDir).mkdirs()

        // Fix hardcoded Termux paths in apt config and package metadata
        onProgress("Configuring package manager…")
        fixTermuxPaths(paths, context.packageName)

        Log.i(TAG, "Bootstrap installed successfully at ${paths.prefixDir}")
    }

    /**
     * The bootstrap was built for com.termux with paths like
     * /data/data/com.termux/files/usr. Rewrite apt configuration
     * and dpkg metadata so they reference our actual prefix.
     */
    private fun fixTermuxPaths(
        paths: Paths,
        packageName: String,
    ) {
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        // Create apt.conf that overrides all directory settings.
        // Use Dir "/" so apt doesn't prepend a prefix to absolute paths.
        val aptConf = File(prefix, "etc/apt/apt.conf")
        aptConf.writeText(
            """
            Dir "/";
            Dir::State "$prefix/var/lib/apt/";
            Dir::State::status "$prefix/var/lib/dpkg/status";
            Dir::Cache "$prefix/var/cache/apt/";
            Dir::Log "$prefix/var/log/apt/";
            Dir::Etc "$prefix/etc/apt/";
            Dir::Etc::SourceList "$prefix/etc/apt/sources.list";
            Dir::Etc::SourceParts "";
            Dir::Bin::dpkg "$prefix/bin/dpkg";
            Dir::Bin::Methods "$prefix/lib/apt/methods/";
            Dir::Bin::apt-key "$prefix/bin/apt-key";
            Dpkg::Options:: "--force-configure-any";
            Dpkg::Options:: "--force-bad-path";
            Dpkg::Options:: "--instdir=$prefix";
            Acquire::AllowInsecureRepositories "true";
            """.trimIndent() + "\n"
        )

        // Create log directory apt expects
        File(prefix, "var/log/apt").mkdirs()

        // Fix sources.list -- use HTTP mirrors to avoid GnuTLS cert path issues
        // (the compiled-in cert path in libgnutls.so points to /data/data/com.termux/)
        val sourcesList = File(prefix, "etc/apt/sources.list")
        if (sourcesList.exists()) {
            val content = sourcesList.readText()
            sourcesList.writeText(
                content
                    .replace("https://", "http://")
                    .replace("com.termux", packageName)
            )
        }

        // Fix dpkg info directory references
        val dpkgStatus = File(prefix, "var/lib/dpkg/status")
        if (dpkgStatus.exists()) {
            val content = dpkgStatus.readText()
            dpkgStatus.writeText(content.replace(termuxPrefix, prefix))
        }

        // Ensure dpkg directories exist
        File(prefix, "var/lib/dpkg/info").mkdirs()
        File(prefix, "var/lib/dpkg/updates").mkdirs()
        File(prefix, "var/lib/dpkg/triggers").mkdirs()
        File(prefix, "var/cache/apt/archives/partial").mkdirs()
        File(prefix, "var/lib/apt/lists/partial").mkdirs()

        // Fix all .list files in dpkg/info that may reference Termux paths
        val dpkgInfoDir = File(prefix, "var/lib/dpkg/info")
        if (dpkgInfoDir.isDirectory) {
            dpkgInfoDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".list")) {
                    try {
                        val text = file.readText()
                        if (text.contains(termuxPrefix)) {
                            file.writeText(text.replace(termuxPrefix, prefix))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fix ${file.name}: ${e.message}")
                    }
                }
            }
        }

        Log.i(TAG, "Fixed Termux paths -> $prefix")
    }

    private fun shouldBeExecutable(entryName: String): Boolean {
        return entryName.startsWith("bin/") ||
            entryName.startsWith("libexec/") ||
            entryName.startsWith("lib/apt/methods/") ||
            entryName.startsWith("lib/bash/") ||
            entryName.endsWith(".so") ||
            entryName.contains("/bin/")
    }

    private fun ensureParentDir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            if (!dir.isDirectory) {
                throw RuntimeException("Unable to create directory: ${dir.absolutePath}")
            }
        }
    }

    private fun determineArchName(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a" -> return "arm"
                "x86_64" -> return "x86_64"
                "x86" -> return "i686"
            }
        }
        throw RuntimeException(
            "Unsupported CPU architecture: ${Build.SUPPORTED_ABIS.joinToString()}"
        )
    }

    private fun deleteRecursive(fileOrDir: File) {
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        fileOrDir.delete()
    }
}
