import { jsonResult, readNumberParam, readStringParam } from "openclaw/plugin-sdk";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { fileURLToPath } from "node:url";

type SearchHit = {
  title: string;
  url: string;
  snippet?: string;
  source: string;
  confidence: number;
};

type RuntimeConfig = {
  timeoutMs: number;
  maxResults: number;
  maxChars: number;
  userAgent: string;
};

type NoiseCounter = Record<string, number>;

type PluginRuntimeMeta = {
  parserVersion: string;
  loadedPath: string;
  indexSha256: string;
  registeredAt: string;
  userAgent: string;
};

const PARSER_VERSION = "v1.2.0";
const DEFAULT_TIMEOUT_MS = 20000;
const DEFAULT_MAX_RESULTS = 6;
const DEFAULT_MAX_CHARS = 12000;
const MAX_RESULTS = 10;
const DEFAULT_USER_AGENT = "AnyClawSearchSuite/1.2";

const SEARCH_ENGINES = {
  google: (query: string) => "https://www.google.com/search?q=" + encodeURIComponent(query) + "&hl=en",
  google_scholar: (query: string) => "https://scholar.google.com/scholar?q=" + encodeURIComponent(query),
  duckduckgo: (query: string) => "https://html.duckduckgo.com/html/?q=" + encodeURIComponent(query),
  bing: (query: string) => "https://www.bing.com/search?q=" + encodeURIComponent(query) + "&setlang=en",
  baidu: (query: string) => "https://www.baidu.com/s?wd=" + encodeURIComponent(query),
  quark: (query: string) => "https://quark.sm.cn/s?q=" + encodeURIComponent(query)
} as const;

type SearchEngineKey = keyof typeof SEARCH_ENGINES;

type SearchParseOutput = {
  hits: SearchHit[];
  totalAnchors: number;
  filteredCount: number;
  noiseReasonsTopN: Array<{ reason: string; count: number }>;
  suspectedChallengePage: boolean;
};

const ENGINE_WEIGHTS: Record<SearchEngineKey, number> = {
  duckduckgo: 1.2,
  google: 1.1,
  bing: 1,
  quark: 0.95,
  baidu: 0.95,
  google_scholar: 1.15
};

function clampNumber(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  if (value < min) return min;
  if (value > max) return max;
  return Math.floor(value);
}

