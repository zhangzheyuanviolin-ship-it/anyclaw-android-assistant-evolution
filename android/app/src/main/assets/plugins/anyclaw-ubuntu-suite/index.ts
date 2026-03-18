import { jsonResult, readStringParam } from "openclaw/plugin-sdk";
import { spawn } from "node:child_process";

type RuntimeConfig = {
  timeoutMs: number;
  installTimeoutMs: number;
  distroName: string;
  prootDistroBin: string;
  autoInstallProotDistro: boolean;
  autoInstallDistro: boolean;
  workspaceRoot: string;
};

type CmdResult = {
  ok: boolean;
  code: number;
  stdout: string;
  stderr: string;
  error?: string;
};

const DEFAULT_TIMEOUT_MS = 45_000;
const DEFAULT_INSTALL_TIMEOUT_MS = 30 * 60_000;
const DEFAULT_DISTRO_NAME = "ubuntu";
const DEFAULT_PROOT_DISTRO_BIN = "proot-distro";
const DEFAULT_WORKSPACE_ROOT = "~/.openclaw/workspace";

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

function readBoolean(value: unknown, fallback: boolean): boolean {
  if (typeof value === "boolean") return value;
  if (typeof value === "string") {
    const text = value.trim().toLowerCase();
    if (["1", "true", "yes", "on"].includes(text)) return true;
    if (["0", "false", "no", "off"].includes(text)) return false;
  }
  if (typeof value === "number") return value !== 0;
  return fallback;
}

function stripInternalNoise(raw: string): string {
  const lines = String(raw || "").split(/\r?\n/);
  const keep = lines.filter((line) => {
    const text = line.trim();
    if (!text) return false;
    if (/^WARNING:\s+apt\.real does not have a stable CLI interface/i.test(text)) return false;
    if (/^WARNING:\s+linker:/i.test(text)) return false;
    return true;
  });
  const cleaned = keep.join("\n").trim();
  if (cleaned.length <= 12000) return cleaned;
  return cleaned.slice(0, 12000) + "\n...(truncated)";
}

function shellSingleQuote(raw: string): string {
  return `'${String(raw).replace(/'/g, `'"'"'`)}'`;
}

function resolvePath(input: string): string {
  const home = String(process.env.HOME || "").trim();
  let value = input.trim();
  if (!value) return value;
  if (value.startsWith("~/") && home) {
    value = home + value.slice(1);
  }
  if (home) {
    value = value.replace(/\$HOME/g, home);
  }
  return value;
}

function resolveRuntimeConfig(rawConfig: unknown): RuntimeConfig {
  const cfg = asObject(rawConfig);

  const timeoutSecondsRaw = typeof cfg.timeoutSeconds === "number" ? cfg.timeoutSeconds : DEFAULT_TIMEOUT_MS / 1000;
  const installTimeoutSecondsRaw =
    typeof cfg.installTimeoutSeconds === "number" ? cfg.installTimeoutSeconds : DEFAULT_INSTALL_TIMEOUT_MS / 1000;

  const distroName =
    typeof cfg.distroName === "string" && cfg.distroName.trim()
      ? cfg.distroName.trim()
      : DEFAULT_DISTRO_NAME;

  const prootDistroBin =
    typeof cfg.prootDistroBin === "string" && cfg.prootDistroBin.trim()
      ? cfg.prootDistroBin.trim()
      : DEFAULT_PROOT_DISTRO_BIN;

  const workspaceRoot =
    typeof cfg.workspaceRoot === "string" && cfg.workspaceRoot.trim()
      ? resolvePath(cfg.workspaceRoot)
      : resolvePath(DEFAULT_WORKSPACE_ROOT);

  return {
    timeoutMs: clampNumber(timeoutSecondsRaw, 10, 300) * 1000,
    installTimeoutMs: clampNumber(installTimeoutSecondsRaw, 60, 3600) * 1000,
    distroName,
    prootDistroBin,
    autoInstallProotDistro: readBoolean(cfg.autoInstallProotDistro, true),
    autoInstallDistro: readBoolean(cfg.autoInstallDistro, false),
    workspaceRoot
  };
}

