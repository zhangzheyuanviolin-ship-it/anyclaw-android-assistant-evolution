package com.codex.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
    }

    private var serverProcess: Process? = null
    private var proxyProcess: Process? = null
    private var openClawGatewayProcess: Process? = null
    private var openClawControlUiProcess: Process? = null

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

        val shell = "${paths.prefixDir}/bin/sh"
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
#!/data/user/0/com.codex.mobile/files/usr/bin/sh
exec /data/user/0/com.codex.mobile/files/usr/bin/node /data/user/0/com.codex.mobile/files/usr/lib/node_modules/@openai/codex/bin/codex.js "${'$'}@"
WEOF
                chmod 700 "$prefix/bin/codex"
            fi

            NPM_CLI="$prefix/lib/node_modules/npm/bin/npm-cli.js"
            if [ -f "${'$'}NPM_CLI" ]; then
                rm -f "$prefix/bin/npm"
                cat > "$prefix/bin/npm" << 'WEOF'
#!/data/user/0/com.codex.mobile/files/usr/bin/sh
exec /data/user/0/com.codex.mobile/files/usr/bin/node /data/user/0/com.codex.mobile/files/usr/lib/node_modules/npm/bin/npm-cli.js "${'$'}@"
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
        return File(npmRoot, "openclaw/package.json").exists()
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
            "git make cmake clang binutils lld",
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

        // Create symlinks for tools that expect different names
        runInPrefix("""
            [ ! -f "$prefix/bin/ar" ] && [ -f "$prefix/bin/llvm-ar" ] && ln -sf llvm-ar "$prefix/bin/ar"
            [ ! -f "$prefix/bin/ld" ] || [ -L "$prefix/bin/ld" ] && ln -sf ld.lld "$prefix/bin/ld"
            echo "Symlinks created"
        """.trimIndent())

        onProgress("Fixing git-core script shebangs…")
        fixGitCoreShebangs(prefix)

        onProgress("Patching make & cmake binaries…")
        patchBinaryTermuxPaths(prefix)

        onProgress("Creating header stubs…")
        createHeaderStubs(prefix)

        return true
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
                "#!/data/user/0/com.codex.mobile/files/usr/bin/sh\n" +
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
        val installCode = runInPrefix(
            "node $npmCli install -g --ignore-scripts openclaw@latest 2>&1",
            onOutput = { onProgress(it) },
        )
        if (installCode != 0) {
            Log.e(TAG, "npm install openclaw failed with code $installCode")
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

        return isOpenClawInstalled()
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
     * Write openclaw.json (gateway auth=none + dangerouslyDisableDeviceAuth)
     * and auth-profiles.json (OpenAI token from existing Codex login).
     * auth.mode is "none" because the Control UI's device-token
     * negotiation can fail on fresh installs when no device token is
     * stored, causing token_missing rejections.  The combination of
     * auth=none + dangerouslyDisableDeviceAuth + allowInsecureAuth
     * lets any local client connect without authentication.
     */
    fun configureOpenClawAuth() {
        val paths = BootstrapInstaller.getPaths(context)
        val prefix = paths.prefixDir
        val openclawDir = File(paths.homeDir, ".openclaw")
        openclawDir.mkdirs()

        val configFile = File(openclawDir, "openclaw.json")
        val configJson = """
            |{
            |  "meta": {
            |    "lastTouchedVersion": "2026.2.21-2",
            |    "lastTouchedAt": "${java.time.Instant.now()}"
            |  },
            |  "commands": {
            |    "native": "auto",
            |    "nativeSkills": "auto",
            |    "restart": true,
            |    "ownerDisplay": "raw"
            |  },
            |  "gateway": {
            |    "mode": "local",
            |    "controlUi": {
            |      "enabled": true,
            |      "allowedOrigins": ["http://127.0.0.1:$OPENCLAW_CONTROL_UI_PORT", "http://localhost:$OPENCLAW_CONTROL_UI_PORT"],
            |      "allowInsecureAuth": true,
            |      "dangerouslyDisableDeviceAuth": true
            |    },
            |    "auth": {
            |      "mode": "none"
            |    }
            |  },
            |  "agents": {
            |    "defaults": {
            |      "model": {
            |        "primary": "openai-codex/gpt-5.3-codex"
            |      }
            |    }
            |  }
            |}
        """.trimMargin()
        configFile.writeText(configJson)
        Log.i(TAG, "Wrote OpenClaw config to $configFile")

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
                  const profiles = {
                    version: 1,
                    profiles: {
                      'openai-codex:codex-cli': {
                        type: 'token',
                        provider: 'openai-codex',
                        token: token,
                        source: 'codex-auth',
                        createdAt: new Date().toISOString()
                      },
                      'openai:codex': {
                        type: 'token',
                        provider: 'openai',
                        token: token,
                        source: 'codex-auth',
                        createdAt: new Date().toISOString()
                      }
                    },
                    order: ['openai-codex:codex-cli', 'openai:codex']
                  };
                  const json = JSON.stringify(profiles, null, 2);
                  fs.writeFileSync('${'$'}HOME/.openclaw/auth-profiles.json', json);
                  const agentDir = '${'$'}HOME/.openclaw/agents/main/agent';
                  fs.mkdirSync(agentDir, { recursive: true });
                  fs.writeFileSync(path.join(agentDir, 'auth-profiles.json'), json);
                  console.log('OpenClaw auth-profiles.json written (global + agent)');
                " 2>&1
            """.trimIndent()
            runInPrefix(copyScript) { Log.d(TAG, "[openclaw-auth] $it") }
        } else {
            Log.w(TAG, "Codex auth.json not found — OpenClaw will lack API credentials")
        }
    }

    /**
     * Start the OpenClaw WebSocket gateway. Requires openclaw.json to be
     * configured first via [configureOpenClawAuth].
     *
     * Before starting:
     * 1. Kill any orphaned gateway process (scanning /proc, PID files).
     * 2. Reset device tokens via `openclaw gateway token reset` so stale
     *    device identities from previous devices/browsers are wiped.
     * 3. Clear lock/pid files and client-side device-auth state.
     *
     * Combined with `dangerouslyDisableDeviceAuth: true` and
     * `allowInsecureAuth: true` in openclaw.json, this ensures any
     * device can connect without "device token mismatch" errors.
     */
    fun startOpenClawGateway(): Boolean {
        if (openClawGatewayProcess != null) {
            try {
                openClawGatewayProcess!!.exitValue()
                openClawGatewayProcess = null
            } catch (_: IllegalThreadStateException) {
                Log.i(TAG, "OpenClaw gateway already running")
                return true
            }
        }

        val paths = BootstrapInstaller.getPaths(context)

        // Kill any orphaned gateway processes and reset all device tokens.
        runInPrefix("""
            # Kill by PID file
            for pidfile in ${paths.prefixDir}/tmp/openclaw*/gateway.pid ${paths.prefixDir}/tmp/openclaw/gateway.pid; do
                [ -f "${'$'}pidfile" ] && kill -9 ${'$'}(cat "${'$'}pidfile" 2>/dev/null) 2>/dev/null
            done
            # Scan /proc for any node process bound to the gateway port
            for pid in ${'$'}(ls /proc 2>/dev/null | grep '^[0-9]'); do
                if cat /proc/${'$'}pid/cmdline 2>/dev/null | tr '\0' ' ' | grep -q "18789"; then
                    kill -9 ${'$'}pid 2>/dev/null
                fi
            done
            # Clear stale lock/pid files
            rm -f ${paths.prefixDir}/tmp/openclaw*/gateway.lock ${paths.prefixDir}/tmp/openclaw*/gateway.pid 2>/dev/null
            rm -f ${paths.prefixDir}/tmp/openclaw/gateway.lock ${paths.prefixDir}/tmp/openclaw/gateway.pid 2>/dev/null
            # Clear device-auth state (identity keypair, device-auth tokens)
            rm -rf ${paths.homeDir}/.local/state/openclaw/identity 2>/dev/null
            rm -f ${paths.homeDir}/.local/state/openclaw/device-auth.json 2>/dev/null
            sleep 1
            # Reset all device tokens so no stale tokens remain
            openclaw gateway token reset 2>&1 || echo "token reset skipped (gateway not running)"
            echo "Gateway state cleaned"
        """.trimIndent()) { Log.d(TAG, "[openclaw-gw] $it") }

        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec openclaw gateway run --force --port $OPENCLAW_GATEWAY_PORT 2>&1"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        openClawGatewayProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[openclaw-gw] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "OpenClaw gateway exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(5000)
        Log.i(TAG, "OpenClaw gateway started on port $OPENCLAW_GATEWAY_PORT")
        return true
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
        val env = buildEnvironment(paths)
        val prefix = paths.prefixDir
        val controlUiRoot = "$prefix/lib/node_modules/openclaw/dist/control-ui"

        if (!File(controlUiRoot).exists()) {
            Log.w(TAG, "OpenClaw control-ui directory not found at $controlUiRoot")
            return false
        }

        val shell = "${paths.prefixDir}/bin/sh"
        val serverScript = """
            node -e "
              const http = require('http');
              const fs = require('fs');
              const path = require('path');
              const root = '$controlUiRoot';
              const mimeTypes = {
                '.html':'text/html','.js':'application/javascript',
                '.css':'text/css','.json':'application/json',
                '.svg':'image/svg+xml','.png':'image/png',
                '.woff2':'font/woff2','.woff':'font/woff',
              };
              http.createServer((req, res) => {
                let url = req.url.split('?')[0];
                if (url === '/') url = '/index.html';
                const fp = path.join(root, url);
                if (!fp.startsWith(root)) { res.writeHead(403); return res.end(); }
                fs.readFile(fp, (err, data) => {
                  if (err) {
                    fs.readFile(path.join(root,'index.html'), (e2, d2) => {
                      res.writeHead(200, {'Content-Type':'text/html'});
                      res.end(d2);
                    });
                    return;
                  }
                  const ext = path.extname(fp);
                  res.writeHead(200, {'Content-Type': mimeTypes[ext]||'application/octet-stream'});
                  res.end(data);
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

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[openclaw-ui] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "OpenClaw Control UI server exited with code: ${proc.waitFor()}")
        }.start()

        Thread.sleep(1000)
        Log.i(TAG, "OpenClaw Control UI server started on port $OPENCLAW_CONTROL_UI_PORT")
        return true
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
#!/data/user/0/com.codex.mobile/files/usr/bin/sh
exec /data/user/0/com.codex.mobile/files/usr/bin/node /data/user/0/com.codex.mobile/files/usr/lib/node_modules/@openai/codex/bin/codex.js "${'$'}@"
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
        val shell = "${paths.prefixDir}/bin/sh"
        val cmd = "exec node ${proxyScript.absolutePath}"

        val pb = ProcessBuilder(shell, "-c", cmd)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        proxyProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[proxy] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Proxy exited with code: ${proc.waitFor()}")
        }.start()

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

        val shell = "${paths.prefixDir}/bin/sh"
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

        val exitCode = proc.waitFor()
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

        val shell = "${paths.prefixDir}/bin/sh"
        val command = "exec node $serverScript --port $SERVER_PORT --no-password"

        Log.i(TAG, "Starting server: $command")

        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        serverProcess = proc

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line = reader.readLine()
            while (line != null) {
                Log.d(TAG, "[server] $line")
                line = reader.readLine()
            }
            Log.i(TAG, "Server process exited with code: ${proc.waitFor()}")
        }.start()

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
                return
            }
        }
        configFile.writeText(desired)
        Log.i(TAG, "Wrote full-access config to $configFile")
    }

    private fun buildEnvironment(
        paths: BootstrapInstaller.Paths,
    ): Map<String, String> {
        val bionicCompat = "${paths.homeDir}/.openclaw-android/patches/bionic-compat.js"
        val bionicCompatOpt = if (File(bionicCompat).exists()) " -r $bionicCompat" else ""

        return mapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
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
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
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
            "NODE_OPTIONS" to "--openssl-config=${paths.prefixDir}/etc/tls/openssl.cnf --unhandled-rejections=warn$bionicCompatOpt",
            "CONTAINER" to "1",
        )
    }
}
