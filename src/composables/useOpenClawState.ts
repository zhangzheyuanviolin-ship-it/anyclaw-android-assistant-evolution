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
  OpenClawSessionSummary,
} from '../types/openclaw'

const HISTORY_LIMIT_STORAGE_KEY = 'anyclaw.openclaw.history.limit.v1'
const SHOW_PROCESS_STORAGE_KEY = 'anyclaw.openclaw.process.enabled.v1'
const HISTORY_DEFAULT = 60
const HISTORY_MIN = 20
const HISTORY_MAX = 400
const HISTORY_STEP = 40
const OPENCLAW_IMAGE_ATTACHMENT_MAX_BYTES = 5_000_000
const OPENCLAW_FILE_UPLOAD_MAX_BYTES = 15_000_000

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

function summarizeForSignature(value: string, head = 80, tail = 24): string {
  const trimmed = value.trim()
  if (trimmed.length <= head + tail + 12) return trimmed
  return `${trimmed.slice(0, head)}|${trimmed.length}|${trimmed.slice(-tail)}`
}

function hashSignature(value: string): string {
  let hash = 2166136261
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i)
    hash += (hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24)
  }
  return (hash >>> 0).toString(36)
}

function buildContentSignature(items: OpenClawContentItem[]): string {
  const rows: string[] = []
  for (const item of items) {
    const type = typeof item.type === 'string' ? item.type : ''
    if (type === 'text') {
      rows.push(`text:${summarizeForSignature(typeof item.text === 'string' ? item.text : '')}`)
      continue
    }
    if (type === 'thinking') {
      rows.push(`thinking:${summarizeForSignature(typeof item.thinking === 'string' ? item.thinking : '')}`)
      continue
    }
    if (type === 'toolCall') {
      rows.push(
        `toolCall:${typeof item.name === 'string' ? item.name : ''}:${summarizeForSignature(safeJsonStringify(item.arguments, 240))}`,
      )
      continue
    }
    if (type === 'image') {
      const sourceData = typeof item.source?.data === 'string' ? item.source.data : ''
      const url = typeof item.url === 'string' ? item.url : ''
      rows.push(`image:${summarizeForSignature(sourceData || url, 48, 16)}`)
      continue
    }
    if (type === 'image_url') {
      const url = typeof item.image_url?.url === 'string' ? item.image_url.url : ''
      rows.push(`image_url:${summarizeForSignature(url, 60, 18)}`)
      continue
    }
    rows.push(`other:${type}`)
  }
  return hashSignature(rows.join('||'))
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

function hasAnyTimestampAtOrAfter(messages: OpenClawHistoryMessage[], sinceMs: number): boolean {
  for (const row of messages) {
    const timestamp = typeof row.timestamp === 'number' ? row.timestamp : 0
    if (timestamp >= sinceMs && timestamp > 0) return true
  }
  return false
}

function hasAssistantOrToolOutputAfter(messages: OpenClawHistoryMessage[], sinceMs: number): boolean {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const row = messages[index]
    const timestamp = typeof row.timestamp === 'number' ? row.timestamp : 0
    if (timestamp > 0 && timestamp < sinceMs) continue
    if (row.role !== 'assistant' && row.role !== 'toolResult') continue
    const items = readContentItems(row.content)
    const text = extractTextSegments(items, ['text'])
    const images = extractImageSegments(items)
    if (text.length > 0 || images.length > 0) return true
  }
  return false
}

