import { computed, ref } from 'vue'
import {
  abortClaudeRun,
  createClaudeSession,
  getClaudeHealth,
  listClaudeSessions,
  readClaudeHistory,
  renameClaudeSession,
  resetClaudeSession,
  sendClaudeMessage,
  uploadClaudeAttachmentBinary,
  waitClaudeRun,
} from '../api/claudeGateway'
import type { UiLiveOverlay, UiMessage } from '../types/codex'
import type {
  ClaudeComposerAttachment,
  ClaudeComposerImageAttachment,
  ClaudeComposerSubmitPayload,
  ClaudeContentItem,
  ClaudeHistoryMessage,
  ClaudeSessionSummary,
} from '../types/claude'

const HISTORY_LIMIT_STORAGE_KEY = 'anyclaw.claude.history.limit.v1'
const SHOW_PROCESS_STORAGE_KEY = 'anyclaw.claude.process.enabled.v1'
const SHARED_STORAGE_STORAGE_KEY = 'anyclaw.claude.shared.storage.enabled.v1'
const DANGEROUS_MODE_STORAGE_KEY = 'anyclaw.claude.dangerous.mode.enabled.v1'
const HISTORY_DEFAULT = 60
const HISTORY_MIN = 20
const HISTORY_MAX = 400
const HISTORY_STEP = 40
const POLL_INTERVAL_MS = 4200
const RUN_WAIT_INTERVAL_MS = 7000
const RUN_WAIT_TIMEOUT_MS = 25000
const CLAUDE_BOOTSTRAP_RETRY_LIMIT = 3
const CLAUDE_BOOTSTRAP_COOLDOWN_MS = 5_000
const CLAUDE_OPTIMISTIC_MESSAGE_TTL_MS = 10 * 60_000

type OptimisticClaudeMessage = UiMessage & {
  createdAtMs: number
  sessionKey: string
}

type SessionSelectOptions = {
  syncHistory?: boolean
}

type HistoryRefreshOptions = {
  silent?: boolean
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

function readContentItems(value: unknown): ClaudeContentItem[] {
  if (!Array.isArray(value)) return []
  return value.filter((row) => row && typeof row === 'object') as ClaudeContentItem[]
}

function extractTextSegments(items: ClaudeContentItem[]): string {
  const textRows: string[] = []
  for (const item of items) {
    const text = typeof item.text === 'string' ? item.text.trim() : ''
    if (!text) continue
    textRows.push(text)
  }
  return textRows.join('\n\n').trim()
}

function hasAssistantTextAfter(messages: ClaudeHistoryMessage[], sinceMs: number): boolean {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const row = messages[index]
    const timestamp = typeof row.timestamp === 'number' ? row.timestamp : 0
    if (timestamp < sinceMs) continue
    if (row.role !== 'assistant') continue
    const items = readContentItems(row.content)
    const text = extractTextSegments(items)
    if (text.length > 0) return true
  }
  return false
}

function collectUserTextSet(messages: ClaudeHistoryMessage[]): Set<string> {
  const values = new Set<string>()
  for (const row of messages) {
    if (row.role !== 'user') continue
    const content = readContentItems(row.content)
    const text = extractTextSegments(content).trim()
    if (text.length > 0) values.add(text)
  }
  return values
}

function toPendingRunReasoningText(status: string, showProcess: boolean): string {
  if (!showProcess) return ''
  const normalized = status.trim().toLowerCase()
  if (!normalized) return '正在等待 Claude 返回结果…'
  if (normalized === 'unknown') {
    return '正在等待任务状态同步…'
  }
  if (normalized === 'submitted' || normalized === 'queued') {
    return `任务已提交，状态 ${status}`
  }
  return `正在执行任务，状态 ${status}`
}

function toUiMessages(messages: ClaudeHistoryMessage[], includeProcess: boolean): UiMessage[] {
  const output: UiMessage[] = []
  for (let index = 0; index < messages.length; index += 1) {
    const row = messages[index]
    const timestamp = typeof row.timestamp === 'number' ? row.timestamp : Date.now() + index
    const baseId = `${timestamp}:${index}`
    const content = readContentItems(row.content)

    if (row.role === 'user') {
      const text = extractTextSegments(content)
      if (text.length > 0) {
        output.push({
          id: `${baseId}:user`,
          role: 'user',
          text,
          messageType: 'claude.user',
        })
      }
      continue
    }

    if (row.role === 'assistant') {
      const text = extractTextSegments(content)
      if (text.length > 0) {
        output.push({
          id: `${baseId}:assistant`,
          role: 'assistant',
          text,
          messageType: 'claude.assistant',
        })
      }
      continue
    }

    if (row.role === 'toolResult' && includeProcess) {
      const text = extractTextSegments(content)
      if (text.length > 0) {
        output.push({
          id: `${baseId}:process`,
          role: 'system',
          text,
          messageType: row.isError ? 'claude.process.error' : 'claude.process',
        })
      }
    }
  }
  return output
}

