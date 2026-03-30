import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { accessSync, constants as fsConstants, createWriteStream } from 'node:fs'
import { mkdtemp, mkdir, readFile, unlink, writeFile } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'

const prefixBin = process.env.PREFIX ? join(process.env.PREFIX, 'bin') : ''
const shellPath = prefixBin ? join(prefixBin, 'sh') : '/bin/sh'
const homeDir = process.env.HOME ?? ''
const promptInjectionPath = homeDir ? join(homeDir, '.openclaw-android', 'state', 'prompt-injection.json') : ''
const shizukuStatusPath = homeDir ? join(homeDir, '.openclaw-android', 'capabilities', 'shizuku.json') : ''
const offlineLinuxRuntimePath = homeDir
  ? join(homeDir, '.openclaw-android', 'state', 'offline-linux-runtime.json')
  : ''
const runtimeHealthPath = homeDir ? join(homeDir, '.openclaw-android', 'state', 'runtime-health.json') : ''
const OPENCLAW_UPLOAD_DIR = homeDir
  ? join(homeDir, '.openclaw', 'workspace', 'uploads')
  : join(process.cwd(), '.openclaw', 'workspace', 'uploads')
const OPENCLAW_UPLOAD_MAX_BYTES = 1_000_000_000
const OPENCLAW_UPLOAD_STREAM_MAX_BYTES = 1_000_000_000
const OPENCLAW_CONFIG_PATH = homeDir
  ? join(homeDir, '.openclaw', 'openclaw.json')
  : join(process.cwd(), '.openclaw', 'openclaw.json')
const OPENCLAW_WORKSPACE_DIR = homeDir
  ? join(homeDir, '.openclaw', 'workspace')
  : join(process.cwd(), '.openclaw', 'workspace')
const LIGHTWEIGHT_STATE_PATH = homeDir
  ? join(homeDir, '.openclaw-android', 'state', 'lightweight-openclaw-sessions.json')
  : join(process.cwd(), '.openclaw-android', 'state', 'lightweight-openclaw-sessions.json')
const LIGHTWEIGHT_MAX_CONTEXT_MESSAGES = 80
const LIGHTWEIGHT_MAX_TOOL_STEPS = 32
const LIGHTWEIGHT_COMMAND_TIMEOUT_MS = 180_000
const LIGHTWEIGHT_OUTPUT_LIMIT = 120_000
const LIGHTWEIGHT_BOOTSTRAP_TARGET_VERSION = '2026.3.2'
const LIGHTWEIGHT_DOC_MAX_CHARS = 4_000
const OPENCLAW_NATIVE_READY_CACHE_MS = 6_000
const OPENCLAW_GATEWAY_CALL_TIMEOUT_MS = 90_000
const OPENCLAW_HISTORY_CALL_TIMEOUT_MS = 75_000
const OPENCLAW_CHAT_SEND_TIMEOUT_MS = 45_000
const OPENCLAW_RUN_WAIT_TIMEOUT_MS = 12_000
const OPENCLAW_GATEWAY_CALL_MAX_RETRIES = 4
const OPENCLAW_GATEWAY_RETRY_BACKOFF_MS = [300, 700, 1200, 1800]
const OPENCLAW_NATIVE_STRICT_MODE = true
const OPENCLAW_RUN_CONTEXT_TTL_MS = 6 * 60 * 60_000
const OPENCLAW_RUN_CONTEXT_MAX = 400
const OPENCLAW_HEARTBEAT_JOB_NAME = 'anyclaw-heartbeat-main'
const OPENCLAW_HEARTBEAT_PROMPT = 'Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. Do not infer or repeat old tasks from prior chats. If nothing needs attention, reply HEARTBEAT_OK.'

type JsonRpcCall = {
  jsonrpc: '2.0'
  id: number
  method: string
  params?: unknown
}

type JsonRpcResponse = {
  id?: number
  result?: unknown
  error?: {
    code: number
    message: string
  }
  method?: string
  params?: unknown
}

type RpcProxyRequest = {
  method: string
  params?: unknown
}

type ServerRequestReply = {
  result?: unknown
  error?: {
    code: number
    message: string
  }
}

type PendingServerRequest = {
  id: number
  method: string
  params: unknown
  receivedAtIso: string
}

type LightweightContentItem = {
  type: string
  text?: string
  thinking?: string
  name?: string
  arguments?: unknown
}

type LightweightHistoryMessage = {
  role: string
  timestamp: number
  content: LightweightContentItem[]
  toolName?: string
  isError?: boolean
}

type LightweightSession = {
  key: string
  title: string
  updatedAt: number
  lastMessagePreview: string
  modelProvider: string
  model: string
  messages: LightweightHistoryMessage[]
}

type LightweightState = {
  sessions: LightweightSession[]
}

type LightweightModelConfig = {
  modelId: string
  modelName: string
  providerName: string
  baseUrl: string
  apiKey: string
}

type OpenClawNativeRunContext = {
  runId: string
  sessionKey: string
  sentAtMs: number
  lastStatus: string
  lastError: string
  updatedAtMs: number
}

let openClawNativeReadyCacheValue: boolean | null = null
let openClawNativeReadyCacheAtMs = 0
const openClawNativeRuns = new Map<string, OpenClawNativeRunContext>()

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

function getErrorMessage(payload: unknown, fallback: string): string {
  if (payload instanceof Error && payload.message.trim().length > 0) {
    return payload.message
  }

  const record = asRecord(payload)
  if (!record) return fallback

  const error = record.error
  if (typeof error === 'string' && error.length > 0) return error

  const nestedError = asRecord(error)
  if (nestedError && typeof nestedError.message === 'string' && nestedError.message.length > 0) {
    return nestedError.message
  }

  return fallback
}

function setJson(res: ServerResponse, statusCode: number, payload: unknown): void {
  res.statusCode = statusCode
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(payload))
}

async function readJsonBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Uint8Array[] = []

  for await (const chunk of req) {
    chunks.push(typeof chunk === 'string' ? Buffer.from(chunk) : chunk)
  }

  if (chunks.length === 0) return null

  const raw = Buffer.concat(chunks).toString('utf8').trim()
  if (raw.length === 0) return null

  return JSON.parse(raw) as unknown
}

async function readJsonFile(path: string): Promise<Record<string, unknown> | null> {
  if (!path) return null
  try {
    const raw = await readFile(path, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    return asRecord(parsed)
  } catch {
    return null
  }
}

function normalizeText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/gu, `'\"'\"'`)}'`
}

function readHeaderText(value: string | string[] | undefined): string {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0].trim() : ''
  }
  return typeof value === 'string' ? value.trim() : ''
}

function clampInt(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min
  if (value < min) return min
  if (value > max) return max
  return Math.floor(value)
}

function normalizeTimeoutMs(value: unknown, fallback: number, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return clampInt(fallback, min, max)
  }
  return clampInt(value, min, max)
}

function sleepMs(delayMs: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, delayMs)))
}

function rememberOpenClawNativeReady(value: boolean): void {
  openClawNativeReadyCacheValue = value
  openClawNativeReadyCacheAtMs = Date.now()
}

function invalidateOpenClawNativeReadyCache(): void {
  openClawNativeReadyCacheValue = null
  openClawNativeReadyCacheAtMs = 0
}

function isOpenClawGatewayRetryableError(message: string): boolean {
  const normalized = message.toLowerCase()
  return normalized.includes('gateway closed (1006') ||
    normalized.includes('abnormal closure') ||
    normalized.includes('connection is not open') ||
    normalized.includes('openclaw gateway call timeout') ||
    normalized.includes('timed out') ||
    normalized.includes('econnreset') ||
    normalized.includes('socket hang up') ||
    normalized.includes('no json payload found') ||
    normalized.includes('empty gateway response')
}

function tryParseJsonPayload(raw: string): boolean {
  try {
    JSON.parse(raw)
    return true
  } catch {
    return false
  }
}

function extractBalancedJsonSegments(raw: string): string[] {
  const segments: string[] = []
  for (let start = 0; start < raw.length; start += 1) {
    const first = raw[start]
    if (first !== '{' && first !== '[') continue
    const stack: string[] = [first === '{' ? '}' : ']']
    let inString = false
    let escaped = false
    for (let index = start + 1; index < raw.length; index += 1) {
      const char = raw[index]
      if (inString) {
        if (escaped) {
          escaped = false
          continue
        }
        if (char === '\\') {
          escaped = true
          continue
        }
        if (char === '"') {
          inString = false
        }
        continue
      }
      if (char === '"') {
        inString = true
        continue
      }
      if (char === '{') {
        stack.push('}')
        continue
      }
      if (char === '[') {
        stack.push(']')
        continue
      }
      const expected = stack[stack.length - 1]
      if ((char === '}' || char === ']') && char === expected) {
        stack.pop()
        if (stack.length === 0) {
          const segment = raw.slice(start, index + 1).trim()
          if (segment.length > 1) {
            segments.push(segment)
          }
          break
        }
      }
    }
  }
  return segments
}

function extractJsonPayload(raw: string): string {
  const trimmed = raw.trim()
  if (!trimmed) {
    throw new Error('Empty gateway response')
  }
  if (tryParseJsonPayload(trimmed)) {
    return trimmed
  }

  const lineCandidates: string[] = []
  const lines = trimmed.split(/\r?\n/)
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index].trimStart()
    if (!line.startsWith('{') && !line.startsWith('[')) continue
    const candidate = lines.slice(index).join('\n').trim()
    if (candidate) lineCandidates.push(candidate)
  }

  const balancedCandidates = extractBalancedJsonSegments(trimmed)
  for (const candidate of [...lineCandidates, ...balancedCandidates]) {
    if (tryParseJsonPayload(candidate)) {
      return candidate
    }
  }

  throw new Error('No JSON payload found in gateway response')
}

async function runOpenClawGatewayCall(
  method: string,
  params: unknown,
  timeoutMs = OPENCLAW_GATEWAY_CALL_TIMEOUT_MS,
): Promise<unknown> {
  const normalizedMethod = method.trim()
  if (!/^[a-zA-Z0-9._/-]+$/u.test(normalizedMethod)) {
    throw new Error(`Invalid OpenClaw gateway method: ${method}`)
  }

  const serializedParams = JSON.stringify(params ?? {})
  const command =
    `openclaw gateway call ${normalizedMethod} --json --params ${shellQuote(serializedParams)}`

  const runCommandOnce = () =>
    new Promise<string>((resolve, reject) => {
      const env = { ...process.env }
      if (prefixBin) {
        const currentPath = typeof env.PATH === 'string' ? env.PATH : ''
        if (!currentPath.split(':').includes(prefixBin)) {
          env.PATH = currentPath.length > 0 ? `${prefixBin}:${currentPath}` : prefixBin
        }
      }

      const child = spawn(shellPath, ['-c', command], {
        stdio: ['ignore', 'pipe', 'pipe'],
        env,
        cwd: homeDir || process.cwd(),
      })

      let stdoutBuffer = ''
      let stderrBuffer = ''
      let settled = false
      child.stdout.setEncoding('utf8')
      child.stderr.setEncoding('utf8')
      const timer = setTimeout(() => {
        if (settled) return
        settled = true
        try {
          child.kill('SIGKILL')
        } catch {
          // ignore kill errors
        }
        reject(new Error(`OpenClaw gateway call timeout: ${normalizedMethod} (${timeoutMs}ms)`))
      }, timeoutMs)

      child.stdout.on('data', (chunk: string) => {
        stdoutBuffer += chunk
      })
      child.stderr.on('data', (chunk: string) => {
        stderrBuffer += chunk
      })

      child.on('error', (error) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        reject(error)
      })
      child.on('exit', (code) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        if (code === 0) {
          const output = stdoutBuffer.trim().length > 0 ? stdoutBuffer : stderrBuffer
          resolve(output)
          return
        }
        const errorText = stderrBuffer.trim() || stdoutBuffer.trim() || `OpenClaw gateway call failed: ${normalizedMethod}`
        reject(new Error(errorText))
      })
    })

  let lastError: unknown = null
  for (let attempt = 0; attempt < OPENCLAW_GATEWAY_CALL_MAX_RETRIES; attempt += 1) {
    try {
      const output = await runCommandOnce()
      const payload = extractJsonPayload(output)
      return JSON.parse(payload) as unknown
    } catch (error) {
      lastError = error
      const message = getErrorMessage(error, '')
      const retryable = isOpenClawGatewayRetryableError(message)
      if (!retryable || attempt >= OPENCLAW_GATEWAY_CALL_MAX_RETRIES - 1) {
        throw error
      }
      invalidateOpenClawNativeReadyCache()
      const delayMs = OPENCLAW_GATEWAY_RETRY_BACKOFF_MS[Math.min(attempt, OPENCLAW_GATEWAY_RETRY_BACKOFF_MS.length - 1)]
      await sleepMs(delayMs)
    }
  }

  throw (lastError instanceof Error ? lastError : new Error(`OpenClaw gateway call failed: ${normalizedMethod}`))
}

