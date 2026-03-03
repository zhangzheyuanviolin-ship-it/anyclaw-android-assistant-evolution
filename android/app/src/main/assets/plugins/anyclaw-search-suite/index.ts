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
  tavilyApiKey?: string;
  tavilyBaseUrl: string;
};

type NoiseCounter = Record<string, number>;

type PluginRuntimeMeta = {
  parserVersion: string;
  loadedPath: string;
  indexSha256: string;
  registeredAt: string;
  userAgent: string;
};

type IntentClass = "doc" | "news" | "social" | "shop" | "academic" | "video" | "generic";

type SearchParseOutput = {
  hits: SearchHit[];
  totalAnchors: number;
  filteredCount: number;
  noiseReasonsTopN: Array<{ reason: string; count: number }>;
  suspectedChallengePage: boolean;
};

type SearchRunOutput = {
  ok: boolean;
  engine: string;
  query: string;
  requestUrl: string;
  status?: number;
  count: number;
  filteredCount: number;
  noiseReasonsTopN: Array<{ reason: string; count: number }>;
  providerQualityScore: number;
  suspectedChallengePage: boolean;
  tookMs: number;
  results: SearchHit[];
  text: string;
  intentClass: IntentClass;
  finalQualityLabel: "good" | "mixed" | "noisy" | "challenge";
  resultDomainDiversity: number;
  error?: string;
};

const PARSER_VERSION = "v1.3.0";
const DEFAULT_TIMEOUT_MS = 20000;
const DEFAULT_MAX_RESULTS = 6;
const DEFAULT_MAX_CHARS = 12000;
const MAX_RESULTS = 10;
const DEFAULT_USER_AGENT = "AnyClawSearchSuite/1.3";
const DEFAULT_TAVILY_BASE_URL = "https://api.tavily.com/search";

const SEARCH_ENGINES = {
  google: (query: string) => "https://www.google.com/search?q=" + encodeURIComponent(query) + "&hl=en",
  google_scholar: (query: string) => "https://scholar.google.com/scholar?q=" + encodeURIComponent(query),
  duckduckgo: (query: string) => "https://html.duckduckgo.com/html/?q=" + encodeURIComponent(query),
  bing: (query: string) => "https://www.bing.com/search?q=" + encodeURIComponent(query) + "&setlang=en",
  baidu: (query: string) => "https://www.baidu.com/s?wd=" + encodeURIComponent(query),
  quark: (query: string) => "https://quark.sm.cn/s?q=" + encodeURIComponent(query)
} as const;

type SearchEngineKey = keyof typeof SEARCH_ENGINES;

const ENGINE_WEIGHTS: Record<SearchEngineKey, number> = {
  duckduckgo: 1.22,
  google: 1.12,
  google_scholar: 1.2,
  bing: 1,
  baidu: 0.9,
  quark: 0.92
};

const PORTAL_DOMAINS = [
  "hao123.com",
  "www.hao123.com",
  "news.baidu.com",
  "map.baidu.com",
  "tieba.baidu.com",
  "zhidao.baidu.com",
  "image.baidu.com",
  "video.baidu.com"
];

const BLOCKED_LOGIN_DOMAINS = [
  "accounts.google.com",
  "consent.google.com",
  "passport.baidu.com",
  "sso.toutiao.com"
];

const CHALLENGE_MARKERS = [
  "enablejs",
  "captcha",
  "verify you are human",
  "unusual traffic",
  "access denied",
  "请开启javascript",
  "访问受限",
  "visitor system"
];

