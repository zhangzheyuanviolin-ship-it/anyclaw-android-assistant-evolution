package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Manages the lifecycle of the Node.js codex-web-local server process running
 * inside the Termux bootstrap environment. Handles installation of Node.js,
 * Codex CLI, the platform-specific native binary, authentication via
 * `codex login`, and the codex-web-local web server.
 */
class CodexServerManager(private val context: Context) {

    companion object {
        private const val TAG = "CodexServerManager"
        const val SERVER_PORT = 18923
        private const val PROXY_PORT = 18924
        private const val CODEX_VERSION = "0.104.0"
        const val OPENCLAW_GATEWAY_PORT = 18789
        const val OPENCLAW_CONTROL_UI_PORT = 19001
        private const val ANYCLAW_SEARCH_PLUGIN_ID = "anyclaw-search-suite"
        private const val ANYCLAW_GITHUB_PLUGIN_ID = "anyclaw-github-suite"
        private const val ANYCLAW_DEVICE_PLUGIN_ID = "anyclaw-device-suite"
        private const val ANYCLAW_RUNTIME_PLUGIN_ID = "anyclaw-runtime-suite"
        private const val ANYCLAW_UBUNTU_PLUGIN_ID = "anyclaw-ubuntu-suite"
        private const val OPENCLAW_TARGET_VERSION = "2026.3.2"
        private const val OPENCLAW_DAVEY_VERSION = "0.1.10"
        private const val NPM_REGISTRY_PRIMARY = "https://registry.npmjs.org"
        private const val NPM_REGISTRY_MIRROR = "https://registry.npmmirror.com"
        private const val OPENCLAW_CHAT_HISTORY_LIMIT_DEFAULT = 60
        private const val OPENCLAW_CHAT_HISTORY_LIMIT_STEP = 40
        private const val OPENCLAW_CHAT_HISTORY_LIMIT_MIN = 20
        private const val OPENCLAW_CHAT_HISTORY_LIMIT_MAX = 400
        private const val OPENCLAW_CHAT_HISTORY_MAX_BYTES = 1 * 1024 * 1024
    }

    private var serverProcess: Process? = null
    private var proxyProcess: Process? = null
    private var openClawGatewayProcess: Process? = null
    private var openClawControlUiProcess: Process? = null

    /**
     * Use Android system shell for process bootstrap.
     * This avoids first-run crashes observed with bootstrap-provided dash.
     */
    private fun runtimeShell(): String = "/system/bin/sh"

