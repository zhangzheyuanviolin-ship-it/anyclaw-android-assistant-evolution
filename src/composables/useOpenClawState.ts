import { computed, ref } from 'vue'
import {
  createOpenClawSession,
  getOpenClawHealth,
  listOpenClawSessions,
  readOpenClawHistory,
  renameOpenClawSession,
  resetOpenClawSession,
  sendOpenClawMessage,
  uploadOpenClawAttachment,
} from '../api/openclawGateway'
import type { UiLiveOverlay, UiMessage } from '../types/codex'
import type {
  OpenClawComposerAttachment,
  OpenClawComposerImageAttachment,
  OpenClawComposerSubmitPayload,
  OpenClawContentItem,
  OpenClawHistoryMessage,
  OpenClawImageAttachment,
  OpenClawLocalFileAttachment,
  OpenClawSessionSummary,
} from '../types/openclaw'

const HISTORY_LIMIT_STORAGE_KEY = 'anyclaw.openclaw.history.limit.v1'
const SHOW_PROCESS_STORAGE_KEY = 'anyclaw.openclaw.process.enabled.v1'
const HISTORY_DEFAULT = 60
const HISTORY_MIN = 20
const HISTORY_MAX = 400
const HISTORY_STEP = 40
const POLL_INTERVAL_MS = 2500
const OPENCLAW_IMAGE_ATTACHMENT_MAX_BYTES = 5_000_000
const OPENCLAW_FILE_UPLOAD_MAX_BYTES = 15_000_000
const OPENCLAW_BOOTSTRAP_RETRY_LIMIT = 3

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

function estimateBase64DecodedBytes(base64: string): number {
  const normalized = base64.trim()
  if (!normalized) return 0
  const padding =
    normalized.endsWith('==') ? 2 : normalized.endsWith('=') ? 1 : 0
  return Math.floor((normalized.length * 3) / 4) - padding
}

function toImageAttachment(image: OpenClawComposerImageAttachment): OpenClawImageAttachment | null {
  const dataUrl = image.dataUrl.trim()
  if (!dataUrl) return null
  const match = /^data:([^;]+);base64,(.+)$/u.exec(dataUrl)
  if (!match) return null
  const mimeType = (match[1] || image.mimeType || 'image/png').trim()
  const content = (match[2] || '').trim()
  if (!mimeType || !content) return null
  const sizeBytes = estimateBase64DecodedBytes(content)
  if (!Number.isFinite(sizeBytes) || sizeBytes < 1 || sizeBytes > OPENCLAW_IMAGE_ATTACHMENT_MAX_BYTES) {
    throw new Error(`图片附件过大或格式无效：${image.name}`)
  }
  return {
    type: 'image',
    mimeType,
    content,
    fileName: image.name.trim() || undefined,
  }
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(new Error(`文件读取失败：${file.name}`))
    reader.onload = () => {
      const raw = typeof reader.result === 'string' ? reader.result : ''
      const match = /^data:[^;]*;base64,(.+)$/u.exec(raw.trim())
      if (!match || !match[1]) {
        reject(new Error(`文件编码失败：${file.name}`))
        return
      }
      resolve(match[1])
    }
    reader.readAsDataURL(file)
  })
}

function appendUploadedFilePathsToMessage(message: string, paths: string[]): string {
  const trimmed = message.trim()
  if (paths.length === 0) return trimmed
  const fileSection = ['已附加本地文件路径：', ...paths].join('\n')
  if (trimmed.length === 0) {
    return `${fileSection}\n\n请先读取以上文件后继续。`
  }
  return `${trimmed}\n\n${fileSection}`
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

  let pollTimer: number | null = null
  let pollInFlight = false
  let lastSendAtMs = 0
  let pollTick = 0

  const selectedSession = computed(() =>
    sessions.value.find((row) => row.key === selectedSessionKey.value) ?? null,
  )

  const selectedSessionTitle = computed(() => selectedSession.value?.title ?? '')

  const liveOverlay = computed<UiLiveOverlay | null>(() => {
    if (pendingRun.value) {
      return {
        activityLabel: showProcess.value ? '执行中' : '处理中',
        activityDetails: [],
        reasoningText: showProcess.value ? '正在等待模型返回结果…' : '',
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

  async function refreshSessions(preferredSessionKey = ''): Promise<void> {
    isLoadingSessions.value = true
    try {
      const rows = await listOpenClawSessions(240)
      sessions.value = rows
      const nextKey = chooseSessionFromList(preferredSessionKey)
      if (nextKey !== selectedSessionKey.value) {
        selectedSessionKey.value = nextKey
      }
      lastError.value = ''
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '加载会话失败'
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
      messages.value = toUiMessages(payload.messages, showProcess.value)
      if (pendingRun.value && hasAssistantTextAfter(payload.messages, lastSendAtMs)) {
        pendingRun.value = false
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
    await refreshHistory()
  }

  async function initialize(preferredSessionKey = ''): Promise<void> {
    await ensureSessionReady(preferredSessionKey)
  }

  async function ensureSessionReady(preferredSessionKey = ''): Promise<string> {
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

  async function createSession(label?: string): Promise<string> {
    try {
      const payload = await createOpenClawSession(label, selectedSessionKey.value)
      await refreshSessions(payload.sessionKey)
      await selectSession(payload.sessionKey)
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
    lastSendAtMs = Date.now()

    try {
      const imageAttachments: OpenClawImageAttachment[] = []
      const fileAttachments = normalized.attachments.filter(
        (row): row is OpenClawLocalFileAttachment => row.type === 'file',
      )
      for (const attachment of normalized.attachments) {
        if (attachment.type !== 'image') continue
        const parsed = toImageAttachment(attachment)
        if (parsed) imageAttachments.push(parsed)
      }

      const uploadedPaths: string[] = []
      for (const fileAttachment of fileAttachments) {
        if (fileAttachment.sizeBytes > OPENCLAW_FILE_UPLOAD_MAX_BYTES) {
          throw new Error(`文件超过大小限制（15MB）：${fileAttachment.name}`)
        }
        const contentBase64 = await readFileAsBase64(fileAttachment.file)
        const uploaded = await uploadOpenClawAttachment({
          fileName: fileAttachment.name,
          mimeType: fileAttachment.mimeType,
          contentBase64,
        })
        uploadedPaths.push(uploaded.path)
      }

      const message = appendUploadedFilePathsToMessage(normalized.text, uploadedPaths)
      await sendOpenClawMessage({
        sessionKey,
        message,
        deliver: false,
        attachments: imageAttachments.length > 0 ? imageAttachments : undefined,
      })
      await refreshHistory()
      await refreshSessions(sessionKey)
      await refreshHealth()
      lastError.value = ''
    } catch (error) {
      pendingRun.value = false
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

  async function pollOnce(): Promise<void> {
    if (pollInFlight) return

    pollInFlight = true
    try {
      if (!selectedSessionKey.value.trim()) {
        await ensureSessionReady()
        return
      }
      pollTick += 1
      if (pollTick % 3 === 0) {
        await refreshSessions(selectedSessionKey.value)
        await refreshHealth()
      }
      await refreshHistory({ silent: true })
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
    startPolling,
    stopPolling,
  }
}
