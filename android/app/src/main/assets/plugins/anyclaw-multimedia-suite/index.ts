import { jsonResult, readNumberParam, readStringParam } from "openclaw/plugin-sdk";
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";

type RuntimeConfig = {
  timeoutMs: number;
  workspaceRoot: string;
  allowInstall: boolean;
  allowExec: boolean;
};

const DEFAULT_TIMEOUT_MS = 90_000;
const DEFAULT_WORKSPACE_ROOT = "~/.openclaw/workspace";
const DEFAULT_INSTALL_PACKAGES = ["ffmpeg", "jq", "python", "nodejs-lts", "yt-dlp"];

function asObject(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function clampNumber(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  if (value < min) return min;
  if (value > max) return max;
  return Math.floor(value);
}

function resolvePath(value: string): string {
  const home = String(process.env.HOME || "").trim();
  const text = value.trim();
  if (!text) return home || "/tmp";
  if (text.startsWith("~/") && home) return home + text.slice(1);
  if (text === "~" && home) return home;
  if (text.includes("$HOME") && home) return text.replace(/\$HOME/g, home);
  return text;
}

function stripInternalNoise(raw: string): string {
  const lines = String(raw || "").split(/\r?\n/);
  const keep = lines.filter((line) => {
    const text = line.trim();
    if (!text) return false;
    if (/^WARNING:\s+apt\.real does not have a stable CLI interface/i.test(text)) return false;
    if (/^WARNING:\s+linker:/i.test(text)) return false;
    if (/^CANNOT LINK EXECUTABLE/i.test(text)) return false;
    if (/^ERROR\s+codex_core::/i.test(text)) return false;
    if (/^proot error:/i.test(text)) return false;
    if (/^\[openclaw-(gw|ui)\]/i.test(text)) return false;
    return true;
  });
  const cleaned = keep.join("\n").trim();
  if (cleaned.length <= 3000) return cleaned;
  return cleaned.slice(0, 3000) + "\n...(truncated)";
}

function resolveRuntimeConfig(rawConfig: unknown): RuntimeConfig {
  const cfg = asObject(rawConfig);
  const timeoutSecondsRaw = typeof cfg.timeoutSeconds === "number" ? cfg.timeoutSeconds : DEFAULT_TIMEOUT_MS / 1000;
  const timeoutMs = clampNumber(timeoutSecondsRaw, 10, 300) * 1000;
  const workspaceRootRaw =
    typeof cfg.workspaceRoot === "string" && cfg.workspaceRoot.trim()
      ? cfg.workspaceRoot
      : DEFAULT_WORKSPACE_ROOT;
  const allowInstall = cfg.allowInstall !== false;
  const allowExec = cfg.allowExec !== false;
  return {
    timeoutMs,
    workspaceRoot: resolvePath(workspaceRootRaw),
    allowInstall,
    allowExec
  };
}

async function runCommand(
  command: string,
  args: string[],
  timeoutMs: number,
  cwd?: string,
): Promise<{ ok: boolean; code: number; stdout: string; stderr: string; error?: string }> {
  return new Promise((resolveDone) => {
    let stdout = "";
    let stderr = "";
    let done = false;

    const child = spawn(command, args, {
      stdio: ["ignore", "pipe", "pipe"],
      cwd: cwd && existsSync(cwd) ? cwd : undefined,
      env: process.env
    });

    const timer = setTimeout(() => {
      if (done) return;
      done = true;
      try {
        child.kill("SIGKILL");
      } catch {
        // ignore
      }
      resolveDone({
        ok: false,
        code: -1,
        stdout: stripInternalNoise(stdout),
        stderr: stripInternalNoise(stderr),
        error: "timeout"
      });
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdout += String(chunk);
    });
    child.stderr.on("data", (chunk) => {
      stderr += String(chunk);
    });

    child.on("error", (error) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      resolveDone({
        ok: false,
        code: -1,
        stdout: stripInternalNoise(stdout),
        stderr: stripInternalNoise(stderr),
        error: String(error)
      });
    });

    child.on("close", (code) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      resolveDone({
        ok: code === 0,
        code: typeof code === "number" ? code : -1,
        stdout: stripInternalNoise(stdout),
        stderr: stripInternalNoise(stderr)
      });
    });
  });
}

function parseArgsJson(raw: string): string[] {
  const value = raw.trim();
  if (!value) return [];
  const parsed = JSON.parse(value);
  if (!Array.isArray(parsed)) {
    throw new Error("argsJson must be a JSON array of strings");
  }
  return parsed.map((item) => String(item));
}

async function readVersion(command: string, args: string[], runtime: RuntimeConfig): Promise<string> {
  const result = await runCommand(command, args, Math.min(runtime.timeoutMs, 20_000));
  const source = (result.stdout || result.stderr || "").split(/\r?\n/).find((line) => line.trim().length > 0) || "";
  return source.trim();
}

function createStatusTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Multimedia Status",
    name: "anyclaw_multimedia_status",
    description: "Check multimedia runtime and toolchain availability (ffmpeg/ffprobe/yt-dlp).",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      const checks: Array<{ name: string; path: string; available: boolean; version: string }> = [];
      const candidates = [
        ["ffmpeg", ["-version"]],
        ["ffprobe", ["-version"]],
        ["yt-dlp", ["--version"]],
        ["python3", ["--version"]],
        ["node", ["--version"]],
      ] as const;

      for (const [name, versionArgs] of candidates) {
        const probe = await runCommand("sh", ["-lc", `command -v ${name}`], 8000);
        const path = (probe.stdout || "").trim();
        const available = !!path;
        const version = available ? await readVersion(name, [...versionArgs], runtime) : "";
        checks.push({ name, path, available, version });
      }

      return jsonResult({
        ok: true,
        tool: "anyclaw_multimedia_status",
        workspaceRoot: runtime.workspaceRoot,
        allowInstall: runtime.allowInstall,
        allowExec: runtime.allowExec,
        checks,
      });
    }
  };
}

function createInstallTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Multimedia Install",
    name: "anyclaw_multimedia_install",
    description: "Install/update multimedia dependencies in OpenClaw runtime.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {
        packagesCsv: { type: "string", description: "Comma-separated package names" },
        updateIndex: { type: "boolean", description: "Run package index update before install" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      if (!runtime.allowInstall) {
        return jsonResult({
          ok: false,
          tool: "anyclaw_multimedia_install",
          error: "install_disabled_by_config"
        });
      }
      const args = asObject(input);
      const packagesCsv = (readStringParam(args, "packagesCsv") || "").trim();
      const updateIndexRaw = args.updateIndex;
      const updateIndex = updateIndexRaw === undefined ? true : Boolean(updateIndexRaw);
      const packages = packagesCsv
        ? packagesCsv.split(",").map((item) => item.trim()).filter(Boolean)
        : DEFAULT_INSTALL_PACKAGES;
      const pkgList = packages.join(" ");
      const installScript = [
        "set +e",
        updateIndex ? "(pkg update -y || apt-get update -y || true)" : "true",
        `(pkg install -y ${pkgList} || apt-get install -y ${pkgList})`,
      ].join("; ");
      const result = await runCommand("sh", ["-lc", installScript], runtime.timeoutMs, runtime.workspaceRoot);
      return jsonResult({
        ok: result.ok,
        tool: "anyclaw_multimedia_install",
        updateIndex,
        packages,
        code: result.code,
        stdout: result.stdout,
        stderr: result.stderr,
        error: result.error
      });
    }
  };
}

function createFfmpegExecTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw FFmpeg Exec",
    name: "anyclaw_ffmpeg_exec",
    description: "Run ffmpeg with argsJson (JSON array). Example: [\"-i\",\"input.mp4\",\"-vn\",\"out.mp3\"]",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["argsJson"],
      properties: {
        argsJson: { type: "string" },
        cwd: { type: "string" },
        timeoutMs: { type: "number", minimum: 5000, maximum: 300000 }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      if (!runtime.allowExec) {
        return jsonResult({
          ok: false,
          tool: "anyclaw_ffmpeg_exec",
          error: "exec_disabled_by_config"
        });
      }
      const args = asObject(input);
      const parsed = parseArgsJson(readStringParam(args, "argsJson", { required: true }) || "[]");
      const cwdInput = (readStringParam(args, "cwd") || "").trim();
      const cwd = cwdInput ? resolve(resolvePath(cwdInput)) : runtime.workspaceRoot;
      const timeoutMs = clampNumber(
        readNumberParam(args, "timeoutMs", { integer: true }) ?? runtime.timeoutMs,
        5000,
        300000,
      );
      const result = await runCommand("ffmpeg", parsed, timeoutMs, cwd);
      return jsonResult({
        ok: result.ok,
        tool: "anyclaw_ffmpeg_exec",
        cwd,
        args: parsed,
        code: result.code,
        stdout: result.stdout,
        stderr: result.stderr,
        error: result.error
      });
    }
  };
}

function createFfprobeExecTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw FFprobe Exec",
    name: "anyclaw_ffprobe_exec",
    description: "Run ffprobe with argsJson (JSON array).",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["argsJson"],
      properties: {
        argsJson: { type: "string" },
        cwd: { type: "string" },
        timeoutMs: { type: "number", minimum: 5000, maximum: 300000 }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      if (!runtime.allowExec) {
        return jsonResult({
          ok: false,
          tool: "anyclaw_ffprobe_exec",
          error: "exec_disabled_by_config"
        });
      }
      const args = asObject(input);
      const parsed = parseArgsJson(readStringParam(args, "argsJson", { required: true }) || "[]");
      const cwdInput = (readStringParam(args, "cwd") || "").trim();
      const cwd = cwdInput ? resolve(resolvePath(cwdInput)) : runtime.workspaceRoot;
      const timeoutMs = clampNumber(
        readNumberParam(args, "timeoutMs", { integer: true }) ?? runtime.timeoutMs,
        5000,
        300000,
      );
      const result = await runCommand("ffprobe", parsed, timeoutMs, cwd);
      return jsonResult({
        ok: result.ok,
        tool: "anyclaw_ffprobe_exec",
        cwd,
        args: parsed,
        code: result.code,
        stdout: result.stdout,
        stderr: result.stderr,
        error: result.error
      });
    }
  };
}

export default {
  id: "anyclaw-multimedia-suite",
  name: "AnyClaw Multimedia Suite",
  register(api: any) {
    const runtime = resolveRuntimeConfig(api.pluginConfig);

    if (api.logger && api.logger.info) {
      api.logger.info(
        "anyclaw-multimedia-suite loaded workspaceRoot=" + runtime.workspaceRoot +
          " allowInstall=" + String(runtime.allowInstall) +
          " allowExec=" + String(runtime.allowExec)
      );
    }

    api.registerTool(createStatusTool(runtime));
    api.registerTool(createInstallTool(runtime));
    api.registerTool(createFfmpegExecTool(runtime));
    api.registerTool(createFfprobeExecTool(runtime));
  }
};