async function runCommand(command: string, args: string[], timeoutMs: number): Promise<CmdResult> {
  return new Promise((resolve) => {
    let stdout = "";
    let stderr = "";
    let done = false;

    const child = spawn(command, args, {
      stdio: ["ignore", "pipe", "pipe"]
    });

    const timer = setTimeout(() => {
      if (done) return;
      done = true;
      try {
        child.kill("SIGKILL");
      } catch {
        // ignore
      }
      resolve({
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
      resolve({
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
      resolve({
        ok: code === 0,
        code: typeof code === "number" ? code : -1,
        stdout: stripInternalNoise(stdout),
        stderr: stripInternalNoise(stderr)
      });
    });
  });
}

async function runShell(script: string, timeoutMs: number): Promise<CmdResult> {
  return runCommand("sh", ["-lc", script], timeoutMs);
}

async function collectStatus(runtime: RuntimeConfig): Promise<Record<string, unknown>> {
  const script = [
    "set +e",
    "echo home=${HOME:-}",
    "echo prefix=${PREFIX:-}",
    `echo proot=$(command -v proot || true)`,
    `echo proot_distro=$(command -v ${runtime.prootDistroBin} || true)`,
    `if command -v ${runtime.prootDistroBin} >/dev/null 2>&1; then ${runtime.prootDistroBin} list 2>/dev/null || true; fi`,
    `if command -v ${runtime.prootDistroBin} >/dev/null 2>&1; then ${runtime.prootDistroBin} login ${runtime.distroName} -- /usr/bin/env bash -lc 'echo distro=ready; uname -a; command -v python3 || true' 2>/dev/null || true; fi`
  ].join("; ");

  const result = await runShell(script, runtime.timeoutMs);

  const output = `${result.stdout}\n${result.stderr}`;
  const hasProot = /proot=\//.test(output);
  const hasProotDistro = /proot_distro=\//.test(output);
  const distroReady = /distro=ready/.test(output);

  return {
    ok: result.ok,
    code: result.code,
    hasProot,
    hasProotDistro,
    distroReady,
    stdout: result.stdout,
    stderr: result.stderr,
    error: result.error
  };
}

function createStatusTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Status",
    name: "anyclaw_ubuntu_status",
    description: "Check proot/proot-distro availability and Ubuntu distro readiness.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      const status = await collectStatus(runtime);
      return jsonResult({
        tool: "anyclaw_ubuntu_status",
        distro: runtime.distroName,
        ...status
      });
    }
  };
}

function createInstallTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Install",
    name: "anyclaw_ubuntu_install",
    description: "Install proot-distro and Ubuntu rootfs when needed.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {
        distroName: { type: "string" },
        forceReinstall: { type: "boolean" },
        installDistro: { type: "boolean" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const distroName = (readStringParam(args, "distroName") || runtime.distroName).trim() || runtime.distroName;
      const forceReinstall = readBoolean(args.forceReinstall, false);
      const installDistro = readBoolean(args.installDistro, runtime.autoInstallDistro);

      const steps: string[] = [
        "set +e",
        "export DEBIAN_FRONTEND=noninteractive",
        "echo [ubuntu-install] start"
      ];

      if (runtime.autoInstallProotDistro) {
        steps.push(
          `if ! command -v ${runtime.prootDistroBin} >/dev/null 2>&1; then apt-get update -y >/dev/null 2>&1 || true; apt-get install -y proot-distro tar xz-utils >/dev/null 2>&1 || pkg install -y proot-distro tar xz-utils >/dev/null 2>&1 || true; fi`
        );
      }

      steps.push(`echo [ubuntu-install] proot_distro=$(command -v ${runtime.prootDistroBin} || true)`);
      steps.push(`if ! command -v ${runtime.prootDistroBin} >/dev/null 2>&1; then echo [ubuntu-install] missing-proot-distro; exit 2; fi`);

      if (forceReinstall) {
        steps.push(`${runtime.prootDistroBin} remove ${distroName} >/dev/null 2>&1 || true`);
      }

      steps.push(`${runtime.prootDistroBin} list 2>/dev/null || true`);
      if (installDistro) {
        steps.push(`${runtime.prootDistroBin} install ${distroName} 2>&1`);
      }

      steps.push(`${runtime.prootDistroBin} login ${distroName} -- /usr/bin/env bash -lc 'echo distro=ready; uname -a; command -v bash; command -v python3 || true' 2>&1`);
      steps.push("echo [ubuntu-install] done");

      const result = await runShell(steps.join("; "), runtime.installTimeoutMs);
      return jsonResult({
        tool: "anyclaw_ubuntu_install",
        ok: result.ok,
        code: result.code,
        distro: distroName,
        forceReinstall,
        installDistro,
        stdout: result.stdout,
        stderr: result.stderr,
        error: result.error
      });
    }
  };
}

function createExecTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Exec",
    name: "anyclaw_ubuntu_exec",
    description: "Execute shell command inside Ubuntu distro via proot-distro.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["command"],
      properties: {
        command: { type: "string" },
        distroName: { type: "string" },
        workingDir: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const command = (readStringParam(args, "command", { required: true }) || "").trim();
      const distroName = (readStringParam(args, "distroName") || runtime.distroName).trim() || runtime.distroName;
      const rawWorkingDir = (readStringParam(args, "workingDir") || runtime.workspaceRoot).trim();
      const workingDir = rawWorkingDir || runtime.workspaceRoot;

      const wrapped = [
        `set -e`,
        `cd ${shellSingleQuote(workingDir)} 2>/dev/null || cd /`,
        command
      ].join("; ");

      const runner = `${runtime.prootDistroBin} login ${distroName} --shared-tmp -- /usr/bin/env bash -lc ${shellSingleQuote(wrapped)}`;
      const result = await runShell(runner, runtime.timeoutMs);

      return jsonResult({
        tool: "anyclaw_ubuntu_exec",
        ok: result.ok,
        code: result.code,
        distro: distroName,
        workingDir,
        command,
        stdout: result.stdout,
        stderr: result.stderr,
        error: result.error
      });
    }
  };
}

function createExecHostTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Host Exec",
    name: "anyclaw_host_exec",
    description: "Execute shell command in the current host prefix for diagnostics.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["command"],
      properties: {
        command: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const command = (readStringParam(args, "command", { required: true }) || "").trim();
      const result = await runShell(command, runtime.timeoutMs);
      return jsonResult({
        tool: "anyclaw_host_exec",
        ok: result.ok,
        code: result.code,
        command,
        stdout: result.stdout,
        stderr: result.stderr,
        error: result.error
      });
    }
  };
}

export default {
  id: "anyclaw-ubuntu-suite",
  name: "AnyClaw Ubuntu Suite",
  register(api: any) {
    const runtime = resolveRuntimeConfig(api.pluginConfig);
    if (api.logger && api.logger.info) {
      api.logger.info(
        "anyclaw-ubuntu-suite loaded distro=" + runtime.distroName +
          " timeoutMs=" + runtime.timeoutMs +
          " installTimeoutMs=" + runtime.installTimeoutMs
      );
    }

    api.registerTool(createStatusTool(runtime));
    api.registerTool(createInstallTool(runtime));
    api.registerTool(createExecTool(runtime));
    api.registerTool(createExecHostTool(runtime));
  }
};
