import { jsonResult, readNumberParam, readStringParam } from "openclaw/plugin-sdk";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";

type RuntimeConfig = {
  timeoutMs: number;
  installTimeoutMs: number;
  sessionTimeoutMs: number;
  runtimeRoot: string;
  runtimeShellPath: string;
  runtimeTmpDir: string;
  fakeSysdataPath: string;
  workspaceRoot: string;
  maxSessionOutputBytes: number;
};

type CmdResult = {
  ok: boolean;
  code: number;
  stdout: string;
  stderr: string;
  error?: string;
};

type UbuntuSession = {
  id: string;
  process: ReturnType<typeof spawn>;
  createdAt: string;
  workingDir: string;
  buffer: string;
  cursor: number;
  closed: boolean;
  closeCode: number | null;
  busy: boolean;
  waiters: Array<() => void>;
};

const DEFAULT_TIMEOUT_MS = 45_000;
const DEFAULT_INSTALL_TIMEOUT_MS = 30 * 60_000;
const DEFAULT_SESSION_TIMEOUT_MS = 10 * 60_000;
const DEFAULT_RUNTIME_ROOT = "~/.openclaw-android/linux-runtime";
const DEFAULT_RUNTIME_SHELL_PATH = "~/.openclaw-android/linux-runtime/bin/ubuntu-shell.sh";
const DEFAULT_WORKSPACE_ROOT = "~/.openclaw/workspace";
const DEFAULT_MAX_SESSION_OUTPUT_BYTES = 512 * 1024;

