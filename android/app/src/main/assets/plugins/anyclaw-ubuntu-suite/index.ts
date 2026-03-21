import { jsonResult, readStringParam } from "openclaw/plugin-sdk";
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";

type RuntimeConfig = {
  timeoutMs: number;
  installTimeoutMs: number;
  runtimeRoot: string;
  runtimeShellPath: string;
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
const DEFAULT_RUNTIME_ROOT = "~/.openclaw-android/linux-runtime";
const DEFAULT_RUNTIME_SHELL_PATH = "~/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh";
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

  const runtimeRoot =
    typeof cfg.runtimeRoot === "string" && cfg.runtimeRoot.trim()
      ? resolvePath(cfg.runtimeRoot)
      : resolvePath(DEFAULT_RUNTIME_ROOT);

  const runtimeShellPath =
    typeof cfg.runtimeShellPath === "string" && cfg.runtimeShellPath.trim()
      ? resolvePath(cfg.runtimeShellPath)
      : resolvePath(DEFAULT_RUNTIME_SHELL_PATH);

  const workspaceRoot =
    typeof cfg.workspaceRoot === "string" && cfg.workspaceRoot.trim()
      ? resolvePath(cfg.workspaceRoot)
      : resolvePath(DEFAULT_WORKSPACE_ROOT);

  return {
    timeoutMs: clampNumber(timeoutSecondsRaw, 10, 300) * 1000,
    installTimeoutMs: clampNumber(installTimeoutSecondsRaw, 60, 3600) * 1000,
    runtimeRoot,
    runtimeShellPath,
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

function buildLinuxCommand(runtime: RuntimeConfig, command: string): string {
  return `${shellSingleQuote(runtime.runtimeShellPath)} --command ${shellSingleQuote(command)}`;
}

async function collectStatus(runtime: RuntimeConfig): Promise<Record<string, unknown>> {
  const shellExists = existsSync(runtime.runtimeShellPath);
  const runtimeRootExists = existsSync(runtime.runtimeRoot);

  const script = [
    "set +e",
    `echo runtime_root=${shellSingleQuote(runtime.runtimeRoot)}`,
    `if [ -x ${shellSingleQuote(runtime.runtimeShellPath)} ]; then echo runtime_shell=ready; else echo runtime_shell=missing; fi`,
    `if [ -d ${shellSingleQuote(runtime.runtimeRoot + "/rootfs/ubuntu-noble-aarch64")} ]; then echo rootfs=ready; else echo rootfs=missing; fi`,
    `${buildLinuxCommand(runtime, "echo distro=ready; uname -a; command -v python3 || true; command -v apt || true")} 2>&1 || true`
  ].join("; ");

  const result = await runShell(script, runtime.timeoutMs);
  const output = `${result.stdout}\n${result.stderr}`;
  const distroReady = /distro=ready/.test(output);

  return {
    ok: result.ok,
    code: result.code,
    shellExists,
    runtimeRootExists,
    distroReady,
    runtimeRoot: runtime.runtimeRoot,
    runtimeShellPath: runtime.runtimeShellPath,
    stdout: result.stdout,
    stderr: result.stderr,
    error: result.error
  };
}

function createStatusTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Status",
    name: "anyclaw_ubuntu_status",
    description: "Check bundled Linux runtime and Ubuntu readiness.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      const status = await collectStatus(runtime);
      return jsonResult({
        tool: "anyclaw_ubuntu_status",
        ...status
      });
    }
  };
}

function createInstallTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Repair",
    name: "anyclaw_ubuntu_install",
    description: "Run bundled Ubuntu runtime self-check and repair scripts (no online proot-distro install).",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {
        aptUpdate: { type: "boolean" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const aptUpdate = readBoolean(args.aptUpdate, false);

      const checks = [
        "set -e",
        `test -x ${shellSingleQuote(runtime.runtimeShellPath)}`,
        `test -d ${shellSingleQuote(runtime.runtimeRoot + "/rootfs/ubuntu-noble-aarch64")}`,
        buildLinuxCommand(runtime, "echo distro=ready; command -v bash; command -v python3 || true")
      ];

      if (aptUpdate) {
        checks.push(buildLinuxCommand(runtime, "apt-get update -y >/dev/null 2>&1 || true; echo apt_update=done"));
      }

      const result = await runShell(checks.join("; "), runtime.installTimeoutMs);
      return jsonResult({
        tool: "anyclaw_ubuntu_install",
        ok: result.ok,
        code: result.code,
        aptUpdate,
        runtimeRoot: runtime.runtimeRoot,
        runtimeShellPath: runtime.runtimeShellPath,
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
    description: "Execute shell commands in bundled Ubuntu runtime.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["command"],
      properties: {
        command: { type: "string" },
        workingDir: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const command = (readStringParam(args, "command", { required: true }) || "").trim();
      const rawWorkingDir = (readStringParam(args, "workingDir") || runtime.workspaceRoot).trim();
      const workingDir = rawWorkingDir || runtime.workspaceRoot;

      const wrapped = [
        "set -e",
        `cd ${shellSingleQuote(workingDir)} 2>/dev/null || cd /`,
        command
      ].join("; ");

      const runner = buildLinuxCommand(runtime, wrapped);
      const result = await runShell(runner, runtime.timeoutMs);

      return jsonResult({
        tool: "anyclaw_ubuntu_exec",
        ok: result.ok,
        code: result.code,
        workingDir,
        command,
        runtimeRoot: runtime.runtimeRoot,
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
    description: "Execute shell command in host prefix for diagnostics.",
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
        runtimeRoot: runtime.runtimeRoot,
        runtimeShellPath: runtime.runtimeShellPath,
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
        "anyclaw-ubuntu-suite loaded runtimeRoot=" + runtime.runtimeRoot +
          " runtimeShellPath=" + runtime.runtimeShellPath +
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