async function tryRunOpenClawGatewayCall(method: string, params: unknown): Promise<unknown | null> {
  try {
    return await runOpenClawGatewayCall(method, params)
  } catch {
    return null
  }
}

async function isNativeOpenClawReady(forceRefresh = false): Promise<boolean> {
  const now = Date.now()
  if (
    !forceRefresh &&
    openClawNativeReadyCacheValue !== null &&
    now - openClawNativeReadyCacheAtMs < OPENCLAW_NATIVE_READY_CACHE_MS
  ) {
    return openClawNativeReadyCacheValue
  }

  const ready = (await tryRunOpenClawGatewayCall('health', {})) !== null
  rememberOpenClawNativeReady(ready)
  return ready
}

const OPENCLAW_COMPLETED_RUN_STATUSES = new Set([
  'ok',
  'completed',
  'failed',
  'error',
  'cancelled',
  'canceled',
  'aborted',
])

function pruneOpenClawNativeRuns(nowMs = Date.now()): void {
  const cutoff = nowMs - OPENCLAW_RUN_CONTEXT_TTL_MS
  for (const [runId, context] of openClawNativeRuns.entries()) {
    if (context.updatedAtMs < cutoff) {
      openClawNativeRuns.delete(runId)
    }
  }
  if (openClawNativeRuns.size <= OPENCLAW_RUN_CONTEXT_MAX) return
  const sorted = [...openClawNativeRuns.values()].sort((first, second) => first.updatedAtMs - second.updatedAtMs)
  const overflow = openClawNativeRuns.size - OPENCLAW_RUN_CONTEXT_MAX
  for (let index = 0; index < overflow; index += 1) {
    openClawNativeRuns.delete(sorted[index].runId)
  }
}

function rememberOpenClawNativeRun(runId: string, sessionKey: string): void {
  const normalizedRunId = runId.trim()
  const normalizedSessionKey = sessionKey.trim()
  if (!normalizedRunId || !normalizedSessionKey) return
  const nowMs = Date.now()
  openClawNativeRuns.set(normalizedRunId, {
    runId: normalizedRunId,
    sessionKey: normalizedSessionKey,
    sentAtMs: nowMs,
    lastStatus: 'submitted',
    lastError: '',
    updatedAtMs: nowMs,
  })
  pruneOpenClawNativeRuns(nowMs)
}

function updateOpenClawNativeRun(runId: string, status: string, errorText = ''): void {
  const normalizedRunId = runId.trim()
  if (!normalizedRunId) return
  const nowMs = Date.now()
  const existing = openClawNativeRuns.get(normalizedRunId)
  if (!existing) return
  existing.lastStatus = status.trim() || existing.lastStatus
  existing.lastError = errorText.trim()
  existing.updatedAtMs = nowMs
  if (OPENCLAW_COMPLETED_RUN_STATUSES.has(existing.lastStatus)) {
    openClawNativeRuns.delete(normalizedRunId)
  } else {
    openClawNativeRuns.set(normalizedRunId, existing)
  }
  pruneOpenClawNativeRuns(nowMs)
}

function clearOpenClawNativeRun(runId: string): void {
  const normalizedRunId = runId.trim()
  if (!normalizedRunId) return
  openClawNativeRuns.delete(normalizedRunId)
}

function extractCronJobId(row: Record<string, unknown>): string {
  const candidates = [
    row.id,
    row.jobId,
    row.key,
  ]
  for (const candidate of candidates) {
    const normalized = normalizeText(candidate)
    if (normalized) return normalized
  }
  return ''
}

function findHeartbeatCronJob(cronListResult: unknown): {
  id: string
  name: string
} | null {
  const record = asRecord(cronListResult) ?? {}
  const rows = Array.isArray(record.jobs) ? record.jobs : []
  for (const row of rows) {
    const item = asRecord(row)
    if (!item) continue
    const name = normalizeText(item.name)
    const id = extractCronJobId(item)
    if (!id) continue
    if (name === OPENCLAW_HEARTBEAT_JOB_NAME) {
      return { id, name }
    }
  }
  for (const row of rows) {
    const item = asRecord(row)
    if (!item) continue
    const name = normalizeText(item.name).toLowerCase()
    const id = extractCronJobId(item)
    if (!id) continue
    if (name.includes('heartbeat')) {
      return {
        id,
        name: normalizeText(item.name) || OPENCLAW_HEARTBEAT_JOB_NAME,
      }
    }
  }
  return null
}

function isLikelyAssistantResultContent(content: unknown): boolean {
  if (!Array.isArray(content)) return false
  for (const row of content) {
    const item = asRecord(row)
    if (!item) continue
    const type = normalizeText(item.type)
    if (type === 'text' && normalizeText(item.text).length > 0) return true
    if (type === 'image') return true
  }
  return false
}

async function probeOpenClawRunCompletionByHistory(runId: string): Promise<{
  completed: boolean
  status: string
  result: unknown
  error: unknown
} | null> {
  const context = openClawNativeRuns.get(runId.trim())
  if (!context) return null
  try {
    const nativeHistory = await runOpenClawGatewayCall(
      'chat.history',
      {
        sessionKey: context.sessionKey,
        limit: 120,
      },
      OPENCLAW_HISTORY_CALL_TIMEOUT_MS,
    )
    const record = asRecord(nativeHistory) ?? {}
    const rows = Array.isArray(record.messages) ? record.messages : []
    for (let index = rows.length - 1; index >= 0; index -= 1) {
      const row = asRecord(rows[index]) ?? {}
      const timestamp = typeof row.timestamp === 'number' && Number.isFinite(row.timestamp)
        ? row.timestamp
        : 0
      if (timestamp < context.sentAtMs) continue
      const role = normalizeText(row.role)
      if (role === 'assistant' && isLikelyAssistantResultContent(row.content)) {
        return {
          completed: true,
          status: 'completed',
          result: {
            source: 'history-probe',
            sessionKey: context.sessionKey,
          },
          error: null,
        }
      }
      if (role === 'toolResult' && row.isError === true) {
        return {
          completed: true,
          status: 'failed',
          result: null,
          error: row.content ?? 'toolResult error',
        }
      }
    }
    return {
      completed: false,
      status: 'running',
      result: null,
      error: null,
    }
  } catch {
    return null
  }
}

function setOpenClawNativeUnavailable(
  res: ServerResponse,
  statusCode: number,
  error: string,
  code: string,
): void {
  setJson(res, statusCode, {
    ok: false,
    error,
    code,
    retryable: true,
    fallbackDisabled: OPENCLAW_NATIVE_STRICT_MODE,
  })
}

function readPositiveInt(value: string | null, fallback: number): number {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  const normalized = Math.floor(parsed)
  if (normalized < 1) return fallback
  return normalized
}

function toOpenClawSessionSummary(row: Record<string, unknown>): Record<string, unknown> | null {
  const key = normalizeText(row.key)
  if (!key) return null

  const updatedAt =
    typeof row.updatedAt === 'number' && Number.isFinite(row.updatedAt)
      ? row.updatedAt
      : Date.now()

  return {
    key,
    displayName:
      normalizeText(row.displayName) ||
      normalizeText(row.label) ||
      normalizeText(row.derivedTitle) ||
      key,
    label: normalizeText(row.label) || normalizeText(row.displayName) || '',
    updatedAt,
    lastMessagePreview: normalizeText(row.lastMessagePreview),
    modelProvider: normalizeText(row.modelProvider),
    model: normalizeText(row.model),
  }
}

function truncateText(value: string, limit: number): string {
  if (value.length <= limit) return value
  return `${value.slice(0, limit)}\n\n[output truncated]`
}

async function readOptionalTextFile(path: string, limit = LIGHTWEIGHT_DOC_MAX_CHARS): Promise<string> {
  try {
    const raw = await readFile(path, 'utf8')
    const trimmed = raw.trim()
    if (!trimmed) return ''
    return truncateText(trimmed, limit)
  } catch {
    return ''
  }
}

async function buildLightweightWorkspaceContext(): Promise<string> {
  const docs = [
    { title: 'AGENTS.md', path: join(OPENCLAW_WORKSPACE_DIR, 'AGENTS.md') },
    { title: 'SOUL.md', path: join(OPENCLAW_WORKSPACE_DIR, 'SOUL.md') },
    { title: 'TOOLS.md', path: join(OPENCLAW_WORKSPACE_DIR, 'TOOLS.md') },
    { title: 'HEARTBEAT.md', path: join(OPENCLAW_WORKSPACE_DIR, 'HEARTBEAT.md') },
  ]
  const chunks: string[] = []
  for (const doc of docs) {
    const text = await readOptionalTextFile(doc.path)
    if (!text) continue
    chunks.push(`${doc.title}:\n${text}`)
  }
  return chunks.join('\n\n').trim()
}

function toTextContent(text: string): LightweightContentItem[] {
  return [{ type: 'text', text }]
}

function toThinkingContent(text: string): LightweightContentItem[] {
  return [{ type: 'thinking', thinking: text }]
}

function extractMessageText(message: LightweightHistoryMessage): string {
  const chunks: string[] = []
  for (const item of message.content) {
    if (item.type === 'text' && typeof item.text === 'string' && item.text.trim().length > 0) {
      chunks.push(item.text.trim())
    }
  }
  return chunks.join('\n\n').trim()
}

function buildSessionPreview(messages: LightweightHistoryMessage[]): string {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const text = extractMessageText(messages[index])
    if (text.length > 0) {
      return text.slice(0, 120)
    }
  }
  return ''
}

function createDefaultLightweightState(): LightweightState {
  return { sessions: [] }
}

