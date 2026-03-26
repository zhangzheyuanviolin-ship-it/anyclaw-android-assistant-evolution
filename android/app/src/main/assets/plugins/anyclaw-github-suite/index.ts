import { jsonResult, readNumberParam, readStringParam } from "openclaw/plugin-sdk";
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, isAbsolute, resolve } from "node:path";

type RuntimeConfig = {
  githubApiBaseUrl: string;
  githubToken?: string;
  timeoutMs: number;
  userAgent: string;
  allowTerminal: boolean;
  terminalTimeoutMs: number;
  workspaceRoot: string;
};

const DEFAULT_TIMEOUT_MS = 35000;
const DEFAULT_TERMINAL_TIMEOUT_MS = 30000;
const DEFAULT_GITHUB_API = "https://api.github.com";
const DEFAULT_USER_AGENT = "AnyClawGithubSuite/1.0";
const DEFAULT_WORKSPACE_ROOT = "/tmp";

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

function parseRepo(repoRaw: string): { owner: string; repo: string } {
  const value = repoRaw.trim();
  const idx = value.indexOf("/");
  if (idx <= 0 || idx >= value.length - 1) {
    throw new Error("repo must be in owner/repo format");
  }
  const owner = value.slice(0, idx).trim();
  const repo = value.slice(idx + 1).trim();
  if (!owner || !repo) {
    throw new Error("repo must be in owner/repo format");
  }
  return { owner, repo };
}

function parseJsonObject(value: string | undefined): Record<string, unknown> {
  if (!value || !value.trim()) return {};
  const parsed = JSON.parse(value);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return {};
  }
  return parsed as Record<string, unknown>;
}

