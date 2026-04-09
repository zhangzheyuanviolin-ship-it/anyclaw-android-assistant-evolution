import type {
  ClaudeHistoryRequest,
  ClaudeHistoryResponse,
  ClaudeRunWaitRequest,
  ClaudeRunWaitResponse,
  ClaudeSendRequest,
  ClaudeSendResponse,
  ClaudeSessionSummary,
} from '../types/claude'

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

async function requestClaude<T>(
  path: string,
  options: RequestInit = {},
  fallbackMessage = 'Claude request failed',
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

function normalizeSessionRow(value: unknown): ClaudeSessionSummary | null {
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

export async function getClaudeHealth(): Promise<{ ok: boolean }> {
  const payload = await requestClaude<{ ok?: boolean }>('/claude-api/health', undefined, 'Claude health check failed')
  return {
    ok: payload?.ok === true,
  }
}

export async function listClaudeSessions(limit = 200): Promise<ClaudeSessionSummary[]> {
  const payload = await requestClaude<{ sessions?: unknown[] }>(
    `/claude-api/sessions?limit=${encodeURIComponent(String(limit))}`,
    undefined,
    'Failed to load Claude sessions',
    20_000,
  )
  const rows = Array.isArray(payload.sessions) ? payload.sessions : []
  const normalized: ClaudeSessionSummary[] = []
  for (const row of rows) {
    const parsed = normalizeSessionRow(row)
    if (parsed) normalized.push(parsed)
  }
  normalized.sort((first, second) => second.updatedAtMs - first.updatedAtMs)
  return normalized
}

export async function createClaudeSession(
  label?: string,
  currentSessionKey?: string,
): Promise<{ sessionKey: string }> {
  const payload = await requestClaude<{ sessionKey?: string }>(
    '/claude-api/sessions/new-independent',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        label: label?.trim() || '',
        currentSessionKey: currentSessionKey?.trim() || '',
      }),
    },
    'Failed to create Claude session',
    20_000,
  )
  const sessionKey = readString(payload.sessionKey).trim()
  if (!sessionKey) {
    throw new Error('Failed to create Claude session: missing session key')
  }
  return { sessionKey }
}

export async function resetClaudeSession(currentSessionKey: string): Promise<{ sessionKey: string }> {
  const nextSessionKey = currentSessionKey.trim()
  if (!nextSessionKey) {
    throw new Error('Failed to reset Claude session: missing current session key')
  }
  const payload = await requestClaude<{ sessionKey?: string }>(
    '/claude-api/sessions/reset',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        currentSessionKey: nextSessionKey,
      }),
    },
    'Failed to reset Claude session',
    20_000,
  )
  const sessionKey = readString(payload.sessionKey).trim()
  if (!sessionKey) {
    throw new Error('Failed to reset Claude session: missing session key')
  }
  return { sessionKey }
}

export async function renameClaudeSession(sessionKey: string, label: string): Promise<void> {
  const nextKey = sessionKey.trim()
  const nextLabel = label.trim()
  if (!nextKey || !nextLabel) return
  await requestClaude<void>(
    '/claude-api/sessions/rename',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionKey: nextKey, label: nextLabel }),
    },
    'Failed to rename Claude session',
  )
}

export async function readClaudeHistory(request: ClaudeHistoryRequest): Promise<ClaudeHistoryResponse> {
  const sessionKey = request.sessionKey.trim()
  if (!sessionKey) {
    throw new Error('Claude history request requires session key')
  }
  const payload = await requestClaude<ClaudeHistoryResponse>(
    '/claude-api/history',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionKey,
        limit: request.limit,
      }),
    },
    'Failed to load Claude history',
    60_000,
  )
  const messages = Array.isArray(payload.messages) ? payload.messages : []
  return {
    sessionKey: readString(payload.sessionKey).trim() || sessionKey,
    messages,
  }
}

export async function sendClaudeMessage(request: ClaudeSendRequest): Promise<ClaudeSendResponse> {
  const sessionKey = request.sessionKey.trim()
  const message = (request.message ?? '').trim()
  const attachmentPaths = Array.isArray(request.attachmentPaths)
    ? request.attachmentPaths.map((row) => row.trim()).filter((row) => row.length > 0)
    : []
  if (!sessionKey) {
    throw new Error('Claude send requires session key')
  }
  if (!message && attachmentPaths.length === 0) {
    throw new Error('Claude send requires message or attachment')
  }
  const payload = await requestClaude<ClaudeSendResponse>(
    '/claude-api/send',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionKey,
        message,
        attachmentPaths,
        allowSharedStorage: request.allowSharedStorage === true,
        dangerousMode: request.dangerousMode === true,
      }),
    },
    'Failed to send Claude message',
    240_000,
  )
  return {
    ok: payload?.ok === true,
    runId: readString(payload?.runId),
  }
}

export async function waitClaudeRun(request: ClaudeRunWaitRequest): Promise<ClaudeRunWaitResponse> {
  const runId = request.runId.trim()
  if (!runId) {
    throw new Error('Claude run wait requires runId')
  }
  const timeoutMs =
    typeof request.timeoutMs === 'number' && Number.isFinite(request.timeoutMs)
      ? Math.min(120_000, Math.max(2_000, Math.floor(request.timeoutMs)))
      : 12_000
  const payload = await requestClaude<ClaudeRunWaitResponse>(
    '/claude-api/run/wait',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        runId,
        timeoutMs,
      }),
    },
    'Failed to wait Claude run',
    timeoutMs + 20_000,
  )
  return {
    ok: readBoolean(payload?.ok),
    runId: readString(payload?.runId).trim() || runId,
    status: readString(payload?.status).trim() || 'running',
    completed: readBoolean(payload?.completed),
    result: payload?.result,
    error: payload?.error,
  }
}

export async function abortClaudeRun(request: {
  sessionKey?: string
  runId?: string
}): Promise<{
  ok: boolean
  aborted: boolean
  status: string
}> {
  const payload = await requestClaude<{
    ok?: boolean
    aborted?: boolean
    status?: string
  }>(
    '/claude-api/run/abort',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionKey: request.sessionKey?.trim() || '',
        runId: request.runId?.trim() || '',
      }),
    },
    'Failed to abort Claude run',
    25_000,
  )
  return {
    ok: readBoolean(payload?.ok),
    aborted: readBoolean(payload?.aborted),
    status: readString(payload?.status).trim() || 'aborted',
  }
}

export async function uploadClaudeAttachmentBinary(payload: {
  fileName: string
  mimeType: string
  file: Blob
}): Promise<{ path: string; fileName: string; sizeBytes: number }> {
  const fileName = payload.fileName.trim() || 'attachment.bin'
  const mimeType = payload.mimeType.trim() || 'application/octet-stream'
  const endpoint = `/claude-api/attachments/upload-stream?fileName=${encodeURIComponent(fileName)}&mimeType=${encodeURIComponent(mimeType)}`
  const response = await requestClaude<{ path?: string; fileName?: string; sizeBytes?: number }>(
    endpoint,
    {
      method: 'POST',
      headers: { 'Content-Type': mimeType },
      body: payload.file,
    },
    'Failed to upload Claude attachment',
    600_000,
  )
  const storedPath = readString(response.path).trim()
  if (!storedPath) {
    throw new Error('Failed to upload Claude attachment: missing path')
  }
  return {
    path: storedPath,
    fileName: readString(response.fileName).trim() || fileName,
    sizeBytes: readNumber(response.sizeBytes),
  }
}
