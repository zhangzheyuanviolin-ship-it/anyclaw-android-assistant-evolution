#!/usr/bin/env node

const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const SERVER_INFO = { name: "anyclaw-toolbox", version: "2.0.0" };
const DEFAULT_TIMEOUT_MS = 30000;
const MAX_STDIO_BYTES = 4 * 1024 * 1024;
const DEFAULT_SEARCH_LIMIT = 6;
const WEB_BRIDGE_URL = process.env.ANYCLAW_WEB_BRIDGE_URL || "http://127.0.0.1:18926/web/call";
const TAVILY_BASE_URL = process.env.ANYCLAW_TAVILY_BASE_URL || "https://api.tavily.com/search";
const EXA_MCP_URL = process.env.ANYCLAW_EXA_MCP_URL || "https://mcp.exa.ai/mcp";
const GITHUB_API_BASE = (process.env.ANYCLAW_GITHUB_API_BASE_URL || "https://api.github.com").replace(/\/$/, "");
const WORKSPACE_ROOT = path.resolve(process.env.ANYCLAW_WORKSPACE_ROOT || path.join(process.env.HOME || "/tmp", ".openclaw", "workspace"));
const MCP_CONFIG_PATH = process.env.ANYCLAW_MCP_CONFIG_PATH || "";

const SEARCH_URLS = {
  google: (query) => "https://www.google.com/search?q=" + encodeURIComponent(query) + "&hl=en",
  google_scholar: (query) => "https://scholar.google.com/scholar?q=" + encodeURIComponent(query),
  duckduckgo: (query) => "https://html.duckduckgo.com/html/?q=" + encodeURIComponent(query),
  bing: (query) => "https://www.bing.com/search?q=" + encodeURIComponent(query) + "&setlang=en",
  baidu: (query) => "https://www.baidu.com/s?wd=" + encodeURIComponent(query),
  quark: (query) => "https://quark.sm.cn/s?q=" + encodeURIComponent(query)
};

const SEARCH_ENGINE_KEYS = Object.keys(SEARCH_URLS);

function tool(name, description, properties = {}, required = []) {
  return {
    name,
    description,
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties,
      required
    }
  };
}