    val isRunning: Boolean
        get() {
            val proc = serverProcess ?: return false
            return try {
                proc.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }

    // ── Shell helpers ──────────────────────────────────────────────────────

    /**
     * Run a shell command inside the Termux prefix environment.
     * Returns the exit code.
     */
    fun runInPrefix(
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)

        val shell = runtimeShell()
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    private fun startProcessLogThread(proc: Process, label: String) {
        Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        val line =
                            try {
                                reader.readLine()
                            } catch (_: InterruptedIOException) {
                                Log.i(TAG, "$label reader interrupted during shutdown")
                                break
                            } catch (error: Exception) {
                                Log.w(TAG, "$label reader stopped: ${error.message}")
                                break
                            }
                        if (line == null) break
                        Log.d(TAG, "[$label] $line")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "$label log thread failed: ${error.message}")
            } finally {
                try {
                    Log.i(TAG, "$label exited with code: ${proc.waitFor()}")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.i(TAG, "$label wait interrupted")
                } catch (error: Exception) {
                    Log.w(TAG, "$label wait failed: ${error.message}")
                }
            }
        }.start()
    }

    /**
     * Run a command and capture its stdout as a single trimmed string.
     */
    private fun runCapture(command: String): String {
        val sb = StringBuilder()
        runInPrefix(command) { sb.appendLine(it) }
        return sb.toString().trim()
    }

    // ── Install checks ─────────────────────────────────────────────────────

    fun isProotInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/proot").exists()
    }

    fun isNodeInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/node").exists()
    }

    fun isCodexInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "lib/node_modules/@openai/codex/bin/codex.js").exists()
    }

    fun isServerBundleInstalled(): Boolean = false

    /**
     * The native Rust binary that the JS launcher delegates to.
     * Required for `codex app-server`, `codex login`, `codex exec`, etc.
     */
    fun isPlatformBinaryInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(
            paths.prefixDir,
            "lib/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/codex",
        ).exists()
    }

    // ── Installation ────────────────────────────────────────────────────────

    fun installNode(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir

        onProgress("Downloading Node.js packages…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated c-ares libicu libsqlite nodejs-lts npm 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download failed with code $dlCode")
        }

        onProgress("Extracting Node.js packages…")
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _stage &&
            for deb in *.deb; do
                echo "Extracting ${'$'}deb..." &&
                dpkg-deb -x "${'$'}deb" _stage/ 2>&1
            done &&
            if [ -d "_stage$termuxPrefix" ]; then
                cp -a _stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_stage/usr" ]; then
                cp -a _stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _stage *.deb 2>/dev/null
            echo "done"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "dpkg-deb extract failed with code $extractCode")
            return false
        }

        onProgress("Fixing script paths…")
        val fixCmd = """
            chmod 700 "$prefix/bin/node" 2>/dev/null

            CODEX_JS="$prefix/lib/node_modules/@openai/codex/bin/codex.js"
            if [ -f "${'$'}CODEX_JS" ]; then
                rm -f "$prefix/bin/codex"
                cat > "$prefix/bin/codex" << 'WEOF'
#!/system/bin/sh
exec $prefix/bin/node $prefix/lib/node_modules/@openai/codex/bin/codex.js "${'$'}@"
WEOF
                chmod 700 "$prefix/bin/codex"
            fi

            NPM_CLI="$prefix/lib/node_modules/npm/bin/npm-cli.js"
            if [ -f "${'$'}NPM_CLI" ]; then
                rm -f "$prefix/bin/npm"
                cat > "$prefix/bin/npm" << 'WEOF'
#!/system/bin/sh
exec $prefix/bin/node $prefix/lib/node_modules/npm/bin/npm-cli.js "${'$'}@"
WEOF
                chmod 700 "$prefix/bin/npm"
            fi

            echo "Wrapper scripts created"
        """.trimIndent()
        runInPrefix(fixCmd, onOutput = { onProgress(it) })

        return isNodeInstalled()
    }

    /**
     * Install proot from the Termux repository. proot uses ptrace to
     * intercept filesystem syscalls and remap hardcoded Termux paths
     * (e.g. /data/data/com.termux/files/usr) to our actual prefix,
     * enabling dpkg, apt-get install, and other tools that have
     * compiled-in path references.
     */
    fun installProot(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Downloading proot…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated proot libtalloc 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download proot failed with code $dlCode")
            return false
        }

        onProgress("Extracting proot…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _proot_stage &&
            for deb in proot*.deb libtalloc*.deb; do
                [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _proot_stage/ 2>&1
            done &&
            if [ -d "_proot_stage$termuxPrefix" ]; then
                cp -a _proot_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_proot_stage/usr" ]; then
                cp -a _proot_stage/usr/* "$prefix/" 2>&1
            fi &&
            chmod 700 "$prefix/bin/proot" 2>/dev/null
            rm -rf _proot_stage proot*.deb libtalloc*.deb 2>/dev/null
            echo "proot installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "proot extract failed with code $extractCode")
            return false
        }

        return isProotInstalled()
    }

    fun isPythonInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.prefixDir, "bin/python3").exists() ||
            File(paths.prefixDir, "bin/python").exists()
    }

    /**
     * Install Python using proot to handle dpkg's hardcoded Termux paths.
     * proot bind-mounts our prefix onto the compiled-in Termux prefix so
     * dpkg postinst scripts and shared library lookups resolve correctly.
     */
    fun installPython(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Downloading Python packages…")

        val downloadCmd = """
            cd $prefix/tmp &&
            apt-get update --allow-insecure-repositories 2>&1;
            apt-get download --allow-unauthenticated python python-pip 2>&1
        """.trimIndent()

        val dlCode = runInPrefix(downloadCmd, onOutput = { onProgress(it) })
        if (dlCode != 0) {
            Log.e(TAG, "apt-get download python failed with code $dlCode")
        }

        onProgress("Extracting Python…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _python_stage &&
            for deb in python*.deb; do
                [ -f "${'$'}deb" ] && echo "Extracting ${'$'}deb..." && dpkg-deb -x "${'$'}deb" _python_stage/ 2>&1
            done &&
            if [ -d "_python_stage$termuxPrefix" ]; then
                cp -a _python_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_python_stage/usr" ]; then
                cp -a _python_stage/usr/* "$prefix/" 2>&1
            fi &&
            chmod 700 "$prefix/bin/python"* 2>/dev/null
            chmod 700 "$prefix/bin/pip"* 2>/dev/null
            rm -rf _python_stage python*.deb 2>/dev/null
            echo "Python installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.e(TAG, "Python extract failed with code $extractCode")
            return false
        }

        // Create python3 wrapper to handle shebang issues
        val fixCmd = """
            if [ -f "$prefix/bin/python3" ] && [ ! -f "$prefix/bin/python" ]; then
                ln -sf python3 "$prefix/bin/python"
            fi
            echo "Python ready"
        """.trimIndent()
        runInPrefix(fixCmd, onOutput = { onProgress(it) })

        return isPythonInstalled()
    }

    // ── OpenClaw ─────────────────────────────────────────────────────────────

    fun isOpenClawInstalled(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val npmRoot = "${paths.prefixDir}/lib/node_modules"
        return File(npmRoot, "openclaw/package.json").exists() &&
            File(npmRoot, "openclaw/openclaw.mjs").exists()
    }

    /**
     * Install bionic-compat.js from APK assets into the home directory.
     * This shim patches process.platform, os.cpus(), and
     * os.networkInterfaces() for Android compatibility.
     * Loaded via NODE_OPTIONS="-r <path>/bionic-compat.js".
     */
    fun ensureBionicCompat() {
        val paths = BootstrapInstaller.getPaths(context)
        val patchDir = File(paths.homeDir, ".openclaw-android/patches")
        patchDir.mkdirs()

        val target = File(patchDir, "bionic-compat.js")
        try {
            context.assets.open("bionic-compat.js").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "bionic-compat.js installed to $target")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract bionic-compat.js: ${e.message}")
        }
    }

    /**
     * Install all Termux packages needed for OpenClaw's native module
     * builds. This includes git, make, cmake, clang, lld (linker),
     * NDK sysroot/multilib, and all transitive shared library deps.
     * Uses dpkg-deb manual extraction (same approach as Node.js install).
     */
    fun installOpenClawDeps(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val termuxPrefix = "/data/data/com.termux/files/usr"

        onProgress("Downloading build dependencies…")

        // All packages needed for native compilation (koffi) in one batch.
        // Split into groups to avoid apt-get download failures on missing pkgs.
        val pkgGroups = listOf(
            "git make cmake clang binutils binutils-bin lld",
            "libllvm libedit libffi ndk-sysroot ndk-multilib libcompiler-rt",
            "libarchive libxml2 liblzma libcurl libuv libnghttp2 libnghttp3",
            "rhash jsoncpp",
        )

        for (group in pkgGroups) {
            val dlCode = runInPrefix(
                "cd $prefix/tmp && apt-get download --allow-unauthenticated $group 2>&1",
                onOutput = { onProgress(it) },
            )
            if (dlCode != 0) {
                Log.w(TAG, "apt-get download ($group) failed with code $dlCode (non-fatal)")
            }
        }

        onProgress("Extracting build dependencies…")
        val extractCmd = """
            cd $prefix/tmp &&
            mkdir -p _deps_stage &&
            for deb in *.deb; do
                [ -f "${'$'}deb" ] && echo "Extracting ${'$'}deb..." && dpkg-deb -x "${'$'}deb" _deps_stage/ 2>&1
            done &&
            if [ -d "_deps_stage$termuxPrefix" ]; then
                cp -a _deps_stage$termuxPrefix/* "$prefix/" 2>&1
            elif [ -d "_deps_stage/usr" ]; then
                cp -a _deps_stage/usr/* "$prefix/" 2>&1
            fi &&
            rm -rf _deps_stage *.deb 2>/dev/null
            echo "Build deps installed"
        """.trimIndent()

        val extractCode = runInPrefix(extractCmd, onOutput = { onProgress(it) })
        if (extractCode != 0) {
            Log.w(TAG, "Deps extract failed with code $extractCode (non-fatal)")
        }

        onProgress("Repairing toolchain links…")
        ensureOpenClawToolchain(onProgress)

        onProgress("Running toolchain preflight…")
        val preflightOk = runOpenClawToolchainPreflight(onProgress)
        if (!preflightOk) {
            onProgress("Toolchain preflight failed, trying external recovery…")
            runExternalRecoveryScripts(onProgress)
            ensureOpenClawToolchain(onProgress)
            if (!runOpenClawToolchainPreflight(onProgress)) {
                onProgress("Toolchain still not healthy after recovery")
            }
        }

        onProgress("Fixing git-core script shebangs…")
        fixGitCoreShebangs(prefix)

        onProgress("Patching make & cmake binaries…")
        patchBinaryTermuxPaths(prefix)

        onProgress("Creating header stubs…")
        createHeaderStubs(prefix)

        onProgress("Final toolchain verification…")
        val finalPreflight = runOpenClawToolchainPreflight(onProgress)
        if (!finalPreflight) {
            Log.w(TAG, "OpenClaw dependency toolchain final preflight failed (non-fatal)")
        }

        return true
    }

    private fun ensureOpenClawToolchain(onProgress: (String) -> Unit) {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val cmd = """
            set -e

            if [ ! -x "$prefix/libexec/binutils/ar" ] || [ ! -x "$prefix/libexec/binutils/ranlib" ]; then
              echo "binutils missing, attempting targeted repair..."
              cd "$prefix/tmp" || exit 1
              apt-get update --allow-insecure-repositories 2>&1 || true
              apt-get download --allow-unauthenticated binutils binutils-bin 2>&1 || true
              rm -rf _binutils_stage
              mkdir -p _binutils_stage
              for deb in binutils*.deb binutils-bin*.deb; do
                [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _binutils_stage/ 2>&1 || true
              done
              if [ -d "_binutils_stage/data/data/com.termux/files/usr" ]; then
                cp -a _binutils_stage/data/data/com.termux/files/usr/* "$prefix/" 2>&1 || true
              elif [ -d "_binutils_stage/usr" ]; then
                cp -a _binutils_stage/usr/* "$prefix/" 2>&1 || true
              fi
              rm -rf _binutils_stage
            fi

            fix_tool() {
              local name="${'$'}1"
              local llvm_name="${'$'}2"
              local target="$prefix/bin/${'$'}name"
              local llvm_target="$prefix/bin/${'$'}llvm_name"
              local termux_target="$prefix/libexec/binutils/${'$'}name"

              if [ -L "${'$'}target" ] && [ ! -e "${'$'}target" ]; then
                rm -f "${'$'}target"
              fi
              if [ -x "${'$'}target" ]; then
                return 0
              fi
              if [ -x "${'$'}llvm_target" ]; then
                ln -sf "${'$'}llvm_name" "${'$'}target"
                return 0
              fi
              if [ -x "${'$'}termux_target" ]; then
                ln -sf "../libexec/binutils/${'$'}name" "${'$'}target"
                return 0
              fi
              return 1
            }

            fix_tool ar llvm-ar || true
            fix_tool ranlib llvm-ranlib || true

            if [ -x "$prefix/bin/ld.lld" ]; then
              ln -sf ld.lld "$prefix/bin/ld"
            fi

            AR_BIN="${'$'}(command -v ar || true)"
            RANLIB_BIN="${'$'}(command -v ranlib || true)"
            mkdir -p "${'$'}HOME/.openclaw-android/state"
            {
              echo "CMAKE_AR=${'$'}AR_BIN"
              echo "CMAKE_RANLIB=${'$'}RANLIB_BIN"
            } > "${'$'}HOME/.openclaw-android/state/toolchain.env"
            echo "toolchain linked: ar=${'$'}AR_BIN ranlib=${'$'}RANLIB_BIN"
        """.trimIndent()

        runInPrefix(cmd, onOutput = { onProgress(it) })
    }

    private fun runOpenClawToolchainPreflight(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val cmd = """
            set -e
            TMP_DIR="$prefix/tmp/anyclaw-toolchain-${'$'}${'$'}"
            rm -rf "${'$'}TMP_DIR"
            mkdir -p "${'$'}TMP_DIR"

            AR_BIN="${'$'}(command -v ar || true)"
            RANLIB_BIN="${'$'}(command -v ranlib || true)"
            CLANG_BIN="${'$'}(command -v clang || true)"
            if [ -z "${'$'}AR_BIN" ] || [ -z "${'$'}RANLIB_BIN" ] || [ -z "${'$'}CLANG_BIN" ]; then
              echo "toolchain missing: ar=${'$'}AR_BIN ranlib=${'$'}RANLIB_BIN clang=${'$'}CLANG_BIN"
              rm -rf "${'$'}TMP_DIR"
              exit 12
            fi

            cat > "${'$'}TMP_DIR/probe.c" <<'EOF'
int anyclaw_toolchain_probe = 1;
EOF

            "${'$'}CLANG_BIN" -c "${'$'}TMP_DIR/probe.c" -o "${'$'}TMP_DIR/probe.o"
            "${'$'}AR_BIN" qc "${'$'}TMP_DIR/libprobe.a" "${'$'}TMP_DIR/probe.o"
            "${'$'}RANLIB_BIN" "${'$'}TMP_DIR/libprobe.a"
            rm -rf "${'$'}TMP_DIR"
            echo "toolchain preflight ok"
        """.trimIndent()

        val code = runInPrefix(cmd, onOutput = { onProgress(it) })
        return code == 0
    }

    private fun runExternalRecoveryScripts(onProgress: (String) -> Unit) {
        val cmd = """
            set +e
            if [ -f /sdcard/Download/CodexExports/termux_pkg_repair.sh ]; then
              sh /sdcard/Download/CodexExports/termux_pkg_repair.sh 2>&1
            fi
            if [ -f /sdcard/Download/CodexExports/tls_doctor.sh ]; then
              sh /sdcard/Download/CodexExports/tls_doctor.sh 2>&1
            fi
            exit 0
        """.trimIndent()
        runInPrefix(cmd, onOutput = { onProgress(it) })
    }

    /**
     * Fix shebangs in all git-core shell scripts. They ship with
     * #!/data/data/com.termux/files/usr/bin/sh which doesn't exist
     * at our actual prefix path.
     */
    private fun fixGitCoreShebangs(prefix: String) {
        val cmd = """
            cd "$prefix/libexec/git-core" 2>/dev/null || exit 0
            for f in git-*; do
                if head -1 "${'$'}f" 2>/dev/null | grep -q "com.termux"; then
                    sed -i "1s|/data/data/com.termux/files/usr|$prefix|" "${'$'}f"
                fi
            done
            echo "Git shebangs fixed"
        """.trimIndent()
        runInPrefix(cmd) { Log.d(TAG, "[fix-shebang] $it") }
    }

    /**
     * Binary-patch the `make` and `cmake` ELF binaries to replace the
     * hardcoded Termux shell paths with /system/bin/sh (null-padded).
     * Without this, cmake's test-compile step and make's recipe execution
     * fail with "Permission denied" on the non-existent Termux sh path.
     */
    private fun patchBinaryTermuxPaths(prefix: String) {
        val patchScript = """
            cat > "$prefix/tmp/_patchbin.py" << 'PYEOF'
import sys
with open(sys.argv[1], "rb") as f:
    data = f.read()
pairs = [
    (b"/data/data/com.termux/files/usr/bin/sh", b"/system/bin/sh"),
    (b"/data/data/com.termux/files/usr/bin/bash", b"/system/bin/sh"),
]
for old, new in pairs:
    padded = new + b"\x00" * (len(old) - len(new))
    data = data.replace(old, padded)
with open(sys.argv[1], "wb") as f:
    f.write(data)
print("patched " + sys.argv[1])
PYEOF
            for bin in "$prefix/bin/make" "$prefix/bin/cmake"; do
                [ -f "${'$'}bin" ] && python3 "$prefix/tmp/_patchbin.py" "${'$'}bin" && chmod 700 "${'$'}bin"
            done
            rm -f "$prefix/tmp/_patchbin.py"
        """.trimIndent()
        runInPrefix(patchScript) { Log.d(TAG, "[patch-bin] $it") }
    }

    /**
     * Create stub headers needed for native builds on Android:
     * - android/api-level.h — cmake system detection
     * - spawn.h — POSIX spawn (not available on older Android NDK)
     * - renameat2_shim.h — syscall wrapper (API 30+ only in bionic)
     */
    private fun createHeaderStubs(prefix: String) {
        val cmd = """
            mkdir -p "$prefix/include/android"

            cat > "$prefix/include/android/api-level.h" << 'H1'
#pragma once
#define __ANDROID_API__ 24
H1

            cat > "$prefix/include/spawn.h" << 'H2'
#pragma once
#include <sys/types.h>
typedef struct { short __flags; pid_t __pgroup; } posix_spawnattr_t;
typedef struct { int __allocated; int __used; void **__actions; } posix_spawn_file_actions_t;
static inline int posix_spawn(pid_t *p,const char *path,const posix_spawn_file_actions_t *fa,const posix_spawnattr_t *a,char *const argv[],char *const envp[]){return -1;}
static inline int posix_spawnp(pid_t *p,const char *file,const posix_spawn_file_actions_t *fa,const posix_spawnattr_t *a,char *const argv[],char *const envp[]){return -1;}
static inline int posix_spawnattr_init(posix_spawnattr_t *a){return 0;}
static inline int posix_spawnattr_destroy(posix_spawnattr_t *a){return 0;}
static inline int posix_spawnattr_setflags(posix_spawnattr_t *a,short f){a->__flags=f;return 0;}
static inline int posix_spawnattr_setpgroup(posix_spawnattr_t *a,pid_t g){a->__pgroup=g;return 0;}
static inline int posix_spawn_file_actions_init(posix_spawn_file_actions_t *fa){return 0;}
static inline int posix_spawn_file_actions_destroy(posix_spawn_file_actions_t *fa){return 0;}
static inline int posix_spawn_file_actions_adddup2(posix_spawn_file_actions_t *fa,int o,int n){return 0;}
static inline int posix_spawn_file_actions_addclose(posix_spawn_file_actions_t *fa,int f){return 0;}
#define POSIX_SPAWN_SETPGROUP 2
#define POSIX_SPAWN_SETSIGDEF 4
#define POSIX_SPAWN_SETSIGMASK 8
H2

            cat > "$prefix/include/renameat2_shim.h" << 'H3'
#pragma once
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>
#include <linux/fs.h>
static inline int renameat2(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, unsigned int flags) {
    return syscall(__NR_renameat2, olddirfd, oldpath, newdirfd, newpath, flags);
}
H3
            echo "Header stubs created"
        """.trimIndent()
        runInPrefix(cmd) { Log.d(TAG, "[headers] $it") }
    }

    /**
     * Install OpenClaw via npm with --ignore-scripts (to skip the koffi
     * native build during npm install), then build koffi separately with
     * the correct CXXFLAGS/LDFLAGS. Finally, apply Termux path patches.
     *
     * Based on https://github.com/AidanPark/openclaw-android
     */
    fun installOpenClaw(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val npmCli = "$prefix/lib/node_modules/npm/bin/npm-cli.js"

        // Create directories OpenClaw expects
        runInPrefix("mkdir -p $prefix/tmp/openclaw ${paths.homeDir}/.openclaw-android/patches ${paths.homeDir}/.openclaw")

        // Install systemctl stub (OpenClaw checks for systemd)
        val systemctlStub = File(prefix, "bin/systemctl")
        if (!systemctlStub.exists()) {
            systemctlStub.writeText(
                "#!/system/bin/sh\n" +
                    "exit 0\n"
            )
            systemctlStub.setExecutable(true)
            Log.i(TAG, "Created systemctl stub")
        }

        // Configure git to use HTTPS instead of SSH (ssh not available in prefix)
        configureGitHttps(paths)

        // Clean npm cache to avoid stale git clones
        runInPrefix("node $npmCli cache clean --force 2>&1") { Log.d(TAG, "[npm-cache] $it") }

        onProgress("Installing OpenClaw (npm)…")
        if (!installOpenClawPackage(prefix, npmCli, onProgress)) {
            Log.e(TAG, "npm install openclaw failed after all install channels")
            return false
        }

        onProgress("Verifying OpenClaw command wrapper…")
        if (!ensureOpenClawCommandWrapper(onProgress)) {
            Log.e(TAG, "OpenClaw command wrapper verification failed after install")
            return false
        }

        // Build koffi native module separately
        onProgress("Building koffi native module…")
        val koffiBuilt = buildKoffi(prefix, paths.homeDir, onProgress)
        if (!koffiBuilt) {
            Log.w(TAG, "koffi build failed (OpenClaw may have limited functionality)")
        }

        // Patch hardcoded paths in the installed JS files
        onProgress("Patching OpenClaw paths…")
        patchOpenClawPaths()

        // Patch gateway JS to survive Android network interface errors
        // and allow device-auth bypass
        onProgress("Patching gateway for Android…")
        patchGatewayForAndroid()
        onProgress("Repairing OpenClaw native bindings…")
        if (!ensureOpenClawNativeBinding(onProgress)) {
            Log.e(TAG, "OpenClaw native binding repair failed during install")
            return false
        }

        return isOpenClawInstalled()
    }

    fun prepareOfflineOpenClawRuntime(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir

        runInPrefix("mkdir -p $prefix/tmp/openclaw ${paths.homeDir}/.openclaw-android/patches ${paths.homeDir}/.openclaw")

        val systemctlStub = File(prefix, "bin/systemctl")
        if (!systemctlStub.exists()) {
            systemctlStub.writeText(
                "#!/system/bin/sh\n" +
                    "exit 0\n"
            )
            systemctlStub.setExecutable(true)
        }

        onProgress("Verifying OpenClaw command wrapper…")
        if (!ensureOpenClawCommandWrapper(onProgress)) {
            return false
        }

        onProgress("Patching OpenClaw paths…")
        patchOpenClawPaths()

        onProgress("Patching gateway for Android…")
        patchGatewayForAndroid()

        onProgress("Repairing OpenClaw native bindings…")
        if (!ensureOpenClawNativeBinding(onProgress)) {
            return false
        }

        return isOpenClawInstalled()
    }

    fun ensureOpenClawVersion(onProgress: (String) -> Unit): Boolean {
        if (!isOpenClawInstalled()) return true

        val installed = getInstalledOpenClawVersion()
        if (installed == OPENCLAW_TARGET_VERSION) {
            onProgress("OpenClaw version already aligned: $installed")
            return true
        }

        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val npmCli = "$prefix/lib/node_modules/npm/bin/npm-cli.js"

        onProgress("Aligning OpenClaw to stable version $OPENCLAW_TARGET_VERSION…")
        if (!installOpenClawPackage(prefix, npmCli, onProgress)) {
            Log.e(TAG, "OpenClaw version alignment failed: all install channels exhausted")
            return false
        }

        onProgress("Rebuilding native dependencies after OpenClaw upgrade…")
        val koffiBuilt = buildKoffi(prefix, paths.homeDir, onProgress)
        if (!koffiBuilt) {
            Log.w(TAG, "koffi rebuild failed after OpenClaw alignment")
        }

        onProgress("Verifying OpenClaw command wrapper…")
        if (!ensureOpenClawCommandWrapper(onProgress)) {
            Log.e(TAG, "OpenClaw command wrapper verification failed after version alignment")
            return false
        }

        onProgress("Re-applying OpenClaw Android patches…")
        patchOpenClawPaths()
        patchGatewayForAndroid()
        onProgress("Repairing OpenClaw native bindings…")
        if (!ensureOpenClawNativeBinding(onProgress)) {
            Log.e(TAG, "OpenClaw native binding repair failed during version alignment")
            return false
        }
        return isOpenClawInstalled()
    }

    private fun ensureOpenClawCommandWrapper(onProgress: (String) -> Unit): Boolean {
        val prefix = BootstrapInstaller.getPaths(context).prefixDir
        val cmd = """
            set -eu
            OPENCLAW_DIR="$prefix/lib/node_modules/openclaw"
            OPENCLAW_MJS="${'$'}OPENCLAW_DIR/openclaw.mjs"
            [ -f "${'$'}OPENCLAW_MJS" ] || { echo "openclaw-mjs-missing"; exit 21; }

            cat > "$prefix/bin/openclaw" <<'EOF'
#!/system/bin/sh
exec $prefix/bin/node $prefix/lib/node_modules/openclaw/openclaw.mjs "${'$'}@"
EOF
            chmod 700 "$prefix/bin/openclaw"

            command -v openclaw >/dev/null 2>&1 || exit 22
            openclaw --version >/dev/null 2>&1 || exit 23
            echo "openclaw-wrapper-ready"
        """.trimIndent()
        val code = runInPrefix(cmd, onOutput = { onProgress(it) })
        return code == 0
    }

    fun ensureOpenClawRuntimeReady(onProgress: (String) -> Unit): Boolean {
        val prefix = BootstrapInstaller.getPaths(context).prefixDir
        if (!isOpenClawInstalled()) {
            onProgress("OpenClaw package not found")
            return false
        }

        onProgress("Validating OpenClaw CLI runtime…")
        if (!ensureOpenClawCommandWrapper(onProgress)) {
            return false
        }

        val checkCode = runInPrefix(
            "openclaw --version >/dev/null 2>&1 && test -f \"$prefix/lib/node_modules/openclaw/openclaw.mjs\"",
            onOutput = { onProgress(it) },
        )
        if (checkCode != 0) {
            onProgress("OpenClaw CLI runtime check failed")
            return false
        }
        onProgress("OpenClaw CLI runtime ready")
        return true
    }

    private fun ensureOpenClawNpmPrerequisites(
        prefix: String,
        npmCli: String,
        onProgress: (String) -> Unit,
        onLine: ((String) -> Unit)? = null,
    ): Boolean {
        onProgress("Preparing npm prerequisites…")
        val cmd = """
            set +e
            export DEBIAN_FRONTEND=noninteractive
            for t in node npm; do
              command -v "${'$'}t" >/dev/null 2>&1 || { echo "missing-required:${'$'}t"; exit 31; }
            done

            missing_extra=""
            for t in git tar xz; do
              command -v "${'$'}t" >/dev/null 2>&1 || missing_extra="${'$'}missing_extra ${'$'}t"
            done
            if [ -n "${'$'}missing_extra" ]; then
              echo "missing-extra:${'$'}missing_extra"
              echo "installing-extra:${'$'}missing_extra"
              apt-get update --allow-insecure-repositories 2>&1 || true
              apt-get install -y --allow-unauthenticated git tar xz-utils 2>&1 || true
              pkg install -y git tar xz-utils 2>&1 || true

              mkdir -p "$prefix/tmp/_npm_prereq_stage"
              cd "$prefix/tmp" || true
              rm -f git*.deb tar*.deb xz-utils*.deb 2>/dev/null || true
              apt-get download --allow-unauthenticated git 2>&1 || true
              apt-get download --allow-unauthenticated tar 2>&1 || true
              apt-get download --allow-unauthenticated xz-utils 2>&1 || true
              for deb in git*.deb tar*.deb xz-utils*.deb; do
                [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _npm_prereq_stage/ 2>&1 || true
              done
              if [ -d "_npm_prereq_stage/data/data/com.termux/files/usr" ]; then
                cp -a _npm_prereq_stage/data/data/com.termux/files/usr/* "$prefix/" 2>&1 || true
              elif [ -d "_npm_prereq_stage/usr" ]; then
                cp -a _npm_prereq_stage/usr/* "$prefix/" 2>&1 || true
              fi
              rm -rf _npm_prereq_stage git*.deb tar*.deb xz-utils*.deb 2>/dev/null || true
            fi

            command -v git >/dev/null 2>&1 && echo "extra-ready:git" || echo "extra-missing:git"
            command -v tar >/dev/null 2>&1 && echo "extra-ready:tar" || echo "extra-missing:tar"
            command -v xz >/dev/null 2>&1 && echo "extra-ready:xz" || echo "extra-missing:xz"

            node "$npmCli" config set registry "$NPM_REGISTRY_PRIMARY" 2>&1 || true
            node "$npmCli" cache clean --force 2>&1 || true
            echo "npm-prerequisites-ready"
        """.trimIndent()
        val code = runInPrefix(cmd, onOutput = {
            onProgress(it)
            onLine?.invoke(it)
        })
        if (code == 0) {
            patchNpmGitCloneRetryBug(prefix, onProgress, onLine)
        }
        return code == 0
    }

    private fun patchNpmGitCloneRetryBug(
        prefix: String,
        onProgress: (String) -> Unit,
        onLine: ((String) -> Unit)? = null,
    ): Boolean {
        val cmd = """
            set +e
            SPAWN_JS="$prefix/lib/node_modules/npm/node_modules/@npmcli/git/lib/spawn.js"
            [ -f "${'$'}SPAWN_JS" ] || { echo "npm-git-spawn-missing"; exit 0; }
            SPAWN_JS="${'$'}SPAWN_JS" node <<'NODE'
            const fs = require('fs')
            const p = process.env.SPAWN_JS
            let src = fs.readFileSync(p, 'utf8')
            if (src.includes('anyclaw-git-clone-cleanup')) {
              console.log('npm-git-spawn-patch:already')
              process.exit(0)
            }
            const needle = "    return spawn(gitPath, args, makeOpts(opts))\\n"
            if (!src.includes(needle)) {
              console.log('npm-git-spawn-patch:needle-missing')
              process.exit(2)
            }
            const injected = [
              "    // anyclaw-git-clone-cleanup",
              "    try {",
              "      const cloneIdx = args.indexOf('clone')",
              "      if (cloneIdx >= 0 && args[cloneIdx + 2]) {",
              "        require('fs').rmSync(args[cloneIdx + 2], { recursive: true, force: true })",
              "      }",
              "    } catch {}",
              needle.trimEnd(),
              "",
            ].join("\\n")
            src = src.replace(needle, injected)
            fs.writeFileSync(p, src)
            console.log('npm-git-spawn-patch:ok')
            NODE
        """.trimIndent()
        val code = runInPrefix(cmd, onOutput = {
            onProgress(it)
            onLine?.invoke(it)
        })
        return code == 0
    }

    private fun installOpenClawPackage(
        prefix: String,
        npmCli: String,
        onProgress: (String) -> Unit,
    ): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val logFile = File(paths.homeDir, ".openclaw-android/state/openclaw-install-last.log")
        logFile.parentFile?.mkdirs()
        val diagnostics = StringBuilder()
        diagnostics.appendLine("===== openclaw-install-start =====")
        diagnostics.appendLine("targetVersion=$OPENCLAW_TARGET_VERSION")

        if (!ensureOpenClawNpmPrerequisites(
                prefix = prefix,
                npmCli = npmCli,
                onProgress = onProgress,
                onLine = { diagnostics.appendLine(it) },
            )
        ) {
            diagnostics.appendLine("===== prerequisites-failed =====")
            logFile.writeText(diagnostics.toString())
            onProgress("OpenClaw install log saved: ${logFile.absolutePath}")
            return false
        }

        val commonInstallPrefix = """
            export GIT_TERMINAL_PROMPT=0
            export GIT_CONFIG_COUNT=2
            export GIT_CONFIG_KEY_0=url.https://github.com/.insteadOf
            export GIT_CONFIG_VALUE_0=ssh://git@github.com/
            export GIT_CONFIG_KEY_1=url.https://github.com/.insteadOf
            export GIT_CONFIG_VALUE_1=git@github.com:
            export npm_config_fetch_retries=0
            export npm_config_fetch_retry_mintimeout=1000
            export npm_config_fetch_retry_maxtimeout=5000
            rm -rf "${'$'}HOME/.npm/_cacache/tmp/git-clone"* "${'$'}HOME/.npm/_cacache/tmp/tmp"* 2>/dev/null || true
        """.trimIndent().replace("\n", " && ")

        val attempts = listOf(
            "default-registry" to """
                CACHE_DIR="$prefix/tmp/npm-cache-openclaw-default"
                rm -rf "${'$'}CACHE_DIR" && mkdir -p "${'$'}CACHE_DIR"
                $commonInstallPrefix
                node $npmCli install -g --ignore-scripts --cache "${'$'}CACHE_DIR" openclaw@$OPENCLAW_TARGET_VERSION 2>&1
            """.trimIndent().replace("\n", " && "),
            "default-force" to """
                CACHE_DIR="$prefix/tmp/npm-cache-openclaw-force"
                rm -rf "${'$'}CACHE_DIR" && mkdir -p "${'$'}CACHE_DIR"
                $commonInstallPrefix
                node $npmCli cache clean --force 2>&1 || true
                node $npmCli install -g --ignore-scripts --force --cache "${'$'}CACHE_DIR" openclaw@$OPENCLAW_TARGET_VERSION 2>&1
            """.trimIndent().replace("\n", " && "),
            "npmmirror-force" to """
                CACHE_DIR="$prefix/tmp/npm-cache-openclaw-mirror"
                rm -rf "${'$'}CACHE_DIR" && mkdir -p "${'$'}CACHE_DIR"
                $commonInstallPrefix
                node $npmCli config set registry $NPM_REGISTRY_MIRROR 2>&1 || true
                node $npmCli cache clean --force 2>&1 || true
                node $npmCli install -g --ignore-scripts --force --cache "${'$'}CACHE_DIR" openclaw@$OPENCLAW_TARGET_VERSION 2>&1
            """.trimIndent().replace("\n", " && "),
            "pack-then-local-install" to """
                CACHE_DIR="$prefix/tmp/npm-cache-openclaw-pack"
                rm -rf "${'$'}CACHE_DIR" "$prefix/tmp/openclaw-pack" && mkdir -p "${'$'}CACHE_DIR" "$prefix/tmp/openclaw-pack"
                $commonInstallPrefix
                node $npmCli config set registry $NPM_REGISTRY_MIRROR 2>&1 || true
                node $npmCli pack openclaw@$OPENCLAW_TARGET_VERSION --pack-destination "$prefix/tmp/openclaw-pack" 2>&1
                PACK_FILE="${'$'}(ls "$prefix/tmp/openclaw-pack"/openclaw-*.tgz 2>/dev/null | head -n 1)"
                [ -n "${'$'}PACK_FILE" ] || exit 71
                node $npmCli install -g --ignore-scripts --force --cache "${'$'}CACHE_DIR" "${'$'}PACK_FILE" 2>&1
            """.trimIndent().replace("\n", " && "),
        )

        var ok = false
        for ((index, attempt) in attempts.withIndex()) {
            val name = attempt.first
            val cmd = attempt.second
            onProgress("Installing OpenClaw via $name (${index + 1}/${attempts.size})…")
            val buffer = StringBuilder()
            val code = runInPrefix(cmd, onOutput = {
                onProgress(it)
                buffer.appendLine(it)
            })
            diagnostics.appendLine("===== attempt:$name code:$code =====")
            diagnostics.append(buffer.toString())
            if (code == 0 && isOpenClawInstalled()) {
                ok = true
                break
            }
        }

        // Always restore primary registry so other npm flows stay deterministic.
        runInPrefix("node $npmCli config set registry $NPM_REGISTRY_PRIMARY 2>&1") {
            Log.d(TAG, "[npm-registry-reset] $it")
        }

        if (!ok) {
            logFile.writeText(diagnostics.toString())
            onProgress("OpenClaw install log saved: ${logFile.absolutePath}")
            return false
        }
        return true
    }

    /**
     * Write git insteadOf rules so all SSH GitHub URLs are rewritten
     * to HTTPS (we don't have ssh in our prefix).
     */
    private fun configureGitHttps(paths: BootstrapInstaller.Paths) {
        val gitconfigFile = File(paths.homeDir, ".gitconfig")
        val desired = """
            |[url "https://github.com/"]
            |	insteadOf = ssh://git@github.com/
            |	insteadOf = git@github.com:
        """.trimMargin()
        val existing = if (gitconfigFile.exists()) gitconfigFile.readText() else ""
        if (!existing.contains("insteadOf = ssh://git@github.com")) {
            gitconfigFile.appendText("\n$desired\n")
        }
    }

    /**
     * OpenClaw 2026.3.x bundles @snazzah/davey, but npm optional dependency
     * resolution is unreliable in this Android/Termux environment:
     * process.platform resolves to linux and libc resolves to null, so neither
     * the Android nor Linux platform package can be installed normally.
     *
     * We work around this by:
     * 1. Patching davey's env-var loader branch so it actually returns the
     *    explicitly requested .node file instead of discarding it.
     * 2. Fetching the Android native package tarball with npm pack.
     * 3. Extracting davey.android-arm64.node into a stable app-private path.
     */
    private fun ensureOpenClawNativeBinding(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val openclawDir = "$prefix/lib/node_modules/openclaw"
        val npmCli = "$prefix/lib/node_modules/npm/bin/npm-cli.js"
        val nativeDir = "${paths.homeDir}/.openclaw-android/native/davey"
        val nativeNode = "$nativeDir/davey.android-arm64.node"
        val cmd = """
            OPENCLAW_DIR="$openclawDir"
            NPM_CLI="$npmCli"
            NATIVE_DIR="$nativeDir"
            NATIVE_NODE="$nativeNode"
            DAVEY_JS="${'$'}OPENCLAW_DIR/node_modules/@snazzah/davey/index.js"
            TMP_PACK_DIR="${paths.homeDir}/.openclaw-android/native/.davey-pack"
            if [ ! -d "${'$'}OPENCLAW_DIR" ]; then
              echo "openclaw-dir-missing"
              exit 0
            fi

            if [ ! -f "${'$'}DAVEY_JS" ]; then
              echo "davey-wrapper-missing"
              exit 1
            fi

            mkdir -p "${'$'}NATIVE_DIR" "${'$'}TMP_PACK_DIR"

            DAVEY_JS="${'$'}DAVEY_JS" node <<'NODE'
            const fs = require('fs')
            const path = process.env.DAVEY_JS
            let src = fs.readFileSync(path, 'utf8')
            const before = '      nativeBinding = require(process.env.NAPI_RS_NATIVE_LIBRARY_PATH);'
            const after = '      return require(process.env.NAPI_RS_NATIVE_LIBRARY_PATH)'
            if (src.includes(after)) {
              console.log('davey-loader-patched:already')
              process.exit(0)
            }
            if (!src.includes(before)) {
              console.log('davey-loader-patched:pattern-missing')
              process.exit(2)
            }
            src = src.replace(before, after)
            fs.writeFileSync(path, src)
            console.log('davey-loader-patched:ok')
            NODE
            patch_code="${'$'}?"
            if [ "${'$'}patch_code" -ne 0 ] && [ "${'$'}patch_code" -ne 2 ]; then
              exit "${'$'}patch_code"
            fi
            if [ "${'$'}patch_code" -eq 2 ]; then
              echo "davey-loader-patched:pattern-missing"
              exit 1
            fi

            if [ -f "${'$'}NATIVE_NODE" ]; then
              echo "davey-native-ready:cached"
              exit 0
            fi

            rm -rf "${'$'}TMP_PACK_DIR/package" "${'$'}TMP_PACK_DIR"/snazzah-davey-android-arm64-*.tgz 2>/dev/null
            echo "davey-pack-fetch:@snazzah/davey-android-arm64@$OPENCLAW_DAVEY_VERSION"
            node "${'$'}NPM_CLI" pack "@snazzah/davey-android-arm64@$OPENCLAW_DAVEY_VERSION" --pack-destination "${'$'}TMP_PACK_DIR" 2>&1 || true

            PACK_FILE="${'$'}(ls "${'$'}TMP_PACK_DIR"/snazzah-davey-android-arm64-*.tgz 2>/dev/null | head -n 1)"
            if [ -z "${'$'}PACK_FILE" ]; then
              echo "davey-pack-missing"
              exit 1
            fi

            python3 - <<'PY'
            import os
            import sys
            import tarfile
            pack_file = os.environ.get('PACK_FILE', '')
            out_dir = os.environ.get('TMP_PACK_DIR', '')
            if not pack_file or not out_dir:
                print('davey-python-extract-env-missing')
                sys.exit(1)
            try:
                with tarfile.open(pack_file, 'r:gz') as tf:
                    tf.extractall(out_dir)
                print('davey-python-extract:ok')
            except Exception as error:
                print(f'davey-python-extract:error:{error}')
                sys.exit(1)
            PY
            if [ ! -f "${'$'}TMP_PACK_DIR/package/davey.android-arm64.node" ]; then
              echo "davey-node-missing"
              exit 1
            fi

            cp -f "${'$'}TMP_PACK_DIR/package/davey.android-arm64.node" "${'$'}NATIVE_NODE"
            chmod 700 "${'$'}NATIVE_NODE" 2>/dev/null || true
            echo "davey-native-ready:installed"
            exit 0
        """.trimIndent()

        val code = runInPrefix(cmd) {
            Log.d(TAG, "[openclaw-davey] $it")
            onProgress(it)
        }
        if (code != 0) {
            Log.w(TAG, "OpenClaw native binding repair failed with code=$code")
            return false
        }
        return true
    }

    /**
     * Build the koffi native FFI module inside the already-installed
     * openclaw package. Uses cmake + make + clang with our patched
     * binaries and header stubs.
     */
    private fun buildKoffi(prefix: String, homeDir: String, onProgress: (String) -> Unit): Boolean {
        val koffiDir = "$prefix/lib/node_modules/openclaw/node_modules/koffi"
        if (!File(koffiDir, "src/cnoke/cnoke.js").exists()) {
            Log.w(TAG, "koffi cnoke.js not found, skipping build")
            return false
        }

        val shimHeader = "$prefix/include/renameat2_shim.h"
        val buildCmd = """
            export CC=clang CXX=clang++ \
                CFLAGS="-include $shimHeader" \
                CXXFLAGS="-include $shimHeader" \
                LDFLAGS="-fuse-ld=lld" \
                SHELL=/system/bin/sh &&
            rm -rf "$koffiDir/build" &&
            cd "$koffiDir" &&
            node src/cnoke/cnoke.js -p . -d src/koffi --prebuild 2>&1
        """.trimIndent()

        val code = runInPrefix(buildCmd, onOutput = { onProgress(it) })
        if (code != 0) {
            Log.e(TAG, "koffi build failed with code $code")
            return false
        }

        Log.i(TAG, "koffi native module built successfully")
        return true
    }

    /**
     * Replace hardcoded Linux paths in OpenClaw's JS files with our
     * Termux prefix equivalents, and fix the openclaw.mjs shebang.
     * Mirrors patch-paths.sh from openclaw-android.
     */
    private fun patchOpenClawPaths() {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val openclawDir = "$prefix/lib/node_modules/openclaw"

        val patchCmd = """
            ODIR="$openclawDir"
            [ ! -d "${'$'}ODIR" ] && echo "OpenClaw dir not found" && exit 0

            # Fix the openclaw.mjs shebang
            if [ -f "${'$'}ODIR/openclaw.mjs" ]; then
                sed -i "1s|#!/usr/bin/env node|#!$prefix/bin/node|" "${'$'}ODIR/openclaw.mjs"
            fi

            # Patch /tmp -> $prefix/tmp
            for f in ${'$'}(grep -rl '/tmp' "${'$'}ODIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null); do
                sed -i "s|\"\/tmp/|\"$prefix/tmp/|g" "${'$'}f"
                sed -i "s|'\/tmp/|'$prefix/tmp/|g" "${'$'}f"
                sed -i "s|\`\/tmp/|\`$prefix/tmp/|g" "${'$'}f"
                sed -i "s|\"\/tmp\"|\"$prefix/tmp\"|g" "${'$'}f"
                sed -i "s|'\/tmp'|'$prefix/tmp'|g" "${'$'}f"
            done

            # Patch /bin/sh -> $prefix/bin/sh
            for f in ${'$'}(grep -rl '"/bin/sh"' "${'$'}ODIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null) \
                     ${'$'}(grep -rl "'/bin/sh'" "${'$'}ODIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null); do
                sed -i "s|\"\/bin\/sh\"|\"$prefix/bin/sh\"|g" "${'$'}f"
                sed -i "s|'\/bin\/sh'|'$prefix/bin/sh'|g" "${'$'}f"
            done

            # Patch /bin/bash -> $prefix/bin/bash
            for f in ${'$'}(grep -rl '"/bin/bash"' "${'$'}ODIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null) \
                     ${'$'}(grep -rl "'/bin/bash'" "${'$'}ODIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null); do
                sed -i "s|\"\/bin\/bash\"|\"$prefix/bin/bash\"|g" "${'$'}f"
                sed -i "s|'\/bin\/bash'|'$prefix/bin/bash'|g" "${'$'}f"
            done

            # Patch /usr/bin/env -> $prefix/bin/env
            for f in ${'$'}(grep -rl '"/usr/bin/env"' "${'$'}ODIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null) \
                     ${'$'}(grep -rl "'/usr/bin/env'" "${'$'}ODIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null); do
                sed -i "s|\"\/usr\/bin\/env\"|\"$prefix/bin/env\"|g" "${'$'}f"
                sed -i "s|'\/usr\/bin\/env'|'$prefix/bin/env'|g" "${'$'}f"
            done

            echo "Path patches applied"
        """.trimIndent()

        runInPrefix(patchCmd) { Log.d(TAG, "[patch] $it") }
        Log.i(TAG, "OpenClaw path patches applied")
    }

    /**
     * Patch OpenClaw gateway JS files for Android compatibility:
     * 1. runner-*.js: Prevent process.exit(1) on @homebridge/ciao
     *    assertion errors (Android's ccmni cellular interface triggers
     *    "Could not find valid addresses for interface 'ccmniN'").
     * 2. gateway-cli-*.js: Make evaluateMissingDeviceIdentity() return
     *    "allow" when dangerouslyDisableDeviceAuth is true, so the
     *    Control UI can connect without generating device identity keys.
     */
    private fun patchGatewayForAndroid() {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val distDir = "$prefix/lib/node_modules/openclaw/dist"

        val patchCmd = """
            DIST="$distDir"
            [ ! -d "${'$'}DIST" ] && echo "dist not found" && exit 0

            # 1. Patch runner-*.js: catch network interface errors
            for f in ${'$'}DIST/runner-*.js; do
                [ ! -f "${'$'}f" ] && continue
                grep -q 'Unhandled promise rejection' "${'$'}f" || continue
                sed -i 's|console.error("\[openclaw\] Unhandled promise rejection:", formatUncaughtError(reason));|if (reason \&\& reason.message \&\& reason.message.includes("interface")) { console.warn("[openclaw] Non-fatal network interface error (continuing):", formatUncaughtError(reason)); return; } console.error("[openclaw] Unhandled promise rejection:", formatUncaughtError(reason));|' "${'$'}f"
                echo "patched runner: ${'$'}f"
            done

            # 2. Patch gateway-cli-*.js: allow device-auth bypass
            for f in ${'$'}DIST/gateway-cli-*.js; do
                [ ! -f "${'$'}f" ] && continue
                grep -q 'evaluateMissingDeviceIdentity' "${'$'}f" || continue
                sed -i 's|function evaluateMissingDeviceIdentity(params) {|function evaluateMissingDeviceIdentity(params) { if (params.controlUiAuthPolicy.allowBypass) return { kind: "allow" };|' "${'$'}f"
                echo "patched gateway-cli: ${'$'}f"
            done

            echo "Gateway Android patches applied"
        """.trimIndent()

        runInPrefix(patchCmd) { Log.d(TAG, "[gw-patch] $it") }
        Log.i(TAG, "Gateway Android patches applied")
    }

    /**
     * Write openclaw.json with loopback + gateway token auth defaults, and
     * auth-profiles.json (OpenAI token from existing Codex login). We keep
     * allowInsecureAuth=true for local loopback ergonomics, but do not disable
     * device auth globally.
     */
    fun configureOpenClawAuth() {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val openclawDir = File(paths.homeDir, ".openclaw")
        openclawDir.mkdirs()

        val configFile = File(openclawDir, "openclaw.json")
        val root = if (configFile.exists()) {
            runCatching { JSONObject(configFile.readText()) }.getOrElse {
                Log.w(TAG, "openclaw.json parse failed, rebuilding safe defaults")
                JSONObject()
            }
        } else {
            JSONObject()
        }
        sanitizeHeartbeatDefaults(root)

        val meta = ensureObject(root, "meta")
        meta.put("lastTouchedVersion", getInstalledOpenClawVersion() ?: OPENCLAW_TARGET_VERSION)
        meta.put("lastTouchedAt", java.time.Instant.now().toString())

        val commands = ensureObject(root, "commands")
        if (!commands.has("native")) commands.put("native", "auto")
        if (!commands.has("nativeSkills")) commands.put("nativeSkills", "auto")
        if (!commands.has("restart")) commands.put("restart", true)
        if (!commands.has("ownerDisplay")) commands.put("ownerDisplay", "raw")

        val gateway = ensureObject(root, "gateway")
        gateway.put("mode", "local")
        gateway.put("bind", "loopback")
        val controlUi = ensureObject(gateway, "controlUi")
        controlUi.put("enabled", true)
        controlUi.put(
            "allowedOrigins",
            JSONArray()
                .put("http://127.0.0.1:$OPENCLAW_CONTROL_UI_PORT")
                .put("http://localhost:$OPENCLAW_CONTROL_UI_PORT"),
        )
        controlUi.put("allowInsecureAuth", true)
        controlUi.put("dangerouslyDisableDeviceAuth", false)
        val auth = ensureObject(gateway, "auth")
        auth.put("mode", "token")
        val gatewayToken = auth.optString("token", "").trim().ifEmpty { generateGatewayToken() }
        auth.put("token", gatewayToken)

        val agents = ensureObject(root, "agents")
        val defaults = ensureObject(agents, "defaults")
        val model = ensureObject(defaults, "model")
        if (model.optString("primary", "").trim().isEmpty()) {
            // Keep legacy default for first install only; never override existing user model choice.
            model.put("primary", "openai-codex/gpt-5.3-codex")
        }
        val heartbeat = ensureObject(defaults, "heartbeat")
        // OpenClaw 2026.3.2 schema does not accept "enabled" in defaults.heartbeat.
        // Keep only supported fields and auto-clean legacy invalid keys to prevent
        // gateway startup from failing with "Invalid config".
        if (heartbeat.has("enabled")) {
            heartbeat.remove("enabled")
        }
        heartbeat.put("every", "20m")
        if (heartbeat.optString("target", "").isBlank()) {
            heartbeat.put("target", "none")
        }
        if (heartbeat.optString("prompt", "").isBlank()) {
            heartbeat.put(
                "prompt",
                "Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. Do not infer or repeat old tasks from prior chats. If nothing needs attention, reply HEARTBEAT_OK.",
            )
        }

        val memorySearch = ensureObject(defaults, "memorySearch")
        if (memorySearch.optString("provider", "").isBlank()) {
            memorySearch.put("provider", "local")
        }
        if (memorySearch.optString("fallback", "").isBlank()) {
            memorySearch.put("fallback", "none")
        }
        val localMemory = ensureObject(memorySearch, "local")
        if (localMemory.optString("modelPath", "").isBlank()) {
            localMemory.put(
                "modelPath",
                "hf:ggml-org/embeddinggemma-300m-qat-q8_0-GGUF/embeddinggemma-300m-qat-Q8_0.gguf",
            )
        }
        val store = ensureObject(memorySearch, "store")
        val vector = ensureObject(store, "vector")
        vector.put("enabled", false)
        if (vector.has("extensionPath")) {
            vector.remove("extensionPath")
        }

        ensureAnyClawSearchPlugin(paths.homeDir)
        ensureAnyClawGithubPlugin(paths.homeDir)
        ensureAnyClawDevicePlugin(paths.homeDir)
        ensureAnyClawRuntimePlugin(paths.homeDir)
        ensureAnyClawUbuntuPlugin(paths.homeDir)
        val plugins = ensureObject(root, "plugins")
        plugins.put("enabled", true)
        val entries = ensureObject(plugins, "entries")
        val searchSuiteEntry = ensureObject(entries, ANYCLAW_SEARCH_PLUGIN_ID)
        searchSuiteEntry.put("enabled", true)
        val searchSuiteConfig = ensureObject(searchSuiteEntry, "config")
        if (!searchSuiteConfig.has("timeoutSeconds")) searchSuiteConfig.put("timeoutSeconds", 20)
        if (!searchSuiteConfig.has("maxResults")) searchSuiteConfig.put("maxResults", 6)
        if (!searchSuiteConfig.has("maxChars")) searchSuiteConfig.put("maxChars", 12000)
        if (!searchSuiteConfig.has("webBridgeUrl")) searchSuiteConfig.put("webBridgeUrl", "http://127.0.0.1:${ShizukuShellBridgeServer.BRIDGE_PORT}/web/call")
        if (!searchSuiteConfig.has("tavilyBaseUrl")) searchSuiteConfig.put("tavilyBaseUrl", "https://api.tavily.com/search")
        val configuredUa = searchSuiteConfig.optString("userAgent", "").trim()
        if (configuredUa.isEmpty() || configuredUa.startsWith("AnyClawSearchSuite/1.")) {
            searchSuiteConfig.put("userAgent", "AnyClawSearchSuite/1.4")
        }

        val githubSuiteEntry = ensureObject(entries, ANYCLAW_GITHUB_PLUGIN_ID)
        githubSuiteEntry.put("enabled", true)
        val githubSuiteConfig = ensureObject(githubSuiteEntry, "config")
        if (!githubSuiteConfig.has("timeoutSeconds")) githubSuiteConfig.put("timeoutSeconds", 35)
        if (!githubSuiteConfig.has("githubApiBaseUrl")) githubSuiteConfig.put("githubApiBaseUrl", "https://api.github.com")
        if (!githubSuiteConfig.has("allowTerminal")) githubSuiteConfig.put("allowTerminal", true)
        if (!githubSuiteConfig.has("terminalTimeoutMs")) githubSuiteConfig.put("terminalTimeoutMs", 30000)
        if (!githubSuiteConfig.has("workspaceRoot")) githubSuiteConfig.put("workspaceRoot", "${paths.homeDir}/.openclaw/workspace")
        val githubUa = githubSuiteConfig.optString("userAgent", "").trim()
        if (githubUa.isEmpty() || githubUa.startsWith("AnyClawGithubSuite/0.")) {
            githubSuiteConfig.put("userAgent", "AnyClawGithubSuite/1.0")
        }

        val deviceSuiteEntry = ensureObject(entries, ANYCLAW_DEVICE_PLUGIN_ID)
        deviceSuiteEntry.put("enabled", true)
        val deviceSuiteConfig = ensureObject(deviceSuiteEntry, "config")
        if (!deviceSuiteConfig.has("timeoutSeconds")) deviceSuiteConfig.put("timeoutSeconds", 20)
        if (!deviceSuiteConfig.has("screenshotDir")) deviceSuiteConfig.put("screenshotDir", "/sdcard/Download/AnyClawShots")
        if (!deviceSuiteConfig.has("uiDumpPath")) deviceSuiteConfig.put("uiDumpPath", "/sdcard/Download/AnyClawShots/ui_dump.xml")
        if (!deviceSuiteConfig.has("maxUiNodes")) deviceSuiteConfig.put("maxUiNodes", 180)
        if (!deviceSuiteConfig.has("inputVerifyReadback")) deviceSuiteConfig.put("inputVerifyReadback", false)
        if (!deviceSuiteConfig.has("inputImePriority")) {
            deviceSuiteConfig.put(
                "inputImePriority",
                JSONArray()
                    .put("com.android.adbkeyboard/.AdbIME")
                    .put("com.kevinluo.autoglm/.input.AutoGLMKeyboardService"),
            )
        }

        val runtimeSuiteEntry = ensureObject(entries, ANYCLAW_RUNTIME_PLUGIN_ID)
        runtimeSuiteEntry.put("enabled", true)
        val runtimeSuiteConfig = ensureObject(runtimeSuiteEntry, "config")
        if (!runtimeSuiteConfig.has("timeoutSeconds")) runtimeSuiteConfig.put("timeoutSeconds", 30)
        if (!runtimeSuiteConfig.has("codexApiBaseUrl")) runtimeSuiteConfig.put("codexApiBaseUrl", "http://127.0.0.1:$SERVER_PORT")
        if (!runtimeSuiteConfig.has("runtimeDoctorPath")) {
            runtimeSuiteConfig.put(
                "runtimeDoctorPath",
                "${paths.homeDir}/.openclaw/workspace/scripts/runtime-env-doctor.sh",
            )
        }

        val ubuntuSuiteEntry = ensureObject(entries, ANYCLAW_UBUNTU_PLUGIN_ID)
        ubuntuSuiteEntry.put("enabled", true)
        val ubuntuSuiteConfig = ensureObject(ubuntuSuiteEntry, "config")
        if (!ubuntuSuiteConfig.has("timeoutSeconds")) ubuntuSuiteConfig.put("timeoutSeconds", 45)
        if (!ubuntuSuiteConfig.has("installTimeoutSeconds")) ubuntuSuiteConfig.put("installTimeoutSeconds", 1800)
        val linuxRuntimePaths = OfflineLinuxRuntimeInstaller.getRuntimePaths(context)
        if (!ubuntuSuiteConfig.has("runtimeRoot")) {
            ubuntuSuiteConfig.put("runtimeRoot", linuxRuntimePaths.runtimeRoot.absolutePath)
        }
        if (!ubuntuSuiteConfig.has("runtimeShellPath")) {
            ubuntuSuiteConfig.put("runtimeShellPath", linuxRuntimePaths.shellScript.absolutePath)
        }
        if (!ubuntuSuiteConfig.has("runtimeTmpDir")) {
            ubuntuSuiteConfig.put("runtimeTmpDir", linuxRuntimePaths.tmpDir.absolutePath)
        }
        if (!ubuntuSuiteConfig.has("fakeSysdataPath")) {
            ubuntuSuiteConfig.put("fakeSysdataPath", linuxRuntimePaths.fakeSysdataScript.absolutePath)
        }
        if (!ubuntuSuiteConfig.has("workspaceRoot")) ubuntuSuiteConfig.put("workspaceRoot", "${paths.homeDir}/.openclaw/workspace")
        if (!ubuntuSuiteConfig.has("sessionTimeoutSeconds")) ubuntuSuiteConfig.put("sessionTimeoutSeconds", 600)
        if (!ubuntuSuiteConfig.has("maxSessionOutputBytes")) ubuntuSuiteConfig.put("maxSessionOutputBytes", 524288)

        val allow = plugins.optJSONArray("allow")
        if (allow != null && allow.length() > 0) {
            if (!jsonArrayContains(allow, ANYCLAW_SEARCH_PLUGIN_ID)) {
                allow.put(ANYCLAW_SEARCH_PLUGIN_ID)
            }
            if (!jsonArrayContains(allow, ANYCLAW_GITHUB_PLUGIN_ID)) {
                allow.put(ANYCLAW_GITHUB_PLUGIN_ID)
            }
            if (!jsonArrayContains(allow, ANYCLAW_DEVICE_PLUGIN_ID)) {
                allow.put(ANYCLAW_DEVICE_PLUGIN_ID)
            }
            if (!jsonArrayContains(allow, ANYCLAW_RUNTIME_PLUGIN_ID)) {
                allow.put(ANYCLAW_RUNTIME_PLUGIN_ID)
            }
            if (!jsonArrayContains(allow, ANYCLAW_UBUNTU_PLUGIN_ID)) {
                allow.put(ANYCLAW_UBUNTU_PLUGIN_ID)
            }
        }

        configFile.writeText(root.toString(2))
        Log.i(TAG, "Updated OpenClaw config at $configFile")
        ensureRuntimeDoctorScripts()
        ensureMainSessionIndexHealthy()

        // Copy the Codex access_token into OpenClaw's auth-profiles.json.
        // The profile needs: version=1, type="token", provider="openai-codex",
        // and the canonical profile ID "openai-codex:codex-cli".
        // Must be written to both global and agent-specific directories.
        val authJson = File(paths.homeDir, ".codex/auth.json")
        if (authJson.exists()) {
            val copyScript = """
                node -e "
                  const fs = require('fs');
                  const path = require('path');
                  const auth = JSON.parse(fs.readFileSync('${'$'}HOME/.codex/auth.json','utf8'));
                  const token = auth.tokens && auth.tokens.access_token;
                  if (!token) { console.error('No access_token in codex auth'); process.exit(1); }
                  function readJson(filePath) {
                    try {
                      if (!fs.existsSync(filePath)) return {};
                      return JSON.parse(fs.readFileSync(filePath,'utf8'));
                    } catch (_) {
                      return {};
                    }
                  }
                  function mergeProfiles(filePath) {
                    const prev = readJson(filePath);
                    const merged = {
                      version: 1,
                      profiles: Object.assign({}, prev.profiles || {}),
                      order: Array.isArray(prev.order) ? prev.order.slice() : []
                    };
                    merged.profiles['openai-codex:codex-cli'] = {
                      type: 'token',
                      provider: 'openai-codex',
                      token: token,
                      source: 'codex-auth',
                      createdAt: new Date().toISOString()
                    };
                    merged.profiles['openai:codex'] = {
                      type: 'token',
                      provider: 'openai',
                      token: token,
                      source: 'codex-auth',
                      createdAt: new Date().toISOString()
                    };
                    const ordered = ['openai-codex:codex-cli', 'openai:codex', ...merged.order];
                    merged.order = Array.from(new Set(ordered));
                    fs.writeFileSync(filePath, JSON.stringify(merged, null, 2));
                  }
                  mergeProfiles('${'$'}HOME/.openclaw/auth-profiles.json');
                  const agentDir = '${'$'}HOME/.openclaw/agents/main/agent';
                  fs.mkdirSync(agentDir, { recursive: true });
                  mergeProfiles(path.join(agentDir, 'auth-profiles.json'));
                  console.log('OpenClaw auth-profiles.json merged (global + agent)');
                " 2>&1
            """.trimIndent()
            runInPrefix(copyScript) { Log.d(TAG, "[openclaw-auth] $it") }
        } else {
            Log.w(TAG, "Codex auth.json not found — OpenClaw will lack API credentials")
        }
    }

    fun runOpenClawPreflight(onProgress: (String) -> Unit): Boolean {
        onProgress("Repairing OpenClaw toolchain links…")
        ensureOpenClawToolchain(onProgress)
        onProgress("Validating ar/ranlib preflight…")
        if (runOpenClawToolchainPreflight(onProgress)) {
            return true
        }

        onProgress("Preflight failed, running recovery scripts…")
        runExternalRecoveryScripts(onProgress)
        ensureOpenClawToolchain(onProgress)
        return runOpenClawToolchainPreflight(onProgress)
    }

    /**
     * Keep gateway chat history payload bounded on mobile to reduce ANR risk on
     * large transcripts. We patch all gateway-cli bundles shipped with OpenClaw
     * because hashed filenames may vary across updates.
     */
    private fun ensureOpenClawGatewayHistoryByteCap() {
        val paths = BootstrapInstaller.getPaths(context)
        val distDir = File(paths.prefixDir, "lib/node_modules/openclaw/dist")
        if (!distDir.exists()) return

        val script = """
            node <<'NODE'
            const fs = require('fs');
            const path = require('path');
            const dist = ${"'" + distDir.absolutePath + "'"};
            let scanned = 0;
            let changed = 0;
            try {
              const files = fs.readdirSync(dist).filter((name) => /^gateway-cli-.*\.js$/.test(name));
              for (const file of files) {
                const full = path.join(dist, file);
                let text = fs.readFileSync(full, 'utf8');
                scanned += 1;
                const next = text.replace(
                  /const DEFAULT_MAX_CHAT_HISTORY_MESSAGES_BYTES\s*=\s*\d+\s*\*\s*1024\s*\*\s*1024;/,
                  'const DEFAULT_MAX_CHAT_HISTORY_MESSAGES_BYTES = ${OPENCLAW_CHAT_HISTORY_MAX_BYTES};',
                );
                if (next !== text) {
                  fs.writeFileSync(full, next, 'utf8');
                  changed += 1;
                }
              }
              console.log('gateway-history-cap scanned=' + scanned + ' changed=' + changed);
            } catch (error) {
              console.log('gateway-history-cap error=' + String(error));
            }
            NODE
        """.trimIndent()
        runInPrefix(script) { Log.d(TAG, "[openclaw-gw-cap] $it") }
    }

    /**
     * Patch control-ui bundle to use a smaller default history window and make
     * it user-adjustable via localStorage-backed limits.
     */
    private fun ensureOpenClawControlUiHistoryPatch(controlUiRoot: String) {
        // Keep this as a no-op for stability: directly patching hashed/minified
        // control-ui bundles is brittle across upstream updates and can break chat
        // startup. We now enforce history limits via runtime bootstrap injection.
        Log.d(TAG, "[openclaw-ui-patch] skipped (runtime-only mode) root=$controlUiRoot")
    }

    /**
     * Start the OpenClaw WebSocket gateway. Requires openclaw.json to be
     * configured first via [configureOpenClawAuth].
     *
     * Before starting:
     * 1. Kill any orphaned gateway process (scanning /proc, PID files).
     * 2. Clear lock/pid files and stale plugin transpile caches.
     *
     * Uses gateway token auth and persistent local state. We intentionally
     * avoid deleting identity/session state on every startup because that can
     * cause recurrent "Loading chat" failures after upgrades.
     */
    fun startOpenClawGateway(): Boolean {
        if (openClawGatewayProcess != null) {
            try {
                openClawGatewayProcess!!.exitValue()
                openClawGatewayProcess = null
            } catch (_: IllegalThreadStateException) {
                if (isOpenClawGatewayResponsive()) {
                    Log.i(TAG, "OpenClaw gateway already running and responsive")
                    return true
                }
                Log.w(TAG, "Gateway process is alive but not responsive; restarting")
                try {
                    openClawGatewayProcess?.destroyForcibly()
                } catch (_: Exception) {
                }
                openClawGatewayProcess = null
                Thread.sleep(320)
            }
        }

        val paths = BootstrapInstaller.getPaths(context)
        sanitizeHeartbeatConfigOnDisk(paths.homeDir)
        ensureOpenClawGatewayHistoryByteCap()
        if (!ensureOpenClawNativeBinding { Log.d(TAG, "[openclaw-davey] $it") }) {
            Log.w(TAG, "OpenClaw native binding repair failed before gateway startup; continuing because gateway can still run without the preflight cache")
        }

        // Kill any orphaned gateway processes and reset all device tokens.
        runInPrefix("""
            # Kill by PID file
            for pidfile in ${paths.prefixDir}/tmp/openclaw*/gateway.pid ${paths.prefixDir}/tmp/openclaw/gateway.pid; do
                [ -f "${'$'}pidfile" ] && kill -9 ${'$'}(cat "${'$'}pidfile" 2>/dev/null) 2>/dev/null
            done
            # Scan /proc for any node process bound to the gateway port
            for pid in ${'$'}(ls /proc 2>/dev/null | grep '^[0-9]'); do
                cmdline=${'$'}(cat /proc/${'$'}pid/cmdline 2>/dev/null | tr '\0' ' ')
                if echo "${'$'}cmdline" | grep -q "openclaw gateway run"; then
                    kill -9 ${'$'}pid 2>/dev/null
                elif echo "${'$'}cmdline" | grep -q "${OPENCLAW_GATEWAY_PORT}"; then
                    kill -9 ${'$'}pid 2>/dev/null
                fi
            done
            # Clear stale lock/pid files
            rm -f ${paths.prefixDir}/tmp/openclaw*/gateway.lock ${paths.prefixDir}/tmp/openclaw*/gateway.pid 2>/dev/null
            rm -f ${paths.prefixDir}/tmp/openclaw/gateway.lock ${paths.prefixDir}/tmp/openclaw/gateway.pid 2>/dev/null
            # Clear plugin transpile/runtime caches so updated extensions are always reloaded
            rm -rf ${paths.homeDir}/.cache/jiti 2>/dev/null
            rm -rf ${paths.homeDir}/.openclaw/.cache/jiti ${paths.homeDir}/.openclaw/.cache/plugins 2>/dev/null
            sleep 1
            echo "Gateway state cleaned"
        """.trimIndent()) { Log.d(TAG, "[openclaw-gw] $it") }

        val env = buildEnvironment(paths).toMutableMap()
        // Keep OpenClaw isolated from Termux preloads so Ubuntu runtime tools
        // don't inherit a broken applet/linker context.
        env.remove("LD_PRELOAD")
        env.remove("TERMUX_PREFIX")
        env.remove("TERMUX__PREFIX")
        env["OPENCLAW_UBUNTU_ISOLATED"] = "1"
        val shell = runtimeShell()
        val cmd = "exec openclaw gateway run --force --port $OPENCLAW_GATEWAY_PORT 2>&1"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        openClawGatewayProcess = proc
        startProcessLogThread(proc, "openclaw-gw")

        // Heartbeat bootstrap must not depend on a short fixed gateway warmup window.
        // Run it asynchronously with retries so slow startups still get cron/task registration.
        ensureHeartbeatBootstrapAsync(paths.homeDir)

        if (waitForOpenClawGatewayReady(proc, timeoutMs = 22_000L, pollMs = 520L)) {
            Log.i(TAG, "OpenClaw gateway started on port $OPENCLAW_GATEWAY_PORT")
            return true
        }

        Log.w(TAG, "OpenClaw gateway process launched but not responsive yet")
        logOpenClawGatewayDiagnostics("start-not-responsive")
        return false
    }

    /**
     * Start a lightweight Node.js static file server to serve the OpenClaw
     * Control UI on [OPENCLAW_CONTROL_UI_PORT]. The UI assets live inside
     * the installed openclaw npm package at dist/control-ui/.
     */
    fun startOpenClawControlUiServer(): Boolean {
        if (openClawControlUiProcess != null) {
            try {
                openClawControlUiProcess!!.exitValue()
                openClawControlUiProcess = null
            } catch (_: IllegalThreadStateException) {
                Log.i(TAG, "OpenClaw Control UI server already running")
                return true
            }
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env.remove("LD_PRELOAD")
        env.remove("TERMUX_PREFIX")
        env.remove("TERMUX__PREFIX")
        env["OPENCLAW_UBUNTU_ISOLATED"] = "1"
        val prefix = paths.prefixDir
        val controlUiRoot = "$prefix/lib/node_modules/openclaw/dist/control-ui"

        if (!File(controlUiRoot).exists()) {
            Log.w(TAG, "OpenClaw control-ui directory not found at $controlUiRoot")
            return false
        }
        ensureOpenClawControlUiHistoryPatch(controlUiRoot)

        val shell = runtimeShell()
        val serverScript = """
            node -e "
              const http = require('http');
              const fs = require('fs');
              const path = require('path');
              const { URL } = require('url');
              const root = '$controlUiRoot';
              const gatewayConfigPath = path.join(process.env.HOME || '', '.openclaw', 'openclaw.json');
              let gatewayToken = '';
              try {
                const parsed = JSON.parse(fs.readFileSync(gatewayConfigPath, 'utf8'));
                gatewayToken = (((parsed || {}).gateway || {}).auth || {}).token || '';
              } catch (_) {
                gatewayToken = '';
              }
              const mimeTypes = {
                '.html':'text/html','.js':'application/javascript',
                '.css':'text/css','.json':'application/json',
                '.svg':'image/svg+xml','.png':'image/png',
                '.ico':'image/x-icon',
                '.woff2':'font/woff2','.woff':'font/woff',
              };
              const localGatewayUrl = 'ws://127.0.0.1:$OPENCLAW_GATEWAY_PORT';
              function injectBootstrap(html) {
                const snippet = '<script>(function(){try{' +
                  'var params=new URLSearchParams(location.search);' +
                  'var pref=params.get(\"localePref\")||localStorage.getItem(\"anyclaw.ui.localePref\")||\"system\";' +
                  'localStorage.setItem(\"anyclaw.ui.localePref\",pref);' +
                  'var nav=(navigator.language||\"\").toLowerCase();' +
                  'var systemLocale=nav.indexOf(\"zh\")===0?\"zh-CN\":\"en\";' +
                  'var locale=(pref===\"system\")?systemLocale:(pref===\"zh-CN\"?\"zh-CN\":\"en\");' +
                  'localStorage.setItem(\"openclaw.i18n.locale\",locale);' +
                  'var settingsKey=\"openclaw.control.settings.v1\";' +
                  'var settings={};' +
                  'try{settings=JSON.parse(localStorage.getItem(settingsKey)||\"{}\");}catch(_){}' +
                  'if(!settings||typeof settings!==\"object\"){settings={};}' +
                  'if(typeof settings.chatFocusMode!==\"boolean\"){settings.chatFocusMode=true;}' +
                  'if(typeof settings.navCollapsed!==\"boolean\"){settings.navCollapsed=true;}' +
                  'var sessionFromUrl=params.get(\"session\");' +
                  'if(sessionFromUrl&&sessionFromUrl.trim()){settings.sessionKey=sessionFromUrl.trim();settings.lastActiveSessionKey=sessionFromUrl.trim();}' +
                  'settings.locale=locale;' +
                  'settings.theme=settings.theme||\"system\";' +
                  'localStorage.setItem(settingsKey,JSON.stringify(settings));' +
                  'var isZh=locale===\"zh-CN\";' +
                  'var HISTORY_DEFAULT=${OPENCLAW_CHAT_HISTORY_LIMIT_DEFAULT},HISTORY_STEP=${OPENCLAW_CHAT_HISTORY_LIMIT_STEP},HISTORY_MIN=${OPENCLAW_CHAT_HISTORY_LIMIT_MIN},HISTORY_MAX=${OPENCLAW_CHAT_HISTORY_LIMIT_MAX};' +
                  'function clampHistory(v){var n=Number(v);if(!Number.isFinite(n)){n=HISTORY_DEFAULT;}n=Math.floor(n);if(n<HISTORY_MIN){n=HISTORY_MIN;}if(n>HISTORY_MAX){n=HISTORY_MAX;}return n;}' +
                  'function getHistoryLimit(){try{return clampHistory(localStorage.getItem(\"anyclaw.chat.history.limit\"));}catch(_){return HISTORY_DEFAULT;}}' +
                  'function setHistoryLimit(next){var n=clampHistory(next);try{localStorage.setItem(\"anyclaw.chat.history.limit\",String(n));localStorage.setItem(\"anyclaw.chat.render.limit\",String(n));}catch(_){}return n;}' +
                  'var fromUrl=params.get(\"historyLimit\");if(fromUrl){setHistoryLimit(fromUrl);}' +
                  'setHistoryLimit(getHistoryLimit());' +
                  'function replaceFirstTextNode(el,next){if(!el){return;}for(var i=0;i<el.childNodes.length;i++){var n=el.childNodes[i];if(n&&n.nodeType===3){n.nodeValue=\" \"+next+\" \";return;}}if(!el.children||el.children.length===0){el.textContent=next;}}' +
                  'function normalizeSpace(text){var s=(text||\"\");return s.replace(/\\s+/g,\" \").trim();}' +
                  'function installHistoryControls(){var wrap=document.getElementById(\"anyclaw-history-controls\");if(!wrap){wrap=document.createElement(\"div\");wrap.id=\"anyclaw-history-controls\";wrap.style.position=\"fixed\";wrap.style.left=\"12px\";wrap.style.top=\"56px\";wrap.style.zIndex=\"2147482999\";wrap.style.display=\"flex\";wrap.style.gap=\"8px\";wrap.style.alignItems=\"center\";wrap.style.flexWrap=\"wrap\";wrap.style.maxWidth=\"92vw\";document.body.appendChild(wrap);}wrap.innerHTML=\"\";var current=getHistoryLimit();var more=document.createElement(\"button\");more.type=\"button\";more.textContent=isZh?(\"加载更早历史 +\"+HISTORY_STEP):(\"Load older +\"+HISTORY_STEP);more.style.padding=\"6px 10px\";more.style.borderRadius=\"8px\";more.style.border=\"1px solid rgba(255,255,255,0.25)\";more.style.background=\"rgba(17,24,39,0.88)\";more.style.color=\"#fff\";more.addEventListener(\"click\",function(){var next=setHistoryLimit(current+HISTORY_STEP);var u=new URL(location.href);u.searchParams.set(\"historyLimit\",String(next));location.assign(u.toString());});var reset=document.createElement(\"button\");reset.type=\"button\";reset.textContent=isZh?\"恢复轻量\":\"Reset lite\";reset.style.padding=\"6px 10px\";reset.style.borderRadius=\"8px\";reset.style.border=\"1px solid rgba(255,255,255,0.25)\";reset.style.background=\"rgba(17,24,39,0.88)\";reset.style.color=\"#fff\";reset.addEventListener(\"click\",function(){var next=setHistoryLimit(HISTORY_DEFAULT);var u=new URL(location.href);u.searchParams.set(\"historyLimit\",String(next));location.assign(u.toString());});var tip=document.createElement(\"span\");tip.textContent=isZh?(\"历史窗口 \"+current):(\"History window \"+current);tip.style.fontSize=\"12px\";tip.style.padding=\"6px 8px\";tip.style.borderRadius=\"8px\";tip.style.background=\"rgba(17,24,39,0.82)\";tip.style.color=\"#fff\";wrap.appendChild(more);wrap.appendChild(reset);wrap.appendChild(tip);}' +
                  'function localizeStatic(){if(!isZh){return;}var map={\"New session\":\"新建会话\",\"Send\":\"发送\",\"Queue\":\"排队发送\",\"Stop\":\"停止\",\"Connect\":\"连接\",\"Refresh\":\"刷新\",\"Exit focus mode\":\"退出专注模式\"};document.querySelectorAll(\"button\").forEach(function(btn){var raw=normalizeSpace(btn.textContent||\"\");if(map[raw]){replaceFirstTextNode(btn,map[raw]);}var aria=normalizeSpace(btn.getAttribute(\"aria-label\")||\"\");if(map[aria]){btn.setAttribute(\"aria-label\",map[aria]);}if(aria===\"Remove queued message\"){btn.setAttribute(\"aria-label\",\"移除排队消息\");}var title=normalizeSpace(btn.getAttribute(\"title\")||\"\");if(map[title]){btn.setAttribute(\"title\",map[title]);}});document.querySelectorAll(\"textarea\").forEach(function(el){var p=normalizeSpace(el.getAttribute(\"placeholder\")||\"\");if(p.indexOf(\"Message\")===0){el.setAttribute(\"placeholder\",\"输入消息（回车发送，Shift+回车换行，可粘贴图片）\");}});document.querySelectorAll(\".muted\").forEach(function(el){var t=normalizeSpace(el.textContent||\"\");if(t===\"Loading chat…\"){el.textContent=\"正在加载聊天…\";}});document.querySelectorAll(\".chat-queue__title\").forEach(function(el){var t=normalizeSpace(el.textContent||\"\");if(t.indexOf(\"Queued (\")===0&&t.endsWith(\")\")){el.textContent=\"排队（\"+t.slice(8,t.length-1)+\"）\";}});document.querySelectorAll(\".chat-new-messages\").forEach(function(el){var t=normalizeSpace(el.textContent||\"\");if(t.indexOf(\"New messages\")===0){el.textContent=\"新消息\";}});}' +
                  'function makeSessionKey(current){var now=Date.now().toString(36);var key=(current||\"main\").trim();if(key.indexOf(\"agent:\")===0){var parts=key.split(\":\");var agent=(parts.length>1&&parts[1])?parts[1]:\"main\";return \"agent:\"+agent+\":mobile-\"+now;}return \"mobile-\"+now;}' +
                  'function patchChatHistoryRequest(){var app=document.querySelector(\"openclaw-app\");if(!app||!app.client||typeof app.client.request!==\"function\"){return;}if(app.client.__anyclawReqPatched===\"1\"){return;}var orig=app.client.request.bind(app.client);app.client.request=function(method,params){try{if(method===\"chat.history\"&&params&&typeof params===\"object\"){var capped=getHistoryLimit();var wanted=Number(params.limit);if(!Number.isFinite(wanted)){wanted=capped;}if(wanted>capped){wanted=capped;}params=Object.assign({},params,{limit:wanted});}}catch(_){}return orig(method,params);};app.client.__anyclawReqPatched=\"1\";}' +
                  'function openNewSessionDirect(){var app=document.querySelector(\"openclaw-app\");if(!app||!app.client||!app.connected){return;}var nextKey=makeSessionKey(app.sessionKey);app.client.request(\"sessions.patch\",{key:nextKey,label:\"新会话 \"+new Date().toLocaleString()}).then(function(){var nextUrl=new URL(location.href);nextUrl.searchParams.set(\"session\",nextKey);location.assign(nextUrl.toString());}).catch(function(){if(typeof app.handleSendChat===\"function\"){app.handleSendChat(\"/new\",{restoreDraft:true});}});}' +
                  'function wireNewSessionButton(){document.querySelectorAll(\"button\").forEach(function(btn){var label=normalizeSpace(btn.textContent||\"\");if(label!==\"New session\"&&label!==\"新建会话\"){return;}if(btn.dataset.anyclawNewBound===\"1\"){return;}btn.dataset.anyclawNewBound=\"1\";btn.addEventListener(\"click\",function(ev){try{ev.preventDefault();ev.stopPropagation();if(ev.stopImmediatePropagation){ev.stopImmediatePropagation();}}catch(_){}openNewSessionDirect();},true);if(isZh){replaceFirstTextNode(btn,\"新建会话\");}});}' +
                  'function installBackButton(){if(document.getElementById(\"anyclaw-back-codex\")){return;}var btn=document.createElement(\"button\");btn.id=\"anyclaw-back-codex\";btn.type=\"button\";btn.textContent=isZh?\"返回 Codex\":\"Back to Codex\";btn.setAttribute(\"aria-label\",btn.textContent);btn.style.position=\"fixed\";btn.style.left=\"12px\";btn.style.top=\"12px\";btn.style.zIndex=\"2147483000\";btn.style.padding=\"8px 12px\";btn.style.borderRadius=\"10px\";btn.style.border=\"1px solid rgba(255,255,255,0.25)\";btn.style.background=\"rgba(17,24,39,0.85)\";btn.style.color=\"#fff\";btn.style.fontSize=\"13px\";btn.addEventListener(\"click\",function(){location.href=\"http://127.0.0.1:18923/\";});document.body.appendChild(btn);}' +
                  'function installTraceToggle(){var id=\"anyclaw-trace-toggle\";var btn=document.getElementById(id);var u=new URL(location.href);var isSimple=u.searchParams.get(\"simple\")!==\"0\";if(!btn){btn=document.createElement(\"button\");btn.id=id;btn.type=\"button\";btn.style.position=\"fixed\";btn.style.left=\"12px\";btn.style.top=\"96px\";btn.style.zIndex=\"2147482998\";btn.style.padding=\"6px 10px\";btn.style.borderRadius=\"8px\";btn.style.border=\"1px solid rgba(255,255,255,0.25)\";btn.style.background=\"rgba(17,24,39,0.88)\";btn.style.color=\"#fff\";btn.style.fontSize=\"12px\";document.body.appendChild(btn);}btn.textContent=isZh?(isSimple?\"过程显示：关\":\"过程显示：开\"):(isSimple?\"Process view: off\":\"Process view: on\");btn.setAttribute(\"aria-label\",btn.textContent);btn.onclick=function(){var next=new URL(location.href);next.searchParams.set(\"simple\",isSimple?\"0\":\"1\");location.assign(next.toString());};}' +
                  'function runPatches(){patchChatHistoryRequest();localizeStatic();wireNewSessionButton();installBackButton();installTraceToggle();installHistoryControls();}' +
                  'var patchTimer=null;function schedulePatches(){if(patchTimer!==null){return;}patchTimer=setTimeout(function(){patchTimer=null;runPatches();},220);}runPatches();document.addEventListener(\"DOMContentLoaded\",runPatches,{once:true});window.addEventListener(\"load\",runPatches,{once:true});var moRoot=document.body||document.documentElement;if(moRoot){var observeUntil=Date.now()+20000;var mo=new MutationObserver(function(muts){if(Date.now()>observeUntil){mo.disconnect();return;}for(var i=0;i<muts.length;i++){var m=muts[i];if(m&&m.type===\"childList\"&&m.addedNodes&&m.addedNodes.length){schedulePatches();break;}}});mo.observe(moRoot,{childList:true,subtree:true});}' +
                  'var p=location.pathname||\"/\";' +
                  'if(p===\"/\"||p===\"/index.html\"){location.replace(\"/chat\"+location.search+location.hash);return;}' +
                  '}catch(_){}})();</' + 'script>';
                if (html.includes('</body>')) {
                  return html.replace('</body>', snippet + '</body>');
                }
                return html + snippet;
              }
              function ensureChatBootstrapUrl(reqUrl) {
                try {
                  const urlObj = new URL(reqUrl || '/', 'http://127.0.0.1');
                  const rawPath = decodeURIComponent(urlObj.pathname || '/');
                  const normalizedPath = rawPath.endsWith('/') && rawPath.length > 1 ? rawPath.slice(0, -1) : rawPath;
                  if (normalizedPath !== '/' && normalizedPath !== '/index.html' && normalizedPath !== '/chat') return null;
                  let changed = false;
                  if (!urlObj.searchParams.get('gatewayUrl')) {
                    urlObj.searchParams.set('gatewayUrl', localGatewayUrl);
                    changed = true;
                  }
                  if (!urlObj.searchParams.get('simple')) {
                    urlObj.searchParams.set('simple', '1');
                    changed = true;
                  }
                  if (gatewayToken && !urlObj.searchParams.get('token')) {
                    urlObj.searchParams.set('token', gatewayToken);
                    changed = true;
                  }
                  if (normalizedPath === '/' || normalizedPath === '/index.html') {
                    urlObj.pathname = '/chat';
                    changed = true;
                  }
                  if (!changed) return null;
                  return urlObj.pathname + (urlObj.search || '') + (urlObj.hash || '');
                } catch (_) {
                  return null;
                }
              }
              function sendStatic(res, filePath, data) {
                const ext = path.extname(filePath);
                const contentType = mimeTypes[ext] || 'application/octet-stream';
                if (contentType === 'text/html') {
                  const html = injectBootstrap(data.toString('utf8'));
                  res.writeHead(200, {'Content-Type':'text/html; charset=utf-8', 'Cache-Control':'no-store'});
                  res.end(html);
                  return;
                }
                res.writeHead(200, {'Content-Type': contentType});
                res.end(data);
              }
              http.createServer((req, res) => {
                const redirectTarget = ensureChatBootstrapUrl(req.url || '/');
                if (redirectTarget) {
                  res.writeHead(302, { 'Location': redirectTarget, 'Cache-Control': 'no-store' });
                  res.end();
                  return;
                }
                let pathname = '/';
                try {
                  const parsed = new URL(req.url || '/', 'http://127.0.0.1');
                  pathname = decodeURIComponent(parsed.pathname || '/');
                } catch (_) {
                  pathname = '/';
                }
                if (pathname === '/') pathname = '/index.html';
                const relativePath = pathname.startsWith('/') ? pathname.slice(1) : pathname;
                const fp = path.join(root, relativePath);
                if (!fp.startsWith(root)) { res.writeHead(403); return res.end(); }
                fs.readFile(fp, (err, data) => {
                  if (err) {
                    fs.readFile(path.join(root,'index.html'), (e2, d2) => {
                      if (e2 || !d2) {
                        res.writeHead(404, {'Content-Type':'text/plain; charset=utf-8'});
                        res.end('Not Found');
                        return;
                      }
                      sendStatic(res, path.join(root,'index.html'), d2);
                    });
                    return;
                  }
                  sendStatic(res, fp, data);
                });
              }).listen($OPENCLAW_CONTROL_UI_PORT, '127.0.0.1', () => console.log('Control UI on port $OPENCLAW_CONTROL_UI_PORT'));
            " 2>&1
        """.trimIndent()

        val pb = ProcessBuilder(shell, "-c", "exec $serverScript")
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        openClawControlUiProcess = proc
        startProcessLogThread(proc, "openclaw-ui")

        Thread.sleep(1000)
        Log.i(TAG, "OpenClaw Control UI server started on port $OPENCLAW_CONTROL_UI_PORT")
        return true
    }

    fun isOpenClawGatewayResponsive(): Boolean {
        if (!isOpenClawInstalled()) return false
        val code = runInPrefix(
            "openclaw gateway call health --json --params '{}' >/dev/null 2>&1",
        )
        return code == 0
    }

    fun disconnectOpenClawGateway(): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        openClawGatewayProcess?.destroy()
        openClawGatewayProcess = null
        openClawControlUiProcess?.destroy()
        openClawControlUiProcess = null

        runInPrefix(
            """
            for pidfile in ${paths.prefixDir}/tmp/openclaw*/gateway.pid ${paths.prefixDir}/tmp/openclaw/gateway.pid; do
                [ -f "${'$'}pidfile" ] && kill -9 ${'$'}(cat "${'$'}pidfile" 2>/dev/null) 2>/dev/null
            done
            for pid in ${'$'}(ls /proc 2>/dev/null | grep '^[0-9]'); do
                cmdline=${'$'}(cat /proc/${'$'}pid/cmdline 2>/dev/null | tr '\0' ' ')
                echo "${'$'}cmdline" | grep -q "openclaw gateway run" && kill -9 ${'$'}pid 2>/dev/null
                echo "${'$'}cmdline" | grep -q "${OPENCLAW_GATEWAY_PORT}" && kill -9 ${'$'}pid 2>/dev/null
                echo "${'$'}cmdline" | grep -q "${OPENCLAW_CONTROL_UI_PORT}" && kill -9 ${'$'}pid 2>/dev/null
            done
            rm -f ${paths.prefixDir}/tmp/openclaw*/gateway.lock ${paths.prefixDir}/tmp/openclaw*/gateway.pid 2>/dev/null
            rm -f ${paths.prefixDir}/tmp/openclaw/gateway.lock ${paths.prefixDir}/tmp/openclaw/gateway.pid 2>/dev/null
            """.trimIndent(),
        )

        Thread.sleep(500)
        return !isOpenClawGatewayResponsive()
    }

    fun reconnectOpenClawGateway(): Boolean {
        configureOpenClawAuth()
        var gatewayOk = startOpenClawGateway()
        if (!gatewayOk) {
            Log.w(TAG, "Gateway first reconnect attempt failed; retrying once")
            disconnectOpenClawGateway()
            Thread.sleep(460)
            configureOpenClawAuth()
            gatewayOk = startOpenClawGateway()
        }
        if (!gatewayOk) {
            logOpenClawGatewayDiagnostics("reconnect-failed")
            return false
        }
        startOpenClawControlUiServer()
        return waitForOpenClawGatewayReady(openClawGatewayProcess, timeoutMs = 10_000L, pollMs = 450L)
    }

    private fun waitForOpenClawGatewayReady(
        process: Process?,
        timeoutMs: Long,
        pollMs: Long,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (isOpenClawGatewayResponsive()) {
                return true
            }
            if (process != null) {
                try {
                    val code = process.exitValue()
                    Log.w(TAG, "OpenClaw gateway exited before ready with code=$code")
                    return false
                } catch (_: IllegalThreadStateException) {
                    // still running
                }
            }
            Thread.sleep(pollMs)
        }
        return isOpenClawGatewayResponsive()
    }

    private fun logOpenClawGatewayDiagnostics(reason: String) {
        val paths = BootstrapInstaller.getPaths(context)
        val cmd =
            """
            echo "[openclaw-gw-diagnose] reason=$reason"
            echo "[openclaw-gw-diagnose] ts=${'$'}(date +%s)"
            echo "[openclaw-gw-diagnose] openclaw=$(command -v openclaw || true)"
            echo "[openclaw-gw-diagnose] node=$(command -v node || true)"
            if [ -f "${paths.homeDir}/.openclaw/openclaw.json" ]; then
              echo "[openclaw-gw-diagnose] openclaw.json(bind/auth):"
              grep -E '"bind"|"auth"|"token"|"mode"' "${paths.homeDir}/.openclaw/openclaw.json" 2>/dev/null || true
            fi
            echo "[openclaw-gw-diagnose] port-listen:"
            ( /system/bin/toybox ss -ltn 2>/dev/null || ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null || true ) | grep "${OPENCLAW_GATEWAY_PORT}" || true
            echo "[openclaw-gw-diagnose] process-list:"
            ps -A 2>/dev/null | grep -E 'openclaw|node' | grep -E 'gateway|${OPENCLAW_GATEWAY_PORT}' || true
            echo "[openclaw-gw-diagnose] health-call:"
            openclaw gateway call health --json --params '{}' 2>&1 || true
            """.trimIndent()
        runInPrefix(cmd) { line ->
            Log.w(TAG, line)
        }
    }

    private fun ensureHeartbeatBootstrap() {
        val script =
            """
            set +e
            mkdir -p "${'$'}HOME/.openclaw/workspace" "${'$'}HOME/.openclaw-android/state"
            HB_FILE="${'$'}HOME/.openclaw/workspace/HEARTBEAT.md"
            DEFAULT_PROMPT="Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. Do not infer or repeat old tasks from prior chats. If nothing needs attention, reply HEARTBEAT_OK."
            STATE_DIR="${'$'}HOME/.openclaw-android/state"
            JOB_NAME="anyclaw-heartbeat-main"
            EVERY="20m"

            if [ ! -f "${'$'}HB_FILE" ] || [ -z "${'$'}(grep -Ev '^[[:space:]]*(#|$)' "${'$'}HB_FILE" 2>/dev/null)" ]; then
              cat > "${'$'}HB_FILE" <<'EOF'
# HEARTBEAT.md
# AnyClaw auto-bootstrap tasks (safe defaults)
1) Check whether gateway and core tools are healthy.
2) If there is no pending task, reply HEARTBEAT_OK.
3) If a blocking failure appears, summarize root cause in one short paragraph.
EOF
            fi

            for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
              openclaw gateway call health --json --params '{}' >/dev/null 2>&1 && break
              sleep 2
            done

            node <<'NODE'
            const { spawnSync } = require('child_process');
            const fs = require('fs');
            const path = require('path');
            function runOpenClaw(args) {
              const res = spawnSync('openclaw', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
              const code = typeof res.status === 'number' ? res.status : 1;
              const errObj = res.error ? String(res.error) : '';
              return {
                ok: code === 0 && !errObj,
                out: String(res.stdout || '').trim(),
                err: [String(res.stderr || '').trim(), errObj].filter(Boolean).join('\n').trim(),
                code
              };
            }
            const stateDir = path.join(process.env.HOME || '', '.openclaw-android', 'state');
            fs.mkdirSync(stateDir, { recursive: true });
            const configPath = path.join(process.env.HOME || '', '.openclaw', 'openclaw.json');
            const NAME = 'anyclaw-heartbeat-main';
            const EVERY = '20m';
            const EVERY_MS = 20 * 60 * 1000;
            const PROMPT = 'Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. Do not infer or repeat old tasks from prior chats. If nothing needs attention, reply HEARTBEAT_OK.';
            const summary = {
              name: NAME,
              every: EVERY,
              configuredAt: new Date().toISOString(),
              attempts: [],
              final: {}
            };

            function sanitizeConfig() {
              try {
                if (!fs.existsSync(configPath)) {
                  return { ok: true, changed: false, reason: 'config_missing' };
                }
                const raw = fs.readFileSync(configPath, 'utf8');
                const parsed = JSON.parse(raw || '{}');
                const hb = (((parsed || {}).agents || {}).defaults || {}).heartbeat;
                if (!hb || typeof hb !== 'object') {
                  return { ok: true, changed: false, reason: 'heartbeat_missing' };
                }
                if (!Object.prototype.hasOwnProperty.call(hb, 'enabled')) {
                  return { ok: true, changed: false, reason: 'already_clean' };
                }
                delete hb.enabled;
                fs.writeFileSync(configPath, JSON.stringify(parsed, null, 2));
                return { ok: true, changed: true };
              } catch (error) {
                return { ok: false, changed: false, error: String(error) };
              }
            }

            summary.attempts.push({ step: 'heartbeat_config_sanitize', result: sanitizeConfig() });

            let hbEnable = runOpenClaw(['system', 'heartbeat', 'enable', '--json']);
            summary.attempts.push({ step: 'heartbeat_enable', result: hbEnable });
            const hbErr = ((hbEnable.err || '') + '\n' + (hbEnable.out || '')).toLowerCase();
            if (!hbEnable.ok && hbErr.includes('unrecognized key') && hbErr.includes('heartbeat') && hbErr.includes('enabled')) {
              summary.attempts.push({ step: 'heartbeat_config_sanitize_retry', result: sanitizeConfig() });
              hbEnable = runOpenClaw(['system', 'heartbeat', 'enable', '--json']);
              summary.attempts.push({ step: 'heartbeat_enable_retry', result: hbEnable });
            }

            function parseJobs(rawObj) {
              try {
                const parsed = JSON.parse(rawObj?.out || '{}');
                return Array.isArray(parsed.jobs) ? parsed.jobs : [];
              } catch (_) {
                return [];
              }
            }

            function findByName(jobs) {
              return jobs.find((j) => j && j.name === NAME);
            }

            let jobsRaw = runOpenClaw(['cron', 'list', '--json']);
            let jobs = parseJobs(jobsRaw);
            let job = findByName(jobs);
            if (!job) {
              for (let i = 0; i < 3 && !job; i += 1) {
                summary.attempts.push({
                  step: 'cron_add_attempt_' + (i + 1),
                  result: runOpenClaw(['cron', 'add', '--name', NAME, '--every', EVERY, '--system-event', PROMPT, '--wake', 'now', '--json'])
                });
                jobsRaw = runOpenClaw(['cron', 'list', '--json']);
                jobs = parseJobs(jobsRaw);
                job = findByName(jobs);
              }
            }

            if (job) {
              const id = job.id || job.jobId || job.key;
              const everyMs = Number((((job || {}).schedule || {}).everyMs) || 0);
              const payloadText = String((((job || {}).payload || {}).text) || '');
              const enabled = job.enabled === true;
              const needsEdit = !enabled || everyMs !== EVERY_MS || payloadText !== PROMPT;
              if (id && needsEdit) {
                summary.attempts.push({
                  step: 'cron_edit',
                  result: runOpenClaw(['cron', 'edit', String(id), '--every', EVERY, '--system-event', PROMPT, '--enable'])
                });
              }
            }

            const cronList = runOpenClaw(['cron', 'list', '--json']);
            const cronStatus = runOpenClaw(['cron', 'status', '--json']);
            const hbLast = runOpenClaw(['system', 'heartbeat', 'last', '--json']);
            const health = runOpenClaw(['gateway', 'call', 'health', '--json', '--params', '{}']);

            summary.final = {
              cronListOk: cronList.ok,
              cronStatusOk: cronStatus.ok,
              heartbeatLastOk: hbLast.ok,
              healthOk: health.ok
            };

            function outputOrFallback(runRes, kind) {
              if ((runRes.out || '').trim()) return runRes.out + '\n';
              return JSON.stringify({
                ok: false,
                kind,
                code: runRes.code,
                error: runRes.err || 'empty_output'
              }, null, 2) + '\n';
            }

            fs.writeFileSync(path.join(stateDir, 'cron-jobs.json'), outputOrFallback(cronList, 'cron_list'), 'utf8');
            fs.writeFileSync(path.join(stateDir, 'cron-status.json'), outputOrFallback(cronStatus, 'cron_status'), 'utf8');
            fs.writeFileSync(path.join(stateDir, 'heartbeat-last.json'), outputOrFallback(hbLast, 'heartbeat_last'), 'utf8');
            fs.writeFileSync(path.join(stateDir, 'heartbeat-bootstrap.json'), JSON.stringify(summary, null, 2) + '\n', 'utf8');
            console.log(JSON.stringify(summary));
            NODE
            """.trimIndent()
        runInPrefix(script) { Log.d(TAG, "[openclaw-heartbeat] $it") }
    }

    private fun isHeartbeatBootstrapHealthy(homeDir: String): Boolean {
        val stateDir = File(homeDir, ".openclaw-android/state")
        val cronJobsFile = File(stateDir, "cron-jobs.json")
        val cronStatusFile = File(stateDir, "cron-status.json")
        if (!cronJobsFile.exists() || !cronStatusFile.exists()) return false

        return try {
            val cronJobsObj = JSONTokener(cronJobsFile.readText()).nextValue()
            val cronStatusObj = JSONTokener(cronStatusFile.readText()).nextValue()
            if (cronJobsObj !is JSONObject || cronStatusObj !is JSONObject) return false

            val jobsCount = cronStatusObj.optInt("jobs", 0)
            val nextWakeAtMs = cronStatusObj.optLong("nextWakeAtMs", 0L)
            if (jobsCount <= 0 || nextWakeAtMs <= 0L) return false

            val jobs = cronJobsObj.optJSONArray("jobs") ?: return false
            var found = false
            for (i in 0 until jobs.length()) {
                val job = jobs.optJSONObject(i) ?: continue
                if (job.optString("name") != "anyclaw-heartbeat-main") continue
                val enabled = job.optBoolean("enabled", false)
                val everyMs = job.optJSONObject("schedule")?.optLong("everyMs", 0L) ?: 0L
                found = enabled && everyMs == 20L * 60L * 1000L
                if (found) break
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureHeartbeatBootstrapAsync(homeDir: String) {
        Thread {
            for (attempt in 1..3) {
                try {
                    ensureHeartbeatBootstrap()
                    if (isHeartbeatBootstrapHealthy(homeDir)) {
                        Log.i(TAG, "OpenClaw heartbeat bootstrap healthy on attempt $attempt")
                        break
                    }
                    Log.w(TAG, "OpenClaw heartbeat bootstrap incomplete on attempt $attempt")
                } catch (error: Exception) {
                    Log.w(TAG, "OpenClaw heartbeat bootstrap attempt $attempt failed: ${error.message}")
                }
                if (attempt < 3) {
                    Thread.sleep((7000L * attempt).coerceAtMost(20000L))
                }
            }
        }.start()
    }

    fun installCodex(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val npmCli = "$prefix/lib/node_modules/npm/bin/npm-cli.js"

        onProgress("Installing Codex CLI…")
        val codexCode = runInPrefix(
            "node $npmCli install -g @openai/codex 2>&1",
            onOutput = { onProgress(it) },
        )
        if (codexCode != 0) {
            Log.e(TAG, "npm install @openai/codex failed with code $codexCode")
            return false
        }

        ensureCodexWrapperScript()
        return isCodexInstalled()
    }

    fun ensureCodexWrapperScript() {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val codexJs = File(prefix, "lib/node_modules/@openai/codex/bin/codex.js")
        val codexBin = File(prefix, "bin/codex")

        if (!codexJs.exists()) return
        if (codexBin.exists()) return

        val wrapperCmd = """
            rm -f "$prefix/bin/codex"
            cat > "$prefix/bin/codex" << 'WEOF'
#!/system/bin/sh
exec $prefix/bin/node $prefix/lib/node_modules/@openai/codex/bin/codex.js "${'$'}@"
WEOF
            chmod 700 "$prefix/bin/codex"
            echo "codex wrapper created"
        """.trimIndent()
        runInPrefix(wrapperCmd)
        Log.i(TAG, "Created codex wrapper at $codexBin")
    }

    fun installServerBundle(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val targetDir = File(paths.prefixDir, "lib/node_modules/codex-web-local")

        try {
            val assetFiles = context.assets.list("server-bundle") ?: emptyArray()
            if (assetFiles.isNotEmpty()) {
                onProgress("Installing server bundle from APK…")
                targetDir.deleteRecursively()
                targetDir.mkdirs()
                extractAssetDir("server-bundle", targetDir)
                Log.i(TAG, "Server bundle extracted to $targetDir")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "No bundled server-bundle asset, will use npm: ${e.message}")
        }

        return false
    }

    /**
     * Install the platform-specific native Codex binary.
     * npm refuses to install it on android (os mismatch), so we download
     * the tarball via Node.js and extract it manually.
     */
    fun installPlatformBinary(onProgress: (String) -> Unit): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val targetPkg = "$prefix/lib/node_modules/@openai/codex-linux-arm64"

        onProgress("Downloading Codex native binary…")

        // Use Node.js (which has working TLS) to download the npm tarball
        val installCmd = """
            mkdir -p "$prefix/tmp/_codex_bin" && cd "$prefix/tmp/_codex_bin" &&
            node -e '
              const https = require("https");
              const fs = require("fs");
              const url = "https://registry.npmjs.org/@openai/codex/-/codex-$CODEX_VERSION-linux-arm64.tgz";
              const file = fs.createWriteStream("codex-bin.tgz");
              https.get(url, (res) => {
                if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                  https.get(res.headers.location, (r2) => r2.pipe(file).on("finish", () => {
                    file.close(); console.log("Downloaded"); process.exit(0);
                  }));
                } else {
                  res.pipe(file).on("finish", () => {
                    file.close(); console.log("Downloaded"); process.exit(0);
                  });
                }
              }).on("error", (e) => { console.error(e.message); process.exit(1); });
            ' 2>&1 &&
            tar xzf codex-bin.tgz 2>&1 &&
            mkdir -p "$targetPkg/vendor/aarch64-unknown-linux-musl/codex" &&
            cp package/vendor/aarch64-unknown-linux-musl/codex/codex "$targetPkg/vendor/aarch64-unknown-linux-musl/codex/codex" &&
            cp package/package.json "$targetPkg/package.json" &&
            chmod 700 "$targetPkg/vendor/aarch64-unknown-linux-musl/codex/codex" &&
            rm -rf "$prefix/tmp/_codex_bin" &&
            echo "Platform binary installed"
        """.trimIndent()

        val code = runInPrefix(installCmd, onOutput = { onProgress(it) })
        if (code != 0) {
            Log.e(TAG, "Platform binary install failed with code $code")
            return false
        }

        return isPlatformBinaryInstalled()
    }

    // ── Proxy ────────────────────────────────────────────────────────────────

    /**
     * Start a Node.js CONNECT proxy so the static-musl codex binary can
     * resolve DNS and reach HTTPS endpoints. Node.js uses Android's native
     * resolver; the proxy forwards TCP connections transparently.
     */
    fun startProxy(): Boolean {
        if (proxyProcess != null) return true

        val paths = BootstrapInstaller.getPaths(context)
        val proxyScript = File(paths.homeDir, "proxy.js")

        // Always overwrite with the latest version from assets
        try {
            context.assets.open("proxy.js").use { input ->
                proxyScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract proxy.js asset: ${e.message}")
            return false
        }

        // Kill any orphaned proxy from a previous run
        val pidFile = File(paths.homeDir, ".proxy.pid")
        if (pidFile.exists()) {
            try {
                val oldPid = pidFile.readText().trim()
                ProcessBuilder("kill", oldPid).start().waitFor()
                Thread.sleep(500)
            } catch (_: Exception) {}
            pidFile.delete()
        }

        val env = buildEnvironment(paths)
        val shell = runtimeShell()
        val cmd = "exec node ${proxyScript.absolutePath}"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        proxyProcess = proc
        startProcessLogThread(proc, "proxy")

        Thread.sleep(800)
        Log.i(TAG, "CONNECT proxy started on 127.0.0.1:$PROXY_PORT")
        return true
    }

    fun stopProxy() {
        proxyProcess?.destroy()
        proxyProcess = null
    }

    // ── Authentication ──────────────────────────────────────────────────────

    private fun codexBinPath(): String {
        val paths = BootstrapInstaller.getPaths(context)
        return "${paths.prefixDir}/lib/node_modules/@openai/codex-linux-arm64" +
            "/vendor/aarch64-unknown-linux-musl/codex/codex"
    }

    fun isLoggedIn(): Boolean {
        val output = runCapture("${codexBinPath()} login status 2>&1")
        Log.i(TAG, "Login status: $output")
        return !output.contains("Not logged in", ignoreCase = true)
    }

    /**
     * Pipe an API key into `codex login --with-api-key` via stdin.
     */
    fun loginWithApiKey(apiKey: String): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)

        val pb = ProcessBuilder(codexBinPath(), "login", "--with-api-key")
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        proc.outputStream.bufferedWriter().use { w ->
            w.write(apiKey)
            w.newLine()
            w.flush()
        }

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            Log.d(TAG, "[login] $line")
            line = reader.readLine()
        }

        val exitCode = proc.waitFor()
        Log.i(TAG, "codex login --with-api-key exited with code $exitCode")
        return exitCode == 0
    }

    /**
     * Run `codex login` (URL-based OAuth flow) using the CONNECT proxy.
     * The native binary starts a local HTTP server for the OAuth callback,
     * prints an auth URL, and waits for the redirect. Parses the URL from
     * stdout and calls [onLoginUrl] so the Activity can open the browser.
     * Blocks until login completes or fails.
     */
    fun loginWithUrl(
        onLoginUrl: (url: String) -> Unit,
        onProgress: (String) -> Unit,
    ): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"

        val pb = ProcessBuilder(codexBinPath(), "login")
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))

        val urlRegex = Regex("""(https://auth\.openai\.com/\S+)""")
        var urlSent = false

        var line = reader.readLine()
        while (line != null) {
            val clean = line.replace(Regex("\\x1b\\[[0-9;]*m"), "").trim()
            Log.d(TAG, "[login] $clean")
            onProgress(clean)

            if (!urlSent) {
                urlRegex.find(clean)?.let {
                    onLoginUrl(it.value)
                    urlSent = true
                }
            }

            line = reader.readLine()
        }

        val exitCode = proc.waitFor()
        Log.i(TAG, "codex login exited with code $exitCode")
        return exitCode == 0
    }

    // ── Health check ────────────────────────────────────────────────────────

    /**
     * Send a minimal prompt ("hi") to Codex in non-interactive (exec) mode
     * via the CONNECT proxy. Confirms the API key is valid and the native
     * binary can reach OpenAI.
     */
    fun healthCheck(onProgress: (String) -> Unit): Boolean {
        onProgress("Sending test message…")

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"

        val shell = runtimeShell()
        val cmd = "${codexBinPath()} exec --skip-git-repo-check \"say hi\" 2>&1"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            val clean = line.replace(Regex("\\x1b\\[[0-9;]*m"), "").trim()
            Log.d(TAG, "[health] $clean")
            sb.appendLine(clean)
            onProgress(clean)
            line = reader.readLine()
        }

        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            onProgress("Health check timed out; skipping")
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
            Log.w(TAG, "Health check timed out and process was terminated")
            return false
        }

        val exitCode = proc.exitValue()
        val output = sb.toString().trim()
        Log.i(TAG, "Health check exit=$exitCode output=$output")

        if (exitCode != 0) {
            Log.e(TAG, "Health check failed with exit code $exitCode")
            return false
        }

        return output.isNotEmpty()
    }

    // ── Server lifecycle ────────────────────────────────────────────────────

    /**
     * Start the codex-web-local server. The CONNECT proxy must be running
     * and authentication must have been completed first.
     */
    fun startServer(): Boolean {
        if (isRunning) {
            Log.i(TAG, "Server already running")
            return true
        }

        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths).toMutableMap()
        env["HTTPS_PROXY"] = "http://127.0.0.1:$PROXY_PORT"
        env["HTTP_PROXY"] = "http://127.0.0.1:$PROXY_PORT"

        val serverScript = "${paths.prefixDir}/lib/node_modules/codex-web-local/dist-cli/index.js"
        if (!File(serverScript).exists()) {
            Log.e(TAG, "Server script not found: $serverScript")
            return false
        }

        val shell = runtimeShell()
        val command = "exec node $serverScript --port $SERVER_PORT --no-password"

        Log.i(TAG, "Starting server: $command")

        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        serverProcess = proc
        startProcessLogThread(proc, "server")

        return true
    }

    fun waitForServer(timeoutMs: Long = 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val url = URL("http://127.0.0.1:$SERVER_PORT/")

        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) {
                    Log.i(TAG, "Server is ready (HTTP $code)")
                    return true
                }
            } catch (_: Exception) {
                // Not ready yet
            }
            Thread.sleep(500)
        }

        Log.e(TAG, "Server did not become ready within ${timeoutMs}ms")
        return false
    }

    fun stopServer() {
        val proc = serverProcess ?: return
        serverProcess = null

        try {
            proc.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying server process: ${e.message}")
        }

        try {
            proc.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        stopOpenClaw()
        stopProxy()
        Log.i(TAG, "Server stopped")
    }

    private fun stopOpenClaw() {
        openClawGatewayProcess?.destroy()
        openClawGatewayProcess = null
        openClawControlUiProcess?.destroy()
        openClawControlUiProcess = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun extractAssetDir(assetPath: String, targetDir: File) {
        val list = context.assets.list(assetPath) ?: return
        targetDir.mkdirs()
        for (entry in list) {
            val subAsset = "$assetPath/$entry"
            val subTarget = File(targetDir, entry)
            val subList = context.assets.list(subAsset)
            if (subList != null && subList.isNotEmpty()) {
                subTarget.mkdirs()
                extractAssetDir(subAsset, subTarget)
            } else {
                context.assets.open(subAsset).use { input ->
                    subTarget.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    fun ensureDefaultWorkspace() {
        val paths = BootstrapInstaller.getPaths(context)
        val workspaceDir = File(paths.homeDir, "codex")
        if (workspaceDir.exists()) return

        workspaceDir.mkdirs()
        runInPrefix("cd ${workspaceDir.absolutePath} && git init 2>&1")
        Log.i(TAG, "Created default workspace at $workspaceDir")
    }

    fun ensureStorageBridge() {
        val paths = BootstrapInstaller.getPaths(context)
        val script = """
            mkdir -p "${'$'}HOME/storage" 2>/dev/null || true
            ln -sfn /sdcard "${'$'}HOME/sdcard" 2>/dev/null || true
            ln -sfn /storage/emulated/0 "${'$'}HOME/storage/shared" 2>/dev/null || true
            ln -sfn /sdcard/Download "${'$'}HOME/storage/downloads" 2>/dev/null || true

            mkdir -p /sdcard/Download/AnyClaw 2>/dev/null || true
            mkdir -p /sdcard/Download/下载管理/AnyClaw 2>/dev/null || true
            mkdir -p /sdcard/下载管理/AnyClaw 2>/dev/null || true
            ln -sfn /sdcard/Download/AnyClaw "${'$'}HOME/storage/anyclaw" 2>/dev/null || true

            # Expose Ubuntu runtime launcher to Codex/CLI without requiring absolute paths.
            RUNTIME_BIN="${'$'}HOME/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh"
            if [ -f "${'$'}RUNTIME_BIN" ]; then
              cat > "${paths.prefixDir}/bin/ubuntu-shell" <<'EOF'
#!/system/bin/sh
exec "${'$'}HOME/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh" "${'$'}@"
EOF
              chmod 700 "${paths.prefixDir}/bin/ubuntu-shell" 2>/dev/null || true

              cat > "${paths.prefixDir}/bin/ubuntu-status" <<'EOF'
#!/system/bin/sh
exec "${'$'}HOME/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh" --status
EOF
              chmod 700 "${paths.prefixDir}/bin/ubuntu-status" 2>/dev/null || true
            fi
        """.trimIndent()

        val code = runInPrefix(script)
        if (code == 0) {
            Log.i(TAG, "Shared-storage bridge initialized")
        } else {
            Log.w(TAG, "Shared-storage bridge init returned code $code")
        }
    }

    fun ensureAptTrustChain() {
        val paths = BootstrapInstaller.getPaths(context)
        val cmd = """
            set +e
            prefix="${paths.prefixDir}"
            key_dir="${paths.prefixDir}/etc/apt/trusted.gpg.d"
            legacy_dir="${paths.prefixDir}/etc/apt/trusted.gpg.d.legacy"
            key_file="${paths.prefixDir}/etc/apt/trusted.gpg.d/termux-main-5a897d96e57cf20c.asc"
            key_url="https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x5A897D96E57CF20C"
            mkdir -p "${'$'}key_dir" "${'$'}legacy_dir" "${paths.prefixDir}/tmp" 2>/dev/null || true

            download_ok=0
            if [ -s "${'$'}key_file" ] && grep -q "BEGIN PGP PUBLIC KEY BLOCK" "${'$'}key_file" 2>/dev/null; then
              download_ok=1
            else
              if command -v curl >/dev/null 2>&1; then
                if curl -fsSL "${'$'}key_url" -o "${'$'}key_file"; then
                  download_ok=1
                fi
              elif command -v wget >/dev/null 2>&1; then
                if wget -qO "${'$'}key_file" "${'$'}key_url"; then
                  download_ok=1
                fi
              fi
            fi

            if [ "${'$'}download_ok" -eq 1 ] && grep -q "BEGIN PGP PUBLIC KEY BLOCK" "${'$'}key_file" 2>/dev/null; then
              chmod 600 "${'$'}key_file" 2>/dev/null || true
              for old in "${'$'}key_dir"/*.gpg; do
                [ -e "${'$'}old" ] || continue
                base="${'$'}(basename "${'$'}old")"
                mv -f "${'$'}old" "${'$'}legacy_dir/${'$'}base" 2>/dev/null || true
              done
            fi

            if [ -x "${paths.prefixDir}/bin/apt.real" ]; then
              "${paths.prefixDir}/bin/apt.real" update >/dev/null 2>&1 || true
            fi

            echo "apt-trust-chain-ready"
            exit 0
        """.trimIndent()

        val code = runInPrefix(cmd)
        if (code == 0) {
            Log.i(TAG, "APT trust chain repaired")
        } else {
            Log.w(TAG, "APT trust chain repair returned $code")
        }
    }

    fun ensurePackageRecoveryScripts() {
        val paths = BootstrapInstaller.getPaths(context)
        val cmd = """
            workspace="${paths.homeDir}/.openclaw/workspace"
            scripts="${paths.homeDir}/.openclaw/workspace/scripts"
            state_dir="${paths.prefixDir}/var/lib/anyclaw-manual"
            mkdir -p "${'$'}workspace" "${'$'}scripts" "${'$'}state_dir" "${paths.prefixDir}/tmp" 2>/dev/null || true

            cat > "${'$'}scripts/manual-deb-manager.sh" <<'EOF'
#!/system/bin/sh
set -eu

PREFIX="${'$'}{PREFIX:-__PREFIX__}"
HOME_DIR="${'$'}{HOME:-__HOME__}"
WORK="${'$'}{HOME_DIR}/.openclaw/workspace/.tmp_deb"
STATE_DIR="${'$'}{PREFIX}/var/lib/anyclaw-manual"
APT_REAL="${'$'}{PREFIX}/bin/apt.real"
DPKG_DEB="${'$'}{PREFIX}/bin/dpkg-deb"

mkdir -p "${'$'}{WORK}" "${'$'}{STATE_DIR}" "${'$'}{PREFIX}" 2>/dev/null || true

if [ ! -x "${'$'}{APT_REAL}" ]; then
  APT_REAL="${'$'}{PREFIX}/bin/apt"
fi
if [ ! -x "${'$'}{DPKG_DEB}" ]; then
  DPKG_DEB="${'$'}{PREFIX}/bin/dpkg-deb"
fi

safe_rel() {
  rel="${'$'}{1#./}"
  case "${'$'}{rel}" in
    ""|/*|".."|../*|*/..|*/../*) return 1 ;;
    *) printf "%s\n" "${'$'}{rel}"; return 0 ;;
  esac
}

install_pkg() {
  pkg="${'$'}{1:-}"
  [ -n "${'$'}{pkg}" ] || return 1
  (
    cd "${'$'}{WORK}"
    "${'$'}{APT_REAL}" download "${'$'}{pkg}" >/dev/null
  ) || return 1

  deb="${'$'}(ls -1t "${'$'}{WORK}/${'$'}{pkg}"_*.deb 2>/dev/null | head -n 1 || true)"
  [ -n "${'$'}{deb}" ] || return 1

  stage="${'$'}(mktemp -d "${'$'}{WORK}/stage.${'$'}{pkg}.XXXXXX")"
  cleanup() {
    rm -rf "${'$'}{stage}" 2>/dev/null || true
  }
  trap cleanup EXIT INT TERM

  "${'$'}{DPKG_DEB}" -x "${'$'}{deb}" "${'$'}{stage}" >/dev/null 2>&1 || return 1

  payload="${'$'}{stage}/data/data/com.termux/files/usr"
  if [ ! -d "${'$'}{payload}" ]; then
    payload="${'$'}{stage}/usr"
  fi
  [ -d "${'$'}{payload}" ] || return 1

  manifest_tmp="${'$'}{STATE_DIR}/${'$'}{pkg}.files.tmp"
  manifest="${'$'}{STATE_DIR}/${'$'}{pkg}.files"
  : > "${'$'}{manifest_tmp}"
  paths_file="${'$'}{stage}/.paths.list"
  find "${'$'}{payload}" -mindepth 1 -print | sort > "${'$'}{paths_file}"
  while IFS= read -r path; do
    rel="${'$'}(safe_rel "${'$'}{path#${'$'}{payload}/}")" || continue
    printf "%s\n" "${'$'}{rel}" >> "${'$'}{manifest_tmp}"
  done < "${'$'}{paths_file}"

  cp -a "${'$'}{payload}"/. "${'$'}{PREFIX}"/
  mv "${'$'}{manifest_tmp}" "${'$'}{manifest}"
  printf "%s\n" "manual_install_ok ${'$'}{pkg}"
  trap - EXIT INT TERM
  cleanup
  return 0
}

remove_pkg() {
  pkg="${'$'}{1:-}"
  [ -n "${'$'}{pkg}" ] || return 1
  manifest="${'$'}{STATE_DIR}/${'$'}{pkg}.files"
  [ -f "${'$'}{manifest}" ] || return 1

  tmp_sorted="${'$'}{manifest}.sorted"
  awk '{ print length, $0 }' "${'$'}{manifest}" | sort -rn | cut -d' ' -f2- > "${'$'}{tmp_sorted}"
  while IFS= read -r rel; do
    [ -n "${'$'}{rel}" ] || continue
    target="${'$'}{PREFIX}/${'$'}{rel}"
    if [ -L "${'$'}{target}" ] || [ -f "${'$'}{target}" ]; then
      rm -f "${'$'}{target}" 2>/dev/null || true
    elif [ -d "${'$'}{target}" ]; then
      rmdir "${'$'}{target}" 2>/dev/null || true
    fi
  done < "${'$'}{tmp_sorted}"
  rm -f "${'$'}{tmp_sorted}" "${'$'}{manifest}" 2>/dev/null || true
  printf "%s\n" "manual_remove_ok ${'$'}{pkg}"
  return 0
}

status_pkg() {
  pkg="${'$'}{1:-}"
  [ -n "${'$'}{pkg}" ] || return 1
  manifest="${'$'}{STATE_DIR}/${'$'}{pkg}.files"
  if [ -f "${'$'}{manifest}" ]; then
    count="${'$'}(wc -l < "${'$'}{manifest}" 2>/dev/null | tr -d ' ')"
    printf "Package: %s\nStatus: install ok installed (manual)\nFiles: %s\n" "${'$'}{pkg}" "${'$'}{count}"
    return 0
  fi
  return 1
}

list_pkgs() {
  found=0
  for f in "${'$'}{STATE_DIR}"/*.files; do
    [ -f "${'$'}{f}" ] || continue
    found=1
    basename "${'$'}{f}" .files
  done
  [ "${'$'}{found}" -eq 1 ] || printf "%s\n" "(no manual packages)"
}

cmd="${'$'}{1:-}"
if [ -z "${'$'}{cmd}" ]; then
  printf "%s\n" "Usage: manual-deb-manager.sh <install|remove|status|list> ..."
  exit 2
fi
shift || true

case "${'$'}{cmd}" in
  install|add)
    [ "${'$'}#" -gt 0 ] || exit 2
    failed=0
    for pkg in "${'$'}@"; do
      install_pkg "${'$'}{pkg}" || failed=1
    done
    [ "${'$'}{failed}" -eq 0 ] || exit 1
    ;;
  remove|purge|uninstall)
    [ "${'$'}#" -gt 0 ] || exit 2
    failed=0
    for pkg in "${'$'}@"; do
      remove_pkg "${'$'}{pkg}" || failed=1
    done
    [ "${'$'}{failed}" -eq 0 ] || exit 1
    ;;
  status)
    [ "${'$'}#" -eq 1 ] || exit 2
    status_pkg "${'$'}1" || exit 1
    ;;
  list)
    list_pkgs
    ;;
  *)
    printf "%s\n" "Unsupported command: ${'$'}{cmd}"
    exit 2
    ;;
esac
EOF

            sed -i "s#__PREFIX__#${paths.prefixDir}#g" "${'$'}scripts/manual-deb-manager.sh"
            sed -i "s#__HOME__#${paths.homeDir}#g" "${'$'}scripts/manual-deb-manager.sh"
            chmod 700 "${'$'}scripts/manual-deb-manager.sh" 2>/dev/null || true
            echo "package-recovery-scripts-ready"
        """.trimIndent()

        val code = runInPrefix(cmd)
        if (code == 0) {
            Log.i(TAG, "Package recovery scripts are ready")
        } else {
            Log.w(TAG, "Package recovery script install returned $code")
        }
    }

    fun ensurePackageManagerWrappers() {
        val paths = BootstrapInstaller.getPaths(context)
        val cmd = """
            set -e
            prefix="${paths.prefixDir}"
            home_dir="${paths.homeDir}"

            make_real() {
              tool="${'$'}1"
              target="${'$'}prefix/bin/${'$'}tool"
              real="${'$'}prefix/bin/${'$'}tool.real"
              if [ ! -f "${'$'}real" ] && [ -f "${'$'}target" ]; then
                mv "${'$'}target" "${'$'}real"
              fi
            }

            write_common_wrapper() {
              tool="${'$'}1"
              target="${'$'}prefix/bin/${'$'}tool"
              cat > "${'$'}target" <<'EOF'
#!/system/bin/sh
set -eu
PREFIX="__PREFIX__"
HOME_DIR="__HOME__"
TOOL="__TOOL__"
REAL="${'$'}{PREFIX}/bin/${'$'}{TOOL}.real"
MANAGER="${'$'}{HOME_DIR}/.openclaw/workspace/scripts/manual-deb-manager.sh"
EXTERNAL="/sdcard/Download/CodexExports/termux_pkg_repair.sh"
BASH_BIN="${'$'}{PREFIX}/bin/bash"

export PREFIX="${'$'}PREFIX"
export HOME="${'$'}HOME_DIR"
export PATH="${'$'}PREFIX/bin:${'$'}PREFIX/bin/applets:/system/bin"
export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
export TMPDIR="${'$'}PREFIX/tmp"
export APT_CONFIG="${'$'}PREFIX/etc/apt/apt.conf"
export DPKG_ADMINDIR="${'$'}PREFIX/var/lib/dpkg"

if [ ! -x "${'$'}REAL" ]; then
  echo "Missing real command: ${'$'}REAL" >&2
  exit 127
fi

if [ "${'$'}TOOL" = "apt" ] || [ "${'$'}TOOL" = "apt-get" ] || [ "${'$'}TOOL" = "pkg" ]; then
  case "${'$'}{1:-}" in
    --version|-v)
      "${'$'}REAL" "${'$'}@" 2>/dev/null || printf "%s (manual compatibility wrapper)\n" "${'$'}TOOL"
      exit 0
      ;;
  esac
fi

first_non_option() {
  for arg in "${'$'}@"; do
    case "${'$'}arg" in
      -*) continue ;;
      *) printf "%s\n" "${'$'}arg"; return 0 ;;
    esac
  done
  printf "\n"
}

collect_packages_after_verb() {
  seen=0
  for arg in "${'$'}@"; do
    if [ "${'$'}seen" -eq 0 ]; then
      case "${'$'}arg" in
        -*) continue ;;
        *) seen=1; continue ;;
      esac
    fi
    case "${'$'}arg" in
      -*) continue ;;
      *) printf "%s\n" "${'$'}arg" ;;
    esac
  done
}

run_external_repair() {
  if [ -x "${'$'}BASH_BIN" ] && [ -f "${'$'}EXTERNAL" ] && [ "${'$'}#" -gt 0 ]; then
    "${'$'}BASH_BIN" "${'$'}EXTERNAL" "${'$'}@"
    return "${'$'}?"
  fi
  return 1
}

verb="__VERB__"
if [ "${'$'}verb" = "pkg" ]; then
  action="${'$'}{1:-}"
  case "${'$'}action" in
    install|add)
      shift || true
      [ "${'$'}#" -gt 0 ] || { echo "No packages provided" >&2; exit 2; }
      if [ -x "${'$'}MANAGER" ] && "${'$'}MANAGER" install "${'$'}@"; then
        exit 0
      fi
      run_external_repair "${'$'}@" && exit 0
      exit 1
      ;;
    remove|uninstall|purge)
      shift || true
      [ "${'$'}#" -gt 0 ] || { echo "No packages provided" >&2; exit 2; }
      if [ -x "${'$'}MANAGER" ]; then
        exec "${'$'}MANAGER" remove "${'$'}@"
      fi
      exit 1
      ;;
    list-installed)
      if [ -x "${'$'}MANAGER" ]; then
        "${'$'}MANAGER" list || true
      fi
      exec "${'$'}REAL" "${'$'}@"
      ;;
    *)
      exec "${'$'}REAL" "${'$'}@"
      ;;
  esac
fi

if [ "${'$'}verb" = "dpkg" ]; then
  action="${'$'}{1:-}"
  case "${'$'}action" in
    --version|-v)
      "${'$'}REAL" "${'$'}@" 2>/dev/null || printf "dpkg (manual compatibility wrapper)\n"
      exit 0
      ;;
    -s|--status)
      pkg="${'$'}{2:-}"
      if [ -n "${'$'}pkg" ] && [ -x "${'$'}MANAGER" ] && "${'$'}MANAGER" status "${'$'}pkg" >/dev/null 2>&1; then
        exec "${'$'}MANAGER" status "${'$'}pkg"
      fi
      exec "${'$'}REAL" "${'$'}@"
      ;;
    -r|--remove|--purge|-P)
      shift || true
      [ "${'$'}#" -gt 0 ] || exit 2
      if [ -x "${'$'}MANAGER" ]; then
        exec "${'$'}MANAGER" remove "${'$'}@"
      fi
      exit 1
      ;;
    *)
      exec "${'$'}REAL" "${'$'}@"
      ;;
  esac
fi

action="$(first_non_option "${'$'}@")"
case "${'$'}action" in
  install|add|reinstall)
    pkgs="$(collect_packages_after_verb "${'$'}@")"
    [ -n "${'$'}pkgs" ] || { echo "No packages provided" >&2; exit 2; }
    set -- ${'$'}pkgs
    if [ -x "${'$'}MANAGER" ] && "${'$'}MANAGER" install "${'$'}@"; then
      exit 0
    fi
    run_external_repair "${'$'}@" && exit 0
    exit 1
    ;;
  remove|purge|uninstall)
    pkgs="$(collect_packages_after_verb "${'$'}@")"
    [ -n "${'$'}pkgs" ] || { echo "No packages provided" >&2; exit 2; }
    set -- ${'$'}pkgs
    if [ -x "${'$'}MANAGER" ]; then
      exec "${'$'}MANAGER" remove "${'$'}@"
    fi
    exit 1
    ;;
  *)
    exec "${'$'}REAL" "${'$'}@"
    ;;
esac
EOF
              sed -i "s#__PREFIX__#${paths.prefixDir}#g" "${'$'}target"
              sed -i "s#__HOME__#${paths.homeDir}#g" "${'$'}target"
              sed -i "s#__TOOL__#${'$'}tool#g" "${'$'}target"
              sed -i "s#__VERB__#${'$'}tool#g" "${'$'}target"
              chmod 700 "${'$'}target"
            }

            make_real apt
            make_real apt-get
            make_real dpkg
            make_real pkg
            write_common_wrapper apt
            write_common_wrapper apt-get
            write_common_wrapper dpkg
            write_common_wrapper pkg
            echo "package-manager-wrappers-ready"
        """.trimIndent()

        val code = runInPrefix(cmd)
        if (code == 0) {
            Log.i(TAG, "Package manager wrappers are ready")
        } else {
            Log.w(TAG, "Package manager wrapper install returned $code")
        }
    }

    fun ensureRuntimeDoctorScripts() {
        val paths = BootstrapInstaller.getPaths(context)
        val cmd = """
            workspace="${paths.homeDir}/.openclaw/workspace"
            scripts="${paths.homeDir}/.openclaw/workspace/scripts"
            state_dir="${paths.homeDir}/.openclaw-android/state"
            mkdir -p "${'$'}workspace" "${'$'}scripts" "${'$'}state_dir" "${paths.prefixDir}/tmp" 2>/dev/null || true

            cat > "${'$'}scripts/runtime-env-doctor.sh" <<'EOF'
#!/system/bin/sh
set -eu

PREFIX="${'$'}{PREFIX:-__PREFIX__}"
HOME_DIR="${'$'}{HOME:-__HOME__}"
STATE_DIR="${'$'}HOME_DIR/.openclaw-android/state"
OUT_JSON="${'$'}STATE_DIR/runtime-health.json"
PROBE=0
REPAIR=0
AGGRESSIVE=0

while [ "${'$'}#" -gt 0 ]; do
  case "${'$'}1" in
    --probe) PROBE=1 ;;
    --repair) REPAIR=1 ;;
    --aggressive) AGGRESSIVE=1 ;;
    --json) ;;
  esac
  shift || true
done

mkdir -p "${'$'}STATE_DIR" "${'$'}PREFIX/tmp" 2>/dev/null || true

py="${'$'}PREFIX/bin/python3"
if [ ! -x "${'$'}py" ] && [ -x "${'$'}PREFIX/bin/python" ]; then
  py="${'$'}PREFIX/bin/python"
fi

repair_prefix() {
  [ -x "${'$'}py" ] || return 0
  "${'$'}py" - <<'PY'
import os
from pathlib import Path

OLD = b"/data/data/com.termux"
NEW = b"/data/user/0/com.codex.mobile.beta"

roots = [
    Path("/data/user/0/com.codex.mobile.beta/files/usr/bin"),
    Path("/data/user/0/com.codex.mobile.beta/files/usr/libexec"),
    Path("/data/user/0/com.codex.mobile.beta/files/home/.openclaw/workspace/scripts"),
    Path("/data/user/0/com.codex.mobile.beta/files/home/.openclaw/workspace/.git/hooks"),
]

patched = 0
busy = 0
failed = 0
for root in roots:
    if not root.exists():
        continue
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        try:
            data = path.read_bytes()
        except Exception:
            failed += 1
            continue
        if OLD not in data:
            continue
        if not (data.startswith(b"#!") or b"com.termux/files/usr" in data[:8192]):
            continue
        new_data = data.replace(OLD, NEW)
        if new_data == data:
            continue
        try:
            path.write_bytes(new_data)
            patched += 1
        except OSError as e:
            if getattr(e, "errno", None) == 26:
                busy += 1
            else:
                failed += 1

print(f"patched={patched} busy={busy} failed={failed}")
PY
}

probe_toolchain() {
  echo "openclaw=$(command -v openclaw || true)"
  echo "python3=$(command -v python3 || true)"
  echo "pip=$(command -v pip || command -v pip3 || true)"
  echo "apt=$(command -v apt || true)"
  echo "dpkg=$(command -v dpkg || true)"
  echo "pkg=$(command -v pkg || true)"
  echo "prefix=${'$'}PREFIX"
  echo "home=${'$'}HOME_DIR"
}

probe_python_modules() {
  [ -x "${'$'}py" ] || { echo "python_available=0"; return 0; }
  echo "python_available=1"
  "${'$'}py" - <<'PY'
import importlib

required = ["json", "ssl"]
optional = ["duckduckgo_mcp_server.server"]
required_missing = []
optional_unavailable = []

for mod in required:
    try:
        importlib.import_module(mod)
    except Exception:
        required_missing.append(mod)

for mod in optional:
    try:
        importlib.import_module(mod)
    except Exception:
        optional_unavailable.append(mod)

print("python_blocking_missing=" + ",".join(required_missing))
print("python_optional_unavailable=" + ",".join(optional_unavailable))
PY
}

repair_python_modules() {
  [ -x "${'$'}py" ] || return 0
  pip_bin="${'$'}PREFIX/bin/pip"
  [ -x "${'$'}pip_bin" ] || pip_bin="${'$'}PREFIX/bin/pip3"
  [ -x "${'$'}pip_bin" ] || return 0
  "${'$'}pip_bin" install --no-input --disable-pip-version-check duckduckgo-mcp-server >/dev/null 2>&1 || true
}

report="$(
  {
    if [ "${'$'}REPAIR" -eq 1 ]; then
      repair_prefix
      repair_python_modules
    fi
    probe_toolchain
    probe_python_modules
  } 2>&1
)"

escaped="$(printf "%s" "${'$'}report" | sed 's/\\/\\\\/g; s/\"/\\"/g')"
ts="$(date -Iseconds 2>/dev/null || date)"
printf '{\"ok\":true,\"probe\":%s,\"repair\":%s,\"aggressive\":%s,\"timestamp\":\"%s\",\"report\":\"%s\"}\n' \
  "${'$'}PROBE" "${'$'}REPAIR" "${'$'}AGGRESSIVE" "${'$'}ts" "${'$'}escaped" > "${'$'}OUT_JSON"
cat "${'$'}OUT_JSON"
exit 0
EOF

            sed -i "s#__PREFIX__#${paths.prefixDir}#g" "${'$'}scripts/runtime-env-doctor.sh"
            sed -i "s#__HOME__#${paths.homeDir}#g" "${'$'}scripts/runtime-env-doctor.sh"
            chmod 700 "${'$'}scripts/runtime-env-doctor.sh" 2>/dev/null || true
            "${'$'}scripts/runtime-env-doctor.sh" --probe --json > "${'$'}state_dir/runtime-health.json" 2>/dev/null || true
            echo "runtime-doctor-ready"
        """.trimIndent()

        val code = runInPrefix(cmd)
        if (code == 0) {
            Log.i(TAG, "Runtime doctor scripts are ready")
        } else {
            Log.w(TAG, "Runtime doctor script install returned $code")
        }
    }

    fun ensureShizukuBridgeScripts() {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val port = ShizukuShellBridgeServer.BRIDGE_PORT
        val cmd = """
            cat > "$prefix/bin/shizuku-shell" <<'EOF'
#!/system/bin/sh
if [ "${'$'}#" -eq 0 ]; then
  echo "Usage: shizuku-shell <command>"
  exit 2
fi
node - "${'$'}@" <<'NODE'
const http = require('http');
const cmd = process.argv.slice(2).join(' ');
const payload = JSON.stringify({ command: cmd });
const req = http.request({
  host: '127.0.0.1',
  port: $port,
  path: '/exec',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload)
  }
}, (res) => {
  let raw = '';
  res.setEncoding('utf8');
  res.on('data', (chunk) => { raw += chunk; });
  res.on('end', () => {
    let data = {};
    try { data = JSON.parse(raw || '{}'); } catch (_) {}
    const out = typeof data.stdout === 'string' ? data.stdout : '';
    const err = typeof data.stderr === 'string' ? data.stderr : '';
    const errorCode = typeof data.error_code === 'string' ? data.error_code : '';
    if (out.length > 0) process.stdout.write(out);
    if (err.length > 0) process.stderr.write(err);
    const code = Number.isInteger(data.exitCode) ? data.exitCode : (data.ok ? 0 : 1);
    if (!data.ok && data.error) {
      process.stderr.write(String(data.error) + '\n');
    }
    if (!data.ok && errorCode.length > 0) {
      process.stderr.write('error_code=' + errorCode + '\n');
    }
    process.exit(code);
  });
});
req.on('error', (e) => {
  process.stderr.write('Shizuku bridge unreachable: ' + e.message + '\n');
  process.exit(3);
});
req.write(payload);
req.end();
NODE
EOF

            cat > "$prefix/bin/shizuku-shell-status" <<'EOF'
#!/system/bin/sh
node - <<'NODE'
const http = require('http');
http.get({ host: '127.0.0.1', port: $port, path: '/status' }, (res) => {
  let raw = '';
  res.setEncoding('utf8');
  res.on('data', (c) => { raw += c; });
  res.on('end', () => {
    try {
      const j = JSON.parse(raw || '{}');
      console.log(JSON.stringify(j, null, 2));
      process.exit(j.ok ? 0 : 1);
    } catch (e) {
      console.error('Invalid response:', e.message);
      process.exit(1);
    }
  });
}).on('error', (e) => {
  console.error('Shizuku bridge unreachable:', e.message);
  process.exit(3);
});
NODE
EOF

            cat > "$prefix/bin/shizuku-shell-enable" <<'EOF'
#!/system/bin/sh
node - <<'NODE'
const http = require('http');
const req = http.request({
  host: '127.0.0.1',
  port: $port,
  path: '/enable',
  method: 'POST',
  headers: { 'Content-Length': 0 }
}, (res) => {
  let raw = '';
  res.setEncoding('utf8');
  res.on('data', (c) => { raw += c; });
  res.on('end', () => {
    console.log(raw || '{"ok":true}');
    process.exit(0);
  });
});
req.on('error', (e) => {
  console.error('Shizuku bridge unreachable:', e.message);
  process.exit(3);
});
req.end();
NODE
EOF

            cat > "$prefix/bin/shizuku-shell-disable" <<'EOF'
#!/system/bin/sh
node - <<'NODE'
const http = require('http');
const req = http.request({
  host: '127.0.0.1',
  port: $port,
  path: '/disable',
  method: 'POST',
  headers: { 'Content-Length': 0 }
}, (res) => {
  let raw = '';
  res.setEncoding('utf8');
  res.on('data', (c) => { raw += c; });
  res.on('end', () => {
    console.log(raw || '{"ok":true}');
    process.exit(0);
  });
});
req.on('error', (e) => {
  console.error('Shizuku bridge unreachable:', e.message);
  process.exit(3);
});
req.end();
NODE
EOF

            cat > "$prefix/bin/system-shell" <<'EOF'
#!/system/bin/sh
if [ "${'$'}#" -eq 0 ]; then
  echo "Usage: system-shell <command>"
  exit 2
fi

# Keep Ubuntu runtime commands in local app shell.
# Routing these through Shizuku loses app-private runtime context.
cmdline="${'$'}*"
case "${'$'}cmdline" in
  *ubuntu-shell*|*ubuntu-status*|*".openclaw-android/linux-runtime/bin/ubuntu-shell.sh"*|*ANYCLAW_UBUNTU_BIN*)
    /system/bin/sh -lc "${'$'}cmdline"
    exit "${'$'}?"
    ;;
esac

attempt=0
while true; do
  shizuku-shell "${'$'}@"
  code="${'$'}?"
  if [ "${'$'}code" -eq 0 ]; then
    exit 0
  fi
  if [ "${'$'}code" -ne 3 ] || [ "${'$'}attempt" -ge 2 ]; then
    exit "${'$'}code"
  fi
  attempt=$((attempt + 1))
  sleep "${'$'}attempt"
done
EOF

            cat > "$prefix/bin/codex-capabilities" <<'EOF'
#!/system/bin/sh
MODE="${'$'}1"
node - "${'$'}MODE" <<'NODE'
const http = require('http');
const mode = process.argv[2] || '';

function emitPlain(j) {
  const to01 = (v) => (v ? '1' : '0');
  const line = [
    'installed=' + to01(!!j.installed),
    'running=' + to01(!!j.running),
    'granted=' + to01(!!j.granted),
    'enabled=' + to01(!!j.enabled),
    'executor=' + (typeof j.executor === 'string' ? j.executor : 'system-shell'),
    'last_error_code=' + (typeof j.last_error_code === 'string' ? j.last_error_code : 'none'),
    'checked_at=' + (typeof j.checked_at === 'string' ? j.checked_at : new Date().toISOString())
  ].join(' ');
  process.stdout.write(line + '\n');
}

function emitJson(j) {
  process.stdout.write(JSON.stringify(j, null, 2) + '\n');
}

http.get({ host: '127.0.0.1', port: $port, path: '/status' }, (res) => {
  let raw = '';
  res.setEncoding('utf8');
  res.on('data', (chunk) => { raw += chunk; });
  res.on('end', () => {
    let j = {};
    try {
      j = JSON.parse(raw || '{}');
    } catch (_) {
      j = {
        ok: false,
        error_code: 'invalid_response',
        error: 'Invalid JSON from status endpoint',
        checked_at: new Date().toISOString()
      };
    }
    if (mode === '--plain') emitPlain(j);
    else emitJson(j);
    process.exit(j.ok === false ? 1 : 0);
  });
}).on('error', (e) => {
  const j = {
    ok: false,
    installed: false,
    running: false,
    granted: false,
    enabled: false,
    executor: 'system-shell',
    error_code: 'bridge_unreachable',
    error: String(e.message || e),
    checked_at: new Date().toISOString()
  };
  if (mode === '--plain') emitPlain(j);
  else emitJson(j);
  process.exit(3);
});
NODE
EOF

            chmod 700 "$prefix/bin/shizuku-shell" \
                      "$prefix/bin/shizuku-shell-status" \
                      "$prefix/bin/shizuku-shell-enable" \
                      "$prefix/bin/shizuku-shell-disable" \
                      "$prefix/bin/system-shell" \
                      "$prefix/bin/codex-capabilities"
        """.trimIndent()
        val code = runInPrefix(cmd)
        if (code == 0) {
            Log.i(TAG, "Shizuku shell bridge scripts installed")
        } else {
            Log.w(TAG, "Shizuku shell bridge script install returned $code")
        }
    }

    fun ensureFullAccessConfig() {
        val paths = BootstrapInstaller.getPaths(context)
        val configDir = File(paths.homeDir, ".codex")
        configDir.mkdirs()
        val configFile = File(configDir, "config.toml")
        val desired = """
            |approval_policy = "never"
            |sandbox_mode = "danger-full-access"
        """.trimMargin().trim() + "\n"

        if (configFile.exists()) {
            val current = configFile.readText()
            if (current.contains("approval_policy") && current.contains("danger-full-access")) {
                ensureShellInitFiles(paths)
                return
            }
        }
        configFile.writeText(desired)
        ensureShellInitFiles(paths)
        Log.i(TAG, "Wrote full-access config to $configFile")
    }

    private fun ensureShellInitFiles(paths: BootstrapInstaller.Paths) {
        val runtimeBinDir = "${paths.homeDir}/.openclaw-android/linux-runtime/bin"
        val shellBlock = """
            export HOME="${paths.homeDir}"
            export PREFIX="${paths.prefixDir}"
            export TERMUX_PREFIX="${paths.prefixDir}"
            export TERMUX__PREFIX="${paths.prefixDir}"
            export TMPDIR="${paths.tmpDir}"
            export TMP="${paths.tmpDir}"
            export TEMP="${paths.tmpDir}"
            export PROOT_TMP_DIR="${paths.tmpDir}"
            export ANYCLAW_UBUNTU_BIN="$runtimeBinDir/ubuntu-shell.sh"
            case ":${'$'}PATH:" in
              *":$runtimeBinDir:"*) ;;
              *) export PATH="$runtimeBinDir:${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin${'$'}{PATH:+:${'$'}PATH}" ;;
            esac
            [ -f "${'$'}HOME/.config/codex/secrets/github_token.env" ] && . "${'$'}HOME/.config/codex/secrets/github_token.env"
        """.trimIndent()

        upsertManagedShellBlock(File(paths.homeDir, ".profile"), shellBlock)
        upsertManagedShellBlock(File(paths.homeDir, ".bashrc"), shellBlock)
        upsertManagedShellBlock(
            File(paths.homeDir, ".bash_profile"),
            "[ -f \"${'$'}HOME/.profile\" ] && . \"${'$'}HOME/.profile\"",
        )
    }

    private fun upsertManagedShellBlock(file: File, body: String) {
        val startMarker = "# >>> anyclaw-managed-shell >>>"
        val endMarker = "# <<< anyclaw-managed-shell <<<"
        val block = "$startMarker\n${body.trim()}\n$endMarker\n"
        val existing = if (file.exists()) file.readText() else ""

        val updated = if (existing.contains(startMarker) && existing.contains(endMarker)) {
            val startIndex = existing.indexOf(startMarker)
            val endIndex = existing.indexOf(endMarker, startIndex)
            if (startIndex >= 0 && endIndex >= startIndex) {
                val suffixStart = endIndex + endMarker.length
                existing.substring(0, startIndex) + block + existing.substring(suffixStart).trimStart('\n')
            } else {
                existing.trimEnd() + if (existing.isBlank()) "" else "\n" + block
            }
        } else {
            existing.trimEnd() + if (existing.isBlank()) "" else "\n" + block
        }

        if (updated != existing) {
            file.parentFile?.mkdirs()
            file.writeText(updated.trimEnd() + "\n")
        }
    }

    private fun ensureObject(parent: JSONObject, key: String): JSONObject {
        val existing = parent.optJSONObject(key)
        if (existing != null) return existing
        val created = JSONObject()
        parent.put(key, created)
        return created
    }

    private fun sanitizeHeartbeatDefaults(root: JSONObject): Boolean {
        val agents = root.optJSONObject("agents") ?: return false
        val defaults = agents.optJSONObject("defaults") ?: return false
        val heartbeat = defaults.optJSONObject("heartbeat") ?: return false
        if (!heartbeat.has("enabled")) return false
        heartbeat.remove("enabled")
        return true
    }

    private fun sanitizeHeartbeatConfigOnDisk(homeDir: String): Boolean {
        val cfgFile = File(homeDir, ".openclaw/openclaw.json")
        if (!cfgFile.exists()) return false
        return runCatching {
            val root = JSONObject(cfgFile.readText())
            val changed = sanitizeHeartbeatDefaults(root)
            if (changed) {
                cfgFile.writeText(root.toString(2))
            }
            changed
        }.getOrDefault(false)
    }

    private fun getInstalledOpenClawVersion(): String? {
        val paths = BootstrapInstaller.getPaths(context)
        val pkgFile = File(paths.prefixDir, "lib/node_modules/openclaw/package.json")
        if (!pkgFile.exists()) return null
        return runCatching {
            JSONObject(pkgFile.readText()).optString("version", "").trim().ifEmpty { null }
        }.getOrNull()
    }

    private fun generateGatewayToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun ensureMainSessionIndexHealthy() {
        val repairScript = """
            node - <<'NODE'
            const fs = require('fs');
            const path = require('path');
            const crypto = require('crypto');
            const home = process.env.HOME;
            const sessionsDir = path.join(home, '.openclaw', 'agents', 'main', 'sessions');
            const indexPath = path.join(sessionsDir, 'sessions.json');
            fs.mkdirSync(sessionsDir, { recursive: true });

            let index = {};
            let dirty = false;
            if (fs.existsSync(indexPath)) {
              try {
                const raw = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
                if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
                  index = raw;
                } else {
                  dirty = true;
                }
              } catch {
                dirty = true;
              }
            }

            const key = 'agent:main:main';
            const existing = index[key] && typeof index[key] === 'object' ? index[key] : {};
            let sessionId = typeof existing.sessionId === 'string' ? existing.sessionId.trim() : '';
            const candidates = fs.readdirSync(sessionsDir)
              .filter((name) => name.endsWith('.jsonl') && !name.includes('.deleted.') && !name.includes('.reset.'))
              .map((name) => name.replace(/\.jsonl$/, ''))
              .filter(Boolean);

            if (!sessionId) {
              sessionId = candidates[candidates.length - 1] || crypto.randomUUID();
              dirty = true;
            }

            const sessionFile = path.join(sessionsDir, sessionId + '.jsonl');
            if (!fs.existsSync(sessionFile)) {
              fs.writeFileSync(sessionFile, '', 'utf8');
              dirty = true;
            }

            const now = Date.now();
            const merged = {
              ...existing,
              sessionId,
              sessionFile,
              updatedAt: typeof existing.updatedAt === 'number' ? existing.updatedAt : now,
              chatType: typeof existing.chatType === 'string' ? existing.chatType : 'direct',
              deliveryContext: existing.deliveryContext && typeof existing.deliveryContext === 'object' ? existing.deliveryContext : { channel: 'webchat' },
              lastChannel: typeof existing.lastChannel === 'string' ? existing.lastChannel : 'webchat',
              origin: existing.origin && typeof existing.origin === 'object' ? existing.origin : { provider: 'webchat', surface: 'webchat', chatType: 'direct' }
            };

            if (JSON.stringify(existing) !== JSON.stringify(merged)) dirty = true;
            index[key] = merged;

            if (dirty) {
              try {
                if (fs.existsSync(indexPath)) {
                  const backup = indexPath + '.bak.' + new Date().toISOString().replace(/[:]/g, '-');
                  fs.copyFileSync(indexPath, backup);
                }
              } catch {}
              fs.writeFileSync(indexPath, JSON.stringify(index, null, 2), 'utf8');
              console.log('OpenClaw session index repaired');
            } else {
              console.log('OpenClaw session index healthy');
            }
            NODE
        """.trimIndent()
        runInPrefix(repairScript) { Log.d(TAG, "[openclaw-session] $it") }
    }

    private fun jsonArrayContains(array: JSONArray, value: String): Boolean {
        for (i in 0 until array.length()) {
            if (array.optString(i) == value) return true
        }
        return false
    }

    private fun ensureAnyClawSearchPlugin(homeDir: String) {
        val pluginRoot = File(homeDir, ".openclaw/extensions/$ANYCLAW_SEARCH_PLUGIN_ID")
        if (!pluginRoot.exists()) {
            pluginRoot.mkdirs()
        }
        val manifestFile = File(pluginRoot, "openclaw.plugin.json")
        val indexFile = File(pluginRoot, "index.ts")
        val manifestChanged = writeAssetIfChanged(
            "plugins/$ANYCLAW_SEARCH_PLUGIN_ID/openclaw.plugin.json",
            manifestFile,
        )
        val indexChanged = writeAssetIfChanged("plugins/$ANYCLAW_SEARCH_PLUGIN_ID/index.ts", indexFile)
        if (manifestChanged || indexChanged) {
            Log.i(TAG, "Installed/updated plugin $ANYCLAW_SEARCH_PLUGIN_ID at $pluginRoot")
        }
    }

    private fun ensureAnyClawGithubPlugin(homeDir: String) {
        val pluginRoot = File(homeDir, ".openclaw/extensions/$ANYCLAW_GITHUB_PLUGIN_ID")
        if (!pluginRoot.exists()) {
            pluginRoot.mkdirs()
        }
        val manifestFile = File(pluginRoot, "openclaw.plugin.json")
        val indexFile = File(pluginRoot, "index.ts")
        val manifestChanged = writeAssetIfChanged(
            "plugins/$ANYCLAW_GITHUB_PLUGIN_ID/openclaw.plugin.json",
            manifestFile,
        )
        val indexChanged = writeAssetIfChanged("plugins/$ANYCLAW_GITHUB_PLUGIN_ID/index.ts", indexFile)
        if (manifestChanged || indexChanged) {
            Log.i(TAG, "Installed/updated plugin $ANYCLAW_GITHUB_PLUGIN_ID at $pluginRoot")
        }
    }

    private fun ensureAnyClawDevicePlugin(homeDir: String) {
        val pluginRoot = File(homeDir, ".openclaw/extensions/$ANYCLAW_DEVICE_PLUGIN_ID")
        if (!pluginRoot.exists()) {
            pluginRoot.mkdirs()
        }
        val manifestFile = File(pluginRoot, "openclaw.plugin.json")
        val indexFile = File(pluginRoot, "index.ts")
        val manifestChanged = writeAssetIfChanged(
            "plugins/$ANYCLAW_DEVICE_PLUGIN_ID/openclaw.plugin.json",
            manifestFile,
        )
        val indexChanged = writeAssetIfChanged("plugins/$ANYCLAW_DEVICE_PLUGIN_ID/index.ts", indexFile)
        if (manifestChanged || indexChanged) {
            Log.i(TAG, "Installed/updated plugin $ANYCLAW_DEVICE_PLUGIN_ID at $pluginRoot")
        }
    }

    private fun ensureAnyClawRuntimePlugin(homeDir: String) {
        val pluginRoot = File(homeDir, ".openclaw/extensions/$ANYCLAW_RUNTIME_PLUGIN_ID")
        if (!pluginRoot.exists()) {
            pluginRoot.mkdirs()
        }
        val manifestFile = File(pluginRoot, "openclaw.plugin.json")
        val indexFile = File(pluginRoot, "index.ts")
        val manifestChanged = writeAssetIfChanged(
            "plugins/$ANYCLAW_RUNTIME_PLUGIN_ID/openclaw.plugin.json",
            manifestFile,
        )
        val indexChanged = writeAssetIfChanged("plugins/$ANYCLAW_RUNTIME_PLUGIN_ID/index.ts", indexFile)
        if (manifestChanged || indexChanged) {
            Log.i(TAG, "Installed/updated plugin $ANYCLAW_RUNTIME_PLUGIN_ID at $pluginRoot")
        }
    }

    private fun ensureAnyClawUbuntuPlugin(homeDir: String) {
        val pluginRoot = File(homeDir, ".openclaw/extensions/$ANYCLAW_UBUNTU_PLUGIN_ID")
        if (!pluginRoot.exists()) {
            pluginRoot.mkdirs()
        }
        val manifestFile = File(pluginRoot, "openclaw.plugin.json")
        val indexFile = File(pluginRoot, "index.ts")
        val manifestChanged = writeAssetIfChanged(
            "plugins/$ANYCLAW_UBUNTU_PLUGIN_ID/openclaw.plugin.json",
            manifestFile,
        )
        val indexChanged = writeAssetIfChanged("plugins/$ANYCLAW_UBUNTU_PLUGIN_ID/index.ts", indexFile)
        if (manifestChanged || indexChanged) {
            Log.i(TAG, "Installed/updated plugin $ANYCLAW_UBUNTU_PLUGIN_ID at $pluginRoot")
        }
    }

    private fun writeAssetIfChanged(assetPath: String, target: File): Boolean {
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            if (target.exists()) {
                val current = target.readBytes()
                if (current.contentEquals(bytes)) {
                    return false
                }
            } else {
                target.parentFile?.mkdirs()
            }
            target.writeBytes(bytes)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Failed writing asset $assetPath to ${target.absolutePath}: ${error.message}")
            false
        }
    }

    private fun buildEnvironment(
        paths: BootstrapInstaller.Paths,
    ): Map<String, String> {
        val bionicCompat = "${paths.homeDir}/.openclaw-android/patches/bionic-compat.js"
        val runtimeBinDir = "${paths.homeDir}/.openclaw-android/linux-runtime/bin"
        val bionicCompatOpt = if (File(bionicCompat).exists()) " -r $bionicCompat" else ""

        val env = mutableMapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "$runtimeBinDir:${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
            "LD_PRELOAD" to "${paths.prefixDir}/lib/libtermux-exec.so",
            "TERMUX_PREFIX" to paths.prefixDir,
            "TERMUX__PREFIX" to paths.prefixDir,
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to paths.tmpDir,
            "TMP" to paths.tmpDir,
            "TEMP" to paths.tmpDir,
            "PROOT_TMP_DIR" to paths.tmpDir,
            "TERM" to "xterm-256color",
            "ANYCLAW_UBUNTU_BIN" to "$runtimeBinDir/ubuntu-shell.sh",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ANDROID_STORAGE" to "/sdcard",
            "EXTERNAL_STORAGE" to "/sdcard",
            "ANYCLAW_EXPORT_DIR" to "/sdcard/Download/AnyClaw",
            "APT_CONFIG" to "${paths.prefixDir}/etc/apt/apt.conf",
            "DPKG_ADMINDIR" to "${paths.prefixDir}/var/lib/dpkg",
            "SSL_CERT_FILE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_SSL_CAINFO" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_EXEC_PATH" to "${paths.prefixDir}/libexec/git-core",
            "GIT_TEMPLATE_DIR" to "${paths.prefixDir}/share/git-core/templates",
            "OPENSSL_CONF" to "${paths.prefixDir}/etc/tls/openssl.cnf",
            "NODE_OPTIONS" to "--openssl-config=${paths.prefixDir}/etc/tls/openssl.cnf --unhandled-rejections=none$bionicCompatOpt",
            "NAPI_RS_NATIVE_LIBRARY_PATH" to "${paths.homeDir}/.openclaw-android/native/davey/davey.android-arm64.node",
            "CONTAINER" to "1",
        )

        val toolchainEnvFile = File(paths.homeDir, ".openclaw-android/state/toolchain.env")
        if (toolchainEnvFile.exists()) {
            toolchainEnvFile.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEachLine
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    env[key] = value
                }
            }
        }

        return env
    }
}
