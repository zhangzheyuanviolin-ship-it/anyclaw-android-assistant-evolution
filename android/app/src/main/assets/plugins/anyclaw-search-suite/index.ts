import { jsonResult, readNumberParam, readStringParam } from "openclaw/plugin-sdk";

type SearchHit = {
  title: string;
  url: string;
  snippet?: string;
  source: string;
};

type RuntimeConfig = {
  timeoutMs: number;
  maxResults: number;
  maxChars: number;
  userAgent: string;
};

const DEFAULT_TIMEOUT_MS = 20000;
const DEFAULT_MAX_RESULTS = 6;
const DEFAULT_MAX_CHARS = 12000;
const MAX_RESULTS = 10;
const DEFAULT_USER_AGENT = "AnyClawSearchSuite/1.0";

const SEARCH_ENGINES = {
  google: (query: string) => "https://www.google.com/search?q=" + encodeURIComponent(query) + "&hl=en",
  google_scholar: (query: string) => "https://scholar.google.com/scholar?q=" + encodeURIComponent(query),
  duckduckgo: (query: string) => "https://html.duckduckgo.com/html/?q=" + encodeURIComponent(query),
  bing: (query: string) => "https://www.bing.com/search?q=" + encodeURIComponent(query),
  baidu: (query: string) => "https://www.baidu.com/s?wd=" + encodeURIComponent(query),
  quark: (query: string) => "https://quark.sm.cn/s?q=" + encodeURIComponent(query)
} as const;

type SearchEngineKey = keyof typeof SEARCH_ENGINES;

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

function resolveUrl(rawHref: string, engine: SearchEngineKey): string | null {
  const href = decodeHtml(rawHref.trim());
  if (!href) return null;

  if (href.startsWith("/url?")) {
    const match = href.match(/[?&]q=([^&]+)/);
    if (!match) return null;
    try {
      return decodeURIComponent(match[1]);
    } catch {
      return null;
    }
  }

  if (href.startsWith("//")) {
    return "https:" + href;
  }

  if (href.startsWith("http://") || href.startsWith("https://")) {
    return href;
  }

  if (href.startsWith("/")) {
    if (engine === "google" || engine === "google_scholar") return "https://www.google.com" + href;
    if (engine === "bing") return "https://www.bing.com" + href;
    if (engine === "baidu") return "https://www.baidu.com" + href;
    if (engine === "quark") return "https://quark.sm.cn" + href;
    if (engine === "duckduckgo") return "https://duckduckgo.com" + href;
  }

  return null;
}

function parseSearchHits(html: string, engine: SearchEngineKey, limit: number): SearchHit[] {
  const hits: SearchHit[] = [];
  const seen = new Set<string>();

  const anchorRegex = /<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
  let match: RegExpExecArray | null = anchorRegex.exec(html);

  while (match && hits.length < limit) {
    const url = resolveUrl(match[1], engine);
    const title = stripHtml(match[2]);

    if (!url || !title) {
      match = anchorRegex.exec(html);
      continue;
    }

    if (url.startsWith("javascript:") || url.startsWith("mailto:") || url.includes("/preferences")) {
      match = anchorRegex.exec(html);
      continue;
    }

    if (seen.has(url)) {
      match = anchorRegex.exec(html);
      continue;
    }

    seen.add(url);

    hits.push({
      title,
      url,
      source: engine
    });

    match = anchorRegex.exec(html);
  }

  return hits;
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

async function runSearch(engine: SearchEngineKey, query: string, limit: number, runtime: RuntimeConfig) {
  const url = SEARCH_ENGINES[engine](query);
  const startedAt = Date.now();

  try {
    const page = await fetchText(url, runtime.timeoutMs, runtime.userAgent);
    const hits = parseSearchHits(page.body, engine, limit);

    return {
      ok: true,
      engine,
      query,
      requestUrl: url,
      status: page.status,
      count: hits.length,
      tookMs: Date.now() - startedAt,
      results: hits,
      text: renderHitsText(hits)
    };
  } catch (error) {
    return {
      ok: false,
      engine,
      query,
      requestUrl: url,
      error: String(error),
      tookMs: Date.now() - startedAt,
      count: 0,
      results: [],
      text: ""
    };
  }
}

async function runMultiSearch(query: string, limit: number, runtime: RuntimeConfig) {
  const engines: SearchEngineKey[] = ["google", "duckduckgo", "bing", "baidu", "quark"];
  const startedAt = Date.now();
  const batches = await Promise.all(engines.map((engine) => runSearch(engine, query, limit, runtime)));

  const merged: SearchHit[] = [];
  const seen = new Set<string>();
  for (const batch of batches) {
    for (const item of batch.results) {
      if (seen.has(item.url)) continue;
      seen.add(item.url);
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
    tookMs: Date.now() - startedAt,
    providers: batches.map((item) => ({
      engine: item.engine,
      ok: item.ok,
      count: item.count,
      tookMs: item.tookMs,
      error: item.ok ? undefined : item.error
    })),
    results: merged,
    text: renderHitsText(merged)
  };
}

function readLimit(args: Record<string, unknown>, runtime: RuntimeConfig): number {
  const requested = readNumberParam(args, "limit", { integer: true }) ?? runtime.maxResults;
  return clampNumber(requested, 1, MAX_RESULTS);
}

function createSearchTool(engine: SearchEngineKey, label: string, description: string, runtime: RuntimeConfig) {
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
      return jsonResult(await runSearch(engine, query, limit, runtime));
    }
  };
}

function createMultiSearchTool(runtime: RuntimeConfig) {
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
      return jsonResult(await runMultiSearch(query, limit, runtime));
    }
  };
}

function createVisitTool(runtime: RuntimeConfig) {
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

      return jsonResult({
        ok: true,
        url,
        status: page.status,
        title,
        chars: text.length,
        tookMs: Date.now() - startedAt,
        text
      });
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

function createHttpTool(runtime: RuntimeConfig) {
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
        return jsonResult({
          ok: response.ok,
          status: response.status,
          statusText: response.statusText,
          method,
          url,
          tookMs: Date.now() - startedAt,
          text
        });
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

export default {
  id: "anyclaw-search-suite",
  name: "AnyClaw Search Suite",
  register(api: any) {
    const runtime = resolveRuntimeConfig(api.pluginConfig);

    api.registerTool(
      createSearchTool(
        "google",
        "Google Search",
        "Search web pages with Google.",
        runtime
      )
    );

    api.registerTool(
      createSearchTool(
        "google_scholar",
        "Google Scholar Search",
        "Search academic publications with Google Scholar.",
        runtime
      )
    );

    api.registerTool(
      createSearchTool(
        "duckduckgo",
        "DuckDuckGo Search",
        "Search web pages with DuckDuckGo.",
        runtime
      )
    );

    api.registerTool(
      createSearchTool(
        "bing",
        "Bing Search",
        "Search web pages with Bing.",
        runtime
      )
    );

    api.registerTool(
      createSearchTool(
        "baidu",
        "Baidu Search",
        "Search web pages with Baidu.",
        runtime
      )
    );

    api.registerTool(
      createSearchTool(
        "quark",
        "Quark Search",
        "Search web pages with Quark.",
        runtime
      )
    );

    api.registerTool(createMultiSearchTool(runtime));
    api.registerTool(createVisitTool(runtime));
    api.registerTool(createHttpTool(runtime));

    if (api.logger && api.logger.info) {
      api.logger.info("anyclaw-search-suite loaded");
    }
  }
};