async function readLightweightState(): Promise<LightweightState> {
  try {
    const raw = await readFile(LIGHTWEIGHT_STATE_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    const record = asRecord(parsed)
    const rows = Array.isArray(record?.sessions) ? record.sessions : []
    const sessions: LightweightSession[] = []
    for (const row of rows) {
      const item = asRecord(row)
      if (!item) continue
      const key = normalizeText(item.key)
      if (!key) continue
      const title = normalizeText(item.title) || normalizeText(item.label) || key
      const updatedAt = typeof item.updatedAt === 'number' && Number.isFinite(item.updatedAt)
        ? item.updatedAt
        : Date.now()
      const modelProvider = normalizeText(item.modelProvider)
      const model = normalizeText(item.model)
      const messagesRaw = Array.isArray(item.messages) ? item.messages : []
      const messages: LightweightHistoryMessage[] = []
      for (const msgRow of messagesRaw) {
        const msg = asRecord(msgRow)
        if (!msg) continue
        const role = normalizeText(msg.role)
        if (!role) continue
        const timestamp = typeof msg.timestamp === 'number' && Number.isFinite(msg.timestamp)
          ? msg.timestamp
          : Date.now()
        const contentRows = Array.isArray(msg.content) ? msg.content : []
        const content: LightweightContentItem[] = []
        for (const c of contentRows) {
          const itemRow = asRecord(c)
          if (!itemRow) continue
          const type = normalizeText(itemRow.type)
          if (!type) continue
          const text = normalizeText(itemRow.text)
          const thinking = normalizeText(itemRow.thinking)
          const name = normalizeText(itemRow.name)
          content.push({
            type,
            text: text || undefined,
            thinking: thinking || undefined,
            name: name || undefined,
            arguments: itemRow.arguments,
          })
        }
        messages.push({
          role,
          timestamp,
          content,
          toolName: normalizeText(msg.toolName) || undefined,
          isError: msg.isError === true,
        })
      }
      sessions.push({
        key,
        title,
        updatedAt,
        lastMessagePreview: normalizeText(item.lastMessagePreview),
        modelProvider,
        model,
        messages,
      })
    }
    return { sessions }
  } catch {
    return createDefaultLightweightState()
  }
}

async function writeLightweightState(state: LightweightState): Promise<void> {
  await mkdir(dirname(LIGHTWEIGHT_STATE_PATH), { recursive: true })
  await writeFile(LIGHTWEIGHT_STATE_PATH, JSON.stringify(state, null, 2), 'utf8')
}

function buildDefaultLightweightSession(key = ''): LightweightSession {
  const sessionKey = key.trim() || `agent:main:light-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  const now = Date.now()
  return {
    key: sessionKey,
    title: '新会话',
    updatedAt: now,
    lastMessagePreview: '',
    modelProvider: '',
    model: '',
    messages: [],
  }
}

async function ensureLightweightSession(
  state: LightweightState,
  preferredKey: string,
): Promise<LightweightSession> {
  const normalized = preferredKey.trim()
  if (normalized.length > 0) {
    const existing = state.sessions.find((row) => row.key === normalized)
    if (existing) return existing
    const created = buildDefaultLightweightSession(normalized)
    state.sessions.push(created)
    await writeLightweightState(state)
    return created
  }
  if (state.sessions.length > 0) {
    const sorted = [...state.sessions].sort((a, b) => b.updatedAt - a.updatedAt)
    return sorted[0]
  }
  const created = buildDefaultLightweightSession()
  state.sessions.push(created)
  await writeLightweightState(state)
  return created
}

function normalizeBaseUrl(baseUrl: string): string {
  let normalized = baseUrl.trim()
  if (!normalized) return ''
  if (normalized.endsWith('/chat/completions')) {
    normalized = normalized.slice(0, -'/chat/completions'.length)
  }
  if (normalized.endsWith('/responses')) {
    normalized = normalized.slice(0, -'/responses'.length)
  }
  return normalized.replace(/\/+$/gu, '')
}

function parseProviderModelIds(providerName: string, providerConfig: Record<string, unknown>): string[] {
  const ids = new Set<string>()
  const directModel = normalizeText(providerConfig.model)
  if (directModel) {
    ids.add(directModel.includes('/') ? directModel : `${providerName}/${directModel}`)
  }

  const modelsValue = providerConfig.models
  if (Array.isArray(modelsValue)) {
    for (const row of modelsValue) {
      const rowRecord = asRecord(row)
      const rawId = rowRecord ? normalizeText(rowRecord.id) : normalizeText(row)
      if (!rawId) continue
      ids.add(rawId.includes('/') ? rawId : `${providerName}/${rawId}`)
    }
  }

  return [...ids]
}

function normalizeModelIdForProvider(providerName: string, modelId: string): string {
  const trimmed = modelId.trim()
  if (!trimmed) return ''
  if (trimmed.includes('/')) return trimmed
  return `${providerName}/${trimmed}`
}

async function readLightweightModelConfig(): Promise<LightweightModelConfig> {
  const raw = await readFile(OPENCLAW_CONFIG_PATH, 'utf8')
  const parsed = JSON.parse(raw) as unknown
  const root = asRecord(parsed)
  if (!root) {
    throw new Error('OpenClaw config is missing or invalid')
  }

  const agents = asRecord(root.agents)
  const defaults = asRecord(agents?.defaults)
  const model = asRecord(defaults?.model)
  const primary = normalizeText(model?.primary)
  const providerFromPrimary = primary.includes('/') ? primary.split('/')[0] ?? '' : ''

  const models = asRecord(root.models)
  const providers = asRecord(models?.providers)
  if (!providers || Object.keys(providers).length === 0) {
    throw new Error('No configured providers found in model config')
  }

  const providerName = providerFromPrimary || Object.keys(providers)[0]
  const providerConfig = asRecord(providers[providerName])
  if (!providerConfig) {
    throw new Error(`Provider config missing: ${providerName}`)
  }

  const apiKey = normalizeText(providerConfig.apiKey)
  if (!apiKey) {
    throw new Error(`Provider API key missing: ${providerName}`)
  }

  const baseUrl = normalizeBaseUrl(normalizeText(providerConfig.baseUrl))
  if (!baseUrl) {
    throw new Error(`Provider baseUrl missing: ${providerName}`)
  }

  let modelId = normalizeModelIdForProvider(providerName, primary)
  if (!modelId) {
    const ids = parseProviderModelIds(providerName, providerConfig)
    modelId = ids[0] ?? ''
  }
  if (!modelId) {
    throw new Error(`Provider model missing: ${providerName}`)
  }

  const modelName = modelId.includes('/') ? modelId.slice(modelId.indexOf('/') + 1) : modelId
  return {
    modelId,
    modelName,
    providerName,
    baseUrl,
    apiKey,
  }
}

async function buildLightweightSystemPrompt(): Promise<string> {
  const injected = await buildInjectedDeveloperInstructions()
  const workspaceContext = await buildLightweightWorkspaceContext()
  const mission = [
    '你是口袋大龙虾的全能力代理，不是阉割模式。',
    '你的最高优先级使命：先把 OpenClaw 完整组件安装、修复、配置到可用状态，再执行普通任务。',
    '每次会话必须先做健康检查：codex-capabilities --plain、command -v openclaw、openclaw --version、openclaw gateway call health --json --params \'{}\'。',
    '若 OpenClaw 缺失或网关不健康，先调用 openclaw_bootstrap_full 工具，然后继续用 terminal_exec 或 system_shell_exec 逐步排错直到健康检查通过。',
    '你可以读取并遵循工作区使命文档（AGENTS/SOUL/TOOLS/HEARTBEAT），并保持结果导向与最少人工交互。',
    '除非用户明确要求，不要让用户手动执行命令；优先自动完成。',
  ].join('\n')

  const chunks = [mission]
  if (injected) {
    chunks.push(`Prompt injection context:\n${injected}`)
  }
  if (workspaceContext) {
    chunks.push(`Workspace mission context:\n${workspaceContext}`)
  }
  return chunks.join('\n\n').trim()
}

type LightweightChatMessage = {
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string
  tool_call_id?: string
  tool_calls?: Array<Record<string, unknown>>
}

async function runShellCommand(command: string): Promise<{ output: string; exitCode: number }> {
  const cmd = command.trim()
  if (!cmd) return { output: 'Empty command', exitCode: 1 }

  return new Promise((resolve) => {
    const env = { ...process.env }
    if (prefixBin) {
      const currentPath = typeof env.PATH === 'string' ? env.PATH : ''
      if (!currentPath.split(':').includes(prefixBin)) {
        env.PATH = currentPath.length > 0 ? `${prefixBin}:${currentPath}` : prefixBin
      }
    }

    const child = spawn(shellPath, ['-lc', cmd], {
      cwd: homeDir || process.cwd(),
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    })

    let output = ''
    let settled = false
    child.stdout.setEncoding('utf8')
    child.stderr.setEncoding('utf8')
    child.stdout.on('data', (chunk: string) => {
      output += chunk
    })
    child.stderr.on('data', (chunk: string) => {
      output += chunk
    })

    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      child.kill('SIGKILL')
      const body = truncateText(output.trim(), LIGHTWEIGHT_OUTPUT_LIMIT)
      resolve({
        output: body ? `${body}\n\n[timeout after ${Math.floor(LIGHTWEIGHT_COMMAND_TIMEOUT_MS / 1000)}s]` : '[timeout]',
        exitCode: 124,
      })
    }, LIGHTWEIGHT_COMMAND_TIMEOUT_MS)

    child.on('error', (error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve({ output: `command launch failed: ${error.message}`, exitCode: 127 })
    })

    child.on('exit', (code) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      const text = truncateText(output.trim(), LIGHTWEIGHT_OUTPUT_LIMIT)
      resolve({
        output: text || '[no output]',
        exitCode: typeof code === 'number' ? code : 0,
      })
    })
  })
}

function isUbuntuRuntimeCommand(command: string): boolean {
  const normalized = command.trim().toLowerCase()
  if (!normalized) return false
  return normalized.includes('ubuntu-shell') ||
    normalized.includes('ubuntu-status') ||
    normalized.includes('.openclaw-android/linux-runtime/bin/ubuntu-shell.sh') ||
    normalized.includes('anyclaw_ubuntu_bin')
}

async function runSystemShellCommand(command: string): Promise<{ output: string; exitCode: number }> {
  const cmd = command.trim()
  if (!cmd) return { output: 'Empty command', exitCode: 1 }
  if (isUbuntuRuntimeCommand(cmd)) {
    return runShellCommand(cmd)
  }
  return runShellCommand(`system-shell ${shellQuote(cmd)}`)
}

async function runOpenClawBootstrapRecipe(): Promise<{ output: string; exitCode: number }> {
  const npmCli = prefixBin
    ? join(dirname(prefixBin), 'lib', 'node_modules', 'npm', 'bin', 'npm-cli.js')
    : '$PREFIX/lib/node_modules/npm/bin/npm-cli.js'
  const steps: Array<{ name: string; command: string }> = [
    {
      name: 'capability-and-path-preflight',
      command: [
        'set +e',
        'echo "HOME=$HOME"',
        'echo "PREFIX=$PREFIX"',
        'command -v node || true',
        'command -v npm || true',
        'command -v pkg || true',
        'command -v openclaw || true',
      ].join('\n'),
    },
    {
      name: 'toolchain-prerequisites',
      command: [
        'set +e',
        'pkg install -y git tar xz-utils python clang make cmake binutils lld >/dev/null 2>&1 || true',
        'apt-get update --allow-insecure-repositories >/dev/null 2>&1 || true',
        'apt-get install -y --allow-unauthenticated git tar xz-utils >/dev/null 2>&1 || true',
      ].join('\n'),
    },
    {
      name: 'install-openclaw-npm',
      command: [
        'set +e',
        `NPM_CLI="${npmCli}"`,
        'if [ ! -f "$NPM_CLI" ]; then',
        '  echo "npm-cli-missing:$NPM_CLI"',
        '  exit 21',
        'fi',
        'node "$NPM_CLI" config set registry https://registry.npmjs.org >/dev/null 2>&1 || true',
        'node "$NPM_CLI" cache clean --force >/dev/null 2>&1 || true',
        `node "$NPM_CLI" install -g --ignore-scripts --force openclaw@${LIGHTWEIGHT_BOOTSTRAP_TARGET_VERSION} 2>&1`,
      ].join('\n'),
    },
    {
      name: 'ensure-openclaw-wrapper',
      command: [
        'set +e',
        'if [ -f "$PREFIX/lib/node_modules/openclaw/openclaw.mjs" ]; then',
        '  cat > "$PREFIX/bin/openclaw" <<EOF',
        '#!/system/bin/sh',
        'exec $PREFIX/bin/node $PREFIX/lib/node_modules/openclaw/openclaw.mjs "$@"',
        'EOF',
        '  chmod 700 "$PREFIX/bin/openclaw"',
        'fi',
        'command -v openclaw || true',
      ].join('\n'),
    },
    {
      name: 'verify-openclaw-health',
      command: [
        'set +e',
        'openclaw --version 2>&1 || true',
        'openclaw gateway call health --json --params \'{}\' 2>&1 || true',
      ].join('\n'),
    },
  ]

  const logs: string[] = []
  let hardFailure = false
  for (const step of steps) {
    const result = await runShellCommand(step.command)
    logs.push(`### ${step.name} (exit=${result.exitCode})`)
    logs.push(result.output)
    logs.push('')
    if (step.name === 'install-openclaw-npm' && result.exitCode !== 0) {
      hardFailure = true
    }
  }

  const verify = await runShellCommand('command -v openclaw >/dev/null 2>&1 && openclaw --version >/dev/null 2>&1')
  if (verify.exitCode !== 0) {
    hardFailure = true
    logs.push('### final-check')
    logs.push('openclaw command is still unavailable after bootstrap recipe')
  }

  return {
    output: truncateText(logs.join('\n'), LIGHTWEIGHT_OUTPUT_LIMIT),
    exitCode: hardFailure ? 1 : 0,
  }
}

