import { computed, ref } from 'vue'
import {
  createOpenClawSession,
  getOpenClawHealth,
  listOpenClawSessions,
  readOpenClawHistory,
  renameOpenClawSession,
  resetOpenClawSession,
  sendOpenClawMessage,
} from '../api/openclawGateway'
import type { UiLiveOverlay, UiMessage } from '../types/codex'
import type { OpenClawContentItem, OpenClawHistoryMessage, OpenClawSessionSummary } from '../types/openclaw'

const HISTORY_LIMIT_STORAGE_KEY = 'anyclaw.openclaw.history.limit.v1'
const SHOW_PROCESS_STORAGE_KEY = 'anyclaw.openclaw.process.enabled.v1'
const HISTORY_DEFAULT = 60
const HISTORY_MIN = 20
const HISTORY_MAX = 400
const HISTORY_STEP = 40
const POLL_INTERVAL_MS = 2500

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
      if (text.length > 0) {
        output.push({
          id: `${baseId}:user`,
          role: 'user',
          text,
          messageType: 'openclaw.user',
        })
      }
      continue
    }

    if (row.role === 'assistant') {
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

  async function sendMessage(text: string): Promise<void> {
    const sessionKey = selectedSessionKey.value.trim()
    const message = text.trim()
    if (!sessionKey || !message) return

    isSendingMessage.value = true
    pendingRun.value = true
    lastSendAtMs = Date.now()

    try {
      await sendOpenClawMessage({
        sessionKey,
        message,
        deliver: false,
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
