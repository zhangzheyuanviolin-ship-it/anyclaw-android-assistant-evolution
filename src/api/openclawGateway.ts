import type {
  OpenClawImageAttachment,
  OpenClawHistoryRequest,
  OpenClawHistoryResponse,
  OpenClawSendRequest,
  OpenClawSendResponse,
  OpenClawSessionSummary,
  OpenClawRunWaitRequest,
  OpenClawRunWaitResponse,
} from '../types/openclaw'

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

function readString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function readNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function readBoolean(value: unknown): boolean {
  return value === true
}

async function requestOpenClaw<T>(
  path: string,
  options: RequestInit = {},
  fallbackMessage = 'OpenClaw request failed',
  timeoutMs = 9000,
): Promise<T> {
  const timerHost = typeof globalThis !== 'undefined' ? globalThis : null
  let response: Response | null = null
  let lastError: unknown = null

  for (let attempt = 0; attempt < 3; attempt += 1) {
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
    const timeout =
      controller && timeoutMs > 0 && timerHost && typeof timerHost.setTimeout === 'function'
        ? timerHost.setTimeout(() => {
            controller.abort()
          }, timeoutMs)
        : null

    try {
      response = await fetch(path, {
        ...options,
        signal: controller?.signal,
      })
      if (timeout !== null && timerHost && typeof timerHost.clearTimeout === 'function') {
        timerHost.clearTimeout(timeout)
      }
      if (response.status >= 500 && attempt < 2) {
        await new Promise((resolve) => timerHost?.setTimeout?.(resolve, 300 + attempt * 300) ?? resolve(null))
        continue
      }
      break
    } catch (error) {
      if (timeout !== null && timerHost && typeof timerHost.clearTimeout === 'function') {
        timerHost.clearTimeout(timeout)
      }
      lastError = error
      const message = error instanceof Error ? error.message : String(error ?? '')
      const isTimeout = error instanceof DOMException && error.name === 'AbortError'
      const retryable = isTimeout || /failed to fetch/i.test(message)
      if (attempt < 2 && retryable) {
        await new Promise((resolve) => timerHost?.setTimeout?.(resolve, 250 + attempt * 250) ?? resolve(null))
        continue
      }
      if (isTimeout) {
        throw new Error(`${fallbackMessage}: request timeout`)
      }
      throw error
    }
  }

  if (!response) {
    throw (lastError instanceof Error ? lastError : new Error(fallbackMessage))
  }

  let payload: unknown = null
  try {
    payload = await response.json()
  } catch {
    payload = null
  }

  if (!response.ok) {
    const record = asRecord(payload)
    const error = readString(record?.error)
    throw new Error(error || `${fallbackMessage} (HTTP ${response.status})`)
  }

  return payload as T
}

function normalizeSessionRow(value: unknown): OpenClawSessionSummary | null {
  const row = asRecord(value)
  if (!row) return null

  const key = readString(row.key).trim()
  if (!key) return null

  const title =
    readString(row.displayName).trim() ||
    readString(row.label).trim() ||
    key

  return {
    key,
    title,
    updatedAtMs: readNumber(row.updatedAt),
    preview: readString(row.lastMessagePreview).trim(),
    modelProvider: readString(row.modelProvider).trim(),
    model: readString(row.model).trim(),
  }
}

export async function getOpenClawHealth(): Promise<{ ok: boolean }> {
  const payload = await requestOpenClaw<{ ok?: boolean }>('/openclaw-api/health', undefined, 'OpenClaw health check failed')
  return {
    ok: payload?.ok === true,
  }
}

export async function listOpenClawSessions(limit = 200): Promise<OpenClawSessionSummary[]> {
  const payload = await requestOpenClaw<{ sessions?: unknown[] }>(
    `/openclaw-api/sessions?limit=${encodeURIComponent(String(limit))}`,
    undefined,
    'Failed to load OpenClaw sessions',
    20_000,
  )

  const rows = Array.isArray(payload.sessions) ? payload.sessions : []
  const normalized: OpenClawSessionSummary[] = []

  for (const row of rows) {
    const parsed = normalizeSessionRow(row)
    if (parsed) normalized.push(parsed)
  }

  normalized.sort((first, second) => second.updatedAtMs - first.updatedAtMs)
  return normalized
}

export async function createOpenClawSession(
  label?: string,
  currentSessionKey?: string,
): Promise<{ sessionKey: string }> {
  const payload = await requestOpenClaw<{ sessionKey?: string }>(
    '/openclaw-api/sessions/new-independent',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        label: label?.trim() || '',
        currentSessionKey: currentSessionKey?.trim() || '',
      }),
    },
    'Failed to create OpenClaw session',
    35_000,
  )

  const sessionKey = readString(payload.sessionKey).trim()
  if (!sessionKey) {
    throw new Error('Failed to create OpenClaw session: missing session key')
  }

  return { sessionKey }
}

