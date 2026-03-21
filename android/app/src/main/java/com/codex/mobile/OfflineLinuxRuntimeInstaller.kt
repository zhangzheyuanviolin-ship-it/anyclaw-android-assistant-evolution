package com.codex.mobile

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject

object OfflineLinuxRuntimeInstaller {

    private const val TAG = "OfflineLinuxRuntime"
    private const val RUNTIME_VERSION = "ubuntu-noble-aarch64-pd-v4.18.0-r2"

    private const val ASSET_UBUNTU_ROOTFS = "runtime/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
    private const val ASSET_PROOT_STATIC = "runtime/proot-v5.3.0-aarch64-static"
    private const val ASSET_FAKE_SYSDATA = "runtime/setup_fake_sysdata.sh"

    data class RuntimePaths(
        val runtimeRoot: File,
        val ubuntuRoot: File,
        val binDir: File,
        val tmpDir: File,
        val shellScript: File,
        val prootBinary: File,
        val fakeSysdataScript: File,
        val markerFile: File,
    )

    fun getRuntimePaths(context: Context): RuntimePaths {
        val base = BootstrapInstaller.getPaths(context)
        val runtimeRoot = File(base.homeDir, ".openclaw-android/linux-runtime")
        val ubuntuRoot = File(runtimeRoot, "rootfs/ubuntu-noble-aarch64")
        val binDir = File(runtimeRoot, "bin")
        val tmpDir = File(runtimeRoot, "tmp")
        val shellScript = File(binDir, "ubuntu-shell.sh")
        val prootBinary = File(binDir, "proot-static")
        val fakeSysdataScript = File(binDir, "setup_fake_sysdata.sh")
        val markerFile = File(base.homeDir, ".openclaw-android/state/offline-linux-runtime.json")
        return RuntimePaths(
            runtimeRoot = runtimeRoot,
            ubuntuRoot = ubuntuRoot,
            binDir = binDir,
            tmpDir = tmpDir,
            shellScript = shellScript,
            prootBinary = prootBinary,
            fakeSysdataScript = fakeSysdataScript,
            markerFile = markerFile,
        )
    }

    fun install(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        val runtimePaths = getRuntimePaths(context)

        if (isCurrentInstallValid(runtimePaths)) {
            Log.i(TAG, "Offline Linux runtime already installed")
            return true
        }

        val stagingRoot = File(context.filesDir, "offline-linux-runtime-staging")
        if (stagingRoot.exists()) {
            stagingRoot.deleteRecursively()
        }
        stagingRoot.mkdirs()

        val stageRuntimeRoot = File(stagingRoot, "linux-runtime")
        val stageRootfsDir = File(stageRuntimeRoot, "rootfs")
        val stageBinDir = File(stageRuntimeRoot, "bin")
        val stageUbuntuRoot = File(stageRootfsDir, "ubuntu-noble-aarch64")

        return try {
            onProgress("Installing bundled Linux runtime…")
            stageRootfsDir.mkdirs()
            stageBinDir.mkdirs()

            onProgress("Extracting Ubuntu rootfs…")
            extractUbuntuRootfs(context, stageRootfsDir)

            if (!stageUbuntuRoot.exists() || !File(stageUbuntuRoot, "bin/bash").exists()) {
                Log.e(TAG, "Ubuntu rootfs missing bash after extraction")
                stagingRoot.deleteRecursively()
                return false
            }

            onProgress("Installing proot runtime…")
            copyAssetToFile(context, ASSET_PROOT_STATIC, File(stageBinDir, "proot-static"), executable = true)

            onProgress("Preparing compatibility runtime…")
            copyAssetToFile(context, ASSET_FAKE_SYSDATA, File(stageBinDir, "setup_fake_sysdata.sh"), executable = true)
            val stageTmpDir = File(stageRuntimeRoot, "tmp")
            stageTmpDir.mkdirs()
            Os.chmod(stageTmpDir.absolutePath, 0b111_000_000)

            onProgress("Preparing runtime wrapper…")
            val wrapper = File(stageBinDir, "ubuntu-shell.sh")
            wrapper.writeText(buildWrapperScript(), Charsets.UTF_8)
            Os.chmod(wrapper.absolutePath, 0b111_000_000)

            onProgress("Activating bundled Linux runtime…")
            if (runtimePaths.runtimeRoot.exists()) {
                runtimePaths.runtimeRoot.deleteRecursively()
            }
            runtimePaths.runtimeRoot.parentFile?.mkdirs()

            if (!stageRuntimeRoot.renameTo(runtimePaths.runtimeRoot)) {
                stageRuntimeRoot.copyRecursively(runtimePaths.runtimeRoot, overwrite = true)
                stagingRoot.deleteRecursively()
            }

            writeMarker(runtimePaths.markerFile)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Offline Linux runtime install failed", error)
            stagingRoot.deleteRecursively()
            false
        }
    }