function toUiMessages(messages: OpenClawHistoryMessage[], includeProcess: boolean): UiMessage[] {
  const output: UiMessage[] = []
  const dedupe = new Map<string, number>()

  for (let index = 0; index < messages.length; index += 1) {
    const row = messages[index]
    const timestamp = typeof row.timestamp === 'number' && Number.isFinite(row.timestamp)
      ? Math.floor(row.timestamp)
      : 0
    const content = readContentItems(row.content)
    const toolName = typeof row.toolName === 'string' ? row.toolName.trim() : ''
    const rowBase = `${row.role}:${timestamp > 0 ? String(timestamp) : 'na'}:${toolName}:${row.isError ? '1' : '0'}:${buildContentSignature(content)}`
    const rowSeen = (dedupe.get(rowBase) ?? 0) + 1
    dedupe.set(rowBase, rowSeen)
    const baseId = `${rowBase}:${rowSeen}`

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

function buildUiMessagesSignature(rows: UiMessage[]): string {
  if (rows.length === 0) return 'empty'
  return rows
    .map((row) => `${row.id}:${row.text.length}:${row.images?.length ?? 0}`)
    .join('|')
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

function appendUploadedAttachmentPathsToMessage(
  message: string,
  imagePaths: string[],
  filePaths: string[],
): string {
  const trimmed = message.trim()
  const sections: string[] = []
  if (imagePaths.length > 0) {
    sections.push(['已附加图片路径：', ...imagePaths].join('\n'))
  }
  if (filePaths.length > 0) {
    sections.push(['已附加本地文件路径：', ...filePaths].join('\n'))
  }
  if (sections.length === 0) return trimmed
  const attachmentSection = sections.join('\n\n')
  if (trimmed.length === 0) {
    return `${attachmentSection}\n\n请先读取以上附件后继续。`
  }
  return `${trimmed}\n\n${attachmentSection}`
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
  const isCancellingRun = ref(false)
  const healthOk = ref(false)
  const showProcess = ref(readStorageBoolean(SHOW_PROCESS_STORAGE_KEY, false))
  const historyLimit = ref(readStorageNumber(HISTORY_LIMIT_STORAGE_KEY, HISTORY_DEFAULT))
  const lastError = ref('')
  const pendingRun = ref(false)

  let pollTimer: number | null = null
  let pollInFlight = false
  let pollLoopEnabled = false
  let lastSendAtMs = 0
  let pollTick = 0
  let lastRenderedSignature = 'empty'
  let pendingBaselineSignature = 'empty'
  let cancelPendingAfterSend = false

  const selectedSession = computed(() =>
    sessions.value.find((row) => row.key === selectedSessionKey.value) ?? null,
  )

  const selectedSessionTitle = computed(() => selectedSession.value?.title ?? '')
  const isRunInProgress = computed(() => pendingRun.value || isSendingMessage.value || isCancellingRun.value)

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
      const nextMessages = toUiMessages(payload.messages, showProcess.value)
      const nextSignature = buildUiMessagesSignature(nextMessages)
      const historyChanged = nextSignature !== lastRenderedSignature
      if (historyChanged) {
        messages.value = nextMessages
        lastRenderedSignature = nextSignature
      }

      if (pendingRun.value && historyChanged) {
        const hasTimedOutput = hasAssistantTextAfter(payload.messages, lastSendAtMs) ||
          hasAssistantOrToolOutputAfter(payload.messages, lastSendAtMs)
        const hasTimestampEvidence = hasAnyTimestampAtOrAfter(payload.messages, lastSendAtMs)
        if (hasTimedOutput || (!hasTimestampEvidence && nextSignature !== pendingBaselineSignature)) {
          pendingRun.value = false
        }
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
    lastRenderedSignature = 'empty'
    pendingBaselineSignature = 'empty'
    cancelPendingAfterSend = false
    pendingRun.value = false
    await refreshHistory()
  }

  async function initialize(preferredSessionKey = ''): Promise<void> {
    await refreshHealth()

    await refreshSessions(preferredSessionKey)
    if (selectedSessionKey.value) {
      await refreshHistory()
    }
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
    pendingBaselineSignature = lastRenderedSignature

    try {
      const imageAttachments = normalized.attachments.filter(
        (row): row is OpenClawComposerImageAttachment => row.type === 'image',
      )

      const uploadedImagePaths: string[] = []
      for (const imageAttachment of imageAttachments) {
        const parsed = toImageAttachment(imageAttachment)
        if (!parsed) {
          throw new Error(`图片编码失败：${imageAttachment.name}`)
        }
        const uploaded = await uploadOpenClawAttachment({
          fileName: imageAttachment.name,
          mimeType: parsed.mimeType,
          contentBase64: parsed.content,
        })
        uploadedImagePaths.push(uploaded.path)
      }

      const uploadedFilePaths: string[] = []
      for (const fileAttachment of normalized.attachments) {
        if (fileAttachment.type !== 'file') continue
        if (fileAttachment.sizeBytes > OPENCLAW_FILE_UPLOAD_MAX_BYTES) {
          throw new Error(`文件超过大小限制（15MB）：${fileAttachment.name}`)
        }
        const contentBase64 = await readFileAsBase64(fileAttachment.file)
        const uploaded = await uploadOpenClawAttachment({
          fileName: fileAttachment.name,
          mimeType: fileAttachment.mimeType,
          contentBase64,
        })
        uploadedFilePaths.push(uploaded.path)
      }
      const message = appendUploadedAttachmentPathsToMessage(
        normalized.text,
        uploadedImagePaths,
        uploadedFilePaths,
      )
      await sendOpenClawMessage({
        sessionKey,
        message,
        deliver: false,
      })
      if (cancelPendingAfterSend) {
        cancelPendingAfterSend = false
        await abortOpenClawRun({ sessionKey })
        pendingRun.value = false
        await refreshHistory({ silent: true })
        lastError.value = ''
        return
      }
      await refreshHistory()
      void refreshSessions(sessionKey)
      void refreshHealth()
      lastError.value = ''
    } catch (error) {
      cancelPendingAfterSend = false
      pendingRun.value = false
      lastError.value = error instanceof Error ? error.message : '发送消息失败'
      throw error
    } finally {
      isSendingMessage.value = false
    }
  }

  async function cancelCurrentRun(): Promise<void> {
    const sessionKey = selectedSessionKey.value.trim()
    if (!sessionKey) return
    if (isCancellingRun.value) return
    if (isSendingMessage.value) {
      cancelPendingAfterSend = true
    }
    isCancellingRun.value = true
    try {
      await abortOpenClawRun({ sessionKey })
      pendingRun.value = false
      await refreshHistory({ silent: true })
      void refreshSessions(sessionKey)
      void refreshHealth()
      lastError.value = ''
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '取消任务失败'
      throw error
    } finally {
      isCancellingRun.value = false
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
    if (!selectedSessionKey.value.trim()) return

    pollInFlight = true
    try {
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

  function computePollDelayMs(): number {
    if (pendingRun.value) return 1200
    if (lastError.value.length > 0) return 4200
    return 2600
  }

  function scheduleNextPoll(initialDelayMs?: number): void {
    if (typeof window === 'undefined') return
    if (!pollLoopEnabled) return
    if (pollTimer !== null) {
      window.clearTimeout(pollTimer)
      pollTimer = null
    }
    const delay = Math.max(300, initialDelayMs ?? computePollDelayMs())
    pollTimer = window.setTimeout(async () => {
      pollTimer = null
      await pollOnce()
      scheduleNextPoll()
    }, delay)
  }

  function startPolling(): void {
    if (pollLoopEnabled || typeof window === 'undefined') return
    pollLoopEnabled = true
    scheduleNextPoll(350)
  }

  function stopPolling(): void {
    if (typeof window === 'undefined') return
    pollLoopEnabled = false
    if (pollTimer === null) return
    window.clearTimeout(pollTimer)
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
    isCancellingRun,
    isRunInProgress,
    liveOverlay,
    lastError,
    initialize,
    refreshHealth,
    refreshSessions,
    refreshHistory,
    selectSession,
    sendMessage,
    cancelCurrentRun,
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