export async function resetOpenClawSession(currentSessionKey: string): Promise<{ sessionKey: string }> {
  const nextSessionKey = currentSessionKey.trim()
  if (!nextSessionKey) {
    throw new Error('Failed to reset OpenClaw session: missing current session key')
  }

  const payload = await requestOpenClaw<{ sessionKey?: string }>(
    '/openclaw-api/sessions/reset',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        currentSessionKey: nextSessionKey,
      }),
    },
    'Failed to reset OpenClaw session',
    20_000,
  )

  const sessionKey = readString(payload.sessionKey).trim()
  if (!sessionKey) {
    throw new Error('Failed to reset OpenClaw session: missing session key')
  }

  return { sessionKey }
}

export async function renameOpenClawSession(sessionKey: string, label: string): Promise<void> {
  const nextKey = sessionKey.trim()
  const nextLabel = label.trim()
  if (!nextKey || !nextLabel) return

  await requestOpenClaw<void>(
    '/openclaw-api/sessions/rename',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionKey: nextKey, label: nextLabel }),
    },
    'Failed to rename OpenClaw session',
  )
}

export async function readOpenClawHistory(request: OpenClawHistoryRequest): Promise<OpenClawHistoryResponse> {
  const sessionKey = request.sessionKey.trim()
  if (!sessionKey) {
    throw new Error('OpenClaw history request requires session key')
  }

  const payload = await requestOpenClaw<OpenClawHistoryResponse>(
    '/openclaw-api/history',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionKey,
        limit: request.limit,
      }),
    },
    'Failed to load OpenClaw history',
    60_000,
  )

  const messages = Array.isArray(payload.messages) ? payload.messages : []

  return {
    sessionKey: readString(payload.sessionKey).trim() || sessionKey,
    messages,
    thinkingLevel: readString(payload.thinkingLevel).trim(),
  }
}

export async function sendOpenClawMessage(request: OpenClawSendRequest): Promise<OpenClawSendResponse> {
  const sessionKey = request.sessionKey.trim()
  const message = (request.message ?? '').trim()
  const attachments = Array.isArray(request.attachments)
    ? request.attachments
      .map(normalizeImageAttachment)
      .filter((row): row is OpenClawImageAttachment => row !== null)
    : []

  if (!sessionKey) {
    throw new Error('OpenClaw send requires session key')
  }
  if (!message && attachments.length === 0) {
    throw new Error('OpenClaw send requires message or attachment')
  }

  const payload = await requestOpenClaw<OpenClawSendResponse>(
    '/openclaw-api/send',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionKey,
        message,
        deliver: request.deliver === true,
        attachments: attachments.length > 0 ? attachments : undefined,
      }),
    },
    'Failed to send OpenClaw message',
    240_000,
  )

  return {
    ok: payload?.ok === true,
    runId: readString(payload?.runId),
  }
}

export async function waitOpenClawRun(request: OpenClawRunWaitRequest): Promise<OpenClawRunWaitResponse> {
  const runId = request.runId.trim()
  if (!runId) {
    throw new Error('OpenClaw run wait requires runId')
  }

  const timeoutMs =
    typeof request.timeoutMs === 'number' && Number.isFinite(request.timeoutMs)
      ? Math.min(120_000, Math.max(2_000, Math.floor(request.timeoutMs)))
      : 12_000

  const payload = await requestOpenClaw<OpenClawRunWaitResponse>(
    '/openclaw-api/run/wait',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        runId,
        sessionKey: request.sessionKey?.trim() || '',
        timeoutMs,
      }),
    },
    'Failed to wait OpenClaw run',
    timeoutMs + 20_000,
  )

  return {
    ok: readBoolean(payload?.ok),
    runId: readString(payload?.runId).trim() || runId,
    status: readString(payload?.status).trim() || 'running',
    completed: readBoolean(payload?.completed),
    result: payload?.result,
    error: payload?.error,
    rawStatus: readString(payload?.rawStatus).trim(),
    source: readString(payload?.source).trim(),
    retryable: readBoolean(payload?.retryable),
    waiting: readBoolean(payload?.waiting),
    revision: readNumber(payload?.revision),
  }
}