function asObject(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function decodeHtml(input: string): string {
  return input
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#x2F;/g, "/")
    .replace(/\s+/g, " ")
    .trim();
}

function stripHtml(input: string): string {
  return decodeHtml(
    input
      .replace(/<script[\s\S]*?<\/script>/gi, " ")
      .replace(/<style[\s\S]*?<\/style>/gi, " ")
      .replace(/<noscript[\s\S]*?<\/noscript>/gi, " ")
      .replace(/<[^>]+>/g, " ")
  );
}

function readUrlParam(url: string, key: string): string | null {
  try {
    const u = new URL(url);
    return u.searchParams.get(key);
  } catch {
    return null;
  }
}

function safeDecodeURIComponent(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function tryDecodeBingUrl(url: string): string {
  try {
    const parsed = new URL(url);
    if (parsed.hostname !== "www.bing.com" || !parsed.pathname.startsWith("/ck/a")) {
      return url;
    }

    const u = parsed.searchParams.get("u") || parsed.searchParams.get("url");
    if (!u) return url;

    if (/^https?:\/\//i.test(u)) return u;

    const normalized = u.startsWith("a1") ? u.slice(2) : u;
    try {
      const decoded = Buffer.from(normalized, "base64").toString("utf8").trim();
      if (/^https?:\/\//i.test(decoded)) return decoded;
    } catch {
      // fall through
    }

    return safeDecodeURIComponent(u);
  } catch {
    return url;
  }
}

function unwrapTrackingUrl(url: string): string {
  try {
    const parsed = new URL(url);

    if ((parsed.hostname === "duckduckgo.com" || parsed.hostname === "www.duckduckgo.com") && parsed.pathname === "/l/") {
      const uddg = parsed.searchParams.get("uddg");
      if (uddg) return safeDecodeURIComponent(uddg);
    }

    if ((parsed.hostname === "google.com" || parsed.hostname === "www.google.com") && parsed.pathname === "/url") {
      const q = parsed.searchParams.get("q") || parsed.searchParams.get("url");
      if (q) return safeDecodeURIComponent(q);
    }

    if (parsed.hostname === "www.bing.com" && parsed.pathname.startsWith("/ck/a")) {
      return tryDecodeBingUrl(url);
    }
  } catch {
    return url;
  }
  return url;
}

function resolveUrl(rawHref: string, engine: SearchEngineKey): string | null {
  const href = decodeHtml(rawHref.trim());
  if (!href) return null;

  if (href.startsWith("/url?")) {
    const q = readUrlParam("https://www.google.com" + href, "q");
    if (q && /^https?:\/\//i.test(q)) return safeDecodeURIComponent(q);
  }

  if (href.startsWith("//")) {
    return unwrapTrackingUrl("https:" + href);
  }

  if (href.startsWith("http://") || href.startsWith("https://")) {
    return unwrapTrackingUrl(href);
  }

  if (href.startsWith("/")) {
    if (engine === "google" || engine === "google_scholar") return unwrapTrackingUrl("https://www.google.com" + href);
    if (engine === "bing") return unwrapTrackingUrl("https://www.bing.com" + href);
    if (engine === "baidu") return unwrapTrackingUrl("https://www.baidu.com" + href);
    if (engine === "quark") return unwrapTrackingUrl("https://quark.sm.cn" + href);
    if (engine === "duckduckgo") return unwrapTrackingUrl("https://duckduckgo.com" + href);
  }

  return null;
}

function pushNoise(counter: NoiseCounter, reason: string): void {
  counter[reason] = (counter[reason] || 0) + 1;
}

function classifyNoise(engine: SearchEngineKey, title: string, url: string): string | null {
  const t = title.toLowerCase();
  const u = url.toLowerCase();

  if (!/^https?:\/\//.test(u)) return "invalid_scheme";
  if (u.startsWith("javascript:") || u.startsWith("mailto:")) return "invalid_scheme";

  if (
    u.includes("/httpservice/retry/enablejs") ||
    u.includes("consent.google") ||
    u.includes("accounts.google.com") ||
    u.includes("/sorry/") ||
    u.includes("passport.baidu.com")
  ) {
    return "challenge_or_login";
  }

  if (u.includes("feedback") || u.includes("setprefs") || u.includes("preferences")) {
    return "settings_or_feedback";
  }

  if (engine === "google" || engine === "google_scholar") {
    if (u.includes("google.com/search?") || u.includes("google.com/scholar?")) return "engine_loop";
    if (t.includes("click here") || t === "feedback") return "low_information";
  }

  if (engine === "bing") {
    if (u.includes("/copilotsearch") || u.includes("setlang=")) return "navigation";
    if (u.includes("bing.com/search?")) return "engine_loop";
  }

  if (engine === "baidu") {
    if (u === "https://www.baidu.com/" || t.includes("百度首页") || t.includes("登录")) return "navigation";
    if (u.includes("baidu.com/s?")) return "engine_loop";
  }

  if (engine === "quark") {
    if (u.includes("tl_request=") || t.includes("一周内") || t.includes("一月内") || t.includes("不限")) return "filter_page";
  }

  if (engine === "duckduckgo") {
    if (u.includes("duckduckgo.com/") && !u.includes("uddg=")) {
      if (t.includes("all regions") || t.includes("safe search") || t.includes("any time") || t.includes("help")) {
        return "navigation";
      }
    }
  }

  return null;
}

function isLikelyChallengePage(html: string): boolean {
  const body = html.toLowerCase();
  return (
    body.includes("enablejs") ||
    body.includes("captcha") ||
    body.includes("verify you are human") ||
    body.includes("unusual traffic") ||
    body.includes("请开启javascript") ||
    body.includes("访问受限")
  );
}

function topNoiseReasons(counter: NoiseCounter): Array<{ reason: string; count: number }> {
  return Object.entries(counter)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([reason, count]) => ({ reason, count }));
}

function parseSearchHits(html: string, engine: SearchEngineKey, limit: number): SearchParseOutput {
  const hits: SearchHit[] = [];
  const seen = new Set<string>();
  const noiseCounter: NoiseCounter = {};

  let filteredCount = 0;
  let totalAnchors = 0;

  const anchorRegex = /<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
  let match: RegExpExecArray | null = anchorRegex.exec(html);

  while (match) {
    totalAnchors += 1;

    const resolved = resolveUrl(match[1], engine);
    const title = stripHtml(match[2]);

    if (!resolved || !title) {
      filteredCount += 1;
      pushNoise(noiseCounter, "empty_title_or_url");
      match = anchorRegex.exec(html);
      continue;
    }

    const qParam = readUrlParam(resolved, "q");
    const normalizedUrl = (engine === "google" || engine === "google_scholar") && qParam && /^https?:\/\//i.test(qParam)
      ? safeDecodeURIComponent(qParam)
      : resolved;

    const noiseReason = classifyNoise(engine, title, normalizedUrl);
    if (noiseReason) {
      filteredCount += 1;
      pushNoise(noiseCounter, noiseReason);
      match = anchorRegex.exec(html);
      continue;
    }

    if (seen.has(normalizedUrl)) {
      filteredCount += 1;
      pushNoise(noiseCounter, "duplicate_url");
      match = anchorRegex.exec(html);
      continue;
    }

    seen.add(normalizedUrl);
    hits.push({
      title,
      url: normalizedUrl,
      source: engine,
      confidence: 0.8
    });

    if (hits.length >= limit) {
      break;
    }

    match = anchorRegex.exec(html);
  }

  return {
    hits,
    totalAnchors,
    filteredCount,
    noiseReasonsTopN: topNoiseReasons(noiseCounter),
    suspectedChallengePage: isLikelyChallengePage(html)
  };
}

function renderHitsText(hits: SearchHit[]): string {
  if (hits.length === 0) return "No results";
  return hits
    .map((item, index) => {
      const head = String(index + 1) + ". " + item.title;
      const url = item.url;
      const snippet = item.snippet ? "\n" + item.snippet : "";
      return head + "\n" + url + snippet;
    })
    .join("\n\n");
}

function providerQualityScore(hits: number, filtered: number): number {
  const total = hits + filtered;
  if (total <= 0) return 0;
  return Math.round((hits / total) * 1000) / 1000;
}

async function fetchText(url: string, timeoutMs: number, userAgent: string): Promise<{ status: number; body: string }> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);

  try {
    const response = await fetch(url, {
      method: "GET",
      signal: ctrl.signal,
      headers: {
        "User-Agent": userAgent,
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
      }
    });

    const body = await response.text();
    return { status: response.status, body };
  } finally {
    clearTimeout(timer);
  }
}

function withMeta(payload: Record<string, unknown>, meta: PluginRuntimeMeta): Record<string, unknown> {
  return {
    ...payload,
    parserVersion: meta.parserVersion,
    pluginRuntime: {
      loadedPath: meta.loadedPath,
      indexSha256: meta.indexSha256,
      registeredAt: meta.registeredAt,
      userAgent: meta.userAgent
    }
  };
}

async function runSearch(engine: SearchEngineKey, query: string, limit: number, runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  const url = SEARCH_ENGINES[engine](query);
  const startedAt = Date.now();

  try {
    const page = await fetchText(url, runtime.timeoutMs, runtime.userAgent);
    const parsed = parseSearchHits(page.body, engine, limit);
    const qualityScore = providerQualityScore(parsed.hits.length, parsed.filteredCount);

    return withMeta({
      ok: true,
      engine,
      query,
      requestUrl: url,
      status: page.status,
      count: parsed.hits.length,
      filteredCount: parsed.filteredCount,
      noiseReasonsTopN: parsed.noiseReasonsTopN,
      providerQualityScore: qualityScore,
      suspectedChallengePage: parsed.suspectedChallengePage,
      tookMs: Date.now() - startedAt,
      results: parsed.hits,
      text: renderHitsText(parsed.hits)
    }, meta);
  } catch (error) {
    return withMeta({
      ok: false,
      engine,
      query,
      requestUrl: url,
      error: String(error),
      tookMs: Date.now() - startedAt,
      count: 0,
      filteredCount: 0,
      providerQualityScore: 0,
      suspectedChallengePage: false,
      noiseReasonsTopN: [],
      results: [],
      text: ""
    }, meta);
  }
}

async function runMultiSearch(query: string, limit: number, runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  const engines: SearchEngineKey[] = ["google", "duckduckgo", "bing", "baidu", "quark"];
  const startedAt = Date.now();

  const batches = await Promise.all(engines.map((engine) => runSearch(engine, query, limit, runtime, meta)));

  const normalizedBatches = batches.map((item) => {
    const engine = String(item.engine || "") as SearchEngineKey;
    const quality = typeof item.providerQualityScore === "number" ? item.providerQualityScore : 0;
    const weight = ENGINE_WEIGHTS[engine] || 1;
    const mergedScore = Math.round(quality * weight * 1000) / 1000;
    const results = Array.isArray(item.results) ? (item.results as SearchHit[]) : [];
    return {
      engine,
      ok: Boolean(item.ok),
      count: Number(item.count || 0),
      filteredCount: Number(item.filteredCount || 0),
      tookMs: Number(item.tookMs || 0),
      providerQualityScore: quality,
      mergedScore,
      suspectedChallengePage: Boolean(item.suspectedChallengePage),
      error: item.ok ? undefined : String(item.error || ""),
      results
    };
  });

  normalizedBatches.sort((a, b) => b.mergedScore - a.mergedScore);

  const merged: SearchHit[] = [];
  const seen = new Set<string>();
  for (const batch of normalizedBatches) {
    for (const item of batch.results) {
      if (seen.has(item.url)) continue;
      seen.add(item.url);
      merged.push({
        ...item,
        confidence: Math.max(0.3, Math.min(0.99, batch.mergedScore))
      });
      if (merged.length >= limit) break;
    }
    if (merged.length >= limit) break;
  }

  return withMeta({
    ok: true,
    engine: "multi",
    query,
    count: merged.length,
    tookMs: Date.now() - startedAt,
    results: merged,
    text: renderHitsText(merged),
    providers: normalizedBatches.map((item) => ({
      engine: item.engine,
      ok: item.ok,
      count: item.count,
      filteredCount: item.filteredCount,
      tookMs: item.tookMs,
      providerQualityScore: item.providerQualityScore,
      mergedScore: item.mergedScore,
      suspectedChallengePage: item.suspectedChallengePage,
      error: item.error
    }))
  }, meta);
}

function readLimit(args: Record<string, unknown>, runtime: RuntimeConfig): number {
  const requested = readNumberParam(args, "limit", { integer: true }) ?? runtime.maxResults;
  return clampNumber(requested, 1, MAX_RESULTS);
}

function createSearchTool(engine: SearchEngineKey, label: string, description: string, runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label,
    name: "anyclaw_" + engine + "_search",
    description,
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["query"],
      properties: {
        query: {
          type: "string",
          description: "Search keywords"
        },
        limit: {
          type: "number",
          minimum: 1,
          maximum: 10,
          description: "Maximum number of results"
        }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const query = readStringParam(args, "query", { required: true });
      const limit = readLimit(args, runtime);
      return jsonResult(await runSearch(engine, query, limit, runtime, meta));
    }
  };
}

function createMultiSearchTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "AnyClaw Multi Search",
    name: "anyclaw_multi_search",
    description: "Search with multiple providers (Google, DuckDuckGo, Bing, Baidu, Quark) and return merged results.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["query"],
      properties: {
        query: {
          type: "string",
          description: "Search keywords"
        },
        limit: {
          type: "number",
          minimum: 1,
          maximum: 10,
          description: "Maximum number of merged results"
        }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const query = readStringParam(args, "query", { required: true });
      const limit = readLimit(args, runtime);
      return jsonResult(await runMultiSearch(query, limit, runtime, meta));
    }
  };
}

function createVisitTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "AnyClaw Web Visit",
    name: "anyclaw_web_visit",
    description: "Fetch a webpage and extract readable text content.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["url"],
      properties: {
        url: {
          type: "string",
          description: "Target webpage URL"
        },
        maxChars: {
          type: "number",
          minimum: 1000,
          maximum: 80000,
          description: "Maximum extracted characters"
        }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const url = readStringParam(args, "url", { required: true });
      const maxChars = clampNumber(
        readNumberParam(args, "maxChars", { integer: true }) ?? runtime.maxChars,
        1000,
        80000
      );
      const startedAt = Date.now();
      const page = await fetchText(url, runtime.timeoutMs, runtime.userAgent);
      const titleMatch = page.body.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
      const title = titleMatch ? stripHtml(titleMatch[1]) : "";
      const text = stripHtml(page.body).slice(0, maxChars);

      return jsonResult(withMeta({
        ok: true,
        url,
        status: page.status,
        title,
        chars: text.length,
        tookMs: Date.now() - startedAt,
        suspectedChallengePage: isLikelyChallengePage(page.body),
        text
      }, meta));
    }
  };
}

function normalizeMethod(raw: string): string {
  const upper = raw.trim().toUpperCase();
  if (!upper) return "GET";
  if (upper === "GET" || upper === "POST" || upper === "PUT" || upper === "PATCH" || upper === "DELETE" || upper === "HEAD" || upper === "OPTIONS") {
    return upper;
  }
  return "GET";
}

function createHttpTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "AnyClaw HTTP",
    name: "anyclaw_http_request",
    description: "Send HTTP requests for diagnostics or data retrieval.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["url"],
      properties: {
        url: {
          type: "string",
          description: "Request URL"
        },
        method: {
          type: "string",
          description: "HTTP method, e.g. GET/POST"
        },
        headersJson: {
          type: "string",
          description: "Optional JSON object string for request headers"
        },
        body: {
          type: "string",
          description: "Optional request body"
        },
        maxChars: {
          type: "number",
          minimum: 1000,
          maximum: 80000,
          description: "Maximum response characters"
        }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const url = readStringParam(args, "url", { required: true });
      const method = normalizeMethod(readStringParam(args, "method") ?? "GET");
      const maxChars = clampNumber(
        readNumberParam(args, "maxChars", { integer: true }) ?? runtime.maxChars,
        1000,
        80000
      );

      const headersRaw = readStringParam(args, "headersJson");
      let headers: Record<string, string> = {};
      if (headersRaw && headersRaw.trim()) {
        try {
          const parsed = JSON.parse(headersRaw);
          if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
            for (const [key, value] of Object.entries(parsed)) {
              if (typeof value === "string") headers[key] = value;
            }
          }
        } catch {
          headers = {};
        }
      }

      if (!headers["User-Agent"]) {
        headers["User-Agent"] = runtime.userAgent;
      }

      const body = readStringParam(args, "body", { allowEmpty: true });
      const startedAt = Date.now();

      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), runtime.timeoutMs);

      try {
        const response = await fetch(url, {
          method,
          headers,
          signal: ctrl.signal,
          body: method === "GET" || method === "HEAD" ? undefined : body
        });

        const text = (await response.text()).slice(0, maxChars);
        return jsonResult(withMeta({
          ok: response.ok,
          status: response.status,
          statusText: response.statusText,
          method,
          url,
          tookMs: Date.now() - startedAt,
          text
        }, meta));
      } finally {
        clearTimeout(timer);
      }
    }
  };
}

