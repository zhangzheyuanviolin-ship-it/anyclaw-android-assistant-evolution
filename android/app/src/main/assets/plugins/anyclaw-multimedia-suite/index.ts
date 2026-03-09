import { jsonResult, readNumberParam, readStringParam } from "openclaw/plugin-sdk";
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";

type RuntimeConfig = {
  timeoutMs: number;
  workspaceRoot: string;
  allowInstall: boolean;
  allowExec: boolean;
  autoRepairOnLinkerError: boolean;
  ffmpegBinary?: string;
  ffprobeBinary?: string;
};

type CommandResult = {
  ok: boolean;
  code: number;
  stdout: string;
  stderr: string;
  error?: string;
};

type MediaExecResult = CommandResult & {
  binary: string;
  linkerIssue: boolean;
  tried: Array<{ binary: string; ok: boolean; code: number; error?: string }>;
};

const DEFAULT_TIMEOUT_MS = 90_000;
const DEFAULT_WORKSPACE_ROOT = "~/.openclaw/workspace";
const DEFAULT_INSTALL_PACKAGES = ["ffmpeg", "libjpeg-turbo", "libjxl", "jq"];

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
    if (/^ERROR\s+codex_core::/i.test(text)) return false;
    if (/^proot error:/i.test(text)) return false;
    if (/^\[openclaw-(gw|ui)\]/i.test(text)) return false;
    return true;
  });
  const cleaned = keep.join("\n").trim();
  if (cleaned.length <= 4000) return cleaned;
  return cleaned.slice(0, 4000) + "\n...(truncated)";
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
  const autoRepairOnLinkerError = cfg.autoRepairOnLinkerError !== false;
  const ffmpegBinary = typeof cfg.ffmpegBinary === "string" ? resolvePath(cfg.ffmpegBinary) : "";
  const ffprobeBinary = typeof cfg.ffprobeBinary === "string" ? resolvePath(cfg.ffprobeBinary) : "";

  return {
    timeoutMs,
    workspaceRoot: resolvePath(workspaceRootRaw),
    allowInstall,
    allowExec,
    autoRepairOnLinkerError,
    ffmpegBinary: ffmpegBinary || undefined,
    ffprobeBinary: ffprobeBinary || undefined,
  };
}

function detectLinkerIssue(text: string): boolean {
  const raw = String(text || "");
  return /CANNOT LINK EXECUTABLE|cannot locate symbol|libjpeg-hyper|jsimd_huff_encode_one_block|library\s+"[^"]+"\s+not found/i.test(raw);
}