function parseJsonStringArray(value: string | undefined): string[] {
  if (!value || !value.trim()) return [];
  const parsed = JSON.parse(value);
  if (!Array.isArray(parsed)) return [];
  return parsed.map((item) => String(item));
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

function resolveToken(args: Record<string, unknown>, runtime: RuntimeConfig): string | null {
  const tokenFromArgs = readStringParam(args, "token")?.trim() || "";
  if (tokenFromArgs) return tokenFromArgs;
  const tokenFromConfig = (runtime.githubToken || "").trim();
  if (tokenFromConfig) return tokenFromConfig;
  const tokenFromEnv = String(process.env.GITHUB_TOKEN || process.env.GH_TOKEN || "").trim();
  if (tokenFromEnv) return tokenFromEnv;
  return null;
}

function ensureEndpoint(pathOrUrl: string): string {
  const trimmed = pathOrUrl.trim();
  if (!trimmed) return "/";
  if (/^https?:\/\//i.test(trimmed)) {
    const url = new URL(trimmed);
    return url.pathname + (url.search || "");
  }
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

async function githubRequest(
  runtime: RuntimeConfig,
  method: string,
  endpoint: string,
  token: string,
  body?: unknown,
): Promise<{ ok: boolean; status: number; url: string; data: unknown }> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), runtime.timeoutMs);
  const url = runtime.githubApiBaseUrl.replace(/\/$/g, "") + ensureEndpoint(endpoint);

  try {
    const response = await fetch(url, {
      method,
      signal: ctrl.signal,
      headers: {
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "Authorization": `Bearer ${token}`,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": runtime.userAgent,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    const raw = await response.text();
    let data: unknown = raw;
    try {
      data = raw ? JSON.parse(raw) : {};
    } catch {
      data = raw;
    }

    return {
      ok: response.ok,
      status: response.status,
      url,
      data,
    };
  } finally {
    clearTimeout(timer);
  }
}

function resolveSafePath(targetPath: string, runtime: RuntimeConfig): string {
  const root = resolve(runtime.workspaceRoot || DEFAULT_WORKSPACE_ROOT);
  const finalPath = isAbsolute(targetPath)
    ? resolve(targetPath)
    : resolve(root, targetPath);
  if (!(finalPath === root || finalPath.startsWith(root + "/"))) {
    throw new Error("path is outside workspaceRoot");
  }
  return finalPath;
}

function createApplyFileTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Apply File",
    name: "anyclaw_apply_file",
    description: "Apply local file changes by replace/append/prepend/find_replace within workspace root.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["path", "mode"],
      properties: {
        path: { type: "string" },
        mode: { type: "string", description: "replace|append|prepend|find_replace" },
        content: { type: "string" },
        find: { type: "string" },
        replace: { type: "string" },
        createDirs: { type: "boolean" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const pathRaw = readStringParam(args, "path", { required: true });
      const mode = (readStringParam(args, "mode", { required: true }) || "").trim();
      const content = readStringParam(args, "content", { allowEmpty: true }) || "";
      const findText = readStringParam(args, "find", { allowEmpty: true }) || "";
      const replaceText = readStringParam(args, "replace", { allowEmpty: true }) || "";
      const createDirs = args.createDirs === true || String(args.createDirs || "").toLowerCase() === "true";

      const fullPath = resolveSafePath(pathRaw, runtime);
      const exists = existsSync(fullPath);
      if (!exists && mode === "find_replace") {
        return jsonResult({ ok: false, error: "target_file_not_found", path: fullPath });
      }
      if (!exists && !createDirs) {
        return jsonResult({ ok: false, error: "target_file_not_found_enable_createDirs", path: fullPath });
      }
      if (createDirs) {
        mkdirSync(dirname(fullPath), { recursive: true });
      }

      const before = exists ? readFileSync(fullPath, "utf8") : "";
      let after = before;

      if (mode === "replace") {
        after = content;
      } else if (mode === "append") {
        after = before + content;
      } else if (mode === "prepend") {
        after = content + before;
      } else if (mode === "find_replace") {
        if (!findText) {
          return jsonResult({ ok: false, error: "find_is_required_for_find_replace", path: fullPath });
        }
        if (!before.includes(findText)) {
          return jsonResult({ ok: false, error: "find_text_not_found", path: fullPath });
        }
        after = before.replace(findText, replaceText);
      } else {
        return jsonResult({ ok: false, error: "unsupported_mode", mode });
      }

      writeFileSync(fullPath, after, "utf8");
      return jsonResult({
        ok: true,
        path: fullPath,
        mode,
        bytesBefore: Buffer.byteLength(before, "utf8"),
        bytesAfter: Buffer.byteLength(after, "utf8"),
        changed: before !== after,
      });
    },
  };
}

function createTerminalTool(runtime: RuntimeConfig) {
  return {
    label: "AnyClaw Terminal",
    name: "anyclaw_terminal",
    description: "Execute local shell command with timeout and bounded output.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["command"],
      properties: {
        command: { type: "string" },
        cwd: { type: "string" },
        timeoutMs: { type: "number", minimum: 1000, maximum: 120000 },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      if (!runtime.allowTerminal) {
        return jsonResult({ ok: false, error: "terminal_disabled_by_config" });
      }
      const args = asObject(input);
      const command = readStringParam(args, "command", { required: true });
      const cwdRaw = readStringParam(args, "cwd") || runtime.workspaceRoot;
      const cwd = resolveSafePath(cwdRaw, runtime);
      const timeoutMs = clampNumber(
        readNumberParam(args, "timeoutMs", { integer: true }) ?? runtime.terminalTimeoutMs,
        1000,
        120000,
      );

      const result = await new Promise<{ ok: boolean; code: number; stdout: string; stderr: string; error?: string }>((resolveResult) => {
        let stdout = "";
        let stderr = "";
        let done = false;

        const child = spawn("sh", ["-lc", command], {
          cwd,
          stdio: ["ignore", "pipe", "pipe"],
        });

        const timer = setTimeout(() => {
          if (done) return;
          done = true;
          try {
            child.kill("SIGKILL");
          } catch {
            // ignore
          }
          resolveResult({
            ok: false,
            code: -1,
            stdout: stripInternalNoise(stdout).slice(0, 12000),
            stderr: stripInternalNoise(stderr).slice(0, 12000),
            error: "terminal_timeout",
          });
        }, timeoutMs);

        child.stdout.on("data", (chunk) => {
          stdout += String(chunk || "");
        });
        child.stderr.on("data", (chunk) => {
          stderr += String(chunk || "");
        });
        child.on("error", (error) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          resolveResult({
            ok: false,
            code: -1,
            stdout: stripInternalNoise(stdout).slice(0, 12000),
            stderr: stripInternalNoise(stderr).slice(0, 12000),
            error: String(error),
          });
        });
        child.on("close", (code) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          resolveResult({
            ok: code === 0,
            code: typeof code === "number" ? code : -1,
            stdout: stripInternalNoise(stdout).slice(0, 12000),
            stderr: stripInternalNoise(stderr).slice(0, 12000),
          });
        });
      });

      return jsonResult({
        ok: result.ok,
        exitCode: result.code,
        stdout: result.stdout,
        stderr: result.stderr,
        error: result.error,
        cwd,
      });
    },
  };
}

