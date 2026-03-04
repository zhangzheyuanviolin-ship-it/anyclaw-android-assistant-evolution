import { jsonResult, readNumberParam, readStringParam } from "openclaw/plugin-sdk";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

type RuntimeConfig = {
  timeoutMs: number;
  screenshotDir: string;
  uiDumpPath: string;
  maxUiNodes: number;
};

type PluginRuntimeMeta = {
  parserVersion: string;
  loadedPath: string;
  indexSha256: string;
  registeredAt: string;
};

const PARSER_VERSION = "v1.0.0";
const DEFAULT_TIMEOUT_MS = 20_000;
const DEFAULT_SCREENSHOT_DIR = "/sdcard/Download/AnyClawShots";
const DEFAULT_UI_DUMP_PATH = "/sdcard/Download/AnyClawShots/ui_dump.xml";
const DEFAULT_MAX_UI_NODES = 180;

function asObject(input: unknown): Record<string, unknown> {
  return input && typeof input === "object" && !Array.isArray(input)
    ? (input as Record<string, unknown>)
    : {};
}

function clampNumber(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.max(min, Math.min(max, Math.trunc(value)));
}

function resolveRuntimeConfig(rawConfig: unknown): RuntimeConfig {
  const cfg = asObject(rawConfig);
  const timeoutSecondsRaw = typeof cfg.timeoutSeconds === "number" ? cfg.timeoutSeconds : DEFAULT_TIMEOUT_MS / 1000;
  const timeoutMs = clampNumber(timeoutSecondsRaw, 5, 120) * 1000;
  const screenshotDir =
    typeof cfg.screenshotDir === "string" && cfg.screenshotDir.trim()
      ? cfg.screenshotDir.trim()
      : DEFAULT_SCREENSHOT_DIR;
  const uiDumpPath =
    typeof cfg.uiDumpPath === "string" && cfg.uiDumpPath.trim()
      ? cfg.uiDumpPath.trim()
      : DEFAULT_UI_DUMP_PATH;
  const maxUiNodesRaw = typeof cfg.maxUiNodes === "number" ? cfg.maxUiNodes : DEFAULT_MAX_UI_NODES;
  const maxUiNodes = clampNumber(maxUiNodesRaw, 20, 400);
  return {
    timeoutMs,
    screenshotDir,
    uiDumpPath,
    maxUiNodes
  };
}

function resolveLoadedPath(): string {
  try {
    return fileURLToPath(import.meta.url);
  } catch {
    return "unknown";
  }
}

function resolveIndexSha256(filePath: string): string {
  if (!filePath || filePath === "unknown") return "unknown";
  try {
    const bytes = fs.readFileSync(filePath);
    return createHash("sha256").update(bytes).digest("hex").slice(0, 16);
  } catch {
    return "unknown";
  }
}

function createRuntimeMeta(): PluginRuntimeMeta {
  const loadedPath = resolveLoadedPath();
  const indexSha256 = resolveIndexSha256(loadedPath);
  return {
    parserVersion: PARSER_VERSION,
    loadedPath,
    indexSha256,
    registeredAt: new Date().toISOString()
  };
}

function withMeta(payload: Record<string, unknown>, meta: PluginRuntimeMeta): Record<string, unknown> {
  return {
    ...payload,
    _meta: {
      parserVersion: meta.parserVersion,
      loadedPath: meta.loadedPath,
      indexSha256: meta.indexSha256,
      registeredAt: meta.registeredAt
    }
  };
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
    return true;
  });
  return keep.join("\n").trim();
}