const TOOL_DEFS = [
  tool("anyclaw_device_exec", "Execute Android system command via Shizuku-backed system-shell.", {
    command: { type: "string", minLength: 1 },
    timeoutMs: { type: "integer", minimum: 1000, maximum: 120000 }
  }, ["command"]),
  tool("anyclaw_device_uiautomator_dump", "Dump UI hierarchy XML into shared storage.", {
    outputPath: { type: "string" }
  }),
  tool("anyclaw_search_web", "Legacy web search alias (DuckDuckGo).", {
    query: { type: "string", minLength: 1 },
    maxResults: { type: "integer", minimum: 1, maximum: 10 }
  }, ["query"]),
  tool("anyclaw_search_wikipedia", "Search Wikipedia summaries.", {
    query: { type: "string", minLength: 1 },
    maxResults: { type: "integer", minimum: 1, maximum: 10 }
  }, ["query"]),
  tool("anyclaw_fetch_url", "Fetch URL text content.", {
    url: { type: "string", minLength: 8 },
    maxChars: { type: "integer", minimum: 200, maximum: 80000 }
  }, ["url"]),
  tool("anyclaw_github_repo", "Legacy GitHub repository metadata alias.", {
    owner: { type: "string", minLength: 1 },
    repo: { type: "string", minLength: 1 },
    token: { type: "string" }
  }, ["owner", "repo"]),
  tool("anyclaw_github_search_repositories", "Legacy GitHub repository search alias.", {
    query: { type: "string", minLength: 1 },
    perPage: { type: "integer", minimum: 1, maximum: 100 },
    token: { type: "string" }
  }, ["query"]),
  tool("anyclaw_github_search_code", "Legacy GitHub code search alias.", {
    query: { type: "string", minLength: 1 },
    perPage: { type: "integer", minimum: 1, maximum: 100 },
    token: { type: "string" }
  }, ["query"]),

  tool("anyclaw_google_search", "Search web pages with Google.", {
    query: { type: "string", minLength: 1 },
    limit: { type: "integer", minimum: 1, maximum: 10 }
  }, ["query"]),
  tool("anyclaw_google_scholar_search", "Search academic publications with Google Scholar.", {
    query: { type: "string", minLength: 1 },
    limit: { type: "integer", minimum: 1, maximum: 10 }
  }, ["query"]),
  tool("anyclaw_duckduckgo_search", "Search web pages with DuckDuckGo.", {
    query: { type: "string", minLength: 1 },
    limit: { type: "integer", minimum: 1, maximum: 10 }
  }, ["query"]),
  tool("anyclaw_bing_search", "Search web pages with Bing.", {
    query: { type: "string", minLength: 1 },
    limit: { type: "integer", minimum: 1, maximum: 10 }
  }, ["query"]),
  tool("anyclaw_baidu_search", "Search web pages with Baidu.", {
    query: { type: "string", minLength: 1 },
    limit: { type: "integer", minimum: 1, maximum: 10 }
  }, ["query"]),
  tool("anyclaw_quark_search", "Search web pages with Quark.", {
    query: { type: "string", minLength: 1 },
    limit: { type: "integer", minimum: 1, maximum: 10 }
  }, ["query"]),
  tool("anyclaw_multi_search", "Multi-source search across Google/Scholar/DDG/Bing/Baidu/Quark.", {
    query: { type: "string", minLength: 1 },
    limit: { type: "integer", minimum: 1, maximum: 10 },
    enginesJson: { type: "string" }
  }, ["query"]),
  tool("anyclaw_web_visit", "Visit webpage and extract text.", {
    url: { type: "string", minLength: 8 },
    maxChars: { type: "integer", minimum: 1000, maximum: 80000 }
  }, ["url"]),
  tool("anyclaw_http_request", "Send arbitrary HTTP request.", {
    url: { type: "string", minLength: 8 },
    method: { type: "string" },
    headersJson: { type: "string" },
    body: { type: "string" },
    maxChars: { type: "integer", minimum: 1000, maximum: 80000 }
  }, ["url"]),
  tool("anyclaw_multipart_request", "Send multipart/form-data request with optional file uploads.", {
    url: { type: "string", minLength: 8 },
    method: { type: "string" },
    headersJson: { type: "string" },
    formDataJson: { type: "string" },
    filesJson: { type: "string" },
    maxChars: { type: "integer", minimum: 1000, maximum: 80000 }
  }, ["url"]),
  tool("anyclaw_open_in_system_browser", "Open URL in Android system browser.", {
    url: { type: "string", minLength: 8 },
    packageName: { type: "string" }
  }, ["url"]),
  tool("anyclaw_tavily_search", "Advanced Tavily search.", {
    query: { type: "string", minLength: 1 },
    maxResults: { type: "integer", minimum: 1, maximum: 10 },
    searchDepth: { type: "string" },
    apiKey: { type: "string" }
  }, ["query"]),
  tool("anyclaw_exa_search", "Exa MCP web search. On timeout, auto-fallbacks to Tavily search.", {
    query: { type: "string", minLength: 1 },
    maxResults: { type: "integer", minimum: 1, maximum: 10 },
    numResults: { type: "integer", minimum: 1, maximum: 10 },
    timeoutMs: { type: "integer", minimum: 3000, maximum: 60000 },
    apiKey: { type: "string" },
    tavilyApiKey: { type: "string" },
    searchDepth: { type: "string" }
  }, ["query"]),

  tool("start_web", "Start persistent web session.", {
    url: { type: "string" },
    user_agent: { type: "string" },
    session_name: { type: "string" },
    headersJson: { type: "string" }
  }),
  tool("stop_web", "Stop one or all web sessions.", {
    session_id: { type: "string" },
    close_all: { type: "boolean" }
  }),
  tool("web_navigate", "Navigate existing session to URL.", {
    url: { type: "string" },
    session_id: { type: "string" },
    headersJson: { type: "string" }
  }, ["url"]),
  tool("web_eval", "Execute JavaScript in active web session.", {
    script: { type: "string" },
    session_id: { type: "string" },
    timeout_ms: { type: "integer", minimum: 1000, maximum: 60000 }
  }, ["script"]),
  tool("web_click", "Click element by aria-ref.", {
    ref: { type: "string" },
    session_id: { type: "string" }
  }, ["ref"]),
  tool("web_fill", "Fill input by CSS selector.", {
    selector: { type: "string" },
    value: { type: "string" },
    session_id: { type: "string" }
  }, ["selector", "value"]),
  tool("web_file_upload", "Upload local files in web session.", {
    session_id: { type: "string" },
    paths: { type: "array", items: { type: "string" } }
  }),
  tool("web_wait_for", "Wait for readiness or selector.", {
    session_id: { type: "string" },
    selector: { type: "string" },
    timeout_ms: { type: "integer", minimum: 1000, maximum: 60000 }
  }),
  tool("web_snapshot", "Capture page snapshot.", {
    session_id: { type: "string" },
    include_links: { type: "boolean" },
    include_images: { type: "boolean" },
    max_chars: { type: "integer", minimum: 1000, maximum: 80000 }
  }),
  tool("web_content", "Extract page content.", {
    session_id: { type: "string" },
    include_links: { type: "boolean" },
    include_images: { type: "boolean" },
    max_chars: { type: "integer", minimum: 1000, maximum: 80000 }
  }),

  tool("anyclaw_apply_file", "Apply local file changes within workspace root.", {
    path: { type: "string", minLength: 1 },
    mode: { type: "string", enum: ["replace", "append", "prepend", "find_replace"] },
    content: { type: "string" },
    find: { type: "string" },
    replace: { type: "string" },
    createDirs: { type: "boolean" }
  }, ["path", "mode"]),
  tool("anyclaw_terminal", "Execute local shell command with timeout.", {
    command: { type: "string", minLength: 1 },
    cwd: { type: "string" },
    timeoutMs: { type: "integer", minimum: 1000, maximum: 120000 }
  }, ["command"]),
  tool("anyclaw_github_repo_info", "Get repository metadata.", {
    repo: { type: "string", minLength: 3 },
    token: { type: "string" }
  }, ["repo"]),
  tool("anyclaw_github_list_issues", "List repository issues.", {
    repo: { type: "string", minLength: 3 },
    state: { type: "string" },
    perPage: { type: "integer", minimum: 1, maximum: 100 },
    page: { type: "integer", minimum: 1, maximum: 1000 },
    token: { type: "string" }
  }, ["repo"]),
  tool("anyclaw_github_create_issue", "Create issue.", {
    repo: { type: "string", minLength: 3 },
    title: { type: "string", minLength: 1 },
    body: { type: "string" },
    labelsJson: { type: "string" },
    assigneesJson: { type: "string" },
    token: { type: "string" }
  }, ["repo", "title"]),
  tool("anyclaw_github_list_prs", "List pull requests.", {
    repo: { type: "string", minLength: 3 },
    state: { type: "string" },
    perPage: { type: "integer", minimum: 1, maximum: 100 },
    page: { type: "integer", minimum: 1, maximum: 1000 },
    token: { type: "string" }
  }, ["repo"]),
  tool("anyclaw_github_create_pr", "Create pull request.", {
    repo: { type: "string", minLength: 3 },
    title: { type: "string", minLength: 1 },
    head: { type: "string", minLength: 1 },
    base: { type: "string", minLength: 1 },
    body: { type: "string" },
    draft: { type: "boolean" },
    token: { type: "string" }
  }, ["repo", "title", "head", "base"]),
  tool("anyclaw_github_list_branches", "List repository branches.", {
    repo: { type: "string", minLength: 3 },
    perPage: { type: "integer", minimum: 1, maximum: 100 },
    page: { type: "integer", minimum: 1, maximum: 1000 },
    token: { type: "string" }
  }, ["repo"]),
  tool("anyclaw_github_compare_commits", "Compare two refs.", {
    repo: { type: "string", minLength: 3 },
    base: { type: "string", minLength: 1 },
    head: { type: "string", minLength: 1 },
    token: { type: "string" }
  }, ["repo", "base", "head"]),
  tool("anyclaw_github_get_commit", "Get commit metadata.", {
    repo: { type: "string", minLength: 3 },
    sha: { type: "string", minLength: 1 },
    token: { type: "string" }
  }, ["repo", "sha"]),
  tool("anyclaw_github_get_file", "Fetch repository file.", {
    repo: { type: "string", minLength: 3 },
    path: { type: "string", minLength: 1 },
    ref: { type: "string" },
    token: { type: "string" }
  }, ["repo", "path"]),
  tool("anyclaw_github_upsert_file", "Create/update repository file.", {
    repo: { type: "string", minLength: 3 },
    path: { type: "string", minLength: 1 },
    content: { type: "string" },
    message: { type: "string", minLength: 1 },
    branch: { type: "string" },
    sha: { type: "string" },
    token: { type: "string" }
  }, ["repo", "path", "content", "message"]),
  tool("anyclaw_github_rest_call", "Generic GitHub REST call.", {
    method: { type: "string", minLength: 1 },
    path: { type: "string", minLength: 1 },
    bodyJson: { type: "string" },
    queryJson: { type: "string" },
    token: { type: "string" }
  }, ["method", "path"]),

  tool("anyclaw_device_screen_info", "Get current screen size and density."),
  tool("anyclaw_device_screenshot", "Take screenshot.", {
    path: { type: "string" },
    outputPath: { type: "string" }
  }),
  tool("anyclaw_device_ui_coordinates", "Dump UI nodes and coordinates.", {
    maxNodes: { type: "integer", minimum: 20, maximum: 400 }
  }),
  tool("anyclaw_device_tap", "Tap coordinates.", {
    x: { type: "integer" },
    y: { type: "integer" }
  }, ["x", "y"]),
  tool("anyclaw_device_double_tap", "Double tap coordinates.", {
    x: { type: "integer" },
    y: { type: "integer" },
    intervalMs: { type: "integer", minimum: 50, maximum: 1000 }
  }, ["x", "y"]),
  tool("anyclaw_device_long_press", "Long press coordinates.", {
    x: { type: "integer" },
    y: { type: "integer" },
    durationMs: { type: "integer", minimum: 200, maximum: 5000 }
  }, ["x", "y"]),
  tool("anyclaw_device_swipe", "Swipe coordinates.", {
    start_x: { type: "integer" },
    start_y: { type: "integer" },
    end_x: { type: "integer" },
    end_y: { type: "integer" },
    durationMs: { type: "integer", minimum: 100, maximum: 8000 }
  }, ["start_x", "start_y", "end_x", "end_y"]),
  tool("anyclaw_device_input_text", "Input focused text.", {
    text: { type: "string" }
  }, ["text"]),
  tool("anyclaw_device_keyevent", "Send Android keyevent.", {
    keycode: { type: "string" }
  }, ["keycode"]),
  tool("anyclaw_device_statusbar", "Status bar control.", {
    action: { type: "string", enum: ["notifications", "quick_settings", "collapse"] }
  }, ["action"]),
  tool("anyclaw_device_open_app", "Launch package app.", {
    packageName: { type: "string" }
  }, ["packageName"]),
  tool("anyclaw_device_open_url", "Open URL by Android intent.", {
    url: { type: "string" },
    packageName: { type: "string" }
  }, ["url"])
];

let lineBuffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  lineBuffer += String(chunk || "");
  processLines();
});
process.stdin.on("end", () => process.exit(0));

function writeMessage(payload) {
  process.stdout.write(JSON.stringify(payload) + "\n");
}

function makeResponse(id, result) {
  return { jsonrpc: "2.0", id, result };
}

function makeError(id, code, message) {
  return { jsonrpc: "2.0", id, error: { code, message } };
}

function processLines() {
  while (true) {
    const idx = lineBuffer.indexOf("\n");
    if (idx === -1) return;
    const raw = lineBuffer.slice(0, idx);
    lineBuffer = lineBuffer.slice(idx + 1);
    const body = raw.trim();
    if (!body) continue;
    let msg;
    try {
      msg = JSON.parse(body);
    } catch {
      continue;
    }
    handleMessage(msg).catch((error) => {
      if (typeof msg?.id !== "undefined") {
        writeMessage(makeError(msg.id, -32603, String(error?.message || error)));
      }
    });
  }
}

async function handleMessage(message) {
  if (!message || typeof message !== "object") return;
  const id = message.id;
  const method = message.method;
  if (!method) return;

  if (method === "initialize") {
    writeMessage(makeResponse(id, {
      protocolVersion: message?.params?.protocolVersion || "2025-11-25",
      capabilities: { tools: {} },
      serverInfo: SERVER_INFO
    }));
    return;
  }
  if (method === "notifications/initialized") return;
  if (method === "ping") {
    writeMessage(makeResponse(id, {}));
    return;
  }
  if (method === "tools/list") {
    writeMessage(makeResponse(id, { tools: TOOL_DEFS }));
    return;
  }
  if (method === "tools/call") {
    const name = String(message?.params?.name || "");
    const args = asObject(message?.params?.arguments);
    try {
      const result = await callTool(name, args);
      const text = typeof result === "string" ? result : safeStringify(result);
      writeMessage(makeResponse(id, { content: [{ type: "text", text }], isError: false }));
    } catch (error) {
      writeMessage(makeResponse(id, {
        content: [{ type: "text", text: "Tool error: " + String(error?.message || error) }],
        isError: true
      }));
    }
    return;
  }

  if (typeof id !== "undefined") {
    writeMessage(makeError(id, -32601, "Method not found: " + method));
  }
}

function asObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
}

function toStringSafe(value, fallback = "") {
  return typeof value === "string" ? value : fallback;
}

function toInt(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : fallback;
}

function clampInt(value, min, max) {
  return Math.max(min, Math.min(max, Math.trunc(value)));
}

function toBool(value, fallback = false) {
  if (value === true || value === false) return value;
  const text = String(value || "").trim().toLowerCase();
  if (["true", "1", "yes", "y"].includes(text)) return true;
  if (["false", "0", "no", "n"].includes(text)) return false;
  return fallback;
}

function safeStringify(value) {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function parseJsonObject(value) {
  if (typeof value !== "string" || !value.trim()) return {};
  try {
    const parsed = JSON.parse(value);
    return asObject(parsed);
  } catch {
    return {};
  }
}

function parseJsonArray(value) {
  if (typeof value !== "string" || !value.trim()) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function shellQuote(value) {
  return "'" + String(value).replace(/'/g, "'\"'\"'") + "'";
}

function runSystemShellCommand(command, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const bin = process.env.ANYCLAW_SYSTEM_SHELL_BIN || "system-shell";
  const result = spawnSync(bin, [String(command)], {
    encoding: "utf8",
    timeout: clampInt(timeoutMs, 1000, 180000),
    maxBuffer: MAX_STDIO_BYTES
  });
  const stdout = String(result.stdout || "").trim();
  const stderr = String(result.stderr || "").trim();
  const status = typeof result.status === "number" ? result.status : 1;
  if (result.error) {
    return { ok: false, code: status, stdout, stderr, error: String(result.error.message || result.error) };
  }
  return { ok: status === 0, code: status, stdout, stderr };
}

function runSystemShellArgs(args, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const command = (Array.isArray(args) ? args : []).map((item) => shellQuote(String(item))).join(" ");
  return runSystemShellCommand(command, timeoutMs);
}

function runLocalShell(command, cwd, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const result = spawnSync("sh", ["-lc", String(command)], {
    cwd,
    encoding: "utf8",
    timeout: clampInt(timeoutMs, 1000, 180000),
    maxBuffer: MAX_STDIO_BYTES
  });
  const stdout = String(result.stdout || "").trim();
  const stderr = String(result.stderr || "").trim();
  const status = typeof result.status === "number" ? result.status : 1;
  if (result.error) {
    return { ok: false, code: status, stdout, stderr, error: String(result.error.message || result.error) };
  }
  return { ok: status === 0, code: status, stdout, stderr };
}

async function fetchWithTimeout(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), clampInt(timeoutMs, 1000, 180000));
  try {
    const response = await fetch(url, { ...options, signal: ctrl.signal });
    const text = await response.text();
    return {
      ok: response.ok,
      status: response.status,
      statusText: response.statusText,
      headers: Object.fromEntries(response.headers.entries()),
      url: response.url,
      text
    };
  } finally {
    clearTimeout(timer);
  }
}

async function fetchJson(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const result = await fetchWithTimeout(url, options, timeoutMs);
  let data = null;
  try {
    data = result.text ? JSON.parse(result.text) : {};
  } catch {
    data = null;
  }
  return { ...result, data };
}

function decodeHtml(input) {
  return String(input || "")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#x2F;/g, "/")
    .replace(/\s+/g, " ")
    .trim();
}

function stripHtml(input) {
  return decodeHtml(
    String(input || "")
      .replace(/<script[\s\S]*?<\/script>/gi, " ")
      .replace(/<style[\s\S]*?<\/style>/gi, " ")
      .replace(/<noscript[\s\S]*?<\/noscript>/gi, " ")
      .replace(/<[^>]+>/g, " ")
  );
}

function maybeDecodeURIComponent(text) {
  try {
    return decodeURIComponent(text);
  } catch {
    return text;
  }
}

function normalizeUrl(raw, baseUrl) {
  const value = String(raw || "").trim();
  if (!value) return "";
  try {
    const base = baseUrl ? new URL(baseUrl) : null;
    let candidate = value;
    if (candidate.startsWith("//")) candidate = "https:" + candidate;
    const resolved = base ? new URL(candidate, base) : new URL(candidate);

    if ((resolved.hostname.includes("google.") || resolved.hostname.includes("baidu.com")) && resolved.pathname === "/url") {
      const q = resolved.searchParams.get("q") || resolved.searchParams.get("url");
      if (q) {
        const decoded = maybeDecodeURIComponent(q);
        return normalizeUrl(decoded, null);
      }
    }

    const q = resolved.searchParams.get("uddg");
    if (q) {
      return normalizeUrl(maybeDecodeURIComponent(q), null);
    }

    if (resolved.protocol !== "http:" && resolved.protocol !== "https:") return "";

    resolved.hash = "";
    ["utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "from", "spm", "ref"].forEach((key) => {
      resolved.searchParams.delete(key);
    });

    return resolved.toString();
  } catch {
    return "";
  }
}

function parseAnchors(html, baseUrl, engine, limit) {
  const hits = [];
  const seen = new Set();
  const regex = /<a\b[^>]*href=("([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>([\s\S]*?)<\/a>/gi;
  let match;
  while ((match = regex.exec(html)) && hits.length < limit * 5) {
    const href = match[2] || match[3] || match[4] || "";
    const title = stripHtml(match[5] || "").slice(0, 200);
    if (!href || title.length < 2) continue;
    const normalized = normalizeUrl(href, baseUrl);
    if (!normalized) continue;

    let parsed;
    try {
      parsed = new URL(normalized);
    } catch {
      continue;
    }

    const host = parsed.hostname.toLowerCase();
    if (host.includes("google.") || host.includes("duckduckgo.com") || host.includes("bing.com") || host.includes("baidu.com") || host.includes("quark.sm.cn")) {
      continue;
    }
    if (host.includes("accounts.google.com") || host.includes("consent.google.com") || host.includes("passport.baidu.com")) {
      continue;
    }

    const key = parsed.toString();
    if (seen.has(key)) continue;
    seen.add(key);

    hits.push({
      title,
      url: key,
      snippet: "",
      source: engine,
      confidence: 0.72
    });
  }

  return hits.slice(0, limit);
}

function renderHitsText(hits) {
  if (!Array.isArray(hits) || hits.length === 0) return "No result";
  return hits.map((item, idx) => {
    const snippet = item.snippet ? " | " + String(item.snippet).slice(0, 220) : "";
    return `${idx + 1}. ${item.title} | ${item.url}${snippet}`;
  }).join("\n");
}

async function searchByEngine(engine, query, limit) {
  const builder = SEARCH_URLS[engine];
  if (!builder) {
    return {
      ok: false,
      engine,
      query,
      requestUrl: "",
      count: 0,
      results: [],
      text: "",
      error: "unsupported_engine"
    };
  }
  const requestUrl = builder(query);
  const startedAt = Date.now();

  const response = await fetchWithTimeout(requestUrl, {
    method: "GET",
    headers: {
      "User-Agent": "AnyClawSearchSuite/1.4",
      "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    }
  }, 30000);

  if (!response.ok) {
    return {
      ok: false,
      engine,
      query,
      requestUrl,
      status: response.status,
      count: 0,
      results: [],
      text: "",
      tookMs: Date.now() - startedAt,
      error: `http_${response.status}`
    };
  }

  let hits = parseAnchors(response.text, requestUrl, engine, limit);
  if (hits.length === 0 && engine === "duckduckgo") {
    const ddg = await fetchJson(
      "https://api.duckduckgo.com/?q=" + encodeURIComponent(query) + "&format=json&no_html=1&no_redirect=1&skip_disambig=0",
      {
        method: "GET",
        headers: {
          "User-Agent": "AnyClawSearchSuite/1.4",
          "Accept": "application/json,text/plain,*/*"
        }
      },
      25000
    );
    if (ddg.ok && ddg.data && typeof ddg.data === "object") {
      const related = [];
      flattenDuckRelated(ddg.data.RelatedTopics, related);
      hits = related.slice(0, limit).map((row) => ({
        title: String(row.text || "").slice(0, 180),
        url: String(row.url || ""),
        snippet: "",
        source: "duckduckgo",
        confidence: 0.74
      })).filter((row) => row.title && row.url);
    }
  }

  return {
    ok: true,
    engine,
    query,
    requestUrl,
    status: response.status,
    count: hits.length,
    results: hits,
    text: renderHitsText(hits),
    tookMs: Date.now() - startedAt,
    finalQualityLabel: hits.length >= 3 ? "good" : hits.length > 0 ? "mixed" : "noisy"
  };
}

function flattenDuckRelated(related, out) {
  if (!Array.isArray(related)) return;
  for (const item of related) {
    if (!item || typeof item !== "object") continue;
    if (Array.isArray(item.Topics)) {
      flattenDuckRelated(item.Topics, out);
    } else if (item.Text) {
      out.push({ text: String(item.Text), url: String(item.FirstURL || "") });
    }
  }
}

async function runMultiSearch(query, limit, engines) {
  const selected = Array.isArray(engines) && engines.length > 0
    ? engines.filter((engine) => SEARCH_ENGINE_KEYS.includes(engine))
    : SEARCH_ENGINE_KEYS.slice();

  const runs = await Promise.all(selected.map((engine) => searchByEngine(engine, query, Math.max(limit, 4))));
  const merged = [];
  const seen = new Set();
  for (const run of runs) {
    if (!run || !run.ok || !Array.isArray(run.results)) continue;
    for (const item of run.results) {
      const key = String(item.url || "");
      if (!key || seen.has(key)) continue;
      seen.add(key);
      merged.push(item);
      if (merged.length >= limit) break;
    }
    if (merged.length >= limit) break;
  }

  return {
    ok: true,
    engine: "multi",
    query,
    count: merged.length,
    results: merged,
    text: renderHitsText(merged),
    providers: runs.map((row) => ({
      engine: row.engine,
      ok: row.ok,
      count: row.count,
      error: row.error
    })),
    finalQualityLabel: merged.length >= 3 ? "good" : merged.length > 0 ? "mixed" : "noisy"
  };
}

async function callWebBridge(method, params) {
  const response = await fetchJson(WEB_BRIDGE_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "User-Agent": "AnyClawSearchSuite/1.4"
    },
    body: JSON.stringify({ method, params: params || {} })
  }, DEFAULT_TIMEOUT_MS);

  if (!response.ok) {
    return {
      ok: false,
      method,
      error: "web_bridge_http_" + String(response.status),
      detail: String(response.text || "").slice(0, 800)
    };
  }

  if (!response.data || typeof response.data !== "object") {
    return {
      ok: false,
      method,
      error: "web_bridge_invalid_json",
      detail: String(response.text || "").slice(0, 800)
    };
  }

  return response.data;
}