function resolveRuntimeConfig(rawConfig: unknown): RuntimeConfig {
  const cfg = asObject(rawConfig);

  const timeoutSecondsRaw = typeof cfg.timeoutSeconds === "number" ? cfg.timeoutSeconds : DEFAULT_TIMEOUT_MS / 1000;
  const timeoutMs = clampNumber(timeoutSecondsRaw, 5, 120) * 1000;

  const maxResultsRaw = typeof cfg.maxResults === "number" ? cfg.maxResults : DEFAULT_MAX_RESULTS;
  const maxResults = clampNumber(maxResultsRaw, 1, MAX_RESULTS);

  const maxCharsRaw = typeof cfg.maxChars === "number" ? cfg.maxChars : DEFAULT_MAX_CHARS;
  const maxChars = clampNumber(maxCharsRaw, 1000, 80000);

  const userAgent = typeof cfg.userAgent === "string" && cfg.userAgent.trim()
    ? cfg.userAgent.trim()
    : DEFAULT_USER_AGENT;

  return {
    timeoutMs,
    maxResults,
    maxChars,
    userAgent
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

function createRuntimeMeta(userAgent: string): PluginRuntimeMeta {
  const loadedPath = resolveLoadedPath();
  const indexSha256 = resolveIndexSha256(loadedPath);
  return {
    parserVersion: PARSER_VERSION,
    loadedPath,
    indexSha256,
    registeredAt: new Date().toISOString(),
    userAgent
  };
}

export default {
  id: "anyclaw-search-suite",
  name: "AnyClaw Search Suite",
  register(api: any) {
    const runtime = resolveRuntimeConfig(api.pluginConfig);
    const runtimeMeta = createRuntimeMeta(runtime.userAgent);

    if (api.logger && api.logger.info) {
      api.logger.info(
        "anyclaw-search-suite loaded parser=" + runtimeMeta.parserVersion +
          " path=" + runtimeMeta.loadedPath +
          " hash=" + runtimeMeta.indexSha256
      );
    }

    api.registerTool(
      createSearchTool(
        "google",
        "Google Search",
        "Search web pages with Google.",
        runtime,
        runtimeMeta
      )
    );

    api.registerTool(
      createSearchTool(
        "google_scholar",
        "Google Scholar Search",
        "Search academic publications with Google Scholar.",
        runtime,
        runtimeMeta
      )
    );

    api.registerTool(
      createSearchTool(
        "duckduckgo",
        "DuckDuckGo Search",
        "Search web pages with DuckDuckGo.",
        runtime,
        runtimeMeta
      )
    );

    api.registerTool(
      createSearchTool(
        "bing",
        "Bing Search",
        "Search web pages with Bing.",
        runtime,
        runtimeMeta
      )
    );

    api.registerTool(
      createSearchTool(
        "baidu",
        "Baidu Search",
        "Search web pages with Baidu.",
        runtime,
        runtimeMeta
      )
    );

    api.registerTool(
      createSearchTool(
        "quark",
        "Quark Search",
        "Search web pages with Quark.",
        runtime,
        runtimeMeta
      )
    );

    api.registerTool(createMultiSearchTool(runtime, runtimeMeta));
    api.registerTool(createVisitTool(runtime, runtimeMeta));
    api.registerTool(createHttpTool(runtime, runtimeMeta));
  }
};
