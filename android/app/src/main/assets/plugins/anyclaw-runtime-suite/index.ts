import { jsonResult, readStringParam } from "openclaw/plugin-sdk";
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";

type RuntimeConfig = {
  timeoutMs: number;
  codexApiBaseUrl: string;
  runtimeDoctorPath: string;
};

const DEFAULT_TIMEOUT_MS = 30_000;
const DEFAULT_CODEX_API_BASE_URL = "http://127.0.0.1:18923";
const DEFAULT_RUNTIME_DOCTOR_PATH = "~/.openclaw/workspace/scripts/runtime-env-doctor.sh";

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
    if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/.test(text) && /(codex_core::|models_manager::manager|openclaw|gateway)/i.test(text)) return false;
    if (/^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\./.test(text) && /(codex_core::|openclaw|gateway)/i.test(text)) return false;
    if (/^\[openclaw-(gw|ui)\]/i.test(text)) return false;
    return true;
  });
  const cleaned = keep.join("\n").trim();
  if (cleaned.length <= 1200) return cleaned;
  return cleaned.slice(0, 1200) + "\n...(truncated)";
}

type McpHealthLevel = "healthy" | "degraded" | "blocked";

function firstString(value: unknown, keys: string[]): string {
  if (!value || typeof value !== "object") return "";
  const obj = value as Record<string, unknown>;
  for (const key of keys) {
    const text = String(obj[key] ?? "").trim();
    if (text) return text;
  }
  return "";
}

function normalizeMcpStatus(value: unknown): string {
  const text = String(value ?? "").trim().toLowerCase();
  return text.replace(/\s+/g, "_");
}

function detectMcpHealth(statusText: string, reason: string): McpHealthLevel {
  const all = `${statusText} ${reason}`.toLowerCase();
  if (/(403|forbidden|denied|unauthorized|blocked|invalid[_\s-]?token)/.test(all)) return "blocked";
  if (/(error|failed|timeout|unavailable|offline|disconnected|disabled)/.test(all)) return "blocked";
  if (/(ok|healthy|ready|connected|running|active|available)/.test(all)) return "healthy";
  if (/(warn|degraded|partial|slow|unstable|limited|init|loading|pending)/.test(all)) return "degraded";
  return "degraded";
}

function flattenMcpItems(value: unknown): Array<Record<string, unknown>> {
  if (Array.isArray(value)) {
    return value.filter((item) => item && typeof item === "object" && !Array.isArray(item)) as Array<Record<string, unknown>>;
  }
  if (!value || typeof value !== "object") return [];
  const obj = value as Record<string, unknown>;
  const direct = ["servers", "items", "list", "services", "results"];
  for (const key of direct) {
    if (Array.isArray(obj[key])) {
      return flattenMcpItems(obj[key]);
    }
  }
  for (const key of ["data", "result", "payload", "body"]) {
    const nested = obj[key];
    const flat = flattenMcpItems(nested);
    if (flat.length > 0) return flat;
  }
  return [];
}

function summarizeMcpHealth(rawData: unknown): Record<string, unknown> {
  const rows = flattenMcpItems(rawData);
  if (rows.length === 0) {
    return {
      level: "degraded",
      total: 0,
      healthy: 0,
      degraded: 0,
      blocked: 0,
      preferredOrder: [],
      servers: []
    };
  }

  const normalized = rows.map((item, index) => {
    const id = firstString(item, ["id", "name", "serverId", "key"]) || `server_${index + 1}`;
    const name = firstString(item, ["name", "displayName", "label"]);
    const statusRaw = firstString(item, ["status", "health", "state", "connectionState", "result"]) || "unknown";
    const reason = firstString(item, ["error", "message", "detail", "lastError", "reason"]);
    const status = normalizeMcpStatus(statusRaw);
    const level = detectMcpHealth(status, reason);
    return { id, name, status, level, reason };
  });

  const healthy = normalized.filter((item) => item.level === "healthy");
  const degraded = normalized.filter((item) => item.level === "degraded");
  const blocked = normalized.filter((item) => item.level === "blocked");

  const level: McpHealthLevel =
    healthy.length > 0 ? "healthy" : (degraded.length > 0 ? "degraded" : "blocked");

  return {
    level,
    total: normalized.length,
    healthy: healthy.length,
    degraded: degraded.length,
    blocked: blocked.length,
    preferredOrder: [...healthy, ...degraded].map((item) => item.id),
    servers: normalized
  };
}

function resolvePath(input: string): string {
  const home = String(process.env.HOME || "").trim();
  let value = input.trim();
  if (value.startsWith("~/") && home) {
    value = home + value.slice(1);
  }
  if (value.includes("$HOME") && home) {
    value = value.replace(/\$HOME/g, home);
  }
  return value;
}

