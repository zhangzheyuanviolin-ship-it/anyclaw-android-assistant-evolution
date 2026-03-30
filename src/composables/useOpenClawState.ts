import { computed, ref } from 'vue'
import {
  abortOpenClawRun,
  createOpenClawSession,
  getOpenClawHealth,
  listOpenClawSessions,
  readOpenClawHistory,
  renameOpenClawSession,
  resetOpenClawSession,
  sendOpenClawMessage,
  triggerOpenClawHeartbeat,
  uploadOpenClawAttachmentBinary,
  waitOpenClawRun,
} from '../api/openclawGateway'
import type { UiLiveOverlay, UiMessage } from '../types/codex'
import type {
  OpenClawComposerAttachment,
  OpenClawComposerImageAttachment,
  OpenClawComposerSubmitPayload,
  OpenClawContentItem,
  OpenClawHistoryMessage,
  OpenClawSessionSummary,
} from '../types/openclaw'

const HISTORY_LIMIT_STORAGE_KEY = 'anyclaw.openclaw.history.limit.v1'
const SHOW_PROCESS_STORAGE_KEY = 'anyclaw.openclaw.process.enabled.v1'
const HISTORY_DEFAULT = 60
const HISTORY_MIN = 20
const HISTORY_MAX = 400
const HISTORY_STEP = 40
const POLL_INTERVAL_MS = 4200
const RUN_WAIT_INTERVAL_MS = 7000
const RUN_WAIT_TIMEOUT_MS = 25000
const OPENCLAW_BOOTSTRAP_RETRY_LIMIT = 3
const OPENCLAW_BOOTSTRAP_COOLDOWN_MS = 5_000
const OPENCLAW_OPTIMISTIC_MESSAGE_TTL_MS = 10 * 60_000

type OptimisticOpenClawMessage = UiMessage & {
  createdAtMs: number
  sessionKey: string
}

type SessionSelectOptions = {
  syncHistory?: boolean
}

type HistoryRefreshOptions = {
  silent?: boolean
}

function safeJsonStringify(value: unknown, maxLength = 360): string {
  try {
    const serialized = JSON.stringify(value)
    if (!serialized) return ''
    if (serialized.length <= maxLength) return serialized
    return `${serialized.slice(0, maxLength)}...`
  } catch {
    return ''
  }
}

function readStorageNumber(key: string, fallback: number): number {
  if (typeof window === 'undefined') return fallback
  const raw = window.localStorage.getItem(key)
  if (!raw) return fallback
  const parsed = Number(raw)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(HISTORY_MAX, Math.max(HISTORY_MIN, Math.floor(parsed)))
}

function saveStorageNumber(key: string, value: number): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(key, String(value))
}

function readStorageBoolean(key: string, fallback: boolean): boolean {
  if (typeof window === 'undefined') return fallback
  const raw = window.localStorage.getItem(key)
  if (raw === null) return fallback
  return raw === '1'
}

function saveStorageBoolean(key: string, value: boolean): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(key, value ? '1' : '0')
}

function readContentItems(value: unknown): OpenClawContentItem[] {
  if (!Array.isArray(value)) return []
  return value.filter((row) => row && typeof row === 'object') as OpenClawContentItem[]
}

function extractTextSegments(items: OpenClawContentItem[], allowedTypes: string[]): string {
  const textRows: string[] = []
  const allow = new Set(allowedTypes)

  for (const item of items) {
    const type = typeof item.type === 'string' ? item.type : ''
    if (!allow.has(type)) continue
    const text = typeof item.text === 'string' ? item.text.trim() : ''
    if (text.length === 0) continue
    textRows.push(text)
  }

  return textRows.join('\n\n').trim()
}

function normalizeImageDataUrl(content: string, mimeType: string): string {
  const trimmedContent = content.trim()
  if (!trimmedContent) return ''
  if (trimmedContent.startsWith('data:')) {
    return trimmedContent
  }
  const normalizedMime = mimeType.trim() || 'image/png'
  return `data:${normalizedMime};base64,${trimmedContent}`
}