function resolveWorkspacePath(target) {
  const raw = String(target || "").trim();
  if (!raw) throw new Error("path is required");
  const candidate = path.isAbsolute(raw) ? path.resolve(raw) : path.resolve(WORKSPACE_ROOT, raw);
  if (!(candidate === WORKSPACE_ROOT || candidate.startsWith(WORKSPACE_ROOT + path.sep))) {
    throw new Error("path is outside workspaceRoot");
  }
  return candidate;
}

function normalizeHttpMethod(raw, fallback = "GET") {
  const upper = String(raw || fallback).trim().toUpperCase();
  if (["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"].includes(upper)) return upper;
  return fallback;
}

function readMcpConfigEnvValue(keys) {
  if (!MCP_CONFIG_PATH || !Array.isArray(keys) || keys.length === 0) return null;
  try {
    const root = asObject(JSON.parse(fs.readFileSync(MCP_CONFIG_PATH, "utf8")));
    const mcpServers = asObject(root.mcpServers);
    const toolbox = asObject(mcpServers.anyclaw_toolbox);
    const env = asObject(toolbox.env);
    for (const key of keys) {
      const value = String(env[key] || "").trim();
      if (value) return value;
    }
    return null;
  } catch {
    return null;
  }
}

function resolveGithubToken(args) {
  const fromArgs = toStringSafe(args.token, "").trim();
  if (fromArgs) return fromArgs;
  const fromEnv = String(
    process.env.ANYCLAW_GITHUB_TOKEN ||
      process.env.GITHUB_TOKEN ||
      process.env.GH_TOKEN ||
      "",
  ).trim();
  if (fromEnv) return fromEnv;
  const fromConfig = readMcpConfigEnvValue(["ANYCLAW_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN"]);
  if (fromConfig) return fromConfig;
  return fromEnv || null;
}

function parseRepoInput(args) {
  const repoText = toStringSafe(args.repo, "").trim();
  if (repoText.includes("/")) {
    const idx = repoText.indexOf("/");
    const owner = repoText.slice(0, idx).trim();
    const repo = repoText.slice(idx + 1).trim();
    if (!owner || !repo) throw new Error("repo must be in owner/repo format");
    return { owner, repo, full: `${owner}/${repo}` };
  }

  const owner = toStringSafe(args.owner, "").trim();
  const repo = toStringSafe(args.repo, "").trim();
  if (!owner || !repo) throw new Error("repo must be in owner/repo format");
  return { owner, repo, full: `${owner}/${repo}` };
}