function resolveRuntimeConfig(rawConfig: unknown): RuntimeConfig {
  const cfg = asObject(rawConfig);
  const timeoutSecondsRaw = typeof cfg.timeoutSeconds === "number" ? cfg.timeoutSeconds : DEFAULT_TIMEOUT_MS / 1000;
  const timeoutMs = clampNumber(timeoutSecondsRaw, 5, 180) * 1000;
  const codexApiBaseUrl =
    typeof cfg.codexApiBaseUrl === "string" && cfg.codexApiBaseUrl.trim()
      ? cfg.codexApiBaseUrl.trim().replace(/\/$/, "")
      : DEFAULT_CODEX_API_BASE_URL;
  const runtimeDoctorPath =
    typeof cfg.runtimeDoctorPath === "string" && cfg.runtimeDoctorPath.trim()
      ? resolvePath(cfg.runtimeDoctorPath)
      : resolvePath(DEFAULT_RUNTIME_DOCTOR_PATH);
  return {
    timeoutMs,
    codexApiBaseUrl,
    runtimeDoctorPath
  };
}

async function runCommand(
  command: string,
  args: string[],
  timeoutMs: number
): Promise<{ ok: boolean; code: number; stdout: string; stderr: string; error?: string }> {
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

async function codexGet(runtime: RuntimeConfig, endpoint: string): Promise<{ ok: boolean; status: number; data: unknown; error?: string }> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), runtime.timeoutMs);
  const url = runtime.codexApiBaseUrl + endpoint;
  try {
    const response = await fetch(url, { method: "GET", signal: ctrl.signal });
    const text = await response.text();
    let data: unknown = text;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = text;
    }
    return { ok: response.ok, status: response.status, data };
  } catch (error) {
    return { ok: false, status: 0, data: {}, error: String(error) };
  } finally {
    clearTimeout(timer);
  }
}

async function codexRpc(runtime: RuntimeConfig, method: string, params: unknown): Promise<{ ok: boolean; status: number; data: unknown; error?: string }> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), runtime.timeoutMs);
  const url = runtime.codexApiBaseUrl + "/codex-api/rpc";
  try {
    const response = await fetch(url, {
      method: "POST",
      signal: ctrl.signal,
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ method, params })
    });
    const text = await response.text();
    let data: unknown = text;
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = text;
    }
    return { ok: response.ok, status: response.status, data };
  } catch (error) {
    return { ok: false, status: 0, data: {}, error: String(error) };
  } finally {
    clearTimeout(timer);
  }
}

async function callDoctor(runtime: RuntimeConfig, doctorArgs: string[]): Promise<{ ok: boolean; code: number; stdout: string; stderr: string; error?: string }> {
  if (!existsSync(runtime.runtimeDoctorPath)) {
    return {
      ok: false,
      code: 127,
      stdout: "",
      stderr: "runtime_doctor_missing"
    };
  }
  return runCommand("sh", [runtime.runtimeDoctorPath, ...doctorArgs], runtime.timeoutMs);
}

function createRuntimeHealthTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Runtime Health",
    name: "anyclaw_runtime_health",
    description: "Inspect package/runtime health and Codex MCP method surface.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      const toolchain = await runCommand(
        "sh",
        [
          "-lc",
          [
            "echo openclaw=$(command -v openclaw || true)",
            "echo python3=$(command -v python3 || true)",
            "echo pip=$(command -v pip || command -v pip3 || true)",
            "echo apt=$(command -v apt || true)",
            "echo dpkg=$(command -v dpkg || true)",
            "echo pkg=$(command -v pkg || true)",
            "echo prefix=${PREFIX:-}",
            "echo home=${HOME:-}",
            "residual=$(grep -RIl '/data/data/com.termux/files/usr' \"${HOME:-}/.openclaw\" \"${PREFIX:-}/bin\" 2>/dev/null | head -n 20 || true)",
            "count=$(printf '%s\\n' \"$residual\" | sed '/^$/d' | wc -l | tr -d ' ')",
            "echo termux_residual_count=$count"
          ].join("; ")
        ],
        runtime.timeoutMs
      );

      const doctorProbe = await callDoctor(runtime, ["--probe", "--json"]);
      const methodCatalog = await codexGet(runtime, "/codex-api/meta/methods");

      return jsonResult({
        ok: toolchain.ok,
        tool: "runtime_health",
        runtimeDoctorPath: runtime.runtimeDoctorPath,
        runtimeDoctorPresent: existsSync(runtime.runtimeDoctorPath),
        toolchain: toolchain.stdout || undefined,
        doctorProbe: {
          ok: doctorProbe.ok,
          code: doctorProbe.code,
          stdout: doctorProbe.stdout || undefined,
          stderr: doctorProbe.stderr || undefined,
          error: doctorProbe.error
        },
        codexMethods: methodCatalog.data,
        codexMethodsOk: methodCatalog.ok,
        codexMethodsError: methodCatalog.error
      });
    }
  };
}

function createRuntimePrefixRepairTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Runtime Prefix Repair",
    name: "anyclaw_runtime_prefix_repair",
    description: "Repair legacy Termux prefix remnants and shebang drift with safe skip-on-busy behavior.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {
        aggressive: { type: "boolean" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const aggressive = args.aggressive === true;
      const doctorArgs = aggressive ? ["--repair", "--aggressive", "--json"] : ["--repair", "--json"];
      const run = await callDoctor(runtime, doctorArgs);
      return jsonResult({
        ok: run.ok,
        tool: "runtime_prefix_repair",
        aggressive,
        exitCode: run.code,
        stdout: run.stdout || undefined,
        stderr: run.stderr || undefined,
        error: run.error
      });
    }
  };
}

function createCodexMethodsTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Codex Methods",
    name: "anyclaw_codex_methods",
    description: "List available codex app-server RPC methods from local bridge.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      const methods = await codexGet(runtime, "/codex-api/meta/methods");
      return jsonResult({
        ok: methods.ok,
        tool: "codex_methods",
        status: methods.status,
        data: methods.data,
        error: methods.error
      });
    }
  };
}

function createCodexMcpStatusTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Codex MCP Status",
    name: "anyclaw_codex_mcp_status",
    description: "Query MCP server status via supported codex RPC methods with fallback.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      const candidates = ["mcpServerStatus/list", "mcpServer/list", "mcp/list"];
      const attempts: Array<Record<string, unknown>> = [];
      for (const method of candidates) {
        const res = await codexRpc(runtime, method, {});
        const health = res.ok ? summarizeMcpHealth(res.data) : undefined;
        attempts.push({ method, ok: res.ok, status: res.status, health, data: res.data, error: res.error });
        if (res.ok) {
          return jsonResult({
            ok: true,
            tool: "codex_mcp_status",
            method,
            health,
            data: res.data,
            attempts
          });
        }
      }
      return jsonResult({
        ok: false,
        tool: "codex_mcp_status",
        error: "no_supported_mcp_status_method",
        attempts
      });
    }
  };
}

function createCodexMcpReloadTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Codex MCP Reload",
    name: "anyclaw_codex_mcp_reload",
    description: "Reload MCP server config via supported codex RPC methods with fallback.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      const candidates = ["config/mcpServer/reload", "mcpServer/reload", "mcp/reload"];
      const attempts: Array<Record<string, unknown>> = [];
      for (const method of candidates) {
        const res = await codexRpc(runtime, method, {});
        attempts.push({ method, ok: res.ok, status: res.status, data: res.data, error: res.error });
        if (res.ok) {
          return jsonResult({
            ok: true,
            tool: "codex_mcp_reload",
            method,
            data: res.data,
            attempts
          });
        }
      }
      return jsonResult({
        ok: false,
        tool: "codex_mcp_reload",
        error: "no_supported_mcp_reload_method",
        attempts
      });
    }
  };
}

function createCodexRpcTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Codex RPC",
    name: "anyclaw_codex_rpc",
    description: "Call any codex app-server RPC method through local bridge.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["method"],
      properties: {
        method: { type: "string" },
        paramsJson: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const method = (readStringParam(args, "method", { required: true }) || "").trim();
      const paramsJson = (readStringParam(args, "paramsJson") || "{}").trim();
      let params: unknown = {};
      try {
        params = paramsJson ? JSON.parse(paramsJson) : {};
      } catch (error) {
        return jsonResult({
          ok: false,
          tool: "codex_rpc",
          error: "invalid_params_json",
          detail: String(error)
        });
      }
      const result = await codexRpc(runtime, method, params);
      return jsonResult({
        ok: result.ok,
        tool: "codex_rpc",
        method,
        status: result.status,
        data: result.data,
        error: result.error
      });
    }
  };
}

export default {
  id: "anyclaw-runtime-suite",
  name: "AnyClaw Runtime Suite",
  register(api: any) {
    const runtime = resolveRuntimeConfig(api.pluginConfig);

    if (api.logger && api.logger.info) {
      api.logger.info(
        "anyclaw-runtime-suite loaded baseUrl=" + runtime.codexApiBaseUrl +
          " doctorPath=" + runtime.runtimeDoctorPath
      );
    }

    api.registerTool(createRuntimeHealthTool(runtime));
    api.registerTool(createRuntimePrefixRepairTool(runtime));
    api.registerTool(createCodexMethodsTool(runtime));
    api.registerTool(createCodexMcpStatusTool(runtime));
    api.registerTool(createCodexMcpReloadTool(runtime));
    api.registerTool(createCodexRpcTool(runtime));
  }
};
