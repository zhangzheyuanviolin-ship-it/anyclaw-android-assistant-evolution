import type {
  OpenClawHistoryRequest,
  OpenClawHistoryResponse,
  OpenClawSendRequest,
  OpenClawSendResponse,
  OpenClawSessionSummary,
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

async function requestOpenClaw<T>(
  path: string,
  options: RequestInit = {},
  fallbackMessage = 'OpenClaw request failed',
  timeoutMs = 9000,
): Promise<T> {
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
  const timerHost = typeof globalThis !== 'undefined' ? globalThis : null
  const timeout =
    controller && timeoutMs > 0 && timerHost && typeof timerHost.setTimeout === 'function'
      ? timerHost.setTimeout(() => {
          controller.abort()
        }, timeoutMs)
      : null

  let response: Response
  try {
    response = await fetch(path, {
      ...options,
      signal: controller?.signal,
    })
  } catch (error) {
    if (timeout !== null && timerHost && typeof timerHost.clearTimeout === 'function') {
      timerHost.clearTimeout(timeout)
    }
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error(`${fallbackMessage}: request timeout`)
    }
    throw error
  }

  if (timeout !== null && timerHost && typeof timerHost.clearTimeout === 'function') {
    timerHost.clearTimeout(timeout)
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

export async function createOpenClawSession(label?: string): Promise<{ sessionKey: string }> {
  const payload = await requestOpenClaw<{ sessionKey?: string }>(
    '/openclaw-api/sessions/new',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ label: label?.trim() || '' }),
    },
    'Failed to create OpenClaw session',
  )

  const sessionKey = readString(payload.sessionKey).trim()
  if (!sessionKey) {
    throw new Error('Failed to create OpenClaw session: missing session key')
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
  const message = request.message.trim()

  if (!sessionKey) {
    throw new Error('OpenClaw send requires session key')
  }
  if (!message) {
    throw new Error('OpenClaw send requires message')
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
      }),
    },
    'Failed to send OpenClaw message',
  )

  return {
    ok: payload?.ok === true,
    runId: readString(payload?.runId),
  }
}