function extractImageSegments(items: OpenClawContentItem[]): string[] {
  const urls: string[] = []
  for (const item of items) {
    const type = typeof item.type === 'string' ? item.type : ''
    if (type === 'image') {
      const source = item.source
      const sourceType = typeof source?.type === 'string' ? source.type : ''
      if (sourceType === 'base64' && typeof source?.data === 'string') {
        const mediaType = typeof source.media_type === 'string' ? source.media_type : 'image/png'
        const dataUrl = normalizeImageDataUrl(source.data, mediaType)
        if (dataUrl) urls.push(dataUrl)
        continue
      }
      if (typeof item.url === 'string' && item.url.trim().length > 0) {
        urls.push(item.url.trim())
      }
      continue
    }

    if (type === 'image_url') {
      const imageUrl = item.image_url?.url
      if (typeof imageUrl === 'string' && imageUrl.trim().length > 0) {
        urls.push(imageUrl.trim())
      }
    }
  }
  return urls
}

function hasAssistantTextAfter(messages: OpenClawHistoryMessage[], sinceMs: number): boolean {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const row = messages[index]
    const timestamp = typeof row.timestamp === 'number' ? row.timestamp : 0
    if (timestamp < sinceMs) continue
    if (row.role !== 'assistant') continue
    const items = readContentItems(row.content)
    const text = extractTextSegments(items, ['text'])
    if (text.length > 0) return true
  }
  return false
}

function collectUserTextSet(messages: OpenClawHistoryMessage[]): Set<string> {
  const values = new Set<string>()
  for (const row of messages) {
    if (row.role !== 'user') continue
    const content = readContentItems(row.content)
    const text = extractTextSegments(content, ['text']).trim()
    if (text.length > 0) values.add(text)
  }
  return values
}

function toPendingRunReasoningText(status: string, showProcess: boolean): string {
  if (!showProcess) return ''
  const normalized = status.trim().toLowerCase()
  if (!normalized) return '正在等待模型返回结果…'
  if (normalized === 'reconnecting' || normalized === 'unknown') {
    return '正在重连并等待工具结果返回…'
  }
  if (normalized === 'submitted' || normalized === 'queued') {
    return `任务已提交，状态 ${status}`
  }
  return `正在执行任务，状态 ${status}`
}

function toUiMessages(messages: OpenClawHistoryMessage[], includeProcess: boolean): UiMessage[] {
  const output: UiMessage[] = []

  for (let index = 0; index < messages.length; index += 1) {
    const row = messages[index]
    const timestamp = typeof row.timestamp === 'number' ? row.timestamp : Date.now() + index
    const baseId = `${timestamp}:${index}`
    const content = readContentItems(row.content)

    if (row.role === 'user') {
      const text = extractTextSegments(content, ['text'])
      const images = extractImageSegments(content)
      if (text.length > 0 || images.length > 0) {
        output.push({
          id: `${baseId}:user`,
          role: 'user',
          text,
          messageType: 'openclaw.user',
          images: images.length > 0 ? images : undefined,
        })
      }
      continue
    }

    if (row.role === 'assistant') {
      const imageRows = extractImageSegments(content)
      if (imageRows.length > 0) {
        output.push({
          id: `${baseId}:assistant:image`,
          role: 'assistant',
          text: '',
          messageType: 'openclaw.assistant.image',
          images: imageRows,
        })
      }
      for (let partIndex = 0; partIndex < content.length; partIndex += 1) {
        const item = content[partIndex]
        const itemType = typeof item.type === 'string' ? item.type : ''

        if (itemType === 'text') {
          const text = typeof item.text === 'string' ? item.text.trim() : ''
          if (!text) continue
          output.push({
            id: `${baseId}:assistant:${partIndex}`,
            role: 'assistant',
            text,
            messageType: 'openclaw.assistant',
          })
          continue
        }

        if (itemType === 'thinking' && includeProcess) {
          const thinking = typeof item.thinking === 'string' ? item.thinking.trim() : ''
          if (!thinking) continue
          output.push({
            id: `${baseId}:thinking:${partIndex}`,
            role: 'system',
            text: `思考\n${thinking}`,
            messageType: 'openclaw.thinking',
          })
          continue
        }

        if (itemType === 'toolCall' && includeProcess) {
          const toolName = typeof item.name === 'string' ? item.name.trim() : 'unknown'
          const argsPreview = safeJsonStringify(item.arguments)
          const detail = argsPreview ? `\n参数 ${argsPreview}` : ''
          output.push({
            id: `${baseId}:toolcall:${partIndex}`,
            role: 'system',
            text: `工具调用 ${toolName}${detail}`,
            messageType: 'openclaw.toolcall',
          })
        }
      }
      continue
    }

    if (row.role === 'toolResult' && includeProcess) {
      const text = extractTextSegments(content, ['text'])
      const toolName = typeof row.toolName === 'string' ? row.toolName.trim() : 'tool'
      const shortText = text.length > 900 ? `${text.slice(0, 900)}...` : text
      output.push({
        id: `${baseId}:toolresult`,
        role: 'system',
        text: shortText.length > 0 ? `工具结果 ${toolName}\n${shortText}` : `工具结果 ${toolName}`,
        messageType: row.isError ? 'openclaw.toolresult.error' : 'openclaw.toolresult',
      })
    }
  }

  return output
}

