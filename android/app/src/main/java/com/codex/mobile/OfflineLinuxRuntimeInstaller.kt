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
    private const val RUNTIME_VERSION = "ubuntu-noble-aarch64-operit-engine-r1"

    private const val ASSET_UBUNTU_ROOTFS = "runtime/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
    private const val ASSET_FAKE_SYSDATA = "runtime/setup_fake_sysdata.sh"

    data class RuntimePaths(
        val runtimeRoot: File,
        val ubuntuRoot: File,
        val binDir: File,
        val tmpDir: File,
        val commonScript: File,
        val shellScript: File,
        val prootBinary: File,
        val prootStaticBinary: File,
        val loaderBinary: File,
        val bashBinary: File,
        val busyboxBinary: File,
        val fakeSysdataScript: File,
        val markerFile: File,
    )

    fun getRuntimePaths(context: Context): RuntimePaths {
        val base = BootstrapInstaller.getPaths(context)
        val runtimeRoot = File(base.homeDir, ".openclaw-android/linux-runtime")
        val ubuntuRoot = File(runtimeRoot, "rootfs/ubuntu-noble-aarch64")
        val binDir = File(runtimeRoot, "bin")
        val tmpDir = File(runtimeRoot, "tmp")
        val commonScript = File(runtimeRoot, "common.sh")
        val shellScript = File(binDir, "ubuntu-shell.sh")
        val prootBinary = File(binDir, "proot")
        val prootStaticBinary = File(binDir, "proot-static")
        val loaderBinary = File(binDir, "loader")
        val bashBinary = File(binDir, "bash")
        val busyboxBinary = File(binDir, "busybox")
        val fakeSysdataScript = File(binDir, "setup_fake_sysdata.sh")
        val markerFile = File(base.homeDir, ".openclaw-android/state/offline-linux-runtime.json")
        return RuntimePaths(
            runtimeRoot = runtimeRoot,
            ubuntuRoot = ubuntuRoot,
            binDir = binDir,
            tmpDir = tmpDir,
            commonScript = commonScript,
            shellScript = shellScript,
            prootBinary = prootBinary,
            prootStaticBinary = prootStaticBinary,
            loaderBinary = loaderBinary,
            bashBinary = bashBinary,
            busyboxBinary = busyboxBinary,
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

            onProgress("Linking terminal engine…")
            installNativeTerminalEngine(context, stageBinDir)

            onProgress("Preparing compatibility runtime…")
            copyAssetToFile(context, ASSET_FAKE_SYSDATA, File(stageBinDir, "setup_fake_sysdata.sh"), executable = true)
            val stageTmpDir = File(stageRuntimeRoot, "tmp")
            stageTmpDir.mkdirs()
            Os.chmod(stageTmpDir.absolutePath, 0b111_000_000)

            onProgress("Preparing terminal runtime…")
            val commonScript = File(stageRuntimeRoot, "common.sh")
            commonScript.writeText(buildCommonScript(context), Charsets.UTF_8)
            Os.chmod(commonScript.absolutePath, 0b111_000_000)

            val wrapper = File(stageBinDir, "ubuntu-shell.sh")
            wrapper.writeText(buildLauncherScript(context), Charsets.UTF_8)
            Os.chmod(wrapper.absolutePath, 0b111_000_000)

            prepareRootfsMountPoints(context, stageUbuntuRoot)

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
        if (!paths.commonScript.exists()) return false
        if (!paths.prootBinary.exists()) return false
        if (!paths.prootStaticBinary.exists()) return false
        if (!paths.loaderBinary.exists()) return false
        if (!paths.bashBinary.exists()) return false
        if (!paths.busyboxBinary.exists()) return false
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

    private fun installNativeTerminalEngine(
        context: Context,
        binDir: File,
    ) {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        linkNativeBinary(nativeLibDir, binDir, "libproot.so", "proot", executable = true)
        linkNativeBinary(nativeLibDir, binDir, "libproot.so", "proot-static", executable = true)
        linkNativeBinary(nativeLibDir, binDir, "libloader.so", "loader", executable = true)
        linkNativeBinary(nativeLibDir, binDir, "libbash.so", "bash", executable = true)
        linkNativeBinary(nativeLibDir, binDir, "libbusybox.so", "busybox", executable = true)
        linkNativeBinary(nativeLibDir, binDir, "liblibtalloc.so.2.so", "libtalloc.so.2", executable = false)
    }

    private fun linkNativeBinary(
        nativeLibDir: File,
        binDir: File,
        sourceName: String,
        linkName: String,
        executable: Boolean,
    ) {
        val source = File(nativeLibDir, sourceName)
        if (!source.exists()) {
            throw IllegalStateException("Missing native terminal binary: $sourceName")
        }

        val target = File(binDir, linkName)
        if (target.exists() || target.isDirectory) {
            target.deleteRecursively()
        }

        try {
            if (executable) {
                Os.chmod(source.absolutePath, 0b111_000_000)
            }
            Os.symlink(source.absolutePath, target.absolutePath)
        } catch (_: Exception) {
            source.copyTo(target, overwrite = true)
            if (executable) {
                Os.chmod(target.absolutePath, 0b111_000_000)
            }
        }
    }

    private fun prepareRootfsMountPoints(
        context: Context,
        ubuntuRoot: File,
    ) {
        val filesDir = context.filesDir.absolutePath
        val homeDir = BootstrapInstaller.getPaths(context).homeDir

        listOf(
            "dev",
            "dev/pts",
            "proc",
            "sys",
            "sdcard",
            "tmp",
            "var/tmp",
            "run/shm",
            filesDir.removePrefix("/"),
            homeDir.removePrefix("/"),
        ).forEach { relative ->
            File(ubuntuRoot, relative).mkdirs()
        }
    }

    private fun buildLauncherScript(
        context: Context,
    ): String {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return listOf(
            "#!/system/bin/sh",
            "set -eu",
            "",
            "SCRIPT_DIR=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"",
            "RUNTIME_ROOT=\"\$(cd \"\$SCRIPT_DIR/..\" && pwd)\"",
            "COMMON_SH=\"\$RUNTIME_ROOT/common.sh\"",
            "RUNTIME_BASH=\"\$SCRIPT_DIR/bash\"",
            "RUNTIME_TMP=\"\$RUNTIME_ROOT/tmp\"",
            "export LD_LIBRARY_PATH=\"\$SCRIPT_DIR:$nativeLibDir\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"",
            "export PROOT_LOADER=\"\$SCRIPT_DIR/loader\"",
            "export TMPDIR=\"\$RUNTIME_TMP\"",
            "export PROOT_TMP_DIR=\"\$RUNTIME_TMP\"",
            "",
            "mkdir -p \"\$RUNTIME_TMP\" 2>/dev/null || true",
            "chmod 700 \"\$RUNTIME_TMP\" 2>/dev/null || true",
            "",
            "if [ ! -x \"\$RUNTIME_BASH\" ]; then",
            "  echo \"linux-runtime-error:runtime-bash-missing\" >&2",
            "  exit 11",
            "fi",
            "if [ ! -f \"\$COMMON_SH\" ]; then",
            "  echo \"linux-runtime-error:common-sh-missing\" >&2",
            "  exit 12",
            "fi",
            "",
            "mode=\"\${1:-}\"",
            "shift || true",
            "",
            "case \"\$mode\" in",
            "  --help|-h|help)",
            "    echo 'usage: ubuntu-shell.sh [--status|--doctor|--session-shell|--command CMD|CMD]'; exit 0",
            "    ;;",
            "  --status)",
            "    exec \"\$RUNTIME_BASH\" -c '. \"\$1\"; ubuntu_status' sh \"\$COMMON_SH\"",
            "    ;;",
            "  --doctor)",
            "    exec \"\$RUNTIME_BASH\" -c '. \"\$1\"; ubuntu_doctor' sh \"\$COMMON_SH\"",
            "    ;;",
            "  --session-shell)",
            "    export ANYCLAW_COMMAND='/bin/bash --noprofile --norc'",
            "    exec \"\$RUNTIME_BASH\" -c '. \"\$1\"; login_ubuntu \"\$ANYCLAW_COMMAND\"' sh \"\$COMMON_SH\"",
            "    ;;",
            "  --command)",
            "    export ANYCLAW_COMMAND=\"\${1:-/bin/bash -il}\"",
            "    exec \"\$RUNTIME_BASH\" -c '. \"\$1\"; login_ubuntu \"\$ANYCLAW_COMMAND\"' sh \"\$COMMON_SH\"",
            "    ;;",
            "  *)",
            "    export ANYCLAW_COMMAND=\"\$mode${'$'}{mode:+ }${'$'}*\"",
            "    if [ -z \"\${ANYCLAW_COMMAND// }\" ]; then",
            "      ANYCLAW_COMMAND='/bin/bash -il'",
            "    fi",
            "    export ANYCLAW_COMMAND",
            "    exec \"\$RUNTIME_BASH\" -c '. \"\$1\"; login_ubuntu \"\$ANYCLAW_COMMAND\"' sh \"\$COMMON_SH\"",
            "    ;;",
            "esac",
            "",
        ).joinToString("\n")
    }

    private fun buildCommonScript(
        context: Context,
    ): String {
        val paths = BootstrapInstaller.getPaths(context)
        val filesDir = paths.filesDir
        val homeDir = paths.homeDir
        val prefixDir = paths.prefixDir
        val tmpDir = "${paths.filesDir}/tmp"
        val runtimeRoot = "$homeDir/.openclaw-android/linux-runtime"
        val ubuntuPath = "$runtimeRoot/rootfs/ubuntu-noble-aarch64"
        val runtimeBin = "$runtimeRoot/bin"

        return listOf(
            "export FILES_DIR=\"$filesDir\"",
            "export HOME=\"$homeDir\"",
            "export PREFIX=\"$prefixDir\"",
            "export TERMUX_PREFIX=\"$prefixDir\"",
            "export RUNTIME_ROOT=\"$runtimeRoot\"",
            "export UBUNTU_PATH=\"$ubuntuPath\"",
            "export BIN=\"$runtimeBin\"",
            "export TMPDIR=\"$tmpDir\"",
            "export PROOT_TMP_DIR=\"$tmpDir\"",
            "export LD_LIBRARY_PATH=\"$runtimeBin:${context.applicationInfo.nativeLibraryDir}\"",
            "export PROOT_LOADER=\"$runtimeBin/loader\"",
            "export PATH=\"$runtimeBin:$prefixDir/bin:/system/bin:/system/xbin\"",
            "export TERM=\"xterm-256color\"",
            "export LANG=\"en_US.UTF-8\"",
            "",
            "ensure_dir(){ mkdir -p \"$1\" 2>/dev/null || true; chmod 700 \"$1\" 2>/dev/null || true; }",
            "write_default_dns(){ cat > \"$1\" <<'EOF'",
            "nameserver 8.8.8.8",
            "nameserver 1.1.1.1",
            "nameserver 223.5.5.5",
            "EOF",
            "}",
            "append_proot_bind_arg(){",
            "  src=\"$1\"; dst=\"$2\"",
            "  [ -e \"\$src\" ] || [ -L \"\$src\" ] || return 0",
            "  if [ -z \"\$dst\" ] || [ \"\$src\" = \"\$dst\" ]; then",
            "    PROOT_BIND_ARGS=\"\$PROOT_BIND_ARGS -b \$src\"",
            "  else",
            "    PROOT_BIND_ARGS=\"\$PROOT_BIND_ARGS -b \$src:\$dst\"",
            "  fi",
            "}",
            "",
            "validate_runtime(){",
            "  [ -x \"\$BIN/proot\" ] || { echo 'linux-runtime-error:proot-missing' >&2; return 21; }",
            "  [ -x \"\$BIN/loader\" ] || { echo 'linux-runtime-error:loader-missing' >&2; return 22; }",
            "  [ -x \"\$BIN/bash\" ] || { echo 'linux-runtime-error:bash-missing' >&2; return 23; }",
            "  [ -x \"\$BIN/busybox\" ] || { echo 'linux-runtime-error:busybox-missing' >&2; return 24; }",
            "  [ -d \"\$UBUNTU_PATH\" ] || { echo 'linux-runtime-error:rootfs-missing' >&2; return 25; }",
            "  [ -x \"\$UBUNTU_PATH/bin/bash\" ] || { echo 'linux-runtime-error:guest-bash-missing' >&2; return 26; }",
            "  ensure_dir \"\$TMPDIR\"",
            "}",
            "",
            "prepare_guest(){",
            "  ensure_dir \"\$UBUNTU_PATH/root\"",
            "  ensure_dir \"\$UBUNTU_PATH/tmp\"",
            "  ensure_dir \"\$UBUNTU_PATH/var/tmp\"",
            "  ensure_dir \"\$UBUNTU_PATH/run/shm\"",
            "  ensure_dir \"\$UBUNTU_PATH\$FILES_DIR\"",
            "  ensure_dir \"\$UBUNTU_PATH\$HOME\"",
            "  ensure_dir \"\$UBUNTU_PATH/sdcard\"",
            "  ensure_dir \"\$UBUNTU_PATH/dev/pts\"",
            "  ensure_dir \"\$UBUNTU_PATH/proc\"",
            "  ensure_dir \"\$UBUNTU_PATH/sys\"",
            "  mkdir -p \"\$UBUNTU_PATH/etc\" 2>/dev/null || true",
            "  write_default_dns \"\$UBUNTU_PATH/etc/resolv.conf\"",
            "}",
            "",
            "prepare_fake_sysdata(){",
            "  if [ -f \"\$BIN/setup_fake_sysdata.sh\" ]; then",
            "    export INSTALLED_ROOTFS_DIR=\"\$RUNTIME_ROOT/rootfs\"",
            "    export distro_name=\"ubuntu-noble-aarch64\"",
            "    export DEFAULT_FAKE_KERNEL_RELEASE=\"\${DEFAULT_FAKE_KERNEL_RELEASE:-6.1.118-android14-anyclaw}\"",
            "    export DEFAULT_FAKE_KERNEL_VERSION=\"\${DEFAULT_FAKE_KERNEL_VERSION:-#1 SMP PREEMPT AnyClaw Operit Engine}\"",
            "    . \"\$BIN/setup_fake_sysdata.sh\" || return 41",
            "    command -v setup_fake_sysdata >/dev/null 2>&1 || return 42",
            "    setup_fake_sysdata || return 43",
            "  fi",
            "}",
            "",
            "run_guest(){",
            "  COMMAND_TO_EXEC=\"$1\"",
            "  PROOT_BIND_ARGS=''",
            "  append_proot_bind_arg '/dev' '/dev'",
            "  append_proot_bind_arg '/proc' '/proc'",
            "  append_proot_bind_arg '/sys' '/sys'",
            "  append_proot_bind_arg '/dev/pts' '/dev/pts'",
            "  append_proot_bind_arg '/storage/emulated/0' '/sdcard'",
            "  append_proot_bind_arg \"\$FILES_DIR\" \"\$FILES_DIR\"",
            "  append_proot_bind_arg \"\$HOME\" \"\$HOME\"",
            "  append_proot_bind_arg \"\$TMPDIR\" '/dev/shm'",
            "  exec \"\$BIN/proot\" -0 -r \"\$UBUNTU_PATH\" --link2symlink \$PROOT_BIND_ARGS -w /root /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin COMMAND_TO_EXEC=\"\$COMMAND_TO_EXEC\" /bin/bash -lc 'echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; mkdir -p /tmp /var/tmp /run/shm >/dev/null 2>&1 || true; chmod 1777 /tmp /var/tmp >/dev/null 2>&1 || true; export TMPDIR=/tmp; eval \"\$COMMAND_TO_EXEC\"'",
            "}",
            "",
            "login_ubuntu(){",
            "  validate_runtime || return $?",
            "  prepare_guest || return $?",
            "  prepare_fake_sysdata || return $?",
            "  cmd=\"$1\"",
            "  [ -n \"\$cmd\" ] || cmd='/bin/bash -il'",
            "  run_guest \"\$cmd\"",
            "}",
            "",
            "ubuntu_status(){",
            "  login_ubuntu 'echo distro=ready; uname -a; command -v python3 || true; command -v apt || true; command -v git || true; printf \"tmpdir=%s\\n\" \"\${TMPDIR:-unset}\"; mktemp >/dev/null 2>&1 && echo mktemp=ok || echo mktemp=failed'",
            "}",
            "",
            "ubuntu_doctor(){",
            "  echo \"host_runtime_root=$runtimeRoot\"",
            "  echo \"host_tmp_dir=$tmpDir\"",
            "  echo \"host_tmp_writable=$( [ -w \"$tmpDir\" ] && echo yes || echo no )\"",
            "  login_ubuntu 'echo distro=ready; pwd; id; command -v bash || true; command -v python3 || true; command -v apt || true; ls -ld /tmp /var/tmp /run/shm 2>/dev/null || true; printf \"guest_tmpdir=%s\\n\" \"\${TMPDIR:-unset}\"; mktemp >/dev/null 2>&1 && echo mktemp=ok || echo mktemp=failed'",
            "}",
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