async function runSystemShell(
  args: string[],
  timeoutMs: number
): Promise<{ ok: boolean; code: number; stdout: string; stderr: string; error?: string }> {
  return new Promise((resolve) => {
    let stdout = "";
    let stderr = "";
    let done = false;

    const child = spawn("system-shell", args, {
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

function createDeviceScreenInfoTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Screen Info",
    name: "anyclaw_device_screen_info",
    description: "Get current device screen size/density and coordinate range.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {}
    },
    execute: async () => {
      const [sizeRun, densityRun] = await Promise.all([
        runSystemShell(["wm", "size"], runtime.timeoutMs),
        runSystemShell(["wm", "density"], runtime.timeoutMs)
      ]);

      const sizeText = [sizeRun.stdout, sizeRun.stderr].filter(Boolean).join("\n");
      const densityText = [densityRun.stdout, densityRun.stderr].filter(Boolean).join("\n");

      const sizeMatch = sizeText.match(/Physical size:\s*(\d+)\s*x\s*(\d+)/i) || sizeText.match(/(\d+)\s*x\s*(\d+)/);
      const densityMatch = densityText.match(/Physical density:\s*(\d+)/i) || densityText.match(/(\d+)/);

      const width = sizeMatch ? Number(sizeMatch[1]) : null;
      const height = sizeMatch ? Number(sizeMatch[2]) : null;
      const density = densityMatch ? Number(densityMatch[1]) : null;

      const result = {
        ok: sizeRun.ok || densityRun.ok,
        tool: "screen_info",
        width,
        height,
        density,
        coordinateHint:
          width && height ? `x:0..${Math.max(width - 1, 0)}, y:0..${Math.max(height - 1, 0)}` : null,
        sizeRaw: sizeText || undefined,
        densityRaw: densityText || undefined
      };
      return jsonResult(withMeta(result, meta));
    }
  };
}

function createDeviceScreenshotTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Screenshot",
    name: "anyclaw_device_screenshot",
    description: "Take a real device screenshot using Android screencap (Shizuku channel).",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {
        path: {
          type: "string",
          description: "Optional absolute output path (.png)."
        }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const customPath = (readStringParam(args, "path") || "").trim();
      const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
      const outputPath = customPath || `${runtime.screenshotDir}/device_screen_${timestamp}.png`;
      const outputDir = outputPath.includes("/") ? outputPath.substring(0, outputPath.lastIndexOf("/")) : runtime.screenshotDir;

      await runSystemShell(["mkdir", "-p", outputDir], runtime.timeoutMs);
      const run = await runSystemShell(["screencap", "-p", outputPath], runtime.timeoutMs);
      const stat = run.ok ? await runSystemShell(["ls", "-l", outputPath], runtime.timeoutMs) : null;

      const result = {
        ok: run.ok,
        tool: "screenshot",
        path: outputPath,
        sizeHint: stat?.stdout || undefined,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      };
      return jsonResult(withMeta(result, meta));
    }
  };
}

function parseUiNodes(xml: string, maxNodes: number): Array<Record<string, unknown>> {
  const rows: Array<Record<string, unknown>> = [];
  const nodeRegex = /<node\b([^>]*?)\/>/g;
  let match: RegExpExecArray | null;
  while ((match = nodeRegex.exec(xml)) && rows.length < maxNodes) {
    const attrs = match[1] || "";
    const attr = (name: string): string => {
      const m = attrs.match(new RegExp(`${name}="([^"]*)"`, "i"));
      return m ? m[1] : "";
    };
    const boundsRaw = attr("bounds");
    const boundsMatch = boundsRaw.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (!boundsMatch) continue;

    const left = Number(boundsMatch[1]);
    const top = Number(boundsMatch[2]);
    const right = Number(boundsMatch[3]);
    const bottom = Number(boundsMatch[4]);
    const centerX = Math.floor((left + right) / 2);
    const centerY = Math.floor((top + bottom) / 2);

    const text = attr("text");
    const contentDesc = attr("content-desc");
    const resourceId = attr("resource-id");
    const clickable = attr("clickable") === "true";
    const focusable = attr("focusable") === "true";

    if (!clickable && !focusable && !text && !contentDesc && !resourceId) continue;

    rows.push({
      text,
      contentDesc,
      resourceId,
      className: attr("class"),
      clickable,
      focusable,
      bounds: { left, top, right, bottom },
      center: { x: centerX, y: centerY }
    });
  }
  return rows;
}

function createDeviceUiCoordinatesTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device UI Coordinates",
    name: "anyclaw_device_ui_coordinates",
    description: "Dump current UI XML and return element bounds with center coordinates.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {
        maxNodes: {
          type: "number",
          minimum: 20,
          maximum: 400,
          description: "Maximum UI nodes to return."
        }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const maxNodes = clampNumber(
        readNumberParam(args, "maxNodes", { integer: true }) ?? runtime.maxUiNodes,
        20,
        400
      );

      const dumpPath = runtime.uiDumpPath;
      const dumpDir = dumpPath.substring(0, dumpPath.lastIndexOf("/")) || "/sdcard/Download";
      await runSystemShell(["mkdir", "-p", dumpDir], runtime.timeoutMs);

      const dumpRun = await runSystemShell(["uiautomator", "dump", dumpPath], runtime.timeoutMs);
      const catRun = await runSystemShell(["cat", dumpPath], runtime.timeoutMs);
      const sizeRun = await runSystemShell(["wm", "size"], runtime.timeoutMs);

      const xml = catRun.stdout || "";
      const nodes = xml ? parseUiNodes(xml, maxNodes) : [];
      const sizeMatch = (sizeRun.stdout || "").match(/Physical size:\s*(\d+)\s*x\s*(\d+)/i);
      const width = sizeMatch ? Number(sizeMatch[1]) : null;
      const height = sizeMatch ? Number(sizeMatch[2]) : null;

      const result = {
        ok: dumpRun.ok && catRun.ok,
        tool: "ui_coordinates",
        dumpPath,
        width,
        height,
        nodeCount: nodes.length,
        nodes,
        dumpOutput: dumpRun.stdout || dumpRun.stderr || undefined,
        errorOutput: dumpRun.ok && catRun.ok ? undefined : (dumpRun.stderr || catRun.stderr || undefined),
        error: dumpRun.error || catRun.error
      };
      return jsonResult(withMeta(result, meta));
    }
  };
}

function createDeviceTapTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Tap",
    name: "anyclaw_device_tap",
    description: "Tap on screen coordinates.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["x", "y"],
      properties: {
        x: { type: "number" },
        y: { type: "number" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const x = readNumberParam(args, "x", { integer: true });
      const y = readNumberParam(args, "y", { integer: true });
      if (x === undefined || y === undefined) {
        return jsonResult(withMeta({ ok: false, tool: "tap", error: "missing_coordinates" }, meta));
      }
      const run = await runSystemShell(["input", "tap", String(x), String(y)], runtime.timeoutMs);
      return jsonResult(withMeta({
        ok: run.ok,
        tool: "tap",
        x,
        y,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      }, meta));
    }
  };
}

function createDeviceDoubleTapTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Double Tap",
    name: "anyclaw_device_double_tap",
    description: "Double tap on screen coordinates.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["x", "y"],
      properties: {
        x: { type: "number" },
        y: { type: "number" },
        intervalMs: { type: "number" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const x = readNumberParam(args, "x", { integer: true });
      const y = readNumberParam(args, "y", { integer: true });
      const intervalMs = clampNumber(readNumberParam(args, "intervalMs", { integer: true }) ?? 120, 50, 1000);
      if (x === undefined || y === undefined) {
        return jsonResult(withMeta({ ok: false, tool: "double_tap", error: "missing_coordinates" }, meta));
      }
      const first = await runSystemShell(["input", "tap", String(x), String(y)], runtime.timeoutMs);
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
      const second = await runSystemShell(["input", "tap", String(x), String(y)], runtime.timeoutMs);
      return jsonResult(withMeta({
        ok: first.ok && second.ok,
        tool: "double_tap",
        x,
        y,
        intervalMs,
        firstExitCode: first.code,
        secondExitCode: second.code,
        errorOutput: first.ok && second.ok ? undefined : [first.stderr, second.stderr].filter(Boolean).join("\n") || undefined
      }, meta));
    }
  };
}

function createDeviceLongPressTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Long Press",
    name: "anyclaw_device_long_press",
    description: "Long press on screen coordinates.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["x", "y"],
      properties: {
        x: { type: "number" },
        y: { type: "number" },
        durationMs: { type: "number" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const x = readNumberParam(args, "x", { integer: true });
      const y = readNumberParam(args, "y", { integer: true });
      const durationMs = clampNumber(readNumberParam(args, "durationMs", { integer: true }) ?? 650, 200, 5000);
      if (x === undefined || y === undefined) {
        return jsonResult(withMeta({ ok: false, tool: "long_press", error: "missing_coordinates" }, meta));
      }
      const run = await runSystemShell(
        ["input", "swipe", String(x), String(y), String(x), String(y), String(durationMs)],
        runtime.timeoutMs
      );
      return jsonResult(withMeta({
        ok: run.ok,
        tool: "long_press",
        x,
        y,
        durationMs,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined)
      }, meta));
    }
  };
}

function createDeviceSwipeTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Swipe",
    name: "anyclaw_device_swipe",
    description: "Swipe on screen coordinates.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["start_x", "start_y", "end_x", "end_y"],
      properties: {
        start_x: { type: "number" },
        start_y: { type: "number" },
        end_x: { type: "number" },
        end_y: { type: "number" },
        durationMs: { type: "number" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const sx = readNumberParam(args, "start_x", { integer: true });
      const sy = readNumberParam(args, "start_y", { integer: true });
      const ex = readNumberParam(args, "end_x", { integer: true });
      const ey = readNumberParam(args, "end_y", { integer: true });
      const durationMs = clampNumber(readNumberParam(args, "durationMs", { integer: true }) ?? 320, 100, 8000);
      if ([sx, sy, ex, ey].some((v) => v === undefined)) {
        return jsonResult(withMeta({ ok: false, tool: "swipe", error: "missing_coordinates" }, meta));
      }
      const run = await runSystemShell(
        ["input", "swipe", String(sx), String(sy), String(ex), String(ey), String(durationMs)],
        runtime.timeoutMs
      );
      return jsonResult(withMeta({
        ok: run.ok,
        tool: "swipe",
        start: { x: sx, y: sy },
        end: { x: ex, y: ey },
        durationMs,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined)
      }, meta));
    }
  };
}

function normalizeInputText(raw: string): string {
  return raw
    .trim()
    .replace(/\s/g, "%s");
}

function createDeviceInputTextTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Input Text",
    name: "anyclaw_device_input_text",
    description: "Input text into focused field.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["text"],
      properties: {
        text: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const textRaw = readStringParam(args, "text", { required: true, allowEmpty: true }) ?? "";
      const text = normalizeInputText(textRaw);
      const run = await runSystemShell(["input", "text", text], runtime.timeoutMs);
      return jsonResult(withMeta({
        ok: run.ok,
        tool: "input_text",
        chars: textRaw.length,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined)
      }, meta));
    }
  };
}

function createDeviceKeyeventTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Keyevent",
    name: "anyclaw_device_keyevent",
    description: "Send Android keyevent (e.g. BACK/HOME/KEYCODE_ENTER).",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["keycode"],
      properties: {
        keycode: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const keycode = readStringParam(args, "keycode", { required: true })?.trim() || "";
      const run = await runSystemShell(["input", "keyevent", keycode], runtime.timeoutMs);
      return jsonResult(withMeta({
        ok: run.ok,
        tool: "keyevent",
        keycode,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined)
      }, meta));
    }
  };
}

function createDeviceStatusBarTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device StatusBar",
    name: "anyclaw_device_statusbar",
    description: "Control status bar: notifications, quick_settings, collapse.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["action"],
      properties: {
        action: {
          type: "string",
          description: "notifications | quick_settings | collapse"
        }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const action = (readStringParam(args, "action", { required: true }) || "").trim().toLowerCase();
      let shellArgs: string[];
      if (action === "notifications") shellArgs = ["cmd", "statusbar", "expand-notifications"];
      else if (action === "quick_settings") shellArgs = ["cmd", "statusbar", "expand-settings"];
      else if (action === "collapse") shellArgs = ["cmd", "statusbar", "collapse"];
      else {
        return jsonResult(withMeta({
          ok: false,
          tool: "statusbar",
          error: "invalid_action",
          message: "action must be notifications | quick_settings | collapse"
        }, meta));
      }
      const run = await runSystemShell(shellArgs, runtime.timeoutMs);
      return jsonResult(withMeta({
        ok: run.ok,
        tool: "statusbar",
        action,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined)
      }, meta));
    }
  };
}

function createDeviceOpenAppTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Open App",
    name: "anyclaw_device_open_app",
    description: "Launch an installed app by package name.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["packageName"],
      properties: {
        packageName: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const packageName = (readStringParam(args, "packageName", { required: true }) || "").trim();
      const run = await runSystemShell(
        ["monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"],
        runtime.timeoutMs
      );
      return jsonResult(withMeta({
        ok: run.ok,
        tool: "open_app",
        packageName,
        exitCode: run.code,
        output: run.stdout || undefined,
        errorOutput: run.ok ? undefined : (run.stderr || undefined)
      }, meta));
    }
  };
}

function createDeviceOpenUrlTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "Device Open URL",
    name: "anyclaw_device_open_url",
    description: "Open URL on system browser/app via Android intent.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["url"],
      properties: {
        url: { type: "string" },
        packageName: { type: "string" }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const url = (readStringParam(args, "url", { required: true }) || "").trim();
      const packageName = (readStringParam(args, "packageName") || "").trim();
      if (!/^https?:\/\//i.test(url)) {
        return jsonResult(withMeta({
          ok: false,
          tool: "open_url",
          error: "invalid_url_scheme",
          message: "url must start with http:// or https://"
        }, meta));
      }
      const shellArgs = ["am", "start", "-a", "android.intent.action.VIEW", "-d", url];
      if (packageName) shellArgs.push("-p", packageName);
      const run = await runSystemShell(shellArgs, runtime.timeoutMs);
      return jsonResult(withMeta({
        ok: run.ok,
        tool: "open_url",
        url,
        packageName: packageName || undefined,
        exitCode: run.code,
        output: run.stdout || undefined,
        errorOutput: run.ok ? undefined : (run.stderr || undefined)
      }, meta));
    }
  };
}

export default {
  id: "anyclaw-device-suite",
  name: "AnyClaw Device Suite",
  register(api: any) {
    const runtime = resolveRuntimeConfig(api.pluginConfig);
    const runtimeMeta = createRuntimeMeta();

    if (api.logger && api.logger.info) {
      api.logger.info(
        "anyclaw-device-suite loaded parser=" + runtimeMeta.parserVersion +
          " path=" + runtimeMeta.loadedPath +
          " hash=" + runtimeMeta.indexSha256
      );
    }

    api.registerTool(createDeviceScreenInfoTool(runtime, runtimeMeta));
    api.registerTool(createDeviceScreenshotTool(runtime, runtimeMeta));
    api.registerTool(createDeviceUiCoordinatesTool(runtime, runtimeMeta));
    api.registerTool(createDeviceTapTool(runtime, runtimeMeta));
    api.registerTool(createDeviceDoubleTapTool(runtime, runtimeMeta));
    api.registerTool(createDeviceLongPressTool(runtime, runtimeMeta));
    api.registerTool(createDeviceSwipeTool(runtime, runtimeMeta));
    api.registerTool(createDeviceInputTextTool(runtime, runtimeMeta));
    api.registerTool(createDeviceKeyeventTool(runtime, runtimeMeta));
    api.registerTool(createDeviceStatusBarTool(runtime, runtimeMeta));
    api.registerTool(createDeviceOpenAppTool(runtime, runtimeMeta));
    api.registerTool(createDeviceOpenUrlTool(runtime, runtimeMeta));
  }
};