function appendUploadedFilePathsToMessage(message: string, paths: string[]): string {
  const trimmed = message.trim()
  if (paths.length === 0) return trimmed
  const rows = paths.map((path) => `PATH::${path}`)
  const fileSection = [
    `已附加本地文件路径（共${paths.length}个）。`,
    '严格规则：后续所有工具调用必须逐字使用 PATH:: 后面的完整绝对路径，不得改写文件名、空格、下划线、连字符或扩展名。',
    'PATH_BEGIN',
    ...rows,
    'PATH_END',
  ].join('\n')
  if (trimmed.length === 0) {
    return `${fileSection}\n\n请逐一读取以上全部文件后继续，并在回复中逐字引用完整路径。`
  }
  return `${trimmed}\n\n${fileSection}\n\n请逐一读取以上全部文件后继续，并逐字引用完整路径。`
}

function normalizeComposerInput(input: string | OpenClawComposerSubmitPayload): {
  text: string
  attachments: OpenClawComposerAttachment[]
} {
  if (typeof input === 'string') {
    return {
      text: input.trim(),
      attachments: [],
    }
  }
  return {
    text: input.text.trim(),
    attachments: Array.isArray(input.attachments) ? input.attachments : [],
  }
}

export function useOpenClawState() {
  const sessions = ref<OpenClawSessionSummary[]>([])
  const selectedSessionKey = ref('')
  const messages = ref<UiMessage[]>([])
  const isLoadingSessions = ref(false)
  const isLoadingMessages = ref(false)
  const isSendingMessage = ref(false)
  const healthOk = ref(false)
  const showProcess = ref(readStorageBoolean(SHOW_PROCESS_STORAGE_KEY, false))
  const historyLimit = ref(readStorageNumber(HISTORY_LIMIT_STORAGE_KEY, HISTORY_DEFAULT))
  const lastError = ref('')
  const pendingRun = ref(false)
  const pendingRunId = ref('')
  const pendingRunStatus = ref('')
  const heartbeatTriggering = ref(false)
  const abortingRun = ref(false)
  const optimisticUserMessages = ref<OptimisticOpenClawMessage[]>([])

  let pollTimer: number | null = null
  let pollInFlight = false
  let runWaitInFlight = false
  let lastRunWaitAtMs = 0
  let lastSendAtMs = 0
  let pollTick = 0
  let bootstrapInFlight: Promise<string> | null = null
  let lastBootstrapAttemptAtMs = 0

  const selectedSession = computed(() =>
    sessions.value.find((row) => row.key === selectedSessionKey.value) ?? null,
  )

  const selectedSessionTitle = computed(() => selectedSession.value?.title ?? '')

  const liveOverlay = computed<UiLiveOverlay | null>(() => {
    if (pendingRun.value) {
      const status = pendingRunStatus.value.trim()
      const reasoningText = toPendingRunReasoningText(status, showProcess.value)
      return {
        activityLabel: showProcess.value ? '执行中' : '处理中',
        activityDetails: [],
        reasoningText,
        errorText: '',
      }
    }

    if (lastError.value.length > 0) {
      return {
        activityLabel: '执行异常',
        activityDetails: [],
        reasoningText: '',
        errorText: lastError.value,
      }
    }

    return null
  })

  function setPendingRunStatus(status: string): void {
    pendingRunStatus.value = status.trim()
  }

  function pruneOptimisticMessages(sessionKey: string, historyMessages: OpenClawHistoryMessage[]): void {
    const now = Date.now()
    const historyTextSet = collectUserTextSet(historyMessages)
    optimisticUserMessages.value = optimisticUserMessages.value.filter((row) => {
      if (row.sessionKey !== sessionKey) return true
      if (now - row.createdAtMs > OPENCLAW_OPTIMISTIC_MESSAGE_TTL_MS) return false
      const text = row.text.trim()
      if (!text) return true
      return !historyTextSet.has(text)
    })
  }

  function renderMessagesFromHistory(historyMessages: OpenClawHistoryMessage[]): void {
    const sessionKey = selectedSessionKey.value.trim()
    const rendered = toUiMessages(historyMessages, showProcess.value)
    if (!sessionKey) {
      messages.value = rendered
      return
    }
    const localOptimistic = optimisticUserMessages.value
      .filter((row) => row.sessionKey === sessionKey)
      .map(({ createdAtMs: _createdAtMs, sessionKey: _sessionKey, ...message }) => message)
    messages.value = localOptimistic.length > 0
      ? [...rendered, ...localOptimistic]
      : rendered
  }

  function chooseSessionFromList(preferredSessionKey: string): string {
    const preferred = preferredSessionKey.trim()
    if (preferred.length > 0) {
      const hasPreferred = sessions.value.some((row) => row.key === preferred)
      if (hasPreferred) return preferred
    }

    if (selectedSessionKey.value.length > 0) {
      const hasCurrent = sessions.value.some((row) => row.key === selectedSessionKey.value)
      if (hasCurrent) return selectedSessionKey.value
    }

    return sessions.value[0]?.key ?? ''
  }

  async function refreshSessions(preferredSessionKey = ''): Promise<boolean> {
    isLoadingSessions.value = true
    try {
      const rows = await listOpenClawSessions(240)
      sessions.value = rows
      const nextKey = chooseSessionFromList(preferredSessionKey)
      if (nextKey !== selectedSessionKey.value) {
        selectedSessionKey.value = nextKey
      }
      lastError.value = ''
      return true
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '加载会话失败'
      return false
    } finally {
      isLoadingSessions.value = false
    }
  }

  async function refreshHealth(): Promise<void> {
    try {
      const health = await getOpenClawHealth()
      healthOk.value = health.ok
    } catch {
      healthOk.value = false
    }
  }

  async function refreshHistory(options: HistoryRefreshOptions = {}): Promise<void> {
    const sessionKey = selectedSessionKey.value.trim()
    if (!sessionKey) {
      messages.value = []
      return
    }

    const showBusy = options.silent !== true
    if (showBusy) {
      isLoadingMessages.value = true
    }
    try {
      const payload = await readOpenClawHistory({
        sessionKey,
        limit: historyLimit.value,
      })
      pruneOptimisticMessages(sessionKey, payload.messages)
      renderMessagesFromHistory(payload.messages)
      if (pendingRun.value && hasAssistantTextAfter(payload.messages, lastSendAtMs)) {
        pendingRun.value = false
        pendingRunId.value = ''
        setPendingRunStatus('')
        optimisticUserMessages.value = optimisticUserMessages.value.filter((row) => row.sessionKey !== sessionKey)
      }
      lastError.value = ''
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '加载聊天记录失败'
    } finally {
      if (showBusy) {
        isLoadingMessages.value = false
      }
    }
  }

  async function selectSession(sessionKey: string, options: SessionSelectOptions = {}): Promise<void> {
    const nextSession = sessionKey.trim()
    if (!nextSession || nextSession === selectedSessionKey.value) {
      if (options.syncHistory !== false) {
        await refreshHistory()
      }
      return
    }

    selectedSessionKey.value = nextSession
    messages.value = []
    pendingRun.value = false
    pendingRunId.value = ''
    setPendingRunStatus('')
    await refreshHistory()
  }

  async function initialize(preferredSessionKey = ''): Promise<void> {
    await ensureSessionReady(preferredSessionKey)
  }

  async function runSessionBootstrap(preferredSessionKey = ''): Promise<string> {
    const preferred = preferredSessionKey.trim()

    for (let attempt = 0; attempt < OPENCLAW_BOOTSTRAP_RETRY_LIMIT; attempt += 1) {
      await refreshHealth()
      await refreshSessions(preferred)

      const currentSession = selectedSessionKey.value.trim()
      if (currentSession) {
        await refreshHistory()
        lastError.value = ''
        return currentSession
      }

      try {
        const createdSession = await createSession()
        if (createdSession.trim()) {
          lastError.value = ''
          return createdSession
        }
      } catch (error) {
        lastError.value = error instanceof Error ? error.message : '创建初始会话失败'
      }

      await new Promise((resolve) => setTimeout(resolve, 300 + attempt * 300))
    }

    return ''
  }

  async function ensureSessionReady(preferredSessionKey = ''): Promise<string> {
    const active = selectedSessionKey.value.trim()
    if (active) {
      return active
    }

    if (bootstrapInFlight) {
      return bootstrapInFlight
    }

    const now = Date.now()
    if (now - lastBootstrapAttemptAtMs < OPENCLAW_BOOTSTRAP_COOLDOWN_MS) {
      return ''
    }
    lastBootstrapAttemptAtMs = now

    bootstrapInFlight = (async () => {
      try {
        return await runSessionBootstrap(preferredSessionKey)
      } finally {
        bootstrapInFlight = null
      }
    })()

    return bootstrapInFlight
  }

  async function createSession(label?: string): Promise<string> {
    try {
      const payload = await createOpenClawSession(label, selectedSessionKey.value)
      selectedSessionKey.value = payload.sessionKey
      await refreshSessions(payload.sessionKey)
      await refreshHistory()
      lastError.value = ''
      return payload.sessionKey
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '创建会话失败'
      throw error
    }
  }

  async function resetCurrentSession(): Promise<string> {
    const currentSessionKey = selectedSessionKey.value.trim()
    if (!currentSessionKey) {
      throw new Error('重置会话失败：当前会话为空')
    }
    try {
      const payload = await resetOpenClawSession(currentSessionKey)
      await refreshSessions(payload.sessionKey)
      await selectSession(payload.sessionKey)
      lastError.value = ''
      return payload.sessionKey
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '重置会话失败'
      throw error
    }
  }

  async function updateSessionTitle(sessionKey: string, title: string): Promise<void> {
    await renameOpenClawSession(sessionKey, title)
    await refreshSessions(sessionKey)
  }

  async function sendMessage(input: string | OpenClawComposerSubmitPayload): Promise<void> {
    const sessionKey = selectedSessionKey.value.trim()
    const normalized = normalizeComposerInput(input)
    if (!sessionKey) return
    if (!normalized.text && normalized.attachments.length === 0) return

    isSendingMessage.value = true
    pendingRun.value = true
    pendingRunId.value = ''
    setPendingRunStatus('queued')
    lastSendAtMs = Date.now()
    let optimisticMessage: OptimisticOpenClawMessage | null = null

    try {
      const imageAttachments = normalized.attachments.filter(
        (row): row is OpenClawComposerImageAttachment => row.type === 'image',
      )

      const uploadedPaths: string[] = []
      const uploadFailures: string[] = []

      for (const attachment of normalized.attachments) {
        const fileName = attachment.name?.trim() || (attachment.type === 'image' ? 'image' : 'file')
        const mimeType = attachment.mimeType?.trim() || 'application/octet-stream'
        try {
          const uploaded = await uploadOpenClawAttachmentBinary({
            fileName,
            mimeType,
            file: attachment.file,
          })
          uploadedPaths.push(uploaded.path)
        } catch (error) {
          const reason = error instanceof Error ? error.message : '附件上传失败'
          uploadFailures.push(`${fileName}: ${reason}`)
        }
      }

      if (!normalized.text && uploadedPaths.length === 0) {
        if (uploadFailures.length > 0) {
          throw new Error(`附件上传失败，未成功上传任何文件：\n${uploadFailures.join('\n')}`)
        }
        throw new Error('发送消息失败：未检测到可发送的文本或附件')
      }

      const message = appendUploadedFilePathsToMessage(normalized.text, uploadedPaths)
      const optimisticImages = imageAttachments
        .map((row) => row.dataUrl.trim())
        .filter((row) => row.length > 0)
      if (message.length > 0 || optimisticImages.length > 0) {
        optimisticMessage = {
          id: `openclaw-user-optimistic:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`,
          role: 'user',
          text: message,
          messageType: 'openclaw.user.optimistic',
          images: optimisticImages.length > 0 ? optimisticImages : undefined,
          createdAtMs: Date.now(),
          sessionKey,
        }
        optimisticUserMessages.value = [
          ...optimisticUserMessages.value.filter((row) => row.sessionKey !== sessionKey || row.id !== optimisticMessage?.id),
          optimisticMessage,
        ]
        messages.value = [
          ...messages.value,
          {
            id: optimisticMessage.id,
            role: optimisticMessage.role,
            text: optimisticMessage.text,
            messageType: optimisticMessage.messageType,
            images: optimisticMessage.images,
          },
        ]
      }
      const sendResult = await sendOpenClawMessage({
        sessionKey,
        message,
        deliver: false,
      })
      pendingRunId.value = sendResult.runId.trim()
      setPendingRunStatus(pendingRunId.value ? 'submitted' : 'running')
      await refreshHistory()
      await refreshSessions(sessionKey)
      await refreshHealth()
      void waitPendingRun({ force: true })
      if (uploadFailures.length > 0) {
        lastError.value = `部分附件上传失败（成功 ${uploadedPaths.length}，失败 ${uploadFailures.length}）：\n${uploadFailures.join('\n')}`
      } else {
        lastError.value = ''
      }
    } catch (error) {
      if (optimisticMessage) {
        optimisticUserMessages.value = optimisticUserMessages.value.filter((row) => row.id !== optimisticMessage?.id)
        messages.value = messages.value.filter((row) => row.id !== optimisticMessage?.id)
      }
      pendingRun.value = false
      pendingRunId.value = ''
      setPendingRunStatus('')
      lastError.value = error instanceof Error ? error.message : '发送消息失败'
      throw error
    } finally {
      isSendingMessage.value = false
    }
  }

  function toggleProcessView(): void {
    showProcess.value = !showProcess.value
    saveStorageBoolean(SHOW_PROCESS_STORAGE_KEY, showProcess.value)
    if (selectedSessionKey.value) {
      void refreshHistory()
    }
  }

  function loadOlderHistory(): void {
    const next = Math.min(HISTORY_MAX, historyLimit.value + HISTORY_STEP)
    if (next === historyLimit.value) return
    historyLimit.value = next
    saveStorageNumber(HISTORY_LIMIT_STORAGE_KEY, next)
    if (selectedSessionKey.value) {
      void refreshHistory()
    }
  }

  function resetHistoryToLite(): void {
    historyLimit.value = HISTORY_DEFAULT
    saveStorageNumber(HISTORY_LIMIT_STORAGE_KEY, historyLimit.value)
    if (selectedSessionKey.value) {
      void refreshHistory()
    }
  }

  async function triggerHeartbeatNow(): Promise<void> {
    const sessionKey = selectedSessionKey.value.trim()
    if (!sessionKey || heartbeatTriggering.value) return

    heartbeatTriggering.value = true
    lastError.value = ''
    try {
      const result = await triggerOpenClawHeartbeat({ sessionKey })
      if (result.runId.trim()) {
        pendingRun.value = true
        pendingRunId.value = result.runId.trim()
        setPendingRunStatus(result.status.trim() || 'submitted')
        lastSendAtMs = Date.now()
        void waitPendingRun({ force: true })
      } else {
        setPendingRunStatus('heartbeat')
      }
      await refreshHistory({ silent: true })
      await refreshSessions(sessionKey)
      await refreshHealth()
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '触发心跳失败'
      throw error
    } finally {
      heartbeatTriggering.value = false
    }
  }

  async function abortCurrentRunNow(): Promise<void> {
    const sessionKey = selectedSessionKey.value.trim()
    if (!sessionKey || abortingRun.value) return

    abortingRun.value = true
    lastError.value = ''
    try {
      const runId = pendingRunId.value.trim()
      const result = await abortOpenClawRun({
        sessionKey,
        runId: runId || undefined,
      })
      if (!result.aborted) {
        throw new Error('终止任务失败：网关未确认中止')
      }
      pendingRun.value = false
      pendingRunId.value = ''
      setPendingRunStatus('aborted')
      await refreshHistory({ silent: true })
      await refreshSessions(sessionKey)
      await refreshHealth()
      setPendingRunStatus('')
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '终止任务失败'
      throw error
    } finally {
      abortingRun.value = false
    }
  }

  async function waitPendingRun(options: { force?: boolean } = {}): Promise<void> {
    if (!pendingRun.value) return

    const runId = pendingRunId.value.trim()
    if (!runId) return
    if (runWaitInFlight) return

    const now = Date.now()
    if (!options.force && now - lastRunWaitAtMs < RUN_WAIT_INTERVAL_MS) return

    runWaitInFlight = true
    lastRunWaitAtMs = now
    try {
      const statusPayload = await waitOpenClawRun({
        runId,
        timeoutMs: RUN_WAIT_TIMEOUT_MS,
      })
      setPendingRunStatus(statusPayload.status.trim() || pendingRunStatus.value || 'running')
      if (statusPayload.completed) {
        pendingRun.value = false
        pendingRunId.value = ''
        setPendingRunStatus('')
        await refreshHistory({ silent: true })
      }
    } catch {
      if (!pendingRunStatus.value) {
        setPendingRunStatus('reconnecting')
      }
    } finally {
      runWaitInFlight = false
    }
  }

  async function pollOnce(): Promise<void> {
    if (pollInFlight) return

    pollInFlight = true
    try {
      if (!selectedSessionKey.value.trim()) {
        await ensureSessionReady()
        return
      }
      pollTick += 1
      if (pollTick % 5 === 0) {
        await refreshSessions(selectedSessionKey.value)
        await refreshHealth()
      }
      await refreshHistory({ silent: true })
      await waitPendingRun()
    } finally {
      pollInFlight = false
    }
  }

  function startPolling(): void {
    if (pollTimer !== null || typeof window === 'undefined') return
    pollTimer = window.setInterval(() => {
      void pollOnce()
    }, POLL_INTERVAL_MS)
  }

  function stopPolling(): void {
    if (pollTimer === null || typeof window === 'undefined') return
    window.clearInterval(pollTimer)
    pollTimer = null
  }

  return {
    sessions,
    selectedSession,
    selectedSessionKey,
    selectedSessionTitle,
    messages,
    showProcess,
    historyLimit,
    healthOk,
    isLoadingSessions,
    isLoadingMessages,
    isSendingMessage,
    heartbeatTriggering,
    abortingRun,
    liveOverlay,
    lastError,
    initialize,
    ensureSessionReady,
    refreshHealth,
    refreshSessions,
    refreshHistory,
    selectSession,
    sendMessage,
    createSession,
    resetCurrentSession,
    updateSessionTitle,
    toggleProcessView,
    loadOlderHistory,
    resetHistoryToLite,
    triggerHeartbeatNow,
    abortCurrentRunNow,
    startPolling,
    stopPolling,
  }
}