function normalizeComposerInput(input: string | ClaudeComposerSubmitPayload): {
  text: string
  attachments: ClaudeComposerAttachment[]
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

export function useClaudeState() {
  const sessions = ref<ClaudeSessionSummary[]>([])
  const selectedSessionKey = ref('')
  const messages = ref<UiMessage[]>([])
  const isLoadingSessions = ref(false)
  const isLoadingMessages = ref(false)
  const isSendingMessage = ref(false)
  const healthOk = ref(false)
  const showProcess = ref(readStorageBoolean(SHOW_PROCESS_STORAGE_KEY, true))
  const historyLimit = ref(readStorageNumber(HISTORY_LIMIT_STORAGE_KEY, HISTORY_DEFAULT))
  const allowSharedStorage = ref(readStorageBoolean(SHARED_STORAGE_STORAGE_KEY, true))
  const dangerousMode = ref(readStorageBoolean(DANGEROUS_MODE_STORAGE_KEY, true))
  const lastError = ref('')
  const pendingRun = ref(false)
  const pendingRunId = ref('')
  const pendingRunStatus = ref('')
  const abortingRun = ref(false)
  const optimisticUserMessages = ref<OptimisticClaudeMessage[]>([])

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

  function setAllowSharedStorage(value: boolean): void {
    allowSharedStorage.value = value
    saveStorageBoolean(SHARED_STORAGE_STORAGE_KEY, value)
  }

  function setDangerousMode(value: boolean): void {
    dangerousMode.value = value
    saveStorageBoolean(DANGEROUS_MODE_STORAGE_KEY, value)
  }

  function toggleAllowSharedStorage(): void {
    setAllowSharedStorage(!allowSharedStorage.value)
  }

  function toggleDangerousMode(): void {
    setDangerousMode(!dangerousMode.value)
  }

  function pruneOptimisticMessages(sessionKey: string, historyMessages: ClaudeHistoryMessage[]): void {
    const now = Date.now()
    const historyTextSet = collectUserTextSet(historyMessages)
    optimisticUserMessages.value = optimisticUserMessages.value.filter((row) => {
      if (row.sessionKey !== sessionKey) return true
      if (now - row.createdAtMs > CLAUDE_OPTIMISTIC_MESSAGE_TTL_MS) return false
      const text = row.text.trim()
      if (!text) return true
      return !historyTextSet.has(text)
    })
  }

  function renderMessagesFromHistory(historyMessages: ClaudeHistoryMessage[]): void {
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
      const rows = await listClaudeSessions(240)
      sessions.value = rows
      const nextKey = chooseSessionFromList(preferredSessionKey)
      if (nextKey !== selectedSessionKey.value) {
        selectedSessionKey.value = nextKey
      }
      lastError.value = ''
      return true
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '加载 Claude 会话失败'
      return false
    } finally {
      isLoadingSessions.value = false
    }
  }

  async function refreshHealth(): Promise<void> {
    try {
      const health = await getClaudeHealth()
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
      const payload = await readClaudeHistory({
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
    for (let attempt = 0; attempt < CLAUDE_BOOTSTRAP_RETRY_LIMIT; attempt += 1) {
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
    if (now - lastBootstrapAttemptAtMs < CLAUDE_BOOTSTRAP_COOLDOWN_MS) {
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
      const payload = await createClaudeSession(label, selectedSessionKey.value)
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
      const payload = await resetClaudeSession(currentSessionKey)
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
    await renameClaudeSession(sessionKey, title)
    await refreshSessions(sessionKey)
  }

  async function sendMessage(input: string | ClaudeComposerSubmitPayload): Promise<void> {
    const sessionKey = selectedSessionKey.value.trim()
    const normalized = normalizeComposerInput(input)
    if (!sessionKey) return
    if (!normalized.text && normalized.attachments.length === 0) return

    isSendingMessage.value = true
    pendingRun.value = true
    pendingRunId.value = ''
    setPendingRunStatus('queued')
    lastSendAtMs = Date.now()
    let optimisticMessage: OptimisticClaudeMessage | null = null

    try {
      const imageAttachments = normalized.attachments.filter(
        (row): row is ClaudeComposerImageAttachment => row.type === 'image',
      )

      const uploadedPaths: string[] = []
      const uploadFailures: string[] = []
      for (const attachment of normalized.attachments) {
        const fileName = attachment.name?.trim() || (attachment.type === 'image' ? 'image' : 'file')
        const mimeType = attachment.mimeType?.trim() || 'application/octet-stream'
        try {
          const uploaded = await uploadClaudeAttachmentBinary({
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

      const optimisticImages = imageAttachments
        .map((row) => row.dataUrl.trim())
        .filter((row) => row.length > 0)
      if (normalized.text.length > 0 || optimisticImages.length > 0) {
        optimisticMessage = {
          id: `claude-user-optimistic:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`,
          role: 'user',
          text: normalized.text,
          messageType: 'claude.user.optimistic',
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

      const sendResult = await sendClaudeMessage({
        sessionKey,
        message: normalized.text,
        attachmentPaths: uploadedPaths,
        allowSharedStorage: allowSharedStorage.value,
        dangerousMode: dangerousMode.value,
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

  async function abortCurrentRunNow(): Promise<void> {
    const sessionKey = selectedSessionKey.value.trim()
    if (!sessionKey || abortingRun.value) return
    abortingRun.value = true
    lastError.value = ''
    try {
      const runId = pendingRunId.value.trim()
      const result = await abortClaudeRun({
        sessionKey,
        runId: runId || undefined,
      })
      if (!result.aborted) {
        throw new Error('终止任务失败：Claude 未确认中止')
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
      const statusPayload = await waitClaudeRun({
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
        setPendingRunStatus('unknown')
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
    allowSharedStorage,
    dangerousMode,
    isLoadingSessions,
    isLoadingMessages,
    isSendingMessage,
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
    setAllowSharedStorage,
    setDangerousMode,
    toggleAllowSharedStorage,
    toggleDangerousMode,
    abortCurrentRunNow,
    startPolling,
    stopPolling,
  }
}
