package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RuntimeBridgeHandler {
  fun handleRuntimeCapabilities(): GatewaySession.InvokeResult {
    val ubuntuPath = resolveUbuntuBridgePath()
    val systemShellPath = resolveExecutablePath("system-shell")
    val payload =
      buildJsonObject {
        put("appShellAvailable", JsonPrimitive(true))
        put("systemShellAvailable", JsonPrimitive(systemShellPath != null))
        put("systemShellPath", JsonPrimitive(systemShellPath.orEmpty()))
        put("ubuntuBridgePath", JsonPrimitive(ubuntuPath))
        put("ubuntuBridgeAvailable", JsonPrimitive(File(ubuntuPath).canExecute()))
      }
    return GatewaySession.InvokeResult.ok(payload.toString())
  }

  fun handleSystemShellExec(paramsJson: String?): GatewaySession.InvokeResult {
    val params = parseJsonParamsObject(paramsJson)
    val command = parseJsonString(params, "command")?.trim().orEmpty().ifEmpty {
      parseJsonString(params, "cmd")?.trim().orEmpty()
    }
    if (command.isEmpty()) {
      return GatewaySession.InvokeResult.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: command is required",
      )
    }
    val systemShell = resolveExecutablePath("system-shell")
      ?: return GatewaySession.InvokeResult.error(
        code = "UNAVAILABLE",
        message = "UNAVAILABLE: system-shell is not available",
      )
    val timeoutMs = normalizeTimeoutMs(parseJsonInt(params, "timeoutMs"))
    return executeAndWrap(command = command, argv = listOf(systemShell, command), timeoutMs = timeoutMs)
  }

  fun handleUbuntuExec(paramsJson: String?): GatewaySession.InvokeResult {
    val params = parseJsonParamsObject(paramsJson)
    val command = parseJsonString(params, "command")?.trim().orEmpty().ifEmpty {
      parseJsonString(params, "cmd")?.trim().orEmpty()
    }
    if (command.isEmpty()) {
      return GatewaySession.InvokeResult.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: command is required",
      )
    }
    val ubuntuPath = resolveUbuntuBridgePath()
    if (!File(ubuntuPath).canExecute()) {
      return GatewaySession.InvokeResult.error(
        code = "UNAVAILABLE",
        message = "UNAVAILABLE: ubuntu bridge is not available",
      )
    }
    val timeoutMs = normalizeTimeoutMs(parseJsonInt(params, "timeoutMs"))
    return executeAndWrap(command = command, argv = listOf(ubuntuPath, "--command", command), timeoutMs = timeoutMs)
  }

  private fun executeAndWrap(
    command: String,
    argv: List<String>,
    timeoutMs: Long,
  ): GatewaySession.InvokeResult {
    return try {
      val pb = ProcessBuilder(argv)
      pb.redirectErrorStream(true)
      val process = pb.start()
      val outputBuffer = StringBuilder()
      val readerThread = thread(start = true, name = "runtime-bridge-reader") {
        process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            if (outputBuffer.length < MAX_OUTPUT_CHARS) {
              if (outputBuffer.isNotEmpty()) outputBuffer.append('\n')
              outputBuffer.append(line)
            }
          }
        }
      }

      val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
      if (!finished) {
        process.destroyForcibly()
        readerThread.join(250)
        return GatewaySession.InvokeResult.error(
          code = "COMMAND_TIMEOUT",
          message = "COMMAND_TIMEOUT: command timed out after ${timeoutMs}ms",
        )
      }

      readerThread.join(250)
      val exitCode = process.exitValue()
      val output = outputBuffer.toString().take(MAX_OUTPUT_CHARS)

      if (exitCode != 0) {
        return GatewaySession.InvokeResult.error(
          code = "COMMAND_FAILED",
          message = "COMMAND_FAILED: exit=$exitCode output=${output.ifEmpty { "(empty)" }}",
        )
      }

      val payload =
        buildJsonObject {
          put("command", JsonPrimitive(command))
          put("exitCode", JsonPrimitive(exitCode))
          put("output", JsonPrimitive(output))
        }
      GatewaySession.InvokeResult.ok(payload.toString())
    } catch (err: Throwable) {
      GatewaySession.InvokeResult.error(
        code = "UNAVAILABLE",
        message = "UNAVAILABLE: ${err.message ?: err::class.java.simpleName}",
      )
    }
  }

  private fun resolveUbuntuBridgePath(): String {
    val envPath = System.getenv("ANYCLAW_UBUNTU_BIN")?.trim().orEmpty()
    if (envPath.isNotEmpty()) return envPath
    val home = System.getenv("HOME")?.trim().orEmpty()
    if (home.isNotEmpty()) return "$home/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh"
    return "/data/user/0/com.codex.mobile.pocketlobster.test/files/home/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh"
  }

  private fun resolveExecutablePath(name: String): String? {
    val path = System.getenv("PATH")?.trim().orEmpty()
    if (path.isEmpty()) return null
    return path
      .split(':')
      .asSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { dir -> File(dir, name) }
      .firstOrNull { file -> file.isFile && file.canExecute() }
      ?.absolutePath
  }

  private fun normalizeTimeoutMs(value: Int?): Long {
    val raw = (value ?: 20_000).toLong()
    return raw.coerceIn(1_000L, 120_000L)
  }

  companion object {
    private const val MAX_OUTPUT_CHARS = 32_000
  }
}