    private fun isCurrentInstallValid(paths: RuntimePaths): Boolean {
        if (!paths.runtimeRoot.exists()) return false
        if (!paths.ubuntuRoot.exists()) return false
        if (!File(paths.ubuntuRoot, "bin/bash").exists()) return false
        if (!paths.prootBinary.exists()) return false
        if (!paths.shellScript.exists()) return false
        if (!paths.tmpDir.exists()) return false
        if (!paths.fakeSysdataScript.exists()) return false

        return try {
            val marker = JSONObject(paths.markerFile.takeIf { it.exists() }?.readText().orEmpty())
            marker.optString("version") == RUNTIME_VERSION
        } catch (_: Exception) {
            false
        }
    }

    private fun extractUbuntuRootfs(
        context: Context,
        rootfsDir: File,
    ) {
        context.assets.open(ASSET_UBUNTU_ROOTFS).use { raw ->
            BufferedInputStream(raw).use { buffered ->
                XZCompressorInputStream(buffered).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val name = entry.name.removePrefix("./")
                            if (name.isNotBlank()) {
                                val output = File(rootfsDir, name)
                                ensureSafePath(rootfsDir, output, name)

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
                                        if ((entry.mode and 0b001_001_001) != 0) {
                                            Os.chmod(output.absolutePath, 0b111_000_000)
                                        }
                                    }
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
            }
        }
    }

    private fun ensureSafePath(
        root: File,
        target: File,
        entryName: String,
    ) {
        val normalizedRoot = root.canonicalFile
        val normalizedTarget = target.canonicalFile
        if (!normalizedTarget.path.startsWith(normalizedRoot.path + File.separator) &&
            normalizedTarget != normalizedRoot
        ) {
            throw IllegalStateException("Unsafe runtime entry: $entryName")
        }
    }

    private fun copyAssetToFile(
        context: Context,
        assetPath: String,
        target: File,
        executable: Boolean = false,
    ) {
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        if (executable) {
            Os.chmod(target.absolutePath, 0b111_000_000)
        }
    }