function resolveRuntimeConfig(rawConfig: unknown): RuntimeConfig {
  const cfg = asObject(rawConfig);
  const timeoutSecondsRaw = typeof cfg.timeoutSeconds === "number" ? cfg.timeoutSeconds : DEFAULT_TIMEOUT_MS / 1000;
  const timeoutMs = clampNumber(timeoutSecondsRaw, 5, 120) * 1000;

  const githubApiBaseUrl = typeof cfg.githubApiBaseUrl === "string" && cfg.githubApiBaseUrl.trim()
    ? cfg.githubApiBaseUrl.trim()
    : DEFAULT_GITHUB_API;

  const githubToken = typeof cfg.githubToken === "string" && cfg.githubToken.trim()
    ? cfg.githubToken.trim()
    : undefined;

  const userAgent = typeof cfg.userAgent === "string" && cfg.userAgent.trim()
    ? cfg.userAgent.trim()
    : DEFAULT_USER_AGENT;

  const allowTerminal = cfg.allowTerminal !== false;

  const terminalTimeoutMs = clampNumber(
    typeof cfg.terminalTimeoutMs === "number" ? cfg.terminalTimeoutMs : DEFAULT_TERMINAL_TIMEOUT_MS,
    1000,
    120000,
  );

  const workspaceRoot = typeof cfg.workspaceRoot === "string" && cfg.workspaceRoot.trim()
    ? cfg.workspaceRoot.trim()
    : (process.env.HOME ? `${process.env.HOME}/.openclaw/workspace` : DEFAULT_WORKSPACE_ROOT);

  return {
    githubApiBaseUrl,
    githubToken,
    timeoutMs,
    userAgent,
    allowTerminal,
    terminalTimeoutMs,
    workspaceRoot,
  };
}

function githubResult(response: { ok: boolean; status: number; url: string; data: unknown }, action: string): Record<string, unknown> {
  return {
    ok: response.ok,
    action,
    status: response.status,
    url: response.url,
    data: response.data,
  };
}

function createGithubRepoInfoTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub Repo Info",
    name: "anyclaw_github_repo_info",
    description: "Get repository metadata via GitHub REST API.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "GET", `/repos/${owner}/${repo}`, token);
      return jsonResult(githubResult(response, "repo_info"));
    },
  };
}

function createGithubListIssuesTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub List Issues",
    name: "anyclaw_github_list_issues",
    description: "List issues for a repository.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        state: { type: "string", description: "open|closed|all" },
        perPage: { type: "number", minimum: 1, maximum: 100 },
        page: { type: "number", minimum: 1, maximum: 1000 },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const state = (readStringParam(args, "state") || "open").trim() || "open";
      const perPage = clampNumber(readNumberParam(args, "perPage", { integer: true }) ?? 30, 1, 100);
      const page = clampNumber(readNumberParam(args, "page", { integer: true }) ?? 1, 1, 1000);
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "GET", `/repos/${owner}/${repo}/issues?state=${encodeURIComponent(state)}&per_page=${perPage}&page=${page}`, token);
      return jsonResult(githubResult(response, "list_issues"));
    },
  };
}

function createGithubCreateIssueTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub Create Issue",
    name: "anyclaw_github_create_issue",
    description: "Create an issue in a repository.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo", "title"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        title: { type: "string" },
        body: { type: "string" },
        labelsJson: { type: "string", description: "JSON array of labels" },
        assigneesJson: { type: "string", description: "JSON array of assignees" },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const title = readStringParam(args, "title", { required: true });
      const body = readStringParam(args, "body") || "";
      const labels = parseJsonStringArray(readStringParam(args, "labelsJson"));
      const assignees = parseJsonStringArray(readStringParam(args, "assigneesJson"));
      const payload: Record<string, unknown> = { title, body };
      if (labels.length > 0) payload.labels = labels;
      if (assignees.length > 0) payload.assignees = assignees;
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "POST", `/repos/${owner}/${repo}/issues`, token, payload);
      return jsonResult(githubResult(response, "create_issue"));
    },
  };
}

function createGithubListPrsTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub List PRs",
    name: "anyclaw_github_list_prs",
    description: "List pull requests for a repository.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        state: { type: "string", description: "open|closed|all" },
        perPage: { type: "number", minimum: 1, maximum: 100 },
        page: { type: "number", minimum: 1, maximum: 1000 },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const state = (readStringParam(args, "state") || "open").trim() || "open";
      const perPage = clampNumber(readNumberParam(args, "perPage", { integer: true }) ?? 30, 1, 100);
      const page = clampNumber(readNumberParam(args, "page", { integer: true }) ?? 1, 1, 1000);
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "GET", `/repos/${owner}/${repo}/pulls?state=${encodeURIComponent(state)}&per_page=${perPage}&page=${page}`, token);
      return jsonResult(githubResult(response, "list_prs"));
    },
  };
}

function createGithubCreatePrTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub Create PR",
    name: "anyclaw_github_create_pr",
    description: "Create a pull request.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo", "title", "head", "base"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        title: { type: "string" },
        head: { type: "string" },
        base: { type: "string" },
        body: { type: "string" },
        draft: { type: "boolean" },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const payload: Record<string, unknown> = {
        title: readStringParam(args, "title", { required: true }),
        head: readStringParam(args, "head", { required: true }),
        base: readStringParam(args, "base", { required: true }),
      };
      const body = readStringParam(args, "body");
      if (body !== undefined) payload.body = body;
      if (args.draft === true || String(args.draft || "").toLowerCase() === "true") payload.draft = true;
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "POST", `/repos/${owner}/${repo}/pulls`, token, payload);
      return jsonResult(githubResult(response, "create_pr"));
    },
  };
}

function createGithubListBranchesTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub List Branches",
    name: "anyclaw_github_list_branches",
    description: "List repository branches.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        perPage: { type: "number", minimum: 1, maximum: 100 },
        page: { type: "number", minimum: 1, maximum: 1000 },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const perPage = clampNumber(readNumberParam(args, "perPage", { integer: true }) ?? 30, 1, 100);
      const page = clampNumber(readNumberParam(args, "page", { integer: true }) ?? 1, 1, 1000);
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "GET", `/repos/${owner}/${repo}/branches?per_page=${perPage}&page=${page}`, token);
      return jsonResult(githubResult(response, "list_branches"));
    },
  };
}

function createGithubCompareTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub Compare Commits",
    name: "anyclaw_github_compare_commits",
    description: "Compare two refs in a repository.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo", "base", "head"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        base: { type: "string" },
        head: { type: "string" },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const base = readStringParam(args, "base", { required: true });
      const head = readStringParam(args, "head", { required: true });
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "GET", `/repos/${owner}/${repo}/compare/${encodeURIComponent(base)}...${encodeURIComponent(head)}`, token);
      return jsonResult(githubResult(response, "compare_commits"));
    },
  };
}

function createGithubGetCommitTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub Get Commit",
    name: "anyclaw_github_get_commit",
    description: "Get commit metadata and file changes.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo", "sha"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        sha: { type: "string" },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const sha = readStringParam(args, "sha", { required: true });
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "GET", `/repos/${owner}/${repo}/commits/${encodeURIComponent(sha)}`, token);
      return jsonResult(githubResult(response, "get_commit"));
    },
  };
}

function createGithubGetFileTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub Get File",
    name: "anyclaw_github_get_file",
    description: "Fetch repository file content via REST contents API.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo", "path"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        path: { type: "string" },
        ref: { type: "string" },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const filePath = readStringParam(args, "path", { required: true });
      const ref = readStringParam(args, "ref");
      const { owner, repo } = parseRepo(repoRaw);
      const refQuery = ref ? `?ref=${encodeURIComponent(ref)}` : "";
      const response = await githubRequest(runtime, "GET", `/repos/${owner}/${repo}/contents/${filePath.replace(/^\/+/, "")}${refQuery}`, token);

      let decodedContent: string | undefined;
      const payload = asObject(response.data);
      const encoding = typeof payload.encoding === "string" ? payload.encoding : "";
      const content = typeof payload.content === "string" ? payload.content : "";
      if (encoding === "base64" && content) {
        try {
          decodedContent = Buffer.from(content.replace(/\n/g, ""), "base64").toString("utf8");
        } catch {
          decodedContent = undefined;
        }
      }

      return jsonResult({
        ...githubResult(response, "get_file"),
        decodedContent,
      });
    },
  };
}

function createGithubPutFileTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub Upsert File",
    name: "anyclaw_github_upsert_file",
    description: "Create or update a repository file and commit.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["repo", "path", "content", "message"],
      properties: {
        repo: { type: "string", description: "owner/repo" },
        path: { type: "string" },
        content: { type: "string" },
        message: { type: "string" },
        branch: { type: "string" },
        sha: { type: "string", description: "required for update if known" },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const repoRaw = readStringParam(args, "repo", { required: true });
      const filePath = readStringParam(args, "path", { required: true });
      const content = readStringParam(args, "content", { required: true });
      const message = readStringParam(args, "message", { required: true });
      const branch = readStringParam(args, "branch");
      const sha = readStringParam(args, "sha");
      const payload: Record<string, unknown> = {
        message,
        content: Buffer.from(content, "utf8").toString("base64"),
      };
      if (branch && branch.trim()) payload.branch = branch.trim();
      if (sha && sha.trim()) payload.sha = sha.trim();
      const { owner, repo } = parseRepo(repoRaw);
      const response = await githubRequest(runtime, "PUT", `/repos/${owner}/${repo}/contents/${filePath.replace(/^\/+/, "")}`, token, payload);
      return jsonResult(githubResult(response, "upsert_file"));
    },
  };
}

function createGithubRestCallTool(runtime: RuntimeConfig) {
  return {
    label: "GitHub REST Call",
    name: "anyclaw_github_rest_call",
    description: "Generic GitHub REST call for advanced scenarios.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["method", "path"],
      properties: {
        method: { type: "string" },
        path: { type: "string", description: "API path like /repos/owner/repo" },
        bodyJson: { type: "string", description: "Optional JSON object string" },
        queryJson: { type: "string", description: "Optional query object as JSON" },
        token: { type: "string" },
      },
    },
    execute: async (_toolCallId: string, input: unknown) => {
      const args = asObject(input);
      const token = resolveToken(args, runtime);
      if (!token) return jsonResult({ ok: false, error: "missing_github_token" });
      const method = (readStringParam(args, "method", { required: true }) || "GET").toUpperCase();
      const path = readStringParam(args, "path", { required: true });
      const query = parseJsonObject(readStringParam(args, "queryJson"));
      const body = parseJsonObject(readStringParam(args, "bodyJson"));
      const queryStr = new URLSearchParams(
        Object.entries(query).reduce((acc, [k, v]) => {
          acc[k] = String(v);
          return acc;
        }, {} as Record<string, string>),
      ).toString();
      const endpoint = queryStr ? `${path}${path.includes("?") ? "&" : "?"}${queryStr}` : path;
      const response = await githubRequest(runtime, method, endpoint, token, Object.keys(body).length > 0 ? body : undefined);
      return jsonResult(githubResult(response, "rest_call"));
    },
  };
}

export default {
  id: "anyclaw-github-suite",
  name: "AnyClaw GitHub Suite",
  register(api: any) {
    const runtime = resolveRuntimeConfig(api.pluginConfig);
    if (api.logger && api.logger.info) {
      api.logger.info("anyclaw-github-suite loaded");
    }

    api.registerTool(createGithubRepoInfoTool(runtime));
    api.registerTool(createGithubListIssuesTool(runtime));
    api.registerTool(createGithubCreateIssueTool(runtime));
    api.registerTool(createGithubListPrsTool(runtime));
    api.registerTool(createGithubCreatePrTool(runtime));
    api.registerTool(createGithubListBranchesTool(runtime));
    api.registerTool(createGithubCompareTool(runtime));
    api.registerTool(createGithubGetCommitTool(runtime));
    api.registerTool(createGithubGetFileTool(runtime));
    api.registerTool(createGithubPutFileTool(runtime));
    api.registerTool(createGithubRestCallTool(runtime));
    api.registerTool(createApplyFileTool(runtime));
    api.registerTool(createTerminalTool(runtime));
  },
};