function buildQueryString(obj) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(asObject(obj))) {
    if (value === undefined || value === null) continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

async function githubRequest(method, endpoint, token, body) {
  const url = endpoint.startsWith("http://") || endpoint.startsWith("https://")
    ? endpoint
    : `${GITHUB_API_BASE}${endpoint.startsWith("/") ? "" : "/"}${endpoint}`;

  const response = await fetchJson(url, {
    method,
    headers: {
      "Accept": "application/vnd.github+json",
      "Content-Type": "application/json",
      "Authorization": `Bearer ${token}`,
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "AnyClawGithubSuite/1.0"
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  }, 60000);

  return {
    ok: response.ok,
    status: response.status,
    url,
    data: response.data !== null ? response.data : response.text
  };
}

function parseUiNodes(xml, maxNodes) {
  const rows = [];
  const regex = /<node\b([^>]*?)\/>/g;
  let match;
  while ((match = regex.exec(xml)) && rows.length < maxNodes) {
    const attrs = match[1] || "";
    const attr = (name) => {
      const m = attrs.match(new RegExp(`${name}="([^"]*)"`, "i"));
      return m ? m[1] : "";
    };
    const boundsRaw = attr("bounds");
    const b = boundsRaw.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (!b) continue;

    const left = Number(b[1]);
    const top = Number(b[2]);
    const right = Number(b[3]);
    const bottom = Number(b[4]);
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

function githubEnvelope(response, action) {
  return {
    ok: response.ok,
    action,
    status: response.status,
    url: response.url,
    data: response.data
  };
}

function requireQuery(args) {
  const query = toStringSafe(args.query, "").trim();
  if (!query) throw new Error("query is required");
  return query;
}

function readLimit(args) {
  const direct = toInt(args.limit, NaN);
  const legacy = toInt(args.maxResults, NaN);
  const value = Number.isFinite(direct) ? direct : (Number.isFinite(legacy) ? legacy : DEFAULT_SEARCH_LIMIT);
  return clampInt(value, 1, 10);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isTimeoutLikeError(error) {
  const text = String(error?.message || error || "").toLowerCase();
  return text.includes("abort") || text.includes("timeout") || text.includes("timed out");
}

function parseMcpSsePayload(rawText) {
  const lines = String(rawText || "").split("\n");
  let lastData = "";
  for (const raw of lines) {
    const line = raw.trim();
    if (!line || !line.startsWith("data:")) continue;
    const chunk = line.slice(5).trim();
    if (chunk) lastData = chunk;
  }
  if (!lastData) return {};
  try {
    return asObject(JSON.parse(lastData));
  } catch {
    return {};
  }
}

function resolveTavilyApiKey(args) {
  return toStringSafe(args.apiKey, "").trim() ||
    toStringSafe(args.tavilyApiKey, "").trim() ||
    String(
      process.env.TAVILY_API_KEY ||
        process.env.ANYCLAW_TAVILY_API_KEY ||
        readMcpConfigEnvValue(["ANYCLAW_TAVILY_API_KEY", "TAVILY_API_KEY"]) ||
        "",
    ).trim();
}

function resolveExaApiKey(args) {
  return toStringSafe(args.apiKey, "").trim() ||
    String(
      process.env.EXA_API_KEY ||
        process.env.ANYCLAW_EXA_API_KEY ||
        readMcpConfigEnvValue(["ANYCLAW_EXA_API_KEY", "EXA_API_KEY"]) ||
        "",
    ).trim();
}

function readExaLimit(args) {
  const maxResults = toInt(args.maxResults, NaN);
  const numResults = toInt(args.numResults, NaN);
  const value = Number.isFinite(maxResults) ? maxResults : (Number.isFinite(numResults) ? numResults : DEFAULT_SEARCH_LIMIT);
  return clampInt(value, 1, 10);
}

async function runTavilySearch(args, extra = {}) {
  const query = requireQuery(args);
  const maxResults = clampInt(toInt(args.maxResults, DEFAULT_SEARCH_LIMIT), 1, 10);
  const depthRaw = toStringSafe(args.searchDepth, "advanced").trim().toLowerCase();
  const searchDepth = depthRaw === "basic" ? "basic" : "advanced";
  const apiKey = resolveTavilyApiKey(args);
  if (!apiKey) {
    return {
      ok: false,
      engine: "tavily",
      error: "missing_tavily_api_key",
      message: "Provide args.apiKey/tavilyApiKey or set TAVILY_API_KEY/ANYCLAW_TAVILY_API_KEY",
      ...extra
    };
  }
  const response = await fetchJson(TAVILY_BASE_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "User-Agent": "AnyClawSearchSuite/1.4"
    },
    body: JSON.stringify({
      api_key: apiKey,
      query,
      search_depth: searchDepth,
      max_results: maxResults,
      include_answer: true,
      include_images: false,
      include_raw_content: false
    })
  }, 45000);

  if (!response.ok) {
    return {
      ok: false,
      engine: "tavily",
      query,
      requestUrl: TAVILY_BASE_URL,
      status: response.status,
      error: `tavily_http_${response.status}`,
      detail: String(response.text || "").slice(0, 800),
      ...extra
    };
  }

  const rows = Array.isArray(response.data?.results) ? response.data.results : [];
  const results = [];
  for (const row of rows) {
    const obj = asObject(row);
    const title = toStringSafe(obj.title, "").trim();
    const url = normalizeUrl(toStringSafe(obj.url, "").trim(), null);
    const snippet = toStringSafe(obj.content, "").trim();
    const score = Number(obj.score);
    if (!title || !url) continue;
    results.push({
      title,
      url,
      snippet,
      source: "tavily",
      confidence: Number.isFinite(score) ? Math.max(0.2, Math.min(0.99, score)) : 0.8
    });
    if (results.length >= maxResults) break;
  }

  return {
    ok: true,
    engine: "tavily",
    query,
    requestUrl: TAVILY_BASE_URL,
    count: results.length,
    results,
    answer: toStringSafe(response.data?.answer, ""),
    text: renderHitsText(results),
    finalQualityLabel: results.length >= 3 ? "good" : results.length > 0 ? "mixed" : "noisy",
    ...extra
  };
}

async function runExaSearch(args) {
  const query = requireQuery(args);
  const maxResults = readExaLimit(args);
  const timeoutMs = clampInt(toInt(args.timeoutMs, 20000), 3000, 60000);
  const apiKey = resolveExaApiKey(args);
  const headers = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
    "User-Agent": "AnyClawSearchSuite/1.4"
  };
  if (apiKey) {
    headers["x-api-key"] = apiKey;
  }
  const rpcPayload = {
    jsonrpc: "2.0",
    id: Date.now(),
    method: "tools/call",
    params: {
      name: "web_search_exa",
      arguments: {
        query,
        numResults: maxResults
      }
    }
  };
  const response = await fetchWithTimeout(EXA_MCP_URL, {
    method: "POST",
    headers,
    body: JSON.stringify(rpcPayload)
  }, timeoutMs);
  if (!response.ok) {
    throw new Error(`exa_http_${response.status}`);
  }
  const payload = parseMcpSsePayload(response.text);
  const error = asObject(payload.error);
  if (error.message) {
    throw new Error(`exa_mcp_error:${String(error.message)}`);
  }
  const result = asObject(payload.result);
  const content = Array.isArray(result.content) ? result.content : [];
  const text = content
    .filter((row) => asObject(row).type === "text")
    .map((row) => toStringSafe(asObject(row).text, ""))
    .join("\n")
    .trim();
  const titleCount = (text.match(/^Title:\s+/gm) || []).length;
  return {
    ok: true,
    engine: "exa",
    query,
    requestUrl: EXA_MCP_URL,
    count: titleCount,
    text: text || "No result",
    raw: text || undefined,
    usedApiKey: apiKey ? 1 : 0,
    fallbackUsed: false,
    finalQualityLabel: titleCount >= 3 ? "good" : titleCount > 0 ? "mixed" : "noisy"
  };
}

async function callTool(name, args) {
  switch (name) {
    case "anyclaw_device_exec": {
      const command = toStringSafe(args.command, "").trim();
      if (!command) throw new Error("command is required");
      const timeoutMs = clampInt(toInt(args.timeoutMs, DEFAULT_TIMEOUT_MS), 1000, 120000);
      const run = runSystemShellCommand(command, timeoutMs);
      if (!run.ok) throw new Error(run.stderr || run.stdout || run.error || `system-shell exit ${run.code}`);
      return { ok: true, tool: "anyclaw_device_exec", command, output: run.stdout || "(no output)" };
    }
    case "anyclaw_device_uiautomator_dump": {
      const outputPath = toStringSafe(args.outputPath, "").trim() || `/sdcard/Download/AnyClawShots/ui_dump_${Date.now()}.xml`;
      const outputDir = outputPath.includes("/") ? outputPath.slice(0, outputPath.lastIndexOf("/")) : "/sdcard/Download/AnyClawShots";
      runSystemShellArgs(["mkdir", "-p", outputDir], 40000);
      const dump = runSystemShellArgs(["uiautomator", "dump", outputPath], 50000);
      const cat = runSystemShellArgs(["cat", outputPath], 50000);
      return {
        ok: dump.ok && cat.ok,
        tool: "anyclaw_device_uiautomator_dump",
        path: outputPath,
        xml: String(cat.stdout || "").slice(0, 12000),
        dumpOutput: dump.stdout || dump.stderr || undefined,
        errorOutput: dump.ok && cat.ok ? undefined : (dump.stderr || cat.stderr || undefined)
      };
    }
    case "anyclaw_search_web":
      return await searchByEngine("duckduckgo", requireQuery(args), clampInt(toInt(args.maxResults, DEFAULT_SEARCH_LIMIT), 1, 10));
    case "anyclaw_search_wikipedia": {
      const query = requireQuery(args);
      const limit = clampInt(toInt(args.maxResults, DEFAULT_SEARCH_LIMIT), 1, 10);
      const url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + encodeURIComponent(query) + "&format=json&srlimit=" + limit;
      const response = await fetchJson(url, {
        method: "GET",
        headers: {
          "User-Agent": "AnyClawClaudeToolbox/2.0",
          "Accept": "application/json,text/plain,*/*"
        }
      }, 25000);
      const items = Array.isArray(response.data?.query?.search) ? response.data.query.search : [];
      const rows = items.map((item, idx) => ({ rank: idx + 1, title: String(item?.title || ""), snippet: stripHtml(String(item?.snippet || "")) }));
      return { ok: response.ok, query, count: rows.length, results: rows, text: rows.length ? rows.map((row) => `${row.rank}. ${row.title} | ${row.snippet}`).join("\n") : "No result" };
    }
    case "anyclaw_fetch_url": {
      const url = toStringSafe(args.url, "").trim();
      if (!/^https?:\/\//i.test(url)) throw new Error("url must start with http:// or https://");
      const maxChars = clampInt(toInt(args.maxChars, 12000), 200, 80000);
      const response = await fetchWithTimeout(url, { method: "GET", headers: { "User-Agent": "AnyClawClaudeToolbox/2.0", "Accept": "text/plain,text/html,*/*" } }, 30000);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return String(response.text || "").replace(/\r/g, "").replace(/\t/g, " ").replace(/\x00/g, "").slice(0, maxChars);
    }
    case "anyclaw_github_repo": {
      const owner = toStringSafe(args.owner, "").trim();
      const repo = toStringSafe(args.repo, "").trim();
      if (!owner || !repo) throw new Error("owner and repo are required");
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const response = await githubRequest("GET", `/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}`, token);
      return githubEnvelope(response, "repo_info");
    }
    case "anyclaw_github_search_repositories": {
      const query = requireQuery(args);
      const perPage = clampInt(toInt(args.perPage, 10), 1, 100);
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const response = await githubRequest("GET", `/search/repositories?q=${encodeURIComponent(query)}&per_page=${perPage}`, token);
      return githubEnvelope(response, "search_repositories");
    }
    case "anyclaw_github_search_code": {
      const query = requireQuery(args);
      const perPage = clampInt(toInt(args.perPage, 10), 1, 100);
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const response = await githubRequest("GET", `/search/code?q=${encodeURIComponent(query)}&per_page=${perPage}`, token);
      return githubEnvelope(response, "search_code");
    }

    case "anyclaw_google_search":
      return await searchByEngine("google", requireQuery(args), readLimit(args));
    case "anyclaw_google_scholar_search":
      return await searchByEngine("google_scholar", requireQuery(args), readLimit(args));
    case "anyclaw_duckduckgo_search":
      return await searchByEngine("duckduckgo", requireQuery(args), readLimit(args));
    case "anyclaw_bing_search":
      return await searchByEngine("bing", requireQuery(args), readLimit(args));
    case "anyclaw_baidu_search":
      return await searchByEngine("baidu", requireQuery(args), readLimit(args));
    case "anyclaw_quark_search":
      return await searchByEngine("quark", requireQuery(args), readLimit(args));
    case "anyclaw_multi_search": {
      const engines = parseJsonArray(toStringSafe(args.enginesJson, "")).map((item) => String(item));
      return await runMultiSearch(requireQuery(args), readLimit(args), engines);
    }
    case "anyclaw_web_visit": {
      const url = toStringSafe(args.url, "").trim();
      if (!/^https?:\/\//i.test(url)) throw new Error("url must start with http:// or https://");
      const maxChars = clampInt(toInt(args.maxChars, 12000), 1000, 80000);
      const response = await fetchWithTimeout(url, { method: "GET", headers: { "User-Agent": "AnyClawSearchSuite/1.4", "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" } }, 30000);
      const titleMatch = String(response.text || "").match(/<title[^>]*>([\s\S]*?)<\/title>/i);
      const title = titleMatch ? stripHtml(titleMatch[1]) : "";
      return { ok: response.ok, status: response.status, engine: "web_visit", url, title, chars: String(response.text || "").length, text: stripHtml(response.text).slice(0, maxChars) };
    }
    case "anyclaw_http_request": {
      const url = toStringSafe(args.url, "").trim();
      if (!/^https?:\/\//i.test(url)) throw new Error("url must start with http:// or https://");
      const method = normalizeHttpMethod(args.method, "GET");
      const headers = parseJsonObject(toStringSafe(args.headersJson, ""));
      if (!headers["User-Agent"]) headers["User-Agent"] = "AnyClawSearchSuite/1.4";
      const maxChars = clampInt(toInt(args.maxChars, 12000), 1000, 80000);
      const body = toStringSafe(args.body, "");
      const response = await fetchWithTimeout(url, { method, headers, body: method === "GET" || method === "HEAD" ? undefined : body }, 45000);
      return { ok: response.ok, status: response.status, statusText: response.statusText, method, url, text: String(response.text || "").slice(0, maxChars) };
    }
    case "anyclaw_multipart_request": {
      const url = toStringSafe(args.url, "").trim();
      if (!/^https?:\/\//i.test(url)) throw new Error("url must start with http:// or https://");
      const method = normalizeHttpMethod(args.method, "POST");
      const maxChars = clampInt(toInt(args.maxChars, 12000), 1000, 80000);
      const headers = parseJsonObject(toStringSafe(args.headersJson, ""));
      delete headers["Content-Type"];
      delete headers["content-type"];
      if (!headers["User-Agent"]) headers["User-Agent"] = "AnyClawSearchSuite/1.4";

      const form = new FormData();
      const formDataObj = parseJsonObject(toStringSafe(args.formDataJson, ""));
      Object.entries(formDataObj).forEach(([k, v]) => {
        if (v !== undefined && v !== null) form.append(k, String(v));
      });

      const uploadedFiles = [];
      const rejectedFiles = [];
      const files = parseJsonArray(toStringSafe(args.filesJson, ""));
      for (const row of files) {
        const obj = asObject(row);
        const filePath = toStringSafe(obj.path, "").trim();
        if (!filePath) {
          rejectedFiles.push({ path: "", error: "missing_path" });
          continue;
        }
        if (!fs.existsSync(filePath)) {
          rejectedFiles.push({ path: filePath, error: "file_not_found" });
          continue;
        }
        const stat = fs.statSync(filePath);
        if (!stat.isFile()) {
          rejectedFiles.push({ path: filePath, error: "not_a_file" });
          continue;
        }
        const field = toStringSafe(obj.field, "").trim() || "file";
        const filename = toStringSafe(obj.filename, "").trim() || path.basename(filePath);
        const contentType = toStringSafe(obj.contentType, "").trim() || "application/octet-stream";
        const buf = fs.readFileSync(filePath);
        const blob = new Blob([buf], { type: contentType });
        form.append(field, blob, filename);
        uploadedFiles.push({ field, path: filePath, filename });
      }

      const response = await fetchWithTimeout(url, { method, headers, body: form }, 60000);
      return {
        ok: response.ok,
        status: response.status,
        statusText: response.statusText,
        method,
        url,
        uploadedFiles,
        rejectedFiles,
        text: String(response.text || "").slice(0, maxChars)
      };
    }
    case "anyclaw_open_in_system_browser": {
      const url = toStringSafe(args.url, "").trim();
      if (!/^https?:\/\//i.test(url)) throw new Error("url must start with http:// or https://");
      const packageName = toStringSafe(args.packageName, "").trim();
      const cmd = ["am", "start", "-a", "android.intent.action.VIEW", "-d", url];
      if (packageName) cmd.push("-p", packageName);
      const run = runSystemShellArgs(cmd, 30000);
      return {
        ok: run.ok,
        tool: "open_in_system_browser",
        url,
        packageName: packageName || undefined,
        exitCode: run.code,
        output: run.stdout || undefined,
        errorOutput: run.ok ? undefined : (run.stderr || undefined),
        error: run.error
      };
    }
    case "anyclaw_tavily_search":
      return await runTavilySearch(args);
    case "anyclaw_exa_search": {
      try {
        return await runExaSearch(args);
      } catch (error) {
        if (isTimeoutLikeError(error)) {
          return await runTavilySearch(args, {
            fallbackUsed: true,
            fallbackFrom: "exa_timeout",
            fallbackReason: String(error?.message || error)
          });
        }
        return {
          ok: false,
          engine: "exa",
          query: toStringSafe(args.query, "").trim(),
          requestUrl: EXA_MCP_URL,
          error: "exa_search_failed",
          detail: String(error?.message || error).slice(0, 500)
        };
      }
    }

    case "start_web": {
      const headers = parseJsonObject(toStringSafe(args.headersJson, ""));
      return await callWebBridge("start_web", {
        url: toStringSafe(args.url, ""),
        user_agent: toStringSafe(args.user_agent, ""),
        session_name: toStringSafe(args.session_name, ""),
        headers
      });
    }
    case "stop_web":
      return await callWebBridge("stop_web", {
        session_id: toStringSafe(args.session_id, ""),
        close_all: toBool(args.close_all, false)
      });
    case "web_navigate": {
      const headers = parseJsonObject(toStringSafe(args.headersJson, ""));
      return await callWebBridge("web_navigate", {
        url: toStringSafe(args.url, ""),
        session_id: toStringSafe(args.session_id, ""),
        headers
      });
    }
    case "web_eval":
      return await callWebBridge("web_eval", {
        script: toStringSafe(args.script, ""),
        session_id: toStringSafe(args.session_id, ""),
        timeout_ms: clampInt(toInt(args.timeout_ms, 10000), 1000, 60000)
      });
    case "web_click":
      return await callWebBridge("web_click", {
        ref: toStringSafe(args.ref, ""),
        session_id: toStringSafe(args.session_id, "")
      });
    case "web_fill":
      return await callWebBridge("web_fill", {
        selector: toStringSafe(args.selector, ""),
        value: toStringSafe(args.value, ""),
        session_id: toStringSafe(args.session_id, "")
      });
    case "web_file_upload": {
      const paths = Array.isArray(args.paths)
        ? args.paths.map((item) => String(item))
        : parseJsonArray(toStringSafe(args.paths, "")).map((item) => String(item));
      return await callWebBridge("web_file_upload", {
        session_id: toStringSafe(args.session_id, ""),
        paths
      });
    }
    case "web_wait_for":
      return await callWebBridge("web_wait_for", {
        session_id: toStringSafe(args.session_id, ""),
        selector: toStringSafe(args.selector, ""),
        timeout_ms: clampInt(toInt(args.timeout_ms, 10000), 1000, 60000)
      });
    case "web_snapshot":
      return await callWebBridge("web_snapshot", {
        session_id: toStringSafe(args.session_id, ""),
        include_links: toBool(args.include_links, true),
        include_images: toBool(args.include_images, false),
        max_chars: clampInt(toInt(args.max_chars, 16000), 1000, 80000)
      });
    case "web_content":
      return await callWebBridge("web_content", {
        session_id: toStringSafe(args.session_id, ""),
        include_links: toBool(args.include_links, true),
        include_images: toBool(args.include_images, false),
        max_chars: clampInt(toInt(args.max_chars, 16000), 1000, 80000)
      });

    case "anyclaw_apply_file": {
      const target = resolveWorkspacePath(toStringSafe(args.path, ""));
      const mode = toStringSafe(args.mode, "").trim();
      const content = toStringSafe(args.content, "");
      const find = toStringSafe(args.find, "");
      const replace = toStringSafe(args.replace, "");
      const createDirs = toBool(args.createDirs, false);

      const exists = fs.existsSync(target);
      if (!exists && mode === "find_replace") {
        return { ok: false, error: "target_file_not_found", path: target };
      }
      if (!exists && !createDirs) {
        return { ok: false, error: "target_file_not_found_enable_createDirs", path: target };
      }
      if (createDirs) {
        fs.mkdirSync(path.dirname(target), { recursive: true });
      }

      const before = exists ? fs.readFileSync(target, "utf8") : "";
      let after = before;

      if (mode === "replace") after = content;
      else if (mode === "append") after = before + content;
      else if (mode === "prepend") after = content + before;
      else if (mode === "find_replace") {
        if (!find) return { ok: false, error: "find_is_required_for_find_replace", path: target };
        if (!before.includes(find)) return { ok: false, error: "find_text_not_found", path: target };
        after = before.replace(find, replace);
      } else {
        return { ok: false, error: "unsupported_mode", mode };
      }

      fs.writeFileSync(target, after, "utf8");
      return {
        ok: true,
        path: target,
        mode,
        changed: before !== after,
        bytesBefore: Buffer.byteLength(before, "utf8"),
        bytesAfter: Buffer.byteLength(after, "utf8")
      };
    }
    case "anyclaw_terminal": {
      const command = toStringSafe(args.command, "").trim();
      if (!command) throw new Error("command is required");
      const cwdRaw = toStringSafe(args.cwd, "").trim();
      const cwd = cwdRaw ? resolveWorkspacePath(cwdRaw) : WORKSPACE_ROOT;
      const timeoutMs = clampInt(toInt(args.timeoutMs, 30000), 1000, 120000);
      const run = runLocalShell(command, cwd, timeoutMs);
      return {
        ok: run.ok,
        exitCode: run.code,
        stdout: String(run.stdout || "").slice(0, 12000),
        stderr: String(run.stderr || "").slice(0, 12000),
        error: run.error,
        cwd
      };
    }
    case "anyclaw_github_repo_info": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const response = await githubRequest("GET", `/repos/${owner}/${repo}`, token);
      return githubEnvelope(response, "repo_info");
    }
    case "anyclaw_github_list_issues": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const state = toStringSafe(args.state, "open").trim() || "open";
      const perPage = clampInt(toInt(args.perPage, 30), 1, 100);
      const page = clampInt(toInt(args.page, 1), 1, 1000);
      const response = await githubRequest("GET", `/repos/${owner}/${repo}/issues?state=${encodeURIComponent(state)}&per_page=${perPage}&page=${page}`, token);
      return githubEnvelope(response, "list_issues");
    }
    case "anyclaw_github_create_issue": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const title = toStringSafe(args.title, "").trim();
      if (!title) throw new Error("title is required");
      const payload = {
        title,
        body: toStringSafe(args.body, ""),
        labels: parseJsonArray(toStringSafe(args.labelsJson, "")).map((v) => String(v)),
        assignees: parseJsonArray(toStringSafe(args.assigneesJson, "")).map((v) => String(v))
      };
      const response = await githubRequest("POST", `/repos/${owner}/${repo}/issues`, token, payload);
      return githubEnvelope(response, "create_issue");
    }
    case "anyclaw_github_list_prs": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const state = toStringSafe(args.state, "open").trim() || "open";
      const perPage = clampInt(toInt(args.perPage, 30), 1, 100);
      const page = clampInt(toInt(args.page, 1), 1, 1000);
      const response = await githubRequest("GET", `/repos/${owner}/${repo}/pulls?state=${encodeURIComponent(state)}&per_page=${perPage}&page=${page}`, token);
      return githubEnvelope(response, "list_prs");
    }
    case "anyclaw_github_create_pr": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const title = toStringSafe(args.title, "").trim();
      const head = toStringSafe(args.head, "").trim();
      const base = toStringSafe(args.base, "").trim();
      if (!title || !head || !base) throw new Error("title/head/base are required");
      const payload = {
        title,
        head,
        base,
        body: toStringSafe(args.body, ""),
        draft: toBool(args.draft, false)
      };
      const response = await githubRequest("POST", `/repos/${owner}/${repo}/pulls`, token, payload);
      return githubEnvelope(response, "create_pr");
    }
    case "anyclaw_github_list_branches": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const perPage = clampInt(toInt(args.perPage, 30), 1, 100);
      const page = clampInt(toInt(args.page, 1), 1, 1000);
      const response = await githubRequest("GET", `/repos/${owner}/${repo}/branches?per_page=${perPage}&page=${page}`, token);
      return githubEnvelope(response, "list_branches");
    }
    case "anyclaw_github_compare_commits": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const base = toStringSafe(args.base, "").trim();
      const head = toStringSafe(args.head, "").trim();
      if (!base || !head) throw new Error("base/head are required");
      const response = await githubRequest("GET", `/repos/${owner}/${repo}/compare/${encodeURIComponent(base)}...${encodeURIComponent(head)}`, token);
      return githubEnvelope(response, "compare_commits");
    }
    case "anyclaw_github_get_commit": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const sha = toStringSafe(args.sha, "").trim();
      if (!sha) throw new Error("sha is required");
      const response = await githubRequest("GET", `/repos/${owner}/${repo}/commits/${encodeURIComponent(sha)}`, token);
      return githubEnvelope(response, "get_commit");
    }
    case "anyclaw_github_get_file": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const filePath = toStringSafe(args.path, "").replace(/^\/+/, "").trim();
      if (!filePath) throw new Error("path is required");
      const ref = toStringSafe(args.ref, "").trim();
      const query = ref ? `?ref=${encodeURIComponent(ref)}` : "";
      const response = await githubRequest("GET", `/repos/${owner}/${repo}/contents/${filePath}${query}`, token);
      const payload = asObject(response.data);
      const encoding = toStringSafe(payload.encoding, "");
      const content = toStringSafe(payload.content, "");
      let decodedContent;
      if (encoding === "base64" && content) {
        try {
          decodedContent = Buffer.from(content.replace(/\n/g, ""), "base64").toString("utf8");
        } catch {
          decodedContent = undefined;
        }
      }
      return {
        ...githubEnvelope(response, "get_file"),
        decodedContent
      };
    }
    case "anyclaw_github_upsert_file": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const { owner, repo } = parseRepoInput(args);
      const filePath = toStringSafe(args.path, "").replace(/^\/+/, "").trim();
      const content = toStringSafe(args.content, "");
      const message = toStringSafe(args.message, "").trim();
      if (!filePath || !message) throw new Error("path/message are required");
      const payload = {
        message,
        content: Buffer.from(content, "utf8").toString("base64"),
        branch: toStringSafe(args.branch, "").trim() || undefined,
        sha: toStringSafe(args.sha, "").trim() || undefined
      };
      const response = await githubRequest("PUT", `/repos/${owner}/${repo}/contents/${filePath}`, token, payload);
      return githubEnvelope(response, "upsert_file");
    }
    case "anyclaw_github_rest_call": {
      const token = resolveGithubToken(args);
      if (!token) return { ok: false, error: "missing_github_token" };
      const method = normalizeHttpMethod(args.method, "GET");
      const apiPath = toStringSafe(args.path, "").trim();
      if (!apiPath) throw new Error("path is required");
      const query = parseJsonObject(toStringSafe(args.queryJson, ""));
      const body = parseJsonObject(toStringSafe(args.bodyJson, ""));
      const endpoint = apiPath + buildQueryString(query);
      const response = await githubRequest(method, endpoint, token, Object.keys(body).length ? body : undefined);
      return githubEnvelope(response, "rest_call");
    }

    case "anyclaw_device_screen_info": {
      const size = runSystemShellArgs(["wm", "size"], 30000);
      const density = runSystemShellArgs(["wm", "density"], 30000);
      const sizeText = [size.stdout, size.stderr].filter(Boolean).join("\n");
      const densityText = [density.stdout, density.stderr].filter(Boolean).join("\n");
      const sizeMatch = sizeText.match(/Physical size:\s*(\d+)\s*x\s*(\d+)/i) || sizeText.match(/(\d+)\s*x\s*(\d+)/);
      const densityMatch = densityText.match(/Physical density:\s*(\d+)/i) || densityText.match(/(\d+)/);
      const width = sizeMatch ? Number(sizeMatch[1]) : null;
      const height = sizeMatch ? Number(sizeMatch[2]) : null;
      const densityValue = densityMatch ? Number(densityMatch[1]) : null;
      return {
        ok: size.ok || density.ok,
        tool: "screen_info",
        width,
        height,
        density: densityValue,
        coordinateHint: width && height ? `x:0..${Math.max(width - 1, 0)}, y:0..${Math.max(height - 1, 0)}` : null,
        sizeRaw: sizeText || undefined,
        densityRaw: densityText || undefined
      };
    }
    case "anyclaw_device_screenshot": {
      const custom = toStringSafe(args.path, "").trim() || toStringSafe(args.outputPath, "").trim();
      const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
      const outputPath = custom || `/sdcard/Download/AnyClawShots/device_screen_${timestamp}.png`;
      const outputDir = outputPath.includes("/") ? outputPath.slice(0, outputPath.lastIndexOf("/")) : "/sdcard/Download/AnyClawShots";
      runSystemShellArgs(["mkdir", "-p", outputDir], 30000);
      const run = runSystemShellArgs(["screencap", "-p", outputPath], 45000);
      const stat = run.ok ? runSystemShellArgs(["ls", "-l", outputPath], 30000) : null;
      return {
        ok: run.ok,
        tool: "screenshot",
        path: outputPath,
        sizeHint: stat?.stdout || undefined,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      };
    }
    case "anyclaw_device_ui_coordinates": {
      const maxNodes = clampInt(toInt(args.maxNodes, 180), 20, 400);
      const dumpPath = `/sdcard/Download/AnyClawShots/ui_dump_${Date.now()}.xml`;
      const dumpDir = "/sdcard/Download/AnyClawShots";
      runSystemShellArgs(["mkdir", "-p", dumpDir], 30000);
      const dump = runSystemShellArgs(["uiautomator", "dump", dumpPath], 50000);
      const cat = runSystemShellArgs(["cat", dumpPath], 50000);
      const size = runSystemShellArgs(["wm", "size"], 30000);
      const xml = String(cat.stdout || "");
      const nodes = xml ? parseUiNodes(xml, maxNodes) : [];
      const sizeMatch = String(size.stdout || "").match(/Physical size:\s*(\d+)\s*x\s*(\d+)/i);
      const width = sizeMatch ? Number(sizeMatch[1]) : null;
      const height = sizeMatch ? Number(sizeMatch[2]) : null;
      return {
        ok: dump.ok && cat.ok,
        tool: "ui_coordinates",
        dumpPath,
        width,
        height,
        nodeCount: nodes.length,
        nodes,
        dumpOutput: dump.stdout || dump.stderr || undefined,
        errorOutput: dump.ok && cat.ok ? undefined : (dump.stderr || cat.stderr || undefined),
        error: dump.error || cat.error
      };
    }
    case "anyclaw_device_tap": {
      const x = toInt(args.x, NaN);
      const y = toInt(args.y, NaN);
      if (!Number.isFinite(x) || !Number.isFinite(y)) return { ok: false, tool: "tap", error: "missing_coordinates" };
      const run = runSystemShellArgs(["input", "tap", String(x), String(y)], 30000);
      return {
        ok: run.ok,
        tool: "tap",
        x,
        y,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      };
    }
    case "anyclaw_device_double_tap": {
      const x = toInt(args.x, NaN);
      const y = toInt(args.y, NaN);
      const intervalMs = clampInt(toInt(args.intervalMs, 120), 50, 1000);
      if (!Number.isFinite(x) || !Number.isFinite(y)) return { ok: false, tool: "double_tap", error: "missing_coordinates" };
      const first = runSystemShellArgs(["input", "tap", String(x), String(y)], 30000);
      await sleep(intervalMs);
      const second = runSystemShellArgs(["input", "tap", String(x), String(y)], 30000);
      return {
        ok: first.ok && second.ok,
        tool: "double_tap",
        x,
        y,
        intervalMs,
        firstExitCode: first.code,
        secondExitCode: second.code,
        errorOutput: first.ok && second.ok ? undefined : ([first.stderr, second.stderr].filter(Boolean).join("\n") || undefined)
      };
    }
    case "anyclaw_device_long_press": {
      const x = toInt(args.x, NaN);
      const y = toInt(args.y, NaN);
      const durationMs = clampInt(toInt(args.durationMs, 650), 200, 5000);
      if (!Number.isFinite(x) || !Number.isFinite(y)) return { ok: false, tool: "long_press", error: "missing_coordinates" };
      const run = runSystemShellArgs(["input", "swipe", String(x), String(y), String(x), String(y), String(durationMs)], 30000);
      return {
        ok: run.ok,
        tool: "long_press",
        x,
        y,
        durationMs,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      };
    }
    case "anyclaw_device_swipe": {
      const sx = toInt(args.start_x, NaN);
      const sy = toInt(args.start_y, NaN);
      const ex = toInt(args.end_x, NaN);
      const ey = toInt(args.end_y, NaN);
      const durationMs = clampInt(toInt(args.durationMs, 320), 100, 8000);
      if (![sx, sy, ex, ey].every(Number.isFinite)) return { ok: false, tool: "swipe", error: "missing_coordinates" };
      const run = runSystemShellArgs(["input", "swipe", String(sx), String(sy), String(ex), String(ey), String(durationMs)], 30000);
      return {
        ok: run.ok,
        tool: "swipe",
        start: { x: sx, y: sy },
        end: { x: ex, y: ey },
        durationMs,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      };
    }
    case "anyclaw_device_input_text": {
      const textRaw = toStringSafe(args.text, "");
      if (textRaw.length === 0) {
        return { ok: true, tool: "input_text", chars: 0, strategy: "noop_empty" };
      }
      const encoded = textRaw.trim().replace(/\s/g, "%s");
      const run = runSystemShellArgs(["input", "text", encoded], 35000);
      return {
        ok: run.ok,
        tool: "input_text",
        chars: textRaw.length,
        strategy: "shell_input_text",
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      };
    }
    case "anyclaw_device_keyevent": {
      const keycode = toStringSafe(args.keycode, "").trim();
      if (!keycode) return { ok: false, tool: "keyevent", error: "missing_keycode" };
      const run = runSystemShellArgs(["input", "keyevent", keycode], 30000);
      return {
        ok: run.ok,
        tool: "keyevent",
        keycode,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      };
    }
    case "anyclaw_device_statusbar": {
      const action = toStringSafe(args.action, "").trim().toLowerCase();
      let shellArgs;
      if (action === "notifications") shellArgs = ["cmd", "statusbar", "expand-notifications"];
      else if (action === "quick_settings") shellArgs = ["cmd", "statusbar", "expand-settings"];
      else if (action === "collapse") shellArgs = ["cmd", "statusbar", "collapse"];
      else {
        return {
          ok: false,
          tool: "statusbar",
          error: "invalid_action",
          message: "action must be notifications | quick_settings | collapse"
        };
      }
      const run = runSystemShellArgs(shellArgs, 30000);
      return {
        ok: run.ok,
        tool: "statusbar",
        action,
        exitCode: run.code,
        errorOutput: run.ok ? undefined : (run.stderr || run.stdout || undefined),
        error: run.error
      };
    }
    case "anyclaw_device_open_app": {
      const packageName = toStringSafe(args.packageName, "").trim();
      if (!packageName) return { ok: false, tool: "open_app", error: "missing_package_name" };
      const run = runSystemShellArgs(["monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"], 30000);
      return {
        ok: run.ok,
        tool: "open_app",
        packageName,
        exitCode: run.code,
        output: run.stdout || undefined,
        errorOutput: run.ok ? undefined : (run.stderr || undefined),
        error: run.error
      };
    }
    case "anyclaw_device_open_url": {
      const url = toStringSafe(args.url, "").trim();
      if (!/^https?:\/\//i.test(url)) {
        return {
          ok: false,
          tool: "open_url",
          error: "invalid_url_scheme",
          message: "url must start with http:// or https://"
        };
      }
      const packageName = toStringSafe(args.packageName, "").trim();
      const shellArgs = ["am", "start", "-a", "android.intent.action.VIEW", "-d", url];
      if (packageName) shellArgs.push("-p", packageName);
      const run = runSystemShellArgs(shellArgs, 30000);
      return {
        ok: run.ok,
        tool: "open_url",
        url,
        packageName: packageName || undefined,
        exitCode: run.code,
        output: run.stdout || undefined,
        errorOutput: run.ok ? undefined : (run.stderr || undefined),
        error: run.error
      };
    }

    default:
      throw new Error("unknown tool: " + name);
  }
}