    private fun buildWrapperScript(): String {
        return listOf(
            "#!/system/bin/sh",
            "set -eu",
            "",
            "SCRIPT_DIR=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"",
            "RUNTIME_ROOT=\"\$(cd \"\$SCRIPT_DIR/..\" && pwd)\"",
            "UBUNTU_ROOT=\"\$RUNTIME_ROOT/rootfs/ubuntu-noble-aarch64\"",
            "PROOT_BIN=\"\$RUNTIME_ROOT/bin/proot-static\"",
            "FAKE_SYSDATA_SCRIPT=\"\$RUNTIME_ROOT/bin/setup_fake_sysdata.sh\"",
            "HOME_BIND=\"\${HOME:-\$RUNTIME_ROOT}\"",
            "TMP_BASE=\"\$RUNTIME_ROOT/tmp\"",
            "GUEST_TMP_MOUNT=\"/tmp-host\"",
            "",
            "ensure_dir() {",
            "  if [ ! -d \"\$1\" ]; then",
            "    mkdir -p \"\$1\"",
            "  fi",
            "  chmod 700 \"\$1\" 2>/dev/null || true",
            "}",
            "",
            "ensure_dir \"\$TMP_BASE\"",
            "",
            "if [ ! -x \"\$PROOT_BIN\" ]; then",
            "  echo \"linux-runtime-error:proot-missing\"",
            "  exit 21",
            "fi",
            "if [ ! -d \"\$UBUNTU_ROOT\" ] || [ ! -x \"\$UBUNTU_ROOT/bin/bash\" ]; then",
            "  echo \"linux-runtime-error:rootfs-missing\"",
            "  exit 22",
            "fi",
            "if [ ! -d \"\$TMP_BASE\" ] || [ ! -w \"\$TMP_BASE\" ]; then",
            "  echo \"linux-runtime-error:tmp-unwritable\"",
            "  exit 23",
            "fi",
            "",
            "export LD_PRELOAD=",
            "export PROOT_NO_SECCOMP=1",
            "export PROOT_TMP_DIR=\"\$TMP_BASE\"",
            "",
            "prepare_fake_sysdata() {",
            "  if [ -f \"\$FAKE_SYSDATA_SCRIPT\" ]; then",
            "    INSTALLED_ROOTFS_DIR=\"\$RUNTIME_ROOT/rootfs\"",
            "    distro_name=\"ubuntu-noble-aarch64\"",
            "    . \"\$FAKE_SYSDATA_SCRIPT\"",
            "    setup_fake_sysdata || true",
            "  fi",
            "}",
            "",
            "run_proot() {",
            "  exec \"\$PROOT_BIN\" -0 -r \"\$UBUNTU_ROOT\" --link2symlink \\",
            "    -b /dev -b /proc -b /sys -b /dev/pts \\",
            "    -b /storage/emulated/0:/sdcard \\",
            "    -b \"\$HOME_BIND\":\"\$HOME_BIND\" \\",
            "    -b \"\$TMP_BASE\":\"\$GUEST_TMP_MOUNT\" \\",
            "    -w /root \\",
            "    /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 TMPDIR=\"\$GUEST_TMP_MOUNT\" GUEST_TMP_MOUNT=\"\$GUEST_TMP_MOUNT\" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin COMMAND_TO_EXEC=\"\$COMMAND_TO_EXEC\" \\",
            "    /bin/bash -lc 'mkdir -p /tmp /var/tmp /run/shm >/dev/null 2>&1 || true; chmod 1777 /tmp /var/tmp >/dev/null 2>&1 || true; [ -d \"$GUEST_TMP_MOUNT\" ] && chmod 700 \"$GUEST_TMP_MOUNT\" >/dev/null 2>&1 || true; export TMPDIR=\"$GUEST_TMP_MOUNT\"; eval \"$COMMAND_TO_EXEC\"'",
            "}",
            "",
            "prepare_fake_sysdata",
            "",
            "if [ \"\${1:-}\" = \"--status\" ]; then",
            "  COMMAND_TO_EXEC='echo distro=ready; uname -a; command -v python3 || true; command -v apt || true; command -v git || true; printf \"tmpdir=%s\\n\" \"${TMPDIR:-unset}\"; mktemp >/dev/null 2>&1 && echo mktemp=ok || echo mktemp=failed'",
            "  run_proot",
            "fi",
            "",
            "if [ \"\${1:-}\" = \"--doctor\" ]; then",
            "  echo \"host_runtime_root=\$RUNTIME_ROOT\"",
            "  echo \"host_tmp_dir=\$TMP_BASE\"",
            "  echo \"host_tmp_writable=\$( [ -w \"\$TMP_BASE\" ] && echo yes || echo no )\"",
            "  COMMAND_TO_EXEC='echo distro=ready; pwd; id; command -v bash || true; command -v python3 || true; command -v apt || true; ls -ld /tmp /var/tmp /run/shm 2>/dev/null || true; printf \"guest_tmpdir=%s\\n\" \"${TMPDIR:-unset}\"; mktemp >/dev/null 2>&1 && echo mktemp=ok || echo mktemp=failed'",
            "  run_proot",
            "fi",
            "",
            "if [ \"\${1:-}\" = \"--session-shell\" ]; then",
            "  COMMAND_TO_EXEC='/bin/bash --noprofile --norc'",
            "  run_proot",
            "fi",
            "",
            "if [ \"\${1:-}\" = \"--command\" ]; then",
            "  shift",
            "  COMMAND_TO_EXEC=\"\${1:-/bin/bash -il}\"",
            "else",
            "  COMMAND_TO_EXEC=\"\$*\"",
            "  if [ -z \"\$COMMAND_TO_EXEC\" ]; then",
            "    COMMAND_TO_EXEC=\"/bin/bash -il\"",
            "  fi",
            "fi",
            "",
            "run_proot",
            "",
        ).joinToString("\n")
    }

    private fun writeMarker(markerFile: File) {
        markerFile.parentFile?.mkdirs()
        val payload =
            JSONObject()
                .put("version", RUNTIME_VERSION)
                .put("installedAt", java.time.Instant.now().toString())
        markerFile.writeText(payload.toString(2), Charsets.UTF_8)
    }
}