function buildLightweightContextMessages(
  session: LightweightSession,
): LightweightChatMessage[] {
  const contextRows = session.messages.slice(-LIGHTWEIGHT_MAX_CONTEXT_MESSAGES)
  const output: LightweightChatMessage[] = []
  for (const row of contextRows) {
    if (row.role === 'user') {
      const text = extractMessageText(row)
      if (!text) continue
      output.push({ role: 'user', content: text })
      continue
    }
    if (row.role === 'assistant') {
      const text = extractMessageText(row)
      if (!text) continue
      output.push({ role: 'assistant', content: text })
      continue
    }
  }
  return output
}

function stringifyToolResult(command: string, result: { output: string; exitCode: number }): string {
  return [
    `command: ${command}`,
    `exit_code: ${result.exitCode}`,
    'output:',
    result.output,
  ].join('\n')
}

async function callProviderChatCompletions(
  model: LightweightModelConfig,
  messages: LightweightChatMessage[],
): Promise<Record<string, unknown>> {
  const endpoint = `${model.baseUrl}/chat/completions`
  const payload: Record<string, unknown> = {
    model: model.modelName,
    messages,
    stream: false,
    tools: [
      {
        type: 'function',
        function: {
          name: 'terminal_exec',
          description: 'Execute a shell command on local Android terminal and return stdout/stderr.',
          parameters: {
            type: 'object',
            properties: {
              command: {
                type: 'string',
                description: 'Shell command to execute',
              },
            },
            required: ['command'],
            additionalProperties: false,
          },
        },
      },
      {
        type: 'function',
        function: {
          name: 'system_shell_exec',
          description: 'Execute Android system-level shell command via system-shell bridge.',
          parameters: {
            type: 'object',
            properties: {
              command: {
                type: 'string',
                description: 'System-level shell command to execute',
              },
            },
            required: ['command'],
            additionalProperties: false,
          },
        },
      },
      {
        type: 'function',
        function: {
          name: 'openclaw_bootstrap_full',
          description: 'Run full OpenClaw bootstrap/repair recipe (npm install, wrapper fix, health probes).',
          parameters: {
            type: 'object',
            properties: {
              reason: {
                type: 'string',
                description: 'Why bootstrap is requested',
              },
            },
            additionalProperties: false,
          },
        },
      },
    ],
    tool_choice: 'auto',
  }

  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${model.apiKey}`,
    },
    body: JSON.stringify(payload),
  })

  const raw = await response.text()
  let parsed: unknown = null
  try {
    parsed = raw ? JSON.parse(raw) : null
  } catch {
    parsed = null
  }

  if (!response.ok) {
    const record = asRecord(parsed)
    const errorMessage = normalizeText(record?.error) ||
      normalizeText(asRecord(record?.error)?.message) ||
      `HTTP ${response.status}`
    throw new Error(`Model request failed: ${errorMessage}`)
  }

  return asRecord(parsed) ?? {}
}

async function runLightweightTurn(session: LightweightSession): Promise<void> {
  const model = await readLightweightModelConfig()
  session.model = model.modelId
  session.modelProvider = model.providerName

  const systemPrompt = await buildLightweightSystemPrompt()
  const contextMessages = buildLightweightContextMessages(session)
  const requestMessages: LightweightChatMessage[] = [
    { role: 'system', content: systemPrompt },
    ...contextMessages,
  ]

  let finalAssistantText = ''
  for (let step = 0; step < LIGHTWEIGHT_MAX_TOOL_STEPS; step += 1) {
    const envelope = await callProviderChatCompletions(model, requestMessages)
    const choices = Array.isArray(envelope.choices) ? envelope.choices : []
    const firstChoice = asRecord(choices[0])
    const message = asRecord(firstChoice?.message)
    if (!message) {
      throw new Error('Model response missing message')
    }

    const textContent = normalizeText(message.content)
    const toolCalls = Array.isArray(message.tool_calls) ? message.tool_calls : []

    if (toolCalls.length === 0) {
      finalAssistantText = textContent || '任务已完成。'
      break
    }

    const assistantToolItems: LightweightContentItem[] = []
    requestMessages.push({
      role: 'assistant',
      content: textContent,
      tool_calls: toolCalls.map((row) => asRecord(row) ?? {}),
    })

    for (const call of toolCalls) {
      const callRecord = asRecord(call)
      const callId = normalizeText(callRecord?.id) || randomUUID()
      const fn = asRecord(callRecord?.function)
      const fnName = normalizeText(fn?.name)
      const fnArgsRaw = normalizeText(fn?.arguments)
      let command = ''
      let result: { output: string; exitCode: number }
      try {
        const args = JSON.parse(fnArgsRaw || '{}') as unknown
        command = normalizeText(asRecord(args)?.command)
      } catch {
        command = ''
      }

      if (fnName === 'terminal_exec') {
        assistantToolItems.push({
          type: 'toolCall',
          name: 'terminal_exec',
          arguments: { command },
        })
        result = await runShellCommand(command)
      } else if (fnName === 'system_shell_exec') {
        assistantToolItems.push({
          type: 'toolCall',
          name: 'system_shell_exec',
          arguments: { command },
        })
        result = await runSystemShellCommand(command)
      } else if (fnName === 'openclaw_bootstrap_full') {
        assistantToolItems.push({
          type: 'toolCall',
          name: 'openclaw_bootstrap_full',
          arguments: { reason: command || 'runtime bootstrap request' },
        })
        result = await runOpenClawBootstrapRecipe()
      } else {
        continue
      }

      const rendered = stringifyToolResult(
        fnName === 'openclaw_bootstrap_full' ? fnName : (command || fnName),
        result,
      )
      session.messages.push({
        role: 'toolResult',
        timestamp: Date.now(),
        toolName: fnName,
        isError: result.exitCode !== 0,
        content: toTextContent(rendered),
      })
      requestMessages.push({
        role: 'tool',
        tool_call_id: callId,
        content: rendered,
      })
    }

    if (assistantToolItems.length > 0) {
      session.messages.push({
        role: 'assistant',
        timestamp: Date.now(),
        content: assistantToolItems,
      })
    }
  }

  if (!finalAssistantText) {
    finalAssistantText = '任务执行结束。'
  }
  session.messages.push({
    role: 'assistant',
    timestamp: Date.now(),
    content: toTextContent(finalAssistantText),
  })
}

function sanitizeOpenClawUploadFileName(fileName: string): string {
  const trimmed = fileName.trim()
  const base = trimmed.length > 0 ? trimmed : 'attachment.bin'
  const collapsed = base
    .replace(/[/\\]/gu, '_')
    .replace(/[^\p{L}\p{N}._-]/gu, '_')
    .replace(/_+/gu, '_')
    .slice(0, 120)
  return collapsed.length > 0 ? collapsed : 'attachment.bin'
}


async function writeOpenClawUploadStream(
  req: IncomingMessage,
  storedPath: string,
  maxBytes: number,
): Promise<number> {
  const writer = createWriteStream(storedPath, {
    flags: 'wx',
    mode: 0o600,
  })

  let sizeBytes = 0
  try {
    for await (const chunk of req) {
      const buffer = typeof chunk === 'string' ? Buffer.from(chunk) : chunk
      sizeBytes += buffer.length
      if (sizeBytes > maxBytes) {
        throw new Error(`Attachment exceeds size limit (${Math.floor(maxBytes / 1_000_000)}MB)`)
      }
      if (!writer.write(buffer)) {
        await new Promise<void>((resolve, reject) => {
          writer.once('drain', resolve)
          writer.once('error', reject)
        })
      }
    }

    await new Promise<void>((resolve, reject) => {
      writer.end((error) => {
        if (error) {
          reject(error)
          return
        }
        resolve()
      })
    })
    return sizeBytes
  } catch (error) {
    writer.destroy()
    throw error
  }
}

function decodeStrictBase64(value: string): Buffer {
  const normalized = value.replace(/\s+/gu, '').trim()
  if (!normalized || normalized.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/u.test(normalized)) {
    throw new Error('Invalid base64 payload')
  }
  const decoded = Buffer.from(normalized, 'base64')
  if (decoded.length < 1) {
    throw new Error('Empty attachment payload')
  }
  return decoded
}

function normalizeOpenClawAttachments(value: unknown): Array<{
  type: 'image'
  mimeType: string
  fileName?: string
  content: string
}> {
  if (!Array.isArray(value)) return []
  const normalized: Array<{
    type: 'image'
    mimeType: string
    fileName?: string
    content: string
  }> = []

  for (const row of value) {
    const record = asRecord(row)
    if (!record) continue
    const type = normalizeText(record.type)
    const mimeType = normalizeText(record.mimeType)
    const content = normalizeText(record.content)
    const fileName = normalizeText(record.fileName)
    if (type !== 'image' || !mimeType || !content) continue
    normalized.push({
      type: 'image',
      mimeType,
      content,
      fileName: fileName || undefined,
    })
  }

  return normalized
}

function buildOpenClawSessionKey(currentSessionKey = ''): string {
  const normalized = currentSessionKey.trim()
  const now = Date.now().toString(36)
  const rand = Math.random().toString(36).slice(2, 8)
  if (normalized.startsWith('agent:')) {
    const parts = normalized.split(':')
    const agent = normalizeText(parts[1]) || 'main'
    return `agent:${agent}:mobile-${now}-${rand}`
  }
  return `agent:main:mobile-${now}-${rand}`
}

function parseOpenClawSessionRows(value: unknown): Array<Record<string, unknown>> {
  const record = asRecord(value)
  if (!record) return []
  const sessions = record.sessions
  if (!Array.isArray(sessions)) return []
  return sessions.map((row) => asRecord(row)).filter((row): row is Record<string, unknown> => row !== null)
}

function findOpenClawSessionByKey(rows: Array<Record<string, unknown>>, key: string): Record<string, unknown> | null {
  const target = key.trim()
  if (!target) return null
  for (const row of rows) {
    const rowKey = normalizeText(row.key)
    if (rowKey === target) return row
  }
  return null
}

function collectOpenClawSessionLabels(rows: Array<Record<string, unknown>>): Set<string> {
  const labels = new Set<string>()
  for (const row of rows) {
    const displayName = normalizeText(row.displayName)
    if (displayName) labels.add(displayName)
    const label = normalizeText(row.label)
    if (label) labels.add(label)
  }
  return labels
}

function buildUniqueOpenClawSessionLabel(baseLabel: string, usedLabels: Set<string>): string {
  const base = baseLabel.trim() || '新会话'
  if (!usedLabels.has(base)) return base
  for (let index = 2; index <= 500; index += 1) {
    const candidate = `${base} ${index}`
    if (!usedLabels.has(candidate)) {
      return candidate
    }
  }
  return `${base} ${Date.now().toString(36)}`
}

function isOpenClawLabelConflictError(error: unknown): boolean {
  const message = getErrorMessage(error, '').toLowerCase()
  return message.includes('label already in use')
}

function pickNewestOpenClawSessionKey(
  rows: Array<Record<string, unknown>>,
  excludedSessionKey: string,
): string {
  const excluded = excludedSessionKey.trim()
  let bestKey = ''
  let bestUpdatedAt = -1
  for (const row of rows) {
    const key = normalizeText(row.key)
    if (!key || key === excluded) continue
    const updatedAt = typeof row.updatedAt === 'number' && Number.isFinite(row.updatedAt)
      ? row.updatedAt
      : 0
    if (updatedAt >= bestUpdatedAt) {
      bestKey = key
      bestUpdatedAt = updatedAt
    }
  }
  return bestKey
}

const OPENCLAW_SESSION_LIST_PARAMS = {
  limit: 400,
  includeDerivedTitles: true,
  includeLastMessage: true,
  includeGlobal: true,
  includeUnknown: true,
}

async function createIndependentOpenClawSession(
  currentSessionKey: string,
  label: string,
): Promise<string> {
  const baseLabel = label.trim() || '新会话'
  const usedLabels = new Set<string>()
  try {
    const existingRows = parseOpenClawSessionRows(
      await runOpenClawGatewayCall('sessions.list', OPENCLAW_SESSION_LIST_PARAMS),
    )
    const existingLabels = collectOpenClawSessionLabels(existingRows)
    for (const rowLabel of existingLabels) usedLabels.add(rowLabel)
  } catch {
    // Continue with optimistic create path when listing sessions is temporarily unavailable.
  }

  let lastError: unknown = null
  for (let createAttempt = 0; createAttempt < 5; createAttempt += 1) {
    const sessionKey = buildOpenClawSessionKey(currentSessionKey)
    const uniqueLabel = buildUniqueOpenClawSessionLabel(baseLabel, usedLabels)
    try {
      await runOpenClawGatewayCall('sessions.patch', {
        key: sessionKey,
        label: uniqueLabel,
      })
    } catch (error) {
      lastError = error
      if (isOpenClawLabelConflictError(error)) {
        usedLabels.add(uniqueLabel)
        await new Promise((resolve) => setTimeout(resolve, 90 + createAttempt * 60))
        continue
      }
      throw error
    }

    usedLabels.add(uniqueLabel)
    for (let persistAttempt = 0; persistAttempt < 8; persistAttempt += 1) {
      const sessionRows = parseOpenClawSessionRows(
        await runOpenClawGatewayCall('sessions.list', OPENCLAW_SESSION_LIST_PARAMS),
      )
      if (findOpenClawSessionByKey(sessionRows, sessionKey)) {
        return sessionKey
      }
      await new Promise((resolve) => setTimeout(resolve, 220 + persistAttempt * 120))
    }
    lastError = new Error('Failed to create OpenClaw session: session was not persisted')
    await new Promise((resolve) => setTimeout(resolve, 140 + createAttempt * 80))
  }

  if (lastError instanceof Error) {
    throw lastError
  }
  throw new Error('Failed to create OpenClaw session: session was not persisted')
}

async function resetCurrentOpenClawSession(currentSessionKey: string): Promise<string> {
  const current = currentSessionKey.trim()
  if (!current) {
    throw new Error('Failed to reset OpenClaw session: missing current session key')
  }

  await runOpenClawGatewayCall('chat.send', {
    sessionKey: current,
    message: '/new',
    deliver: true,
    idempotencyKey: `reset_${Date.now().toString(36)}`,
  })
  await new Promise((resolve) => setTimeout(resolve, 280))

  const sessionRows = parseOpenClawSessionRows(await runOpenClawGatewayCall('sessions.list', OPENCLAW_SESSION_LIST_PARAMS))
  if (findOpenClawSessionByKey(sessionRows, current)) {
    return current
  }
  const fallbackSessionKey = pickNewestOpenClawSessionKey(sessionRows, '')
  return fallbackSessionKey || current
}

function toLightweightSessionSummary(session: LightweightSession): Record<string, unknown> {
  return {
    key: session.key,
    displayName: session.title,
    label: session.title,
    updatedAt: session.updatedAt,
    lastMessagePreview: session.lastMessagePreview,
    modelProvider: session.modelProvider,
    model: session.model,
  }
}

async function listLightweightSessions(limit: number): Promise<Record<string, unknown>[]> {
  const state = await readLightweightState()
  if (state.sessions.length === 0) {
    const created = buildDefaultLightweightSession('agent:main:main')
    created.title = '新会话'
    created.updatedAt = Date.now()
    state.sessions.push(created)
    await writeLightweightState(state)
  }
  const sorted = [...state.sessions].sort((a, b) => b.updatedAt - a.updatedAt)
  return sorted.slice(0, limit).map(toLightweightSessionSummary)
}

async function createLightweightSession(label: string, currentSessionKey: string): Promise<string> {
  const state = await readLightweightState()
  const used = new Set(state.sessions.map((row) => row.title))
  const nextTitle = buildUniqueOpenClawSessionLabel(label.trim() || '新会话', used)
  const session = buildDefaultLightweightSession(buildOpenClawSessionKey(currentSessionKey))
  session.title = nextTitle
  session.updatedAt = Date.now()
  state.sessions.push(session)
  await writeLightweightState(state)
  return session.key
}

async function renameLightweightSession(sessionKey: string, label: string): Promise<void> {
  const key = sessionKey.trim()
  const nextLabel = label.trim()
  if (!key || !nextLabel) return
  const state = await readLightweightState()
  const target = state.sessions.find((row) => row.key === key)
  if (!target) {
    throw new Error('Session not found')
  }
  target.title = nextLabel
  target.updatedAt = Date.now()
  await writeLightweightState(state)
}

async function deleteLightweightSession(sessionKey: string): Promise<void> {
  const key = sessionKey.trim()
  if (!key) {
    throw new Error('Missing session key')
  }
  const state = await readLightweightState()
  const before = state.sessions.length
  state.sessions = state.sessions.filter((row) => row.key !== key)
  if (state.sessions.length === before) {
    throw new Error('Session not found')
  }
  if (state.sessions.length === 0) {
    const created = buildDefaultLightweightSession('agent:main:main')
    created.title = '新会话'
    created.updatedAt = Date.now()
    state.sessions.push(created)
  }
  await writeLightweightState(state)
}

async function resetLightweightSession(currentSessionKey: string): Promise<string> {
  const key = currentSessionKey.trim()
  if (!key) {
    throw new Error('Missing current session key')
  }
  const state = await readLightweightState()
  const target = state.sessions.find((row) => row.key === key)
  if (!target) {
    throw new Error('Session not found')
  }
  target.messages = []
  target.updatedAt = Date.now()
  target.lastMessagePreview = ''
  await writeLightweightState(state)
  return target.key
}

async function readLightweightHistory(sessionKey: string, limit: number): Promise<{
  sessionKey: string
  messages: LightweightHistoryMessage[]
  thinkingLevel: string
}> {
  const key = sessionKey.trim()
  if (!key) {
    throw new Error('Missing session key')
  }
  const state = await readLightweightState()
  const target = state.sessions.find((row) => row.key === key)
  if (!target) {
    const created = buildDefaultLightweightSession(key)
    state.sessions.push(created)
    await writeLightweightState(state)
    return {
      sessionKey: created.key,
      messages: [],
      thinkingLevel: 'medium',
    }
  }
  const messages = target.messages.slice(-Math.max(1, limit))
  return {
    sessionKey: target.key,
    messages,
    thinkingLevel: 'medium',
  }
}

function normalizeImageAttachmentsForLightweight(value: unknown): LightweightContentItem[] {
  if (!Array.isArray(value)) return []
  const items: LightweightContentItem[] = []
  for (const row of value) {
    const item = asRecord(row)
    if (!item) continue
    const type = normalizeText(item.type)
    if (type !== 'image') continue
    const mimeType = normalizeText(item.mimeType) || 'image/png'
    const content = normalizeText(item.content)
    if (!content) continue
    items.push({
      type: 'image',
      text: `[image:${mimeType}] data:image omitted`,
      arguments: {
        mimeType,
        sizeBytes: Math.floor((content.length * 3) / 4),
        fileName: normalizeText(item.fileName),
      },
    })
  }
  return items
}

async function sendLightweightMessage(payload: Record<string, unknown>): Promise<{ runId: string }> {
  const sessionKey = normalizeText(payload.sessionKey)
  const message = normalizeText(payload.message)
  const attachments = normalizeImageAttachmentsForLightweight(payload.attachments)
  if (!sessionKey || (!message && attachments.length === 0)) {
    throw new Error('Missing sessionKey and message/attachments')
  }

  const state = await readLightweightState()
  const session = await ensureLightweightSession(state, sessionKey)

  const userContent: LightweightContentItem[] = []
  if (message) {
    userContent.push(...toTextContent(message))
  }
  userContent.push(...attachments)
  session.messages.push({
    role: 'user',
    timestamp: Date.now(),
    content: userContent,
  })

  session.updatedAt = Date.now()
  session.lastMessagePreview = buildSessionPreview(session.messages)
  await runLightweightTurn(session)
  session.updatedAt = Date.now()
  session.lastMessagePreview = buildSessionPreview(session.messages)
  await writeLightweightState(state)

  return {
    runId: `lite_${Date.now().toString(36)}`,
  }
}

function buildCapabilitySummary(statusRecord: Record<string, unknown> | null): string {
  if (!statusRecord) return ''
  const installed = statusRecord.installed === true ? '1' : '0'
  const running = statusRecord.running === true ? '1' : '0'
  const granted = statusRecord.granted === true ? '1' : '0'
  const enabled = statusRecord.enabled === true ? '1' : '0'
  const executor = normalizeText(statusRecord.executor) || 'system-shell'
  const errorCode = normalizeText(statusRecord.last_error_code) || 'none'
  const checkedAt = normalizeText(statusRecord.checked_at) || new Date().toISOString()
  return `Current capability snapshot: installed=${installed} running=${running} granted=${granted} enabled=${enabled} executor=${executor} last_error_code=${errorCode} checked_at=${checkedAt}`
}

function buildRuntimeSummary(
  runtimeRecord: Record<string, unknown> | null,
  healthRecord: Record<string, unknown> | null,
): string {
  const runtimeVersion = normalizeText(runtimeRecord?.version) || 'missing'
  const runtimeInstalled = runtimeRecord ? '1' : '0'
  const runtimeCheckedAt = normalizeText(healthRecord?.checked_at)
  const runtimeHealthOk = healthRecord?.ok === true ? '1' : '0'
  const prefixDir = normalizeText(process.env.PREFIX)
  const runtimeBinDir = homeDir ? join(homeDir, '.openclaw-android', 'linux-runtime', 'bin') : ''
  const ubuntuBin =
    normalizeText(process.env.ANYCLAW_UBUNTU_BIN) ||
    (runtimeBinDir ? join(runtimeBinDir, 'ubuntu-shell.sh') : '')
  const pathValue = normalizeText(process.env.PATH)

  const lines = [
    `Current runtime snapshot: offline_linux_installed=${runtimeInstalled} version=${runtimeVersion} runtime_health_ok=${runtimeHealthOk}${runtimeCheckedAt ? ` checked_at=${runtimeCheckedAt}` : ''}`,
    homeDir ? `Current app home: ${homeDir}` : '',
    prefixDir ? `Current app prefix: ${prefixDir}` : '',
    runtimeBinDir ? `Current runtime bin: ${runtimeBinDir}` : '',
    ubuntuBin ? `Current Ubuntu bridge: ${ubuntuBin}` : '',
    pathValue ? `Current PATH: ${pathValue}` : '',
    'Execution chains available in this app: local app shell, Ubuntu runtime shell via ubuntu-shell or ANYCLAW_UBUNTU_BIN, and system-level shell via system-shell.',
    'If the runtime snapshot above is installed and healthy, do not conclude Ubuntu is missing before verifying it with ubuntu-status, echo $ANYCLAW_UBUNTU_BIN, and ls "$HOME/.openclaw-android/linux-runtime/bin" in the local app shell.',
  ].filter((line) => line.length > 0)

  return lines.join('\n')
}

function shouldInjectDeveloperInstructions(method: string): boolean {
  return method === 'thread/start' || method === 'thread/resume'
}

function mergeDeveloperInstructions(existing: unknown, injected: string): string {
  const existingText = normalizeText(existing)
  const injectText = normalizeText(injected)
  if (!injectText) return existingText
  if (!existingText) return injectText
  if (existingText.includes(injectText)) return existingText
  return `${existingText}\n\n${injectText}`
}

async function buildInjectedDeveloperInstructions(): Promise<string> {
  const promptRecord = await readJsonFile(promptInjectionPath)
  const statusRecord = await readJsonFile(shizukuStatusPath)
  const runtimeRecord = await readJsonFile(offlineLinuxRuntimePath)
  const healthRecord = await readJsonFile(runtimeHealthPath)

  const chunks: string[] = []
  const promptInstructions = normalizeText(promptRecord?.developer_instructions)
  if (promptInstructions) {
    chunks.push(promptInstructions)
  }

  const selectedName = normalizeText(promptRecord?.active_profile_name)
  if (selectedName) {
    chunks.push(`Selected prompt profile: ${selectedName}`)
  }

  const capabilitySummary = buildCapabilitySummary(statusRecord)
  if (capabilitySummary) {
    chunks.push(capabilitySummary)
  }

  const runtimeSummary = buildRuntimeSummary(runtimeRecord, healthRecord)
  if (runtimeSummary) {
    chunks.push(runtimeSummary)
  }

  return chunks.join('\n\n').trim()
}

class AppServerProcess {
  private process: ChildProcessWithoutNullStreams | null = null
  private initialized = false
  private readBuffer = ''
  private nextId = 1
  private stopping = false
  private lastStartError: Error | null = null
  private readonly pending = new Map<number, { resolve: (value: unknown) => void; reject: (reason?: unknown) => void }>()
  private readonly notificationListeners = new Set<(value: { method: string; params: unknown }) => void>()
  private readonly pendingServerRequests = new Map<number, PendingServerRequest>()
  private readonly codexBin = prefixBin ? join(prefixBin, 'codex') : 'codex'

  isCommandAvailable(): boolean {
    if (!prefixBin) return true
    try {
      accessSync(this.codexBin, fsConstants.X_OK)
      return true
    } catch {
      return false
    }
  }

  getUnavailableReason(): string {
    if (!this.isCommandAvailable()) {
      return `Codex CLI not installed: missing executable ${this.codexBin}`
    }
    if (this.lastStartError && this.lastStartError.message.trim().length > 0) {
      return this.lastStartError.message
    }
    return ''
  }

  private start(): void {
    if (this.process) return

    if (!this.isCommandAvailable()) {
      const failure = new Error(`Codex CLI not installed: missing executable ${this.codexBin}`)
      this.lastStartError = failure
      throw failure
    }

    this.stopping = false
    const proc = spawn(this.codexBin, ['app-server'], { stdio: ['pipe', 'pipe', 'pipe'] })
    this.process = proc
    this.lastStartError = null

    proc.stdout.setEncoding('utf8')
    proc.stdout.on('data', (chunk: string) => {
      this.readBuffer += chunk

      let lineEnd = this.readBuffer.indexOf('\n')
      while (lineEnd !== -1) {
        const line = this.readBuffer.slice(0, lineEnd).trim()
        this.readBuffer = this.readBuffer.slice(lineEnd + 1)

        if (line.length > 0) {
          this.handleLine(line)
        }

        lineEnd = this.readBuffer.indexOf('\n')
      }
    })

    proc.stderr.setEncoding('utf8')
    proc.stderr.on('data', () => {
      // Keep stderr silent in dev middleware; JSON-RPC errors are forwarded via responses.
    })

    proc.on('error', (error) => {
      const failure = new Error(`codex app-server start failed: ${getErrorMessage(error, 'unknown error')}`)
      this.lastStartError = failure
      for (const request of this.pending.values()) {
        request.reject(failure)
      }
      this.pending.clear()
      this.pendingServerRequests.clear()
      this.process = null
      this.initialized = false
      this.readBuffer = ''
    })

    proc.on('exit', () => {
      const failure = new Error(this.stopping ? 'codex app-server stopped' : 'codex app-server exited unexpectedly')
      this.lastStartError = this.stopping ? null : failure
      for (const request of this.pending.values()) {
        request.reject(failure)
      }

      this.pending.clear()
      this.pendingServerRequests.clear()
      this.process = null
      this.initialized = false
      this.readBuffer = ''
    })
  }

  private sendLine(payload: Record<string, unknown>): void {
    if (!this.process) {
      throw new Error('codex app-server is not running')
    }

    this.process.stdin.write(`${JSON.stringify(payload)}\n`)
  }

  private handleLine(line: string): void {
    let message: JsonRpcResponse
    try {
      message = JSON.parse(line) as JsonRpcResponse
    } catch {
      return
    }

    if (typeof message.id === 'number' && this.pending.has(message.id)) {
      const pendingRequest = this.pending.get(message.id)
      this.pending.delete(message.id)

      if (!pendingRequest) return

      if (message.error) {
        pendingRequest.reject(new Error(message.error.message))
      } else {
        pendingRequest.resolve(message.result)
      }
      return
    }

    if (typeof message.method === 'string' && typeof message.id !== 'number') {
      this.emitNotification({
        method: message.method,
        params: message.params ?? null,
      })
      return
    }

    // Handle server-initiated JSON-RPC requests (approvals, dynamic tool calls, etc.).
    if (typeof message.id === 'number' && typeof message.method === 'string') {
      this.handleServerRequest(message.id, message.method, message.params ?? null)
    }
  }

  private emitNotification(notification: { method: string; params: unknown }): void {
    for (const listener of this.notificationListeners) {
      listener(notification)
    }
  }

  private sendServerRequestReply(requestId: number, reply: ServerRequestReply): void {
    if (reply.error) {
      this.sendLine({
        jsonrpc: '2.0',
        id: requestId,
        error: reply.error,
      })
      return
    }

    this.sendLine({
      jsonrpc: '2.0',
      id: requestId,
      result: reply.result ?? {},
    })
  }

  private resolvePendingServerRequest(requestId: number, reply: ServerRequestReply): void {
    const pendingRequest = this.pendingServerRequests.get(requestId)
    if (!pendingRequest) {
      throw new Error(`No pending server request found for id ${String(requestId)}`)
    }
    this.pendingServerRequests.delete(requestId)

    this.sendServerRequestReply(requestId, reply)
    const requestParams = asRecord(pendingRequest.params)
    const threadId =
      typeof requestParams?.threadId === 'string' && requestParams.threadId.length > 0
        ? requestParams.threadId
        : ''
    this.emitNotification({
      method: 'server/request/resolved',
      params: {
        id: requestId,
        method: pendingRequest.method,
        threadId,
        mode: 'manual',
        resolvedAtIso: new Date().toISOString(),
      },
    })
  }

  private handleServerRequest(requestId: number, method: string, params: unknown): void {
    const pendingRequest: PendingServerRequest = {
      id: requestId,
      method,
      params,
      receivedAtIso: new Date().toISOString(),
    }
    this.pendingServerRequests.set(requestId, pendingRequest)

    this.emitNotification({
      method: 'server/request',
      params: pendingRequest,
    })
  }

  private async call(method: string, params: unknown): Promise<unknown> {
    this.start()
    const id = this.nextId++

    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })

      this.sendLine({
        jsonrpc: '2.0',
        id,
        method,
        params,
      } satisfies JsonRpcCall)
    })
  }

  private async ensureInitialized(): Promise<void> {
    if (this.initialized) return

    await this.call('initialize', {
      clientInfo: {
        name: 'codex-web-local',
        version: '0.1.0',
      },
    })

    this.initialized = true
  }

  async rpc(method: string, params: unknown): Promise<unknown> {
    await this.ensureInitialized()
    return this.call(method, params)
  }

  onNotification(listener: (value: { method: string; params: unknown }) => void): () => void {
    this.notificationListeners.add(listener)
    return () => {
      this.notificationListeners.delete(listener)
    }
  }

  async respondToServerRequest(payload: unknown): Promise<void> {
    await this.ensureInitialized()

    const body = asRecord(payload)
    if (!body) {
      throw new Error('Invalid response payload: expected object')
    }

    const id = body.id
    if (typeof id !== 'number' || !Number.isInteger(id)) {
      throw new Error('Invalid response payload: "id" must be an integer')
    }

    const rawError = asRecord(body.error)
    if (rawError) {
      const message = typeof rawError.message === 'string' && rawError.message.trim().length > 0
        ? rawError.message.trim()
        : 'Server request rejected by client'
      const code = typeof rawError.code === 'number' && Number.isFinite(rawError.code)
        ? Math.trunc(rawError.code)
        : -32000
      this.resolvePendingServerRequest(id, { error: { code, message } })
      return
    }

    if (!('result' in body)) {
      throw new Error('Invalid response payload: expected "result" or "error"')
    }

    this.resolvePendingServerRequest(id, { result: body.result })
  }

  listPendingServerRequests(): PendingServerRequest[] {
    return Array.from(this.pendingServerRequests.values())
  }

  dispose(): void {
    if (!this.process) return

    const proc = this.process
    this.stopping = true
    this.process = null
    this.initialized = false
    this.readBuffer = ''

    const failure = new Error('codex app-server stopped')
    for (const request of this.pending.values()) {
      request.reject(failure)
    }
    this.pending.clear()
    this.pendingServerRequests.clear()

    try {
      proc.stdin.end()
    } catch {
      // ignore close errors on shutdown
    }

    try {
      proc.kill('SIGTERM')
    } catch {
      // ignore kill errors on shutdown
    }

    const forceKillTimer = setTimeout(() => {
      if (!proc.killed) {
        try {
          proc.kill('SIGKILL')
        } catch {
          // ignore kill errors on shutdown
        }
      }
    }, 1500)
    forceKillTimer.unref()
  }
}

class MethodCatalog {
  private methodCache: string[] | null = null
  private notificationCache: string[] | null = null

  private async runGenerateSchemaCommand(outDir: string): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      const codexBin = prefixBin ? join(prefixBin, 'codex') : 'codex'
      const process = spawn(codexBin, ['app-server', 'generate-json-schema', '--out', outDir], {
        stdio: ['ignore', 'ignore', 'pipe'],
      })

      let stderr = ''

      process.stderr.setEncoding('utf8')
      process.stderr.on('data', (chunk: string) => {
        stderr += chunk
      })

      process.on('error', reject)
      process.on('exit', (code) => {
        if (code === 0) {
          resolve()
          return
        }

        reject(new Error(stderr.trim() || `generate-json-schema exited with code ${String(code)}`))
      })
    })
  }

  private extractMethodsFromClientRequest(payload: unknown): string[] {
    const root = asRecord(payload)
    const oneOf = Array.isArray(root?.oneOf) ? root.oneOf : []
    const methods = new Set<string>()

    for (const entry of oneOf) {
      const row = asRecord(entry)
      const properties = asRecord(row?.properties)
      const methodDef = asRecord(properties?.method)
      const methodEnum = Array.isArray(methodDef?.enum) ? methodDef.enum : []

      for (const item of methodEnum) {
        if (typeof item === 'string' && item.length > 0) {
          methods.add(item)
        }
      }
    }

    return Array.from(methods).sort((a, b) => a.localeCompare(b))
  }

  private extractMethodsFromServerNotification(payload: unknown): string[] {
    const root = asRecord(payload)
    const oneOf = Array.isArray(root?.oneOf) ? root.oneOf : []
    const methods = new Set<string>()

    for (const entry of oneOf) {
      const row = asRecord(entry)
      const properties = asRecord(row?.properties)
      const methodDef = asRecord(properties?.method)
      const methodEnum = Array.isArray(methodDef?.enum) ? methodDef.enum : []

      for (const item of methodEnum) {
        if (typeof item === 'string' && item.length > 0) {
          methods.add(item)
        }
      }
    }

    return Array.from(methods).sort((a, b) => a.localeCompare(b))
  }

  async listMethods(): Promise<string[]> {
    if (this.methodCache) {
      return this.methodCache
    }

    const outDir = await mkdtemp(join(tmpdir(), 'codex-web-local-schema-'))
    await this.runGenerateSchemaCommand(outDir)

    const clientRequestPath = join(outDir, 'ClientRequest.json')
    const raw = await readFile(clientRequestPath, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    const methods = this.extractMethodsFromClientRequest(parsed)

    this.methodCache = methods
    return methods
  }

  async listNotificationMethods(): Promise<string[]> {
    if (this.notificationCache) {
      return this.notificationCache
    }

    const outDir = await mkdtemp(join(tmpdir(), 'codex-web-local-schema-'))
    await this.runGenerateSchemaCommand(outDir)

    const serverNotificationPath = join(outDir, 'ServerNotification.json')
    const raw = await readFile(serverNotificationPath, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    const methods = this.extractMethodsFromServerNotification(parsed)

    this.notificationCache = methods
    return methods
  }
}

type CodexBridgeMiddleware = ((req: IncomingMessage, res: ServerResponse, next: () => void) => Promise<void>) & {
  dispose: () => void
}

type SharedBridgeState = {
  appServer: AppServerProcess
  methodCatalog: MethodCatalog
}

const SHARED_BRIDGE_KEY = '__codexRemoteSharedBridge__'

function getSharedBridgeState(): SharedBridgeState {
  const globalScope = globalThis as typeof globalThis & {
    [SHARED_BRIDGE_KEY]?: SharedBridgeState
  }

  const existing = globalScope[SHARED_BRIDGE_KEY]
  if (existing) return existing

  const created: SharedBridgeState = {
    appServer: new AppServerProcess(),
    methodCatalog: new MethodCatalog(),
  }
  globalScope[SHARED_BRIDGE_KEY] = created
  return created
}

export function createCodexBridgeMiddleware(): CodexBridgeMiddleware {
  const { appServer, methodCatalog } = getSharedBridgeState()

  const middleware = async (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    try {
      if (!req.url) {
        next()
        return
      }

      const url = new URL(req.url, 'http://localhost')

      if (req.method === 'POST' && url.pathname === '/codex-api/rpc') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 503, { error: appServer.getUnavailableReason() || 'Codex CLI not installed' })
          return
        }
        const payload = await readJsonBody(req)
        const body = asRecord(payload) as RpcProxyRequest | null

        if (!body || typeof body.method !== 'string' || body.method.length === 0) {
          setJson(res, 400, { error: 'Invalid body: expected { method, params? }' })
          return
        }

        let nextParams: unknown = body.params ?? null
        if (shouldInjectDeveloperInstructions(body.method)) {
          const injected = await buildInjectedDeveloperInstructions()
          if (injected.length > 0) {
            const paramsRecord = asRecord(nextParams) ?? {}
            paramsRecord.developerInstructions = mergeDeveloperInstructions(
              paramsRecord.developerInstructions,
              injected,
            )
            nextParams = paramsRecord
          }
        }

        const result = await appServer.rpc(body.method, nextParams)
        setJson(res, 200, { result })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/availability') {
        setJson(res, 200, {
          ok: appServer.isCommandAvailable(),
          reason: appServer.getUnavailableReason(),
        })
        return
      }

      if (req.method === 'GET' && url.pathname === '/openclaw-api/health') {
        const nativeHealth = await tryRunOpenClawGatewayCall('health', {})
        if (nativeHealth !== null) {
          const record = asRecord(nativeHealth) ?? {}
          setJson(res, 200, {
            ok: true,
            data: {
              mode: 'native-gateway',
              gatewayRequired: true,
              ...record,
            },
          })
          return
        }

        setJson(res, 200, {
          ok: true,
          data: {
            mode: 'lightweight-proxy',
            gatewayRequired: false,
          },
        })
        return
      }

      if (req.method === 'GET' && url.pathname === '/openclaw-api/sessions') {
        const limit = readPositiveInt(url.searchParams.get('limit'), 200)
        if (await isNativeOpenClawReady()) {
          try {
            const nativeSessions = await runOpenClawGatewayCall(
              'sessions.list',
              {
                limit,
                includeDerivedTitles: true,
                includeLastMessage: true,
                includeGlobal: true,
                includeUnknown: true,
              },
              OPENCLAW_HISTORY_CALL_TIMEOUT_MS,
            )
            const record = asRecord(nativeSessions)
            const sessions = Array.isArray(record?.sessions)
              ? record.sessions
                .map((row) => toOpenClawSessionSummary(asRecord(row) ?? {}))
                .filter((row): row is Record<string, unknown> => row !== null)
              : []
            setJson(res, 200, { sessions })
            return
          } catch {
            invalidateOpenClawNativeReadyCache()
            if (OPENCLAW_NATIVE_STRICT_MODE) {
              setOpenClawNativeUnavailable(
                res,
                502,
                'OpenClaw sessions temporarily unavailable',
                'openclaw_native_sessions_unavailable',
              )
              return
            }
          }
        }
        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        const sessions = await listLightweightSessions(limit)
        setJson(res, 200, { sessions })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/history') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        if (!sessionKey) {
          setJson(res, 400, { error: 'Missing sessionKey' })
          return
        }
        const limit = readPositiveInt(String(payload?.limit ?? ''), 60)
        if (await isNativeOpenClawReady()) {
          try {
            const nativeHistory = await runOpenClawGatewayCall(
              'chat.history',
              {
                sessionKey,
                limit,
              },
              OPENCLAW_HISTORY_CALL_TIMEOUT_MS,
            )
            const record = asRecord(nativeHistory) ?? {}
            setJson(res, 200, {
              sessionKey: normalizeText(record.sessionKey) || sessionKey,
              messages: Array.isArray(record.messages) ? record.messages : [],
              thinkingLevel: normalizeText(record.thinkingLevel) || 'medium',
            })
            return
          } catch {
            invalidateOpenClawNativeReadyCache()
            if (OPENCLAW_NATIVE_STRICT_MODE) {
              setOpenClawNativeUnavailable(
                res,
                502,
                'OpenClaw history temporarily unavailable',
                'openclaw_native_history_unavailable',
              )
              return
            }
          }
        }
        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        const result = await readLightweightHistory(sessionKey, limit)
        setJson(res, 200, result)
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/send') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        const message = normalizeText(payload?.message)
        const attachments = normalizeOpenClawAttachments(payload?.attachments)
        if (!sessionKey || (!message && attachments.length === 0)) {
          setJson(res, 400, { error: 'Missing sessionKey and message/attachments' })
          return
        }

        const sendTimeoutMs = normalizeTimeoutMs(
          payload?.timeoutMs,
          OPENCLAW_CHAT_SEND_TIMEOUT_MS,
          8_000,
          240_000,
        )

        if (await isNativeOpenClawReady()) {
          try {
            const nativeRun = await runOpenClawGatewayCall(
              'chat.send',
              {
                sessionKey,
                message,
                deliver: payload?.deliver === true,
                attachments: attachments.length > 0 ? attachments : undefined,
                timeoutMs: sendTimeoutMs,
                idempotencyKey: `mobile-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
              },
              sendTimeoutMs + 20_000,
            )
            const record = asRecord(nativeRun)
            const runId = normalizeText(record?.runId)
            if (!runId) {
              setJson(res, 502, { error: 'chat.send missing runId' })
              return
            }
            rememberOpenClawNativeReady(true)
            rememberOpenClawNativeRun(runId, sessionKey)
            setJson(res, 200, { ok: true, runId })
            return
          } catch {
            invalidateOpenClawNativeReadyCache()
            if (OPENCLAW_NATIVE_STRICT_MODE) {
              setOpenClawNativeUnavailable(
                res,
                502,
                'OpenClaw send temporarily unavailable',
                'openclaw_native_send_unavailable',
              )
              return
            }
          }
        }
        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        const run = await sendLightweightMessage({
          sessionKey,
          message,
          deliver: payload?.deliver === true,
          attachments: attachments.length > 0 ? attachments : undefined,
        })
        setJson(res, 200, { ok: true, runId: run.runId })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/run/wait') {
        const payload = asRecord(await readJsonBody(req))
        const runId = normalizeText(payload?.runId)
        if (!runId) {
          setJson(res, 400, { error: 'Missing runId' })
          return
        }

        const waitTimeoutMs = normalizeTimeoutMs(
          payload?.timeoutMs,
          OPENCLAW_RUN_WAIT_TIMEOUT_MS,
          2_000,
          120_000,
        )

        if (await isNativeOpenClawReady()) {
          const waitAttempts = 3
          for (let attempt = 0; attempt < waitAttempts; attempt += 1) {
            try {
              const nativeWait = await runOpenClawGatewayCall(
                'agent.wait',
                {
                  runId,
                  timeoutMs: waitTimeoutMs,
                },
                waitTimeoutMs + 30_000,
              )
              const record = asRecord(nativeWait) ?? {}
              const rawStatus = normalizeText(record.status).toLowerCase()
              const status = rawStatus === 'timeout' ? 'running' : (rawStatus || 'running')
              const completed = OPENCLAW_COMPLETED_RUN_STATUSES.has(status)
              rememberOpenClawNativeReady(true)
              updateOpenClawNativeRun(runId, status, '')
              if (completed) {
                clearOpenClawNativeRun(runId)
              }
              setJson(res, 200, {
                ok: true,
                runId,
                status,
                completed,
                result: record.result ?? null,
                error: record.error ?? null,
                rawStatus,
              })
              return
            } catch (error) {
              invalidateOpenClawNativeReadyCache()
              const errorMessage = getErrorMessage(error, 'agent.wait failed')
              const retryable = isOpenClawGatewayRetryableError(errorMessage)
              updateOpenClawNativeRun(runId, 'reconnecting', errorMessage)
              if (retryable && attempt < waitAttempts - 1) {
                await sleepMs(500 + attempt * 800)
                continue
              }
              const probe = await probeOpenClawRunCompletionByHistory(runId)
              if (probe) {
                if (probe.completed) {
                  clearOpenClawNativeRun(runId)
                } else {
                  updateOpenClawNativeRun(runId, probe.status, '')
                }
                setJson(res, 200, {
                  ok: probe.completed,
                  runId,
                  status: probe.status,
                  completed: probe.completed,
                  result: probe.result ?? null,
                  error: probe.error ?? null,
                  source: 'history-probe',
                })
                return
              }
              setJson(res, 200, {
                ok: false,
                runId,
                status: retryable ? 'reconnecting' : 'failed',
                completed: false,
                error: errorMessage,
                retryable,
              })
              return
            }
          }
        }
        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setJson(res, 503, {
            ok: false,
            runId,
            status: 'reconnecting',
            completed: false,
            error: 'OpenClaw gateway unavailable',
            code: 'openclaw_native_unavailable',
            retryable: true,
          })
          return
        }

        setJson(res, 200, {
          ok: true,
          runId,
          status: 'completed',
          completed: true,
          source: 'lightweight',
        })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/heartbeat/trigger') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)

        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        try {
          if (await isNativeOpenClawReady()) {
            try {
              const cronList = await runOpenClawGatewayCall(
                'cron.list',
                {
                  includeDisabled: false,
                  limit: 200,
                  offset: 0,
                },
                20_000,
              )
              const heartbeatJob = findHeartbeatCronJob(cronList)
              if (heartbeatJob) {
                const cronRun = await runOpenClawGatewayCall(
                  'cron.run',
                  {
                    id: heartbeatJob.id,
                    mode: 'force',
                  },
                  30_000,
                )
                const cronRunRecord = asRecord(cronRun) ?? {}
                const runId = normalizeText(cronRunRecord.runId)
                if (runId && sessionKey) {
                  rememberOpenClawNativeRun(runId, sessionKey)
                }
                setJson(res, 200, {
                  ok: true,
                  status: 'submitted',
                  runId,
                  source: 'cron.run',
                  message: `Triggered ${heartbeatJob.name}`,
                })
                return
              }
            } catch {
              invalidateOpenClawNativeReadyCache()
            }

            if (!sessionKey) {
              setJson(res, 400, {
                ok: false,
                error: 'Missing sessionKey for heartbeat fallback',
              })
              return
            }

            const fallbackRun = await runOpenClawGatewayCall(
              'chat.send',
              {
                sessionKey,
                message: OPENCLAW_HEARTBEAT_PROMPT,
                deliver: false,
                timeoutMs: OPENCLAW_CHAT_SEND_TIMEOUT_MS,
                idempotencyKey: `heartbeat-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
              },
              OPENCLAW_CHAT_SEND_TIMEOUT_MS + 20_000,
            )
            const record = asRecord(fallbackRun) ?? {}
            const runId = normalizeText(record.runId)
            if (runId) {
              rememberOpenClawNativeRun(runId, sessionKey)
            }
            setJson(res, 200, {
              ok: true,
              status: runId ? 'submitted' : 'running',
              runId,
              source: 'chat.send',
              message: 'Heartbeat fallback message submitted',
            })
            return
          }
        } catch {
          invalidateOpenClawNativeReadyCache()
          setOpenClawNativeUnavailable(
            res,
            502,
            'OpenClaw heartbeat trigger failed',
            'openclaw_native_heartbeat_unavailable',
          )
          return
        }

        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        setJson(res, 503, {
          ok: false,
          error: 'OpenClaw heartbeat unavailable',
        })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/run/abort') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        const runId = normalizeText(payload?.runId)
        if (!sessionKey && !runId) {
          setJson(res, 400, { error: 'Missing sessionKey or runId' })
          return
        }

        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        try {
          if (await isNativeOpenClawReady()) {
            const params: Record<string, unknown> = {}
            if (sessionKey) params.sessionKey = sessionKey
            if (runId) params.runId = runId
            await runOpenClawGatewayCall(
              'chat.abort',
              params,
              20_000,
            )
            if (runId) {
              clearOpenClawNativeRun(runId)
            }
            setJson(res, 200, {
              ok: true,
              aborted: true,
              status: 'aborted',
              source: 'chat.abort',
            })
            return
          }
        } catch {
          invalidateOpenClawNativeReadyCache()
          setOpenClawNativeUnavailable(
            res,
            502,
            'OpenClaw abort failed',
            'openclaw_native_abort_unavailable',
          )
          return
        }

        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        setJson(res, 503, {
          ok: false,
          aborted: false,
          status: 'failed',
          source: 'unavailable',
        })
        return
      }


      if (req.method === 'POST' && url.pathname === '/openclaw-api/attachments/upload-stream') {
        const fileName = sanitizeOpenClawUploadFileName(
          normalizeText(url.searchParams.get('fileName')) ||
          readHeaderText(req.headers['x-file-name']) ||
          'attachment.bin',
        )
        const contentType = readHeaderText(req.headers['content-type'])
        const mimeType =
          normalizeText(url.searchParams.get('mimeType')) ||
          readHeaderText(req.headers['x-mime-type']) ||
          (contentType.split(';')[0]?.trim() || 'application/octet-stream')

        await mkdir(OPENCLAW_UPLOAD_DIR, { recursive: true })
        const storedName = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}-${fileName}`
        const storedPath = join(OPENCLAW_UPLOAD_DIR, storedName)

        try {
          const sizeBytes = await writeOpenClawUploadStream(
            req,
            storedPath,
            OPENCLAW_UPLOAD_STREAM_MAX_BYTES,
          )
          setJson(res, 200, {
            ok: true,
            path: storedPath,
            fileName,
            mimeType,
            sizeBytes,
          })
        } catch (error) {
          await unlink(storedPath).catch(() => undefined)
          const message = error instanceof Error ? error.message : 'Attachment upload failed'
          if (message.includes('Attachment exceeds size limit')) {
            setJson(res, 400, { error: message })
          } else {
            setJson(res, 500, { error: message })
          }
        }
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/attachments/upload') {
        const payload = asRecord(await readJsonBody(req))
        const fileName = sanitizeOpenClawUploadFileName(normalizeText(payload?.fileName))
        const mimeType = normalizeText(payload?.mimeType) || 'application/octet-stream'
        const contentBase64 = normalizeText(payload?.contentBase64)
        if (!contentBase64) {
          setJson(res, 400, { error: 'Missing attachment content' })
          return
        }

        const decoded = decodeStrictBase64(contentBase64)
        if (decoded.length > OPENCLAW_UPLOAD_MAX_BYTES) {
          setJson(res, 400, { error: 'Attachment exceeds size limit (1000MB)' })
          return
        }

        await mkdir(OPENCLAW_UPLOAD_DIR, { recursive: true })
        const storedName = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}-${fileName}`
        const storedPath = join(OPENCLAW_UPLOAD_DIR, storedName)
        await writeFile(storedPath, decoded, { mode: 0o600 })

        setJson(res, 200, {
          ok: true,
          path: storedPath,
          fileName,
          mimeType,
          sizeBytes: decoded.length,
        })
        return
      }

      if (req.method === 'POST' && (url.pathname === '/openclaw-api/sessions/new-independent' || url.pathname === '/openclaw-api/sessions/new')) {
        const payload = asRecord(await readJsonBody(req))
        const label = normalizeText(payload?.label) || '新会话'
        const currentSessionKey = normalizeText(payload?.currentSessionKey)
        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }
        const sessionKey = await (await isNativeOpenClawReady()
          ? createIndependentOpenClawSession(currentSessionKey, label)
          : createLightweightSession(label, currentSessionKey))
        setJson(res, 200, { sessionKey })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/sessions/reset') {
        const payload = asRecord(await readJsonBody(req))
        const currentSessionKey = normalizeText(payload?.currentSessionKey)
        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }
        const sessionKey = await (await isNativeOpenClawReady()
          ? resetCurrentOpenClawSession(currentSessionKey)
          : resetLightweightSession(currentSessionKey))
        setJson(res, 200, { sessionKey })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/sessions/rename') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        const label = normalizeText(payload?.label)
        if (!sessionKey || !label) {
          setJson(res, 400, { error: 'Missing sessionKey or label' })
          return
        }
        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }
        if (await isNativeOpenClawReady()) {
          await runOpenClawGatewayCall('sessions.patch', {
            key: sessionKey,
            label,
          })
        } else {
          await renameLightweightSession(sessionKey, label)
        }
        setJson(res, 200, { ok: true })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/sessions/delete') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        if (!sessionKey) {
          setJson(res, 400, { error: 'Missing sessionKey' })
          return
        }
        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }
        if (await isNativeOpenClawReady()) {
          await runOpenClawGatewayCall('sessions.delete', {
            key: sessionKey,
            deleteTranscript: true,
            emitLifecycleHooks: false,
          })
        } else {
          await deleteLightweightSession(sessionKey)
        }
        setJson(res, 200, { ok: true })
        return
      }

      if (req.method === 'POST' && url.pathname === '/codex-api/server-requests/respond') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 503, { error: appServer.getUnavailableReason() || 'Codex CLI not installed' })
          return
        }
        const payload = await readJsonBody(req)
        await appServer.respondToServerRequest(payload)
        setJson(res, 200, { ok: true })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/server-requests/pending') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 200, { data: [] })
          return
        }
        setJson(res, 200, { data: appServer.listPendingServerRequests() })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/meta/methods') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 200, { data: [] })
          return
        }
        const methods = await methodCatalog.listMethods()
        setJson(res, 200, { data: methods })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/meta/notifications') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 200, { data: [] })
          return
        }
        const methods = await methodCatalog.listNotificationMethods()
        setJson(res, 200, { data: methods })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/events') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 503, { error: appServer.getUnavailableReason() || 'Codex CLI not installed' })
          return
        }
        res.statusCode = 200
        res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
        res.setHeader('Cache-Control', 'no-cache, no-transform')
        res.setHeader('Connection', 'keep-alive')
        res.setHeader('X-Accel-Buffering', 'no')

        const unsubscribe = appServer.onNotification((notification) => {
          if (res.writableEnded || res.destroyed) return
          const payload = {
            ...notification,
            atIso: new Date().toISOString(),
          }
          res.write(`data: ${JSON.stringify(payload)}\n\n`)
        })

        res.write(`event: ready\ndata: ${JSON.stringify({ ok: true })}\n\n`)
        const keepAlive = setInterval(() => {
          res.write(': ping\n\n')
        }, 15000)

        const close = () => {
          clearInterval(keepAlive)
          unsubscribe()
          if (!res.writableEnded) {
            res.end()
          }
        }

        req.on('close', close)
        req.on('aborted', close)
        return
      }

      next()
    } catch (error) {
      const message = getErrorMessage(error, 'Unknown bridge error')
      setJson(res, 502, { error: message })
    }
  }

  middleware.dispose = () => {
    appServer.dispose()
  }

  return middleware
}