const sessions = new Map<string, UbuntuSession>();

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
  if (cleaned.length <= 16000) return cleaned;
  return cleaned.slice(0, 16000) + "\n...(truncated)";
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
  const sessionTimeoutSecondsRaw =
    typeof cfg.sessionTimeoutSeconds === "number" ? cfg.sessionTimeoutSeconds : DEFAULT_SESSION_TIMEOUT_MS / 1000;

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

  const runtimeTmpDir =
    typeof cfg.runtimeTmpDir === "string" && cfg.runtimeTmpDir.trim()
      ? resolvePath(cfg.runtimeTmpDir)
      : `${runtimeRoot}/tmp`;

  const fakeSysdataPath =
    typeof cfg.fakeSysdataPath === "string" && cfg.fakeSysdataPath.trim()
      ? resolvePath(cfg.fakeSysdataPath)
      : `${runtimeRoot}/bin/setup_fake_sysdata.sh`;

  const maxSessionOutputBytesRaw =
    typeof cfg.maxSessionOutputBytes === "number" ? cfg.maxSessionOutputBytes : DEFAULT_MAX_SESSION_OUTPUT_BYTES;

  return {
    timeoutMs: clampNumber(timeoutSecondsRaw, 10, 300) * 1000,
    installTimeoutMs: clampNumber(installTimeoutSecondsRaw, 60, 3600) * 1000,
    sessionTimeoutMs: clampNumber(sessionTimeoutSecondsRaw, 30, 3600) * 1000,
    runtimeRoot,
    runtimeShellPath,
    runtimeTmpDir,
    fakeSysdataPath,
    workspaceRoot,
    maxSessionOutputBytes: clampNumber(maxSessionOutputBytesRaw, 64 * 1024, 2 * 1024 * 1024)
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

async function runLinuxCommand(runtime: RuntimeConfig, command: string, timeoutMs: number): Promise<CmdResult> {
  return runCommand(runtime.runtimeShellPath, ["--command", command], timeoutMs);
}

async function runLinuxDoctor(runtime: RuntimeConfig): Promise<CmdResult> {
  return runCommand(runtime.runtimeShellPath, ["--doctor"], runtime.installTimeoutMs);
}

function composeGuestCommand(command: string, workingDir: string): string {
  return [
    "set -e",
    `cd ${shellSingleQuote(workingDir)} 2>/dev/null || cd /`,
    command
  ].join("; ");
}

function trimSessionBuffer(session: UbuntuSession, maxBytes: number) {
  if (session.buffer.length <= maxBytes) return;
  const delta = session.buffer.length - maxBytes;
  session.buffer = session.buffer.slice(delta);
  session.cursor = Math.max(0, session.cursor - delta);
}

function notifyWaiters(session: UbuntuSession) {
  const waiters = session.waiters.splice(0, session.waiters.length);
  for (const waiter of waiters) {
    try {
      waiter();
    } catch {
      // ignore
    }
  }
}

function attachSessionReaders(session: UbuntuSession, runtime: RuntimeConfig) {
  const onChunk = (chunk: Buffer | string) => {
    session.buffer += String(chunk);
    trimSessionBuffer(session, runtime.maxSessionOutputBytes);
    notifyWaiters(session);
  };

  if (session.process.stdout) {
    session.process.stdout.on("data", onChunk);
  }
  if (session.process.stderr) {
    session.process.stderr.on("data", onChunk);
  }
  session.process.on("close", (code) => {
    session.closed = true;
    session.closeCode = typeof code === "number" ? code : -1;
    notifyWaiters(session);
  });
  session.process.on("error", (error) => {
    session.buffer += `\nsession-error:${String(error)}\n`;
    trimSessionBuffer(session, runtime.maxSessionOutputBytes);
    session.closed = true;
    session.closeCode = -1;
    notifyWaiters(session);
  });
}

async function waitForCondition(
  session: UbuntuSession,
  runtime: RuntimeConfig,
  predicate: () => boolean,
  timeoutMs: number
): Promise<boolean> {
  if (predicate()) return true;
  return new Promise((resolve) => {
    let done = false;
    const timer = setTimeout(() => {
      if (done) return;
      done = true;
      resolve(false);
    }, timeoutMs);

    const waiter = () => {
      if (done) return;
      if (predicate()) {
        done = true;
        clearTimeout(timer);
        resolve(true);
      } else if (session.closed) {
        done = true;
        clearTimeout(timer);
        resolve(false);
      }
    };

    session.waiters.push(waiter);
  });
}

async function createSession(runtime: RuntimeConfig, workingDir: string): Promise<UbuntuSession> {
  const id = `ubuntu_${randomUUID()}`;
  const process = spawn(runtime.runtimeShellPath, ["--session-shell"], {
    stdio: ["pipe", "pipe", "pipe"]
  });

  const session: UbuntuSession = {
    id,
    process,
    createdAt: new Date().toISOString(),
    workingDir,
    buffer: "",
    cursor: 0,
    closed: false,
    closeCode: null,
    busy: false,
    waiters: []
  };

  attachSessionReaders(session, runtime);
  if (!process.stdin) {
    try {
      process.kill("SIGKILL");
    } catch {
      // ignore
    }
    throw new Error("Ubuntu session stdin unavailable");
  }

  const readyMarker = `__ANYCLAW_UBUNTU_READY__:${id}`;
  process.stdin.write(`printf '%s\\n' '${readyMarker}'\n`);
  const ready = await waitForCondition(session, runtime, () => session.buffer.includes(readyMarker), runtime.timeoutMs);
  if (!ready) {
    try {
      process.kill("SIGKILL");
    } catch {
      // ignore
    }
    throw new Error(`Ubuntu session bootstrap failed: ${session.buffer.slice(-1200) || "no output"}`);
  }
  sessions.set(id, session);
  return session;
}

function getSession(sessionId: string): UbuntuSession {
  const session = sessions.get(sessionId);
  if (!session) {
    throw new Error(`Ubuntu session not found: ${sessionId}`);
  }
  return session;
}

async function execInSession(
  session: UbuntuSession,
  runtime: RuntimeConfig,
  command: string,
  workingDir: string,
  timeoutMs: number
): Promise<Record<string, unknown>> {
  if (session.closed) {
    throw new Error(`Ubuntu session already closed: ${session.id}`);
  }
  if (session.busy) {
    throw new Error(`Ubuntu session is busy: ${session.id}`);
  }

  session.busy = true;
  try {
    const token = randomUUID().replace(/-/g, "");
    const beginMarker = `__ANYCLAW_BEGIN__:${token}`;
    const endMarker = `__ANYCLAW_END__:${token}:`;
    const scriptPath = `\${TMPDIR:-/tmp}/anyclaw_ubuntu_${token}.sh`;
    const guestCommand = composeGuestCommand(command, workingDir);
    const cursorStart = session.buffer.length;

    const payload = [
      `printf '%s\\n' '${beginMarker}'`,
      `cat >\"${scriptPath}\" <<'__ANYCLAW_SCRIPT_${token}__'`,
      guestCommand,
      `__ANYCLAW_SCRIPT_${token}__`,
      `/bin/bash --noprofile --norc \"${scriptPath}\" </dev/null`,
      `__anyclaw_rc=$?`,
      `rm -f \"${scriptPath}\"`,
      `printf '%s%s\\n' '${endMarker}' \"$__anyclaw_rc\"`
    ].join("\n") + "\n";

    if (!session.process.stdin) {
      throw new Error(`Ubuntu session stdin unavailable: ${session.id}`);
    }
    session.process.stdin.write(payload);

    const finished = await waitForCondition(
      session,
      runtime,
      () => session.buffer.slice(cursorStart).includes(endMarker),
      timeoutMs
    );

    if (!finished) {
      throw new Error(`Ubuntu session command timeout or shell exited: ${session.buffer.slice(-1600) || "no output"}`);
    }

    const raw = session.buffer.slice(cursorStart);
    const beginIndex = raw.indexOf(beginMarker);
    const endIndex = raw.indexOf(endMarker, beginIndex >= 0 ? beginIndex : 0);
    if (beginIndex < 0 || endIndex < 0) {
      throw new Error(`Ubuntu session markers missing: ${raw.slice(-1600) || "no output"}`);
    }

    const output = raw
      .slice(beginIndex + beginMarker.length, endIndex)
      .replace(/^\s+/, "")
      .replace(/\s+$/, "");

    const tail = raw.slice(endIndex + endMarker.length);
    const rcLine = tail.split(/\r?\n/).map((line) => line.trim()).find(Boolean) || "-1";
    const code = Number.parseInt(rcLine, 10);

    session.workingDir = workingDir;

    return {
      ok: code === 0,
      code: Number.isFinite(code) ? code : -1,
      stdout: stripInternalNoise(output),
      sessionId: session.id,
      workingDir
    };
  } finally {
    session.busy = false;
  }
}

async function collectStatus(runtime: RuntimeConfig): Promise<Record<string, unknown>> {
  const shellExists = existsSync(runtime.runtimeShellPath);
  const runtimeRootExists = existsSync(runtime.runtimeRoot);
  const tmpDirExists = existsSync(runtime.runtimeTmpDir);
  const fakeSysdataExists = existsSync(runtime.fakeSysdataPath);
  const prootExists = existsSync(`${runtime.runtimeRoot}/bin/proot-static`);
  const rootfsExists = existsSync(`${runtime.runtimeRoot}/rootfs/ubuntu-noble-aarch64`);

  const hostCheckScript = [
    "set +e",
    `if [ -d ${shellSingleQuote(runtime.runtimeTmpDir)} ]; then echo tmp_dir=ready; else echo tmp_dir=missing; fi`,
    `if [ -w ${shellSingleQuote(runtime.runtimeTmpDir)} ]; then echo tmp_writable=yes; else echo tmp_writable=no; fi`,
    `if [ -x ${shellSingleQuote(runtime.fakeSysdataPath)} ]; then echo fake_sysdata=ready; else echo fake_sysdata=missing; fi`
  ].join("; ");

  const hostCheck = await runShell(hostCheckScript, runtime.timeoutMs);
  const doctor = shellExists ? await runLinuxDoctor(runtime) : { ok: false, code: -1, stdout: "", stderr: "", error: "runtime-shell-missing" };
  const distroReady = /distro=ready/.test(`${doctor.stdout}\n${doctor.stderr}`);
  const mktempReady = /mktemp=ok/.test(`${doctor.stdout}\n${doctor.stderr}`);

  return {
    ok: doctor.ok,
    code: doctor.code,
    shellExists,
    runtimeRootExists,
    prootExists,
    rootfsExists,
    tmpDirExists,
    fakeSysdataExists,
    distroReady,
    mktempReady,
    runtimeRoot: runtime.runtimeRoot,
    runtimeShellPath: runtime.runtimeShellPath,
    runtimeTmpDir: runtime.runtimeTmpDir,
    activeSessions: Array.from(sessions.values()).map((session) => ({
      id: session.id,
      closed: session.closed,
      closeCode: session.closeCode,
      busy: session.busy,
      workingDir: session.workingDir,
      createdAt: session.createdAt
    })),
    hostStdout: hostCheck.stdout,
    hostStderr: hostCheck.stderr,
    stdout: doctor.stdout,
    stderr: doctor.stderr,
    error: doctor.error
  };
}

function createStatusTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Status",
    name: "anyclaw_ubuntu_status",
    description: "Check bundled Linux runtime, tmpdir, fake-sysdata, and active Ubuntu sessions.",
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
    description: "Verify and repair bundled Ubuntu runtime prerequisites, then run a full runtime doctor.",
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

      const hostRepairScript = [
        "set -eu",
        `mkdir -p ${shellSingleQuote(runtime.runtimeRoot)}`,
        `mkdir -p ${shellSingleQuote(runtime.runtimeTmpDir)}`,
        `chmod 700 ${shellSingleQuote(runtime.runtimeTmpDir)} 2>/dev/null || true`,
        `test -x ${shellSingleQuote(runtime.runtimeShellPath)}`,
        `test -x ${shellSingleQuote(runtime.runtimeRoot + "/bin/proot-static")}`,
        `test -x ${shellSingleQuote(runtime.fakeSysdataPath)}`,
        `test -d ${shellSingleQuote(runtime.runtimeRoot + "/rootfs/ubuntu-noble-aarch64")}`,
        `test -w ${shellSingleQuote(runtime.runtimeTmpDir)}`,
        "echo host_repair=ok"
      ].join("; ");

      const hostRepair = await runShell(hostRepairScript, runtime.timeoutMs);
      const doctor = hostRepair.ok ? await runLinuxDoctor(runtime) : { ok: false, code: -1, stdout: "", stderr: hostRepair.stderr, error: hostRepair.error || "host-repair-failed" };

      let aptResult: CmdResult | null = null;
      if (doctor.ok && aptUpdate) {
        aptResult = await runLinuxCommand(runtime, "apt-get update -y", runtime.installTimeoutMs);
      }

      return jsonResult({
        tool: "anyclaw_ubuntu_install",
        ok: hostRepair.ok && doctor.ok && (aptResult ? aptResult.ok : true),
        code: aptResult ? aptResult.code : doctor.code,
        aptUpdate,
        runtimeRoot: runtime.runtimeRoot,
        runtimeShellPath: runtime.runtimeShellPath,
        runtimeTmpDir: runtime.runtimeTmpDir,
        hostRepairStdout: hostRepair.stdout,
        hostRepairStderr: hostRepair.stderr,
        doctorStdout: doctor.stdout,
        doctorStderr: doctor.stderr,
        aptStdout: aptResult?.stdout,
        aptStderr: aptResult?.stderr,
        error: aptResult?.error || doctor.error || hostRepair.error
      });
    }
  };
}

function createExecTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Exec",
    name: "anyclaw_ubuntu_exec",
    description: "Execute a one-shot shell command inside the bundled Ubuntu runtime.",
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

      const result = await runLinuxCommand(runtime, composeGuestCommand(command, workingDir), runtime.timeoutMs);

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
    description: "Execute host-side shell command for Android/runtime diagnostics.",
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

function createSessionOpenTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Session Open",
    name: "anyclaw_ubuntu_session_open",
    description: "Open a persistent Ubuntu shell session that preserves working directory and shell state.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {
        workingDir: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const rawWorkingDir = (readStringParam(args, "workingDir") || runtime.workspaceRoot).trim();
      const workingDir = rawWorkingDir || runtime.workspaceRoot;
      const session = await createSession(runtime, workingDir);
      return jsonResult({
        tool: "anyclaw_ubuntu_session_open",
        ok: true,
        sessionId: session.id,
        workingDir: session.workingDir,
        createdAt: session.createdAt
      });
    }
  };
}

function createSessionExecTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Session Exec",
    name: "anyclaw_ubuntu_session_exec",
    description: "Execute a command inside an existing persistent Ubuntu session.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["sessionId", "command"],
      properties: {
        sessionId: { type: "string" },
        command: { type: "string" },
        workingDir: { type: "string" },
        timeoutSeconds: { type: "number" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const sessionId = (readStringParam(args, "sessionId", { required: true }) || "").trim();
      const command = (readStringParam(args, "command", { required: true }) || "").trim();
      const session = getSession(sessionId);
      const rawWorkingDir = (readStringParam(args, "workingDir") || session.workingDir || runtime.workspaceRoot).trim();
      const workingDir = rawWorkingDir || runtime.workspaceRoot;
      const timeoutSeconds = readNumberParam(args, "timeoutSeconds") || runtime.sessionTimeoutMs / 1000;
      const timeoutMs = clampNumber(timeoutSeconds, 10, 3600) * 1000;
      const result = await execInSession(session, runtime, command, workingDir, timeoutMs);
      return jsonResult({
        tool: "anyclaw_ubuntu_session_exec",
        ...result,
        command
      });
    }
  };
}

function createSessionReadTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Session Read",
    name: "anyclaw_ubuntu_session_read",
    description: "Read unread buffered output from a persistent Ubuntu session.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["sessionId"],
      properties: {
        sessionId: { type: "string" },
        maxChars: { type: "number" },
        resetCursor: { type: "boolean" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const sessionId = (readStringParam(args, "sessionId", { required: true }) || "").trim();
      const session = getSession(sessionId);
      const maxCharsRaw = readNumberParam(args, "maxChars") || 12000;
      const maxChars = clampNumber(maxCharsRaw, 200, 64000);
      const resetCursor = readBoolean(args.resetCursor, false);
      const unread = session.buffer.slice(session.cursor);
      const payload = unread.length > maxChars ? unread.slice(-maxChars) : unread;
      if (resetCursor || unread.length > 0) {
        session.cursor = session.buffer.length;
      }
      return jsonResult({
        tool: "anyclaw_ubuntu_session_read",
        ok: true,
        sessionId: session.id,
        closed: session.closed,
        closeCode: session.closeCode,
        busy: session.busy,
        output: stripInternalNoise(payload)
      });
    }
  };
}

function createSessionInterruptTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Session Interrupt",
    name: "anyclaw_ubuntu_session_interrupt",
    description: "Send an interrupt signal to a persistent Ubuntu session.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["sessionId"],
      properties: {
        sessionId: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const sessionId = (readStringParam(args, "sessionId", { required: true }) || "").trim();
      const session = getSession(sessionId);
      try {
        session.process.kill("SIGINT");
      } catch (error) {
        return jsonResult({
          tool: "anyclaw_ubuntu_session_interrupt",
          ok: false,
          sessionId,
          error: String(error)
        });
      }
      return jsonResult({
        tool: "anyclaw_ubuntu_session_interrupt",
        ok: true,
        sessionId
      });
    }
  };
}

function createSessionCloseTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Session Close",
    name: "anyclaw_ubuntu_session_close",
    description: "Close a persistent Ubuntu session.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["sessionId"],
      properties: {
        sessionId: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const sessionId = (readStringParam(args, "sessionId", { required: true }) || "").trim();
      const session = getSession(sessionId);
      try {
        session.process.kill("SIGKILL");
      } catch {
        // ignore
      }
      session.closed = true;
      sessions.delete(sessionId);
      return jsonResult({
        tool: "anyclaw_ubuntu_session_close",
        ok: true,
        sessionId
      });
    }
  };
}

function createSessionListTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Ubuntu Session List",
    name: "anyclaw_ubuntu_session_list",
    description: "List current persistent Ubuntu sessions.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      return jsonResult({
        tool: "anyclaw_ubuntu_session_list",
        ok: true,
        sessions: Array.from(sessions.values()).map((session) => ({
          id: session.id,
          createdAt: session.createdAt,
          workingDir: session.workingDir,
          closed: session.closed,
          closeCode: session.closeCode,
          busy: session.busy,
          bufferedChars: session.buffer.length,
          unreadChars: Math.max(0, session.buffer.length - session.cursor)
        }))
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
          " runtimeTmpDir=" + runtime.runtimeTmpDir +
          " timeoutMs=" + runtime.timeoutMs +
          " installTimeoutMs=" + runtime.installTimeoutMs +
          " sessionTimeoutMs=" + runtime.sessionTimeoutMs
      );
    }

    api.registerTool(createStatusTool(runtime));
    api.registerTool(createInstallTool(runtime));
    api.registerTool(createExecTool(runtime));
    api.registerTool(createExecHostTool(runtime));
    api.registerTool(createSessionOpenTool(runtime));
    api.registerTool(createSessionExecTool(runtime));
    api.registerTool(createSessionReadTool(runtime));
    api.registerTool(createSessionInterruptTool(runtime));
    api.registerTool(createSessionCloseTool(runtime));
    api.registerTool(createSessionListTool(runtime));
  }
};