export async function getOpenClawRunWatchdogStatus(request: {
  sessionKey: string
  suspectAfterMs?: number
  triggerAfterMs?: number
}): Promise<{
  ok: boolean
  sessionKey: string
  activeRun: boolean
  runId: string
  status: string
  completed: boolean
  recommendAction: string
}> {
  const sessionKey = request.sessionKey.trim()
  if (!sessionKey) {
    throw new Error('OpenClaw watchdog status requires session key')
  }

  const suspectAfterMs =
    typeof request.suspectAfterMs === 'number' && Number.isFinite(request.suspectAfterMs)
      ? Math.max(20_000, Math.min(300_000, Math.floor(request.suspectAfterMs)))
      : 75_000

  const triggerAfterMs =
    typeof request.triggerAfterMs === 'number' && Number.isFinite(request.triggerAfterMs)
      ? Math.max(suspectAfterMs + 5_000, Math.min(600_000, Math.floor(request.triggerAfterMs)))
      : 120_000

  const payload = await requestOpenClaw<{
    ok?: boolean
    sessionKey?: string
    activeRun?: boolean
    runId?: string
    status?: string
    completed?: boolean
    recommendAction?: string
  }>(
    '/openclaw-api/watchdog/status',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionKey,
        suspectAfterMs,
        triggerAfterMs,
      }),
    },
    'Failed to read OpenClaw watchdog status',
    10_000,
  )

  return {
    ok: readBoolean(payload?.ok),
    sessionKey: readString(payload?.sessionKey).trim() || sessionKey,
    activeRun: readBoolean(payload?.activeRun),
    runId: readString(payload?.runId).trim(),
    status: readString(payload?.status).trim() || 'idle',
    completed: readBoolean(payload?.completed),
    recommendAction: readString(payload?.recommendAction).trim() || 'none',
  }
}

export async function triggerOpenClawHeartbeat(request: {
  sessionKey?: string
}): Promise<{
  ok: boolean
  runId: string
  status: string
  source: string
  message: string
}> {
  const payload = await requestOpenClaw<{
    ok?: boolean
    runId?: string
    status?: string
    source?: string
    message?: string
  }>(
    '/openclaw-api/heartbeat/trigger',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionKey: request.sessionKey?.trim() || '',
      }),
    },
    'Failed to trigger OpenClaw heartbeat',
    35_000,
  )

  return {
    ok: readBoolean(payload?.ok),
    runId: readString(payload?.runId).trim(),
    status: readString(payload?.status).trim() || 'running',
    source: readString(payload?.source).trim() || 'unknown',
    message: readString(payload?.message).trim(),
  }
}

export async function abortOpenClawRun(request: {
  sessionKey?: string
  runId?: string
}): Promise<{
  ok: boolean
  aborted: boolean
  status: string
  source: string
}> {
  const payload = await requestOpenClaw<{
    ok?: boolean
    aborted?: boolean
    status?: string
    source?: string
  }>(
    '/openclaw-api/run/abort',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionKey: request.sessionKey?.trim() || '',
        runId: request.runId?.trim() || '',
      }),
    },
    'Failed to abort OpenClaw run',
    25_000,
  )

  return {
    ok: readBoolean(payload?.ok),
    aborted: readBoolean(payload?.aborted),
    status: readString(payload?.status).trim() || 'aborted',
    source: readString(payload?.source).trim() || 'unknown',
  }
}

function normalizeImageAttachment(value: unknown): OpenClawImageAttachment | null {
  const row = asRecord(value)
  if (!row) return null
  const type = readString(row.type).trim()
  const mimeType = readString(row.mimeType).trim()
  const content = readString(row.content).trim()
  const fileName = readString(row.fileName).trim()
  if (type !== 'image' || !mimeType || !content) return null
  return {
    type: 'image',
    mimeType,
    content,
    fileName: fileName || undefined,
  }
}

export async function uploadOpenClawAttachment(payload: {
  fileName: string
  mimeType: string
  contentBase64: string
}): Promise<{ path: string; fileName: string; sizeBytes: number }> {
  const response = await requestOpenClaw<{ path?: string; fileName?: string; sizeBytes?: number }>(
    '/openclaw-api/attachments/upload',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
    'Failed to upload OpenClaw attachment',
    20_000,
  )

  const path = readString(response.path).trim()
  if (!path) {
    throw new Error('Failed to upload OpenClaw attachment: missing path')
  }

  return {
    path,
    fileName: readString(response.fileName).trim() || payload.fileName,
    sizeBytes: readNumber(response.sizeBytes),
  }
}

export async function uploadOpenClawAttachmentBinary(payload: {
  fileName: string
  mimeType: string
  file: Blob
}): Promise<{ path: string; fileName: string; sizeBytes: number }> {
  const fileName = payload.fileName.trim() || 'attachment.bin'
  const mimeType = payload.mimeType.trim() || 'application/octet-stream'
  const endpoint = `/openclaw-api/attachments/upload-stream?fileName=${encodeURIComponent(fileName)}&mimeType=${encodeURIComponent(mimeType)}`

  const response = await requestOpenClaw<{ path?: string; fileName?: string; sizeBytes?: number }>(
    endpoint,
    {
      method: 'POST',
      headers: { 'Content-Type': mimeType },
      body: payload.file,
    },
    'Failed to upload OpenClaw attachment',
    600_000,
  )

  const storedPath = readString(response.path).trim()
  if (!storedPath) {
    throw new Error('Failed to upload OpenClaw attachment: missing path')
  }

  return {
    path: storedPath,
    fileName: readString(response.fileName).trim() || fileName,
    sizeBytes: readNumber(response.sizeBytes),
  }
}