const INTENT_DOMAIN_BOOST: Record<IntentClass, Array<[string, number]>> = {
  doc: [
    ["docs.", 1.4],
    ["developer.", 1.35],
    ["github.com", 1.35],
    ["readthedocs.io", 1.35],
    ["python.org", 1.3],
    ["openclaw.ai", 1.4]
  ],
  news: [
    ["reuters.com", 1.25],
    ["nytimes.com", 1.25],
    ["bbc.com", 1.2],
    ["cnn.com", 1.2],
    ["thepaper.cn", 1.2],
    ["xinhuanet.com", 1.2],
    ["people.com.cn", 1.2]
  ],
  social: [
    ["weibo.com", 1.35],
    ["zhihu.com", 1.25],
    ["reddit.com", 1.2],
    ["x.com", 1.2],
    ["twitter.com", 1.2]
  ],
  shop: [
    ["jd.com", 1.45],
    ["tmall.com", 1.4],
    ["taobao.com", 1.35],
    ["amazon.", 1.3],
    ["pinduoduo.com", 1.35]
  ],
  academic: [
    ["scholar.google.", 1.5],
    ["arxiv.org", 1.45],
    ["acm.org", 1.35],
    ["ieee.org", 1.35],
    ["nature.com", 1.3],
    ["sciencedirect.com", 1.3],
    ["springer.com", 1.3],
    ["doi.org", 1.3]
  ],
  video: [
    ["youtube.com", 1.35],
    ["bilibili.com", 1.35],
    ["vimeo.com", 1.25]
  ],
  generic: []
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

function safeDecodeURIComponent(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function parseUrlSafe(raw: string): URL | null {
  try {
    return new URL(raw);
  } catch {
    return null;
  }
}

function readUrlParam(rawUrl: string, key: string): string | null {
  const parsed = parseUrlSafe(rawUrl);
  if (!parsed) return null;
  return parsed.searchParams.get(key);
}

function domainFromUrl(rawUrl: string): string {
  const parsed = parseUrlSafe(rawUrl);
  return parsed ? parsed.hostname.toLowerCase() : "";
}

function pathFromUrl(rawUrl: string): string {
  const parsed = parseUrlSafe(rawUrl);
  return parsed ? parsed.pathname.toLowerCase() : "";
}

function normalizeUrlForDedupe(rawUrl: string): string {
  const parsed = parseUrlSafe(rawUrl);
  if (!parsed) return rawUrl;
  parsed.hash = "";

  const dropParams = ["utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "from", "spm", "ref"];
  for (const key of dropParams) {
    parsed.searchParams.delete(key);
  }
  return parsed.toString();
}

function hasAny(source: string, terms: string[]): boolean {
  const text = source.toLowerCase();
  return terms.some((term) => text.includes(term));
}

function classifyIntent(query: string): IntentClass {
  const q = query.toLowerCase();

  if (hasAny(q, ["arxiv", "paper", "citation", "scholar", "论文", "学术", "doi"])) return "academic";
  if (hasAny(q, ["api", "docs", "documentation", "manual", "sdk", "教程", "文档", "开发"])) return "doc";
  if (hasAny(q, ["price", "buy", "jd", "tmall", "taobao", "购买", "价格", "京东", "天猫"])) return "shop";
  if (hasAny(q, ["hot", "trending", "weibo", "reddit", "热搜", "微博"])) return "social";
  if (hasAny(q, ["video", "youtube", "bilibili", "b站", "教学视频"])) return "video";
  if (hasAny(q, ["news", "headline", "breaking", "新闻", "头条"])) return "news";
  return "generic";
}

function resolveIntentDomainBoost(intent: IntentClass, rawUrl: string): number {
  const domain = domainFromUrl(rawUrl);
  if (!domain) return 0.5;

  if (PORTAL_DOMAINS.includes(domain)) return 0.45;

  const entries = INTENT_DOMAIN_BOOST[intent] || [];
  for (const [needle, score] of entries) {
    if (needle.endsWith(".")) {
      if (domain.startsWith(needle)) return score;
    } else if (domain.includes(needle)) {
      return score;
    }
  }

  if (domain.endsWith("google.com") || domain.endsWith("bing.com") || domain.endsWith("baidu.com") || domain.endsWith("quark.sm.cn")) {
    return 0.7;
  }

  return 1;
}

function pushNoise(counter: NoiseCounter, reason: string): void {
  counter[reason] = (counter[reason] || 0) + 1;
}

function isLikelyChallengePage(html: string, status?: number): boolean {
  const body = html.toLowerCase();
  if (status === 403 || status === 429 || status === 503) return true;
  return CHALLENGE_MARKERS.some((marker) => body.includes(marker));
}

function topNoiseReasons(counter: NoiseCounter): Array<{ reason: string; count: number }> {
  return Object.entries(counter)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([reason, count]) => ({ reason, count }));
}

function isBlockedLoginDomain(domain: string): boolean {
  return BLOCKED_LOGIN_DOMAINS.includes(domain);
}

function isPortalDomain(domain: string): boolean {
  return PORTAL_DOMAINS.includes(domain);
}

function classifyNoise(
  engine: SearchEngineKey,
  intent: IntentClass,
  title: string,
  rawUrl: string
): string | null {
  const url = normalizeUrlForDedupe(rawUrl);
  const lowerTitle = title.toLowerCase();
  const lowerUrl = url.toLowerCase();
  const domain = domainFromUrl(url);
  const path = pathFromUrl(url);

  if (!/^https?:\/\//.test(lowerUrl)) return "invalid_scheme";
  if (lowerUrl.startsWith("javascript:") || lowerUrl.startsWith("mailto:")) return "invalid_scheme";

  if (lowerUrl.includes("/httpservice/retry/enablejs") || lowerUrl.includes("/sorry/")) return "challenge_or_retry";
  if (isBlockedLoginDomain(domain)) return "login_or_consent";

  if (isPortalDomain(domain) && intent !== "news") return "portal_navigation";

  if (engine === "duckduckgo") {
    if (domain.includes("duckduckgo.com") && path === "/y.js") return "ddg_ad_redirect";
    if (domain.includes("duckduckgo.com") && path === "/" && hasAny(lowerTitle, ["all regions", "safe search", "any time", "help"])) {
      return "navigation";
    }
  }

  if (engine === "google") {
    if (domain.includes("google.com") && (path === "/search" || path === "/url")) return "engine_loop";
    if (hasAny(lowerTitle, ["click here", "feedback", "google chrome", "mozilla firefox"])) return "low_information";
  }

  if (engine === "google_scholar") {
    if (domain.includes("google.com") && path === "/scholar") return "engine_loop";
    if (hasAny(lowerTitle, ["citations", "alerts", "my profile", "settings", "help"])) return "scholar_navigation";
  }

  if (engine === "bing") {
    if (domain === "www.bing.com" && (path === "/" || path === "/search")) return "engine_loop";
    if (lowerUrl.includes("/copilotsearch") || lowerUrl.includes("setlang=")) return "navigation";
    if (hasAny(lowerTitle, ["all", "images", "videos", "maps", "news"])) {
      if (domain === "www.bing.com") return "navigation";
    }
  }

  if (engine === "baidu") {
    if (domain === "www.baidu.com" && path === "/") return "navigation";
    if (domain === "www.baidu.com" && path === "/s") return "engine_loop";
    if (hasAny(lowerTitle, ["百度首页", "登录", "抗击肺炎"])) return "portal_navigation";
  }

  if (engine === "quark") {
    if (domain === "quark.sm.cn" && path === "/s") {
      if (lowerUrl.includes("tl_request=") || lowerUrl.includes("by=tuijian") || lowerUrl.includes("from=")) {
        return "secondary_search";
      }
    }
    if (hasAny(lowerTitle, ["一周内", "一月内", "一年内", "不限"])) return "filter_page";
  }

  if (intent === "academic" && hasAny(lowerTitle, ["chrome", "firefox", "search settings"])) {
    return "academic_noise";
  }

  return null;
}

function tryDecodeBingUrl(url: string): string {
  const parsed = parseUrlSafe(url);
  if (!parsed) return url;
  if (parsed.hostname !== "www.bing.com" || !parsed.pathname.startsWith("/ck/a")) return url;

  const encoded = parsed.searchParams.get("u") || parsed.searchParams.get("url");
  if (!encoded) return url;

  if (/^https?:\/\//i.test(encoded)) return encoded;

  const normalized = encoded.startsWith("a1") ? encoded.slice(2) : encoded;
  try {
    const decodedBase64 = Buffer.from(normalized, "base64").toString("utf8").trim();
    if (/^https?:\/\//i.test(decodedBase64)) return decodedBase64;
  } catch {
    // ignore
  }

  return safeDecodeURIComponent(encoded);
}

function unwrapTrackingUrl(url: string): string {
  const parsed = parseUrlSafe(url);
  if (!parsed) return url;

  if ((parsed.hostname === "duckduckgo.com" || parsed.hostname === "www.duckduckgo.com") && parsed.pathname === "/l/") {
    const uddg = parsed.searchParams.get("uddg") || parsed.searchParams.get("u");
    if (uddg) return safeDecodeURIComponent(uddg);
  }

  if ((parsed.hostname === "duckduckgo.com" || parsed.hostname === "www.duckduckgo.com") && parsed.pathname === "/y.js") {
    const u = parsed.searchParams.get("u") || parsed.searchParams.get("uddg");
    if (u && /^https?:\/\//i.test(u)) return safeDecodeURIComponent(u);
  }

  if ((parsed.hostname === "google.com" || parsed.hostname === "www.google.com") && parsed.pathname === "/url") {
    const q = parsed.searchParams.get("q") || parsed.searchParams.get("url");
    if (q) return safeDecodeURIComponent(q);
  }

  if (parsed.hostname === "www.bing.com" && parsed.pathname.startsWith("/ck/a")) {
    return tryDecodeBingUrl(url);
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
    return normalizeUrlForDedupe(unwrapTrackingUrl("https:" + href));
  }

  if (href.startsWith("http://") || href.startsWith("https://")) {
    return normalizeUrlForDedupe(unwrapTrackingUrl(href));
  }

  if (href.startsWith("/")) {
    if (engine === "google" || engine === "google_scholar") return normalizeUrlForDedupe(unwrapTrackingUrl("https://www.google.com" + href));
    if (engine === "bing") return normalizeUrlForDedupe(unwrapTrackingUrl("https://www.bing.com" + href));
    if (engine === "baidu") return normalizeUrlForDedupe(unwrapTrackingUrl("https://www.baidu.com" + href));
    if (engine === "quark") return normalizeUrlForDedupe(unwrapTrackingUrl("https://quark.sm.cn" + href));
    if (engine === "duckduckgo") return normalizeUrlForDedupe(unwrapTrackingUrl("https://duckduckgo.com" + href));
  }

  return null;
}

function parseGenericSearchHits(html: string, engine: SearchEngineKey, intent: IntentClass, limit: number): SearchParseOutput {
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

    const noiseReason = classifyNoise(engine, intent, title, resolved);
    if (noiseReason) {
      filteredCount += 1;
      pushNoise(noiseCounter, noiseReason);
      match = anchorRegex.exec(html);
      continue;
    }

    if (seen.has(resolved)) {
      filteredCount += 1;
      pushNoise(noiseCounter, "duplicate_url");
      match = anchorRegex.exec(html);
      continue;
    }

    seen.add(resolved);
    hits.push({
      title,
      url: resolved,
      source: engine,
      confidence: 0.78
    });

    if (hits.length >= limit) break;
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

function parseScholarHits(html: string, intent: IntentClass, limit: number): SearchParseOutput {
  const hits: SearchHit[] = [];
  const seen = new Set<string>();
  const noiseCounter: NoiseCounter = {};

  let filteredCount = 0;
  let totalAnchors = 0;

  const h3Regex = /<h3[^>]*class=["'][^"']*gs_rt[^"']*["'][^>]*>([\s\S]*?)<\/h3>/gi;
  let h3Match: RegExpExecArray | null = h3Regex.exec(html);

  while (h3Match) {
    totalAnchors += 1;
    const h3Raw = h3Match[1];
    const linkMatch = /<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/i.exec(h3Raw);

    if (!linkMatch) {
      filteredCount += 1;
      pushNoise(noiseCounter, "scholar_no_anchor");
      h3Match = h3Regex.exec(html);
      continue;
    }

    const resolved = resolveUrl(linkMatch[1], "google_scholar");
    const title = stripHtml(linkMatch[2]);

    if (!resolved || !title) {
      filteredCount += 1;
      pushNoise(noiseCounter, "empty_title_or_url");
      h3Match = h3Regex.exec(html);
      continue;
    }

    const lowerTitle = title.toLowerCase();
    if (hasAny(lowerTitle, ["citations", "alerts", "my profile", "settings", "help"])) {
      filteredCount += 1;
      pushNoise(noiseCounter, "scholar_navigation");
      h3Match = h3Regex.exec(html);
      continue;
    }

    const noiseReason = classifyNoise("google_scholar", intent, title, resolved);
    if (noiseReason) {
      filteredCount += 1;
      pushNoise(noiseCounter, noiseReason);
      h3Match = h3Regex.exec(html);
      continue;
    }

    if (seen.has(resolved)) {
      filteredCount += 1;
      pushNoise(noiseCounter, "duplicate_url");
      h3Match = h3Regex.exec(html);
      continue;
    }

    const near = html.slice(h3Match.index, h3Match.index + 1400);
    const snippetMatch = /<div[^>]*class=["'][^"']*gs_rs[^"']*["'][^>]*>([\s\S]*?)<\/div>/i.exec(near);
    const snippet = snippetMatch ? stripHtml(snippetMatch[1]) : undefined;

    seen.add(resolved);
    hits.push({
      title,
      url: resolved,
      snippet,
      source: "google_scholar",
      confidence: 0.9
    });

    if (hits.length >= limit) break;
    h3Match = h3Regex.exec(html);
  }

  if (hits.length === 0) {
    return parseGenericSearchHits(html, "google_scholar", intent, limit);
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

function resultDomainDiversity(results: SearchHit[]): number {
  const set = new Set<string>();
  for (const item of results) {
    const domain = domainFromUrl(item.url);
    if (domain) set.add(domain);
  }
  return set.size;
}

function resolveQualityLabel(qualityScore: number, count: number, suspectedChallengePage: boolean): "good" | "mixed" | "noisy" | "challenge" {
  if (suspectedChallengePage && count === 0) return "challenge";
  if (qualityScore >= 0.7 && count >= 3) return "good";
  if (qualityScore >= 0.4 && count >= 1) return "mixed";
  return "noisy";
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

async function runSearchInternal(engine: SearchEngineKey, query: string, limit: number, runtime: RuntimeConfig): Promise<SearchRunOutput> {
  const requestUrl = SEARCH_ENGINES[engine](query);
  const startedAt = Date.now();
  const intentClass = classifyIntent(query);

  try {
    const page = await fetchText(requestUrl, runtime.timeoutMs, runtime.userAgent);
    const parsed = engine === "google_scholar"
      ? parseScholarHits(page.body, intentClass, limit)
      : parseGenericSearchHits(page.body, engine, intentClass, limit);

    const qualityScore = providerQualityScore(parsed.hits.length, parsed.filteredCount);
    const suspected = parsed.suspectedChallengePage || isLikelyChallengePage(page.body, page.status);
    const finalQualityLabel = resolveQualityLabel(qualityScore, parsed.hits.length, suspected);

    return {
      ok: true,
      engine,
      query,
      requestUrl,
      status: page.status,
      count: parsed.hits.length,
      filteredCount: parsed.filteredCount,
      noiseReasonsTopN: parsed.noiseReasonsTopN,
      providerQualityScore: qualityScore,
      suspectedChallengePage: suspected,
      tookMs: Date.now() - startedAt,
      results: parsed.hits,
      text: renderHitsText(parsed.hits),
      intentClass,
      finalQualityLabel,
      resultDomainDiversity: resultDomainDiversity(parsed.hits)
    };
  } catch (error) {
    return {
      ok: false,
      engine,
      query,
      requestUrl,
      count: 0,
      filteredCount: 0,
      noiseReasonsTopN: [],
      providerQualityScore: 0,
      suspectedChallengePage: false,
      tookMs: Date.now() - startedAt,
      results: [],
      text: "",
      intentClass,
      finalQualityLabel: "noisy",
      resultDomainDiversity: 0,
      error: String(error)
    };
  }
}

async function runMultiSearchInternal(query: string, limit: number, runtime: RuntimeConfig) {
  const engines: SearchEngineKey[] = ["google", "duckduckgo", "bing", "baidu", "quark"];
  const startedAt = Date.now();
  const intentClass = classifyIntent(query);

  const batches = await Promise.all(engines.map((engine) => runSearchInternal(engine, query, limit, runtime)));

  const scoredProviders = batches.map((item) => {
    const key = item.engine as SearchEngineKey;
    const baseWeight = ENGINE_WEIGHTS[key] || 1;
    const challengePenalty = item.suspectedChallengePage ? 0.5 : 1;
    const providerScore = Math.round(item.providerQualityScore * baseWeight * challengePenalty * 1000) / 1000;
    return {
      ...item,
      providerScore
    };
  });

  scoredProviders.sort((a, b) => b.providerScore - a.providerScore);

  const bestByUrl = new Map<string, SearchHit & { score: number }>();
  for (const provider of scoredProviders) {
    for (let i = 0; i < provider.results.length; i += 1) {
      const result = provider.results[i];
      const normalizedUrl = normalizeUrlForDedupe(result.url);
      const domainBoost = resolveIntentDomainBoost(intentClass, normalizedUrl);
      const rankFactor = Math.max(0.45, 1 - i * 0.08);
      const score = provider.providerScore * domainBoost * rankFactor;
      const boundedScore = Math.max(0.05, Math.min(0.99, score));

      const candidate: SearchHit & { score: number } = {
        ...result,
        url: normalizedUrl,
        confidence: boundedScore,
        score: boundedScore
      };

      const existing = bestByUrl.get(normalizedUrl);
      if (!existing || candidate.score > existing.score) {
        bestByUrl.set(normalizedUrl, candidate);
      }
    }
  }

  const merged = Array.from(bestByUrl.values())
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map(({ score: _score, ...hit }) => hit);

  const mergedNoise = scoredProviders.flatMap((item) => item.noiseReasonsTopN);
  const mergedNoiseCounter: NoiseCounter = {};
  for (const row of mergedNoise) {
    mergedNoiseCounter[row.reason] = (mergedNoiseCounter[row.reason] || 0) + row.count;
  }

  const avgQuality = scoredProviders.length > 0
    ? scoredProviders.reduce((sum, item) => sum + item.providerQualityScore, 0) / scoredProviders.length
    : 0;
  const allChallenge = scoredProviders.length > 0 && scoredProviders.every((item) => item.suspectedChallengePage);
  const finalQualityLabel = resolveQualityLabel(avgQuality, merged.length, allChallenge);

  return {
    ok: true,
    engine: "multi",
    query,
    requestUrl: "multi",
    count: merged.length,
    filteredCount: scoredProviders.reduce((sum, item) => sum + item.filteredCount, 0),
    noiseReasonsTopN: topNoiseReasons(mergedNoiseCounter),
    providerQualityScore: Math.round(avgQuality * 1000) / 1000,
    suspectedChallengePage: allChallenge,
    tookMs: Date.now() - startedAt,
    results: merged,
    text: renderHitsText(merged),
    intentClass,
    finalQualityLabel,
    resultDomainDiversity: resultDomainDiversity(merged),
    providers: scoredProviders.map((item) => ({
      engine: item.engine,
      ok: item.ok,
      count: item.count,
      filteredCount: item.filteredCount,
      tookMs: item.tookMs,
      providerQualityScore: item.providerQualityScore,
      providerScore: item.providerScore,
      suspectedChallengePage: item.suspectedChallengePage,
      finalQualityLabel: item.finalQualityLabel,
      resultDomainDiversity: item.resultDomainDiversity,
      error: item.error
    }))
  };
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
      const result = await runSearchInternal(engine, query, limit, runtime);
      return jsonResult(withMeta(result as unknown as Record<string, unknown>, meta));
    }
  };
}

function createMultiSearchTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "AnyClaw Multi Search",
    name: "anyclaw_multi_search",
    description: "Search with multiple providers and return intent-aware merged results.",
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
      const result = await runMultiSearchInternal(query, limit, runtime);
      return jsonResult(withMeta(result as unknown as Record<string, unknown>, meta));
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
      const suspected = isLikelyChallengePage(page.body, page.status);

      const result = {
        ok: true,
        engine: "web_visit",
        url,
        status: page.status,
        title,
        chars: text.length,
        tookMs: Date.now() - startedAt,
        suspectedChallengePage: suspected,
        finalQualityLabel: suspected ? "challenge" : "good",
        text
      };

      return jsonResult(withMeta(result, meta));
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
        const result = {
          ok: response.ok,
          status: response.status,
          statusText: response.statusText,
          method,
          url,
          tookMs: Date.now() - startedAt,
          finalQualityLabel: response.ok ? "good" : "noisy",
          text
        };

        return jsonResult(withMeta(result, meta));
      } finally {
        clearTimeout(timer);
      }
    }
  };
}

function resolveTavilyApiKey(config: RuntimeConfig): string | null {
  const key = (config.tavilyApiKey || "").trim();
  if (!key) return null;
  return key;
}

function createTavilySearchTool(runtime: RuntimeConfig, meta: PluginRuntimeMeta) {
  return {
    label: "AnyClaw Tavily Search",
    name: "anyclaw_tavily_search",
    description: "Advanced web search powered by Tavily. Requires Tavily API key.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["query"],
      properties: {
        query: {
          type: "string",
          description: "Search query"
        },
        maxResults: {
          type: "number",
          minimum: 1,
          maximum: 10,
          description: "Maximum number of results"
        },
        searchDepth: {
          type: "string",
          description: "basic or advanced"
        }
      }
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const query = readStringParam(args, "query", { required: true });
      const maxResults = clampNumber(
        readNumberParam(args, "maxResults", { integer: true }) ?? runtime.maxResults,
        1,
        10
      );
      const searchDepthRaw = (readStringParam(args, "searchDepth") || "advanced").toLowerCase();
      const searchDepth = searchDepthRaw === "basic" ? "basic" : "advanced";
      const startedAt = Date.now();
      const intentClass = classifyIntent(query);

      const apiKey = resolveTavilyApiKey(runtime);
      if (!apiKey) {
        const result = {
          ok: false,
          engine: "tavily",
          query,
          requestUrl: runtime.tavilyBaseUrl,
          count: 0,
          filteredCount: 0,
          noiseReasonsTopN: [],
          providerQualityScore: 0,
          suspectedChallengePage: false,
          tookMs: Date.now() - startedAt,
          results: [],
          text: "",
          intentClass,
          finalQualityLabel: "noisy",
          resultDomainDiversity: 0,
          error: "missing_tavily_api_key",
          message: "Missing Tavily API key. Configure plugins.entries.anyclaw-search-suite.config.tavilyApiKey."
        };
        return jsonResult(withMeta(result as unknown as Record<string, unknown>, meta));
      }

      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), runtime.timeoutMs);
      try {
        const response = await fetch(runtime.tavilyBaseUrl, {
          method: "POST",
          signal: ctrl.signal,
          headers: {
            "Content-Type": "application/json",
            "User-Agent": runtime.userAgent
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
        });

        const rawText = await response.text();
        if (!response.ok) {
          const result = {
            ok: false,
            engine: "tavily",
            query,
            requestUrl: runtime.tavilyBaseUrl,
            count: 0,
            filteredCount: 0,
            noiseReasonsTopN: [],
            providerQualityScore: 0,
            suspectedChallengePage: false,
            tookMs: Date.now() - startedAt,
            results: [],
            text: "",
            intentClass,
            finalQualityLabel: "noisy",
            resultDomainDiversity: 0,
            error: "tavily_http_" + String(response.status),
            detail: rawText.slice(0, 800)
          };
          return jsonResult(withMeta(result as unknown as Record<string, unknown>, meta));
        }

        const payload = JSON.parse(rawText || "{}");
        const rows = Array.isArray(payload.results) ? payload.results : [];
        const hits: SearchHit[] = [];

        for (let i = 0; i < rows.length && hits.length < maxResults; i += 1) {
          const row = rows[i] && typeof rows[i] === "object" ? (rows[i] as Record<string, unknown>) : {};
          const title = typeof row.title === "string" ? row.title.trim() : "";
          const url = typeof row.url === "string" ? normalizeUrlForDedupe(row.url.trim()) : "";
          const snippet = typeof row.content === "string" ? row.content.trim() : "";
          const scoreRaw = typeof row.score === "number" ? row.score : 0.8;
          const confidence = Math.max(0.2, Math.min(0.99, scoreRaw));

          if (!title || !url) continue;
          hits.push({
            title,
            url,
            snippet,
            source: "tavily",
            confidence
          });
        }

        const quality = hits.length > 0 ? 0.95 : 0;
        const result = {
          ok: true,
          engine: "tavily",
          query,
          requestUrl: runtime.tavilyBaseUrl,
          count: hits.length,
          filteredCount: 0,
          noiseReasonsTopN: [],
          providerQualityScore: quality,
          suspectedChallengePage: false,
          tookMs: Date.now() - startedAt,
          results: hits,
          text: renderHitsText(hits),
          answer: typeof payload.answer === "string" ? payload.answer : "",
          intentClass,
          finalQualityLabel: hits.length >= 3 ? "good" : hits.length > 0 ? "mixed" : "noisy",
          resultDomainDiversity: resultDomainDiversity(hits)
        };

        return jsonResult(withMeta(result as unknown as Record<string, unknown>, meta));
      } catch (error) {
        const result = {
          ok: false,
          engine: "tavily",
          query,
          requestUrl: runtime.tavilyBaseUrl,
          count: 0,
          filteredCount: 0,
          noiseReasonsTopN: [],
          providerQualityScore: 0,
          suspectedChallengePage: false,
          tookMs: Date.now() - startedAt,
          results: [],
          text: "",
          intentClass,
          finalQualityLabel: "noisy",
          resultDomainDiversity: 0,
          error: String(error)
        };
        return jsonResult(withMeta(result as unknown as Record<string, unknown>, meta));
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

  const tavilyApiKey = typeof cfg.tavilyApiKey === "string" && cfg.tavilyApiKey.trim()
    ? cfg.tavilyApiKey.trim()
    : undefined;

  const tavilyBaseUrl = typeof cfg.tavilyBaseUrl === "string" && cfg.tavilyBaseUrl.trim()
    ? cfg.tavilyBaseUrl.trim()
    : DEFAULT_TAVILY_BASE_URL;

  return {
    timeoutMs,
    maxResults,
    maxChars,
    userAgent,
    tavilyApiKey,
    tavilyBaseUrl
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

    api.registerTool(createSearchTool("google", "Google Search", "Search web pages with Google.", runtime, runtimeMeta));
    api.registerTool(createSearchTool("google_scholar", "Google Scholar Search", "Search academic publications with Google Scholar.", runtime, runtimeMeta));
    api.registerTool(createSearchTool("duckduckgo", "DuckDuckGo Search", "Search web pages with DuckDuckGo.", runtime, runtimeMeta));
    api.registerTool(createSearchTool("bing", "Bing Search", "Search web pages with Bing.", runtime, runtimeMeta));
    api.registerTool(createSearchTool("baidu", "Baidu Search", "Search web pages with Baidu.", runtime, runtimeMeta));
    api.registerTool(createSearchTool("quark", "Quark Search", "Search web pages with Quark.", runtime, runtimeMeta));

    api.registerTool(createMultiSearchTool(runtime, runtimeMeta));
    api.registerTool(createVisitTool(runtime, runtimeMeta));
    api.registerTool(createHttpTool(runtime, runtimeMeta));
    api.registerTool(createTavilySearchTool(runtime, runtimeMeta));
  }
};