async function runCommand(
  command: string,
  args: string[],
  timeoutMs: number,
  cwd?: string,
): Promise<CommandResult> {
  return new Promise((resolveDone) => {
    let stdout = "";
    let stderr = "";
    let done = false;

    const child = spawn(command, args, {
      stdio: ["ignore", "pipe", "pipe"],
      cwd: cwd && existsSync(cwd) ? cwd : undefined,
      env: process.env,
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
        error: "timeout",
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
        error: String(error),
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
        stderr: stripInternalNoise(stderr),
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

function uniqueStrings(items: string[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const item of items) {
    const v = item.trim();
    if (!v || seen.has(v)) continue;
    seen.add(v);
    out.push(v);
  }
  return out;
}

function buildBinaryCandidates(binaryName: "ffmpeg" | "ffprobe", runtime: RuntimeConfig): string[] {
  const configured = binaryName === "ffmpeg" ? (runtime.ffmpegBinary || "") : (runtime.ffprobeBinary || "");
  const prefix = String(process.env.PREFIX || "").trim();
  const fromPrefix = prefix ? `${prefix}/bin/${binaryName}` : "";
  const fromWorkspace = `${runtime.workspaceRoot}/tools/ffmpeg/bin/${binaryName}`;
  return uniqueStrings([configured, fromWorkspace, binaryName, fromPrefix]);
}

async function runMediaBinary(
  binaryName: "ffmpeg" | "ffprobe",
  args: string[],
  timeoutMs: number,
  cwd: string,
  runtime: RuntimeConfig,
): Promise<MediaExecResult> {
  const candidates = buildBinaryCandidates(binaryName, runtime);
  const tried: Array<{ binary: string; ok: boolean; code: number; error?: string }> = [];
  let last: CommandResult = {
    ok: false,
    code: -1,
    stdout: "",
    stderr: "",
    error: "binary_not_found",
  };
  let selected = candidates[0] || binaryName;

  for (const candidate of candidates) {
    selected = candidate;
    const result = await runCommand(candidate, args, timeoutMs, cwd);
    tried.push({ binary: candidate, ok: result.ok, code: result.code, error: result.error });
    last = result;
    if (result.ok) {
      return {
        ...result,
        binary: candidate,
        linkerIssue: false,
        tried,
      };
    }
    if (detectLinkerIssue(`${result.stdout}\n${result.stderr}\n${result.error || ""}`)) {
      return {
        ...result,
        binary: candidate,
        linkerIssue: true,
        tried,
      };
    }
  }

  return {
    ...last,
    binary: selected,
    linkerIssue: detectLinkerIssue(`${last.stdout}\n${last.stderr}\n${last.error || ""}`),
    tried,
  };
}

async function readVersion(binaryName: "ffmpeg" | "ffprobe", runtime: RuntimeConfig): Promise<{ version: string; binary: string; linkerIssue: boolean }> {
  const result = await runMediaBinary(binaryName, ["-version"], Math.min(runtime.timeoutMs, 20_000), runtime.workspaceRoot, runtime);
  const source = (result.stdout || result.stderr || "").split(/\r?\n/).find((line) => line.trim().length > 0) || "";
  return {
    version: source.trim(),
    binary: result.binary,
    linkerIssue: result.linkerIssue,
  };
}

function buildRepairScript(packages: string[], updateIndex: boolean, deepRepair: boolean): string {
  const pkgList = packages.join(" ");
  const prefix = String(process.env.PREFIX || "").trim();
  const aptGet = prefix ? `${prefix}/bin/apt-get` : "apt-get";
  const aptMark = prefix ? `${prefix}/bin/apt-mark` : "apt-mark";
  const repairShebangs = prefix
    ? [
        `[ -f "${prefix}/bin/pkg.real" ] && sed -i "1s|^#!.*com\\.termux/files/usr/bin/bash|#!${prefix}/bin/bash|" "${prefix}/bin/pkg.real" || true`,
        `[ -f "${prefix}/bin/apt-key" ] && sed -i "1s|^#!.*com\\.termux/files/usr/bin/sh|#!${prefix}/bin/sh|" "${prefix}/bin/apt-key" || true`,
        `[ -f "${prefix}/bin/apt-get" ] && sed -i "1s|^#!.*com\\.termux/files/usr/bin/sh|#!${prefix}/bin/sh|" "${prefix}/bin/apt-get" || true`,
      ].join("; ")
    : "true";
  const lines = [
    "set +e",
    repairShebangs,
    updateIndex
      ? `(DEBIAN_FRONTEND=noninteractive ${aptGet} update -y || DEBIAN_FRONTEND=noninteractive ${aptGet} update --allow-insecure-repositories || true)`
      : "true",
    `(DEBIAN_FRONTEND=noninteractive ${aptGet} install -y ${pkgList} || DEBIAN_FRONTEND=noninteractive ${aptGet} install --allow-unauthenticated -y ${pkgList} || true)`,
  ];

  if (deepRepair) {
    lines.push(`(DEBIAN_FRONTEND=noninteractive ${aptGet} install --reinstall -y libjpeg-turbo libjxl ffmpeg || DEBIAN_FRONTEND=noninteractive ${aptGet} install --reinstall --allow-unauthenticated -y libjpeg-turbo libjxl ffmpeg || true)`);
    lines.push(`(${aptMark} hold nodejs nodejs-lts >/dev/null 2>&1 || true)`);
  }

  lines.push("(ffmpeg -version >/dev/null 2>&1 || true)");
  lines.push("(ffprobe -version >/dev/null 2>&1 || true)");
  return lines.join("; ");
}

async function runRepair(runtime: RuntimeConfig, packages: string[], updateIndex: boolean, deepRepair: boolean, cwd: string): Promise<CommandResult> {
  const script = buildRepairScript(packages, updateIndex, deepRepair);
  return runCommand("sh", ["-lc", script], runtime.timeoutMs, cwd);
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
      const checks: Array<{ name: string; path: string; available: boolean; version: string; linkerIssue?: boolean }> = [];
      const baseChecks = ["yt-dlp", "python3", "node"] as const;

      for (const name of baseChecks) {
        const probe = await runCommand("sh", ["-lc", `command -v ${name}`], 8000);
        const path = (probe.stdout || "").trim();
        const available = !!path;
        let version = "";
        if (available) {
          const versionResult = await runCommand(name, ["--version"], 15_000, runtime.workspaceRoot);
          const row = (versionResult.stdout || versionResult.stderr || "").split(/\r?\n/).find((line) => line.trim().length > 0) || "";
          version = row.trim();
        }
        checks.push({ name, path, available, version });
      }

      const ffmpegVersion = await readVersion("ffmpeg", runtime);
      checks.push({
        name: "ffmpeg",
        path: ffmpegVersion.binary,
        available: ffmpegVersion.version.length > 0,
        version: ffmpegVersion.version,
        linkerIssue: ffmpegVersion.linkerIssue,
      });

      const ffprobeVersion = await readVersion("ffprobe", runtime);
      checks.push({
        name: "ffprobe",
        path: ffprobeVersion.binary,
        available: ffprobeVersion.version.length > 0,
        version: ffprobeVersion.version,
        linkerIssue: ffprobeVersion.linkerIssue,
      });

      return jsonResult({
        ok: true,
        tool: "anyclaw_multimedia_status",
        workspaceRoot: runtime.workspaceRoot,
        allowInstall: runtime.allowInstall,
        allowExec: runtime.allowExec,
        autoRepairOnLinkerError: runtime.autoRepairOnLinkerError,
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
        updateIndex: { type: "boolean", description: "Run package index update before install" },
        deepRepair: { type: "boolean", description: "Run reinstall and upgrade for ffmpeg/jpeg/jxl stack" }
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
      const deepRepairRaw = args.deepRepair;
      const deepRepair = deepRepairRaw === undefined ? true : Boolean(deepRepairRaw);
      const packages = packagesCsv
        ? packagesCsv.split(",").map((item) => item.trim()).filter(Boolean)
        : DEFAULT_INSTALL_PACKAGES;
      const result = await runRepair(runtime, packages, updateIndex, deepRepair, runtime.workspaceRoot);
      return jsonResult({
        ok: result.ok,
        tool: "anyclaw_multimedia_install",
        updateIndex,
        deepRepair,
        packages,
        code: result.code,
        stdout: result.stdout,
        stderr: result.stderr,
        error: result.error,
      });
    }
  };
}

function createDoctorTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Multimedia Doctor",
    name: "anyclaw_multimedia_doctor",
    description: "Diagnose ffmpeg linker issues and optionally auto-repair jpeg/jxl/ffmpeg stack.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {
        autoRepair: { type: "boolean", description: "Try auto repair when linker issue is detected" },
        updateIndex: { type: "boolean" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const autoRepairRaw = args.autoRepair;
      const autoRepair = autoRepairRaw === undefined ? true : Boolean(autoRepairRaw);
      const updateIndexRaw = args.updateIndex;
      const updateIndex = updateIndexRaw === undefined ? true : Boolean(updateIndexRaw);

      const before = await runMediaBinary("ffmpeg", ["-version"], Math.min(runtime.timeoutMs, 20_000), runtime.workspaceRoot, runtime);
      let repair: CommandResult | null = null;
      let after: MediaExecResult | null = null;

      if (before.linkerIssue && autoRepair && runtime.allowInstall) {
        repair = await runRepair(runtime, DEFAULT_INSTALL_PACKAGES, updateIndex, true, runtime.workspaceRoot);
        after = await runMediaBinary("ffmpeg", ["-version"], Math.min(runtime.timeoutMs, 20_000), runtime.workspaceRoot, runtime);
      }

      return jsonResult({
        ok: after ? after.ok : before.ok,
        tool: "anyclaw_multimedia_doctor",
        linkerIssueDetected: before.linkerIssue,
        attemptedRepair: !!repair,
        before,
        repair,
        after,
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
        timeoutMs: { type: "number", minimum: 5000, maximum: 300000 },
        autoRepair: { type: "boolean", description: "Auto repair and retry when linker issue is detected" }
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
      const autoRepairRaw = args.autoRepair;
      const autoRepair = autoRepairRaw === undefined ? runtime.autoRepairOnLinkerError : Boolean(autoRepairRaw);

      const first = await runMediaBinary("ffmpeg", parsed, timeoutMs, cwd, runtime);
      if (first.ok || !first.linkerIssue || !autoRepair || !runtime.allowInstall) {
        return jsonResult({
          ok: first.ok,
          tool: "anyclaw_ffmpeg_exec",
          cwd,
          args: parsed,
          binary: first.binary,
          tried: first.tried,
          code: first.code,
          stdout: first.stdout,
          stderr: first.stderr,
          error: first.error,
          linkerIssue: first.linkerIssue,
          attemptedRepair: false,
        });
      }

      const repair = await runRepair(runtime, DEFAULT_INSTALL_PACKAGES, true, true, cwd);
      const second = await runMediaBinary("ffmpeg", parsed, timeoutMs, cwd, runtime);
      return jsonResult({
        ok: second.ok,
        tool: "anyclaw_ffmpeg_exec",
        cwd,
        args: parsed,
        binary: second.binary,
        tried: second.tried,
        code: second.code,
        stdout: second.stdout,
        stderr: second.stderr,
        error: second.error,
        linkerIssue: second.linkerIssue,
        attemptedRepair: true,
        repair,
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
        timeoutMs: { type: "number", minimum: 5000, maximum: 300000 },
        autoRepair: { type: "boolean", description: "Auto repair and retry when linker issue is detected" }
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
      const autoRepairRaw = args.autoRepair;
      const autoRepair = autoRepairRaw === undefined ? runtime.autoRepairOnLinkerError : Boolean(autoRepairRaw);

      const first = await runMediaBinary("ffprobe", parsed, timeoutMs, cwd, runtime);
      if (first.ok || !first.linkerIssue || !autoRepair || !runtime.allowInstall) {
        return jsonResult({
          ok: first.ok,
          tool: "anyclaw_ffprobe_exec",
          cwd,
          args: parsed,
          binary: first.binary,
          tried: first.tried,
          code: first.code,
          stdout: first.stdout,
          stderr: first.stderr,
          error: first.error,
          linkerIssue: first.linkerIssue,
          attemptedRepair: false,
        });
      }

      const repair = await runRepair(runtime, DEFAULT_INSTALL_PACKAGES, true, true, cwd);
      const second = await runMediaBinary("ffprobe", parsed, timeoutMs, cwd, runtime);
      return jsonResult({
        ok: second.ok,
        tool: "anyclaw_ffprobe_exec",
        cwd,
        args: parsed,
        binary: second.binary,
        tried: second.tried,
        code: second.code,
        stdout: second.stdout,
        stderr: second.stderr,
        error: second.error,
        linkerIssue: second.linkerIssue,
        attemptedRepair: true,
        repair,
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
          " allowExec=" + String(runtime.allowExec) +
          " autoRepairOnLinkerError=" + String(runtime.autoRepairOnLinkerError)
      );
    }

    api.registerTool(createStatusTool(runtime));
    api.registerTool(createInstallTool(runtime));
    api.registerTool(createDoctorTool(runtime));
    api.registerTool(createFfmpegExecTool(runtime));
    api.registerTool(createFfprobeExecTool(runtime));
  }
};
