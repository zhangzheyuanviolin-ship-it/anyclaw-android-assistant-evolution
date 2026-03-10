<template>
  <DesktopLayout :is-sidebar-collapsed="isSidebarCollapsed">
    <template #sidebar>
      <section v-if="isOpenClawRoute" class="sidebar-root">
        <div v-if="!isSidebarCollapsed" class="openclaw-sidebar-actions">
          <button
            type="button"
            class="openclaw-sidebar-button"
            :aria-label="t('openclaw_back_codex')"
            @click="onBackToCodexRoute"
          >
            {{ t('openclaw_back_codex') }}
          </button>
          <button
            type="button"
            class="openclaw-sidebar-button"
            :aria-label="t('openclaw_new_session')"
            @click="onCreateOpenClawSession"
          >
            {{ t('openclaw_new_session') }}
          </button>
          <button
            type="button"
            class="openclaw-sidebar-button"
            :aria-label="t('openclaw_refresh')"
            @click="onRefreshOpenClaw"
          >
            {{ t('openclaw_refresh') }}
          </button>
        </div>

        <div v-if="!isSidebarCollapsed" class="sidebar-search-bar">
          <IconTablerSearch class="sidebar-search-bar-icon" />
          <input
            v-model="openClawSearchQuery"
            class="sidebar-search-input"
            type="text"
            :aria-label="t('openclaw_search_sessions')"
            :placeholder="t('openclaw_filter_sessions')"
          />
          <button
            v-if="openClawSearchQuery.length > 0"
            class="sidebar-search-clear"
            type="button"
            :aria-label="t('sidebar_clear_search')"
            @click="onToggleOpenClawSearch"
          >
            <IconTablerX class="sidebar-search-clear-icon" />
          </button>
        </div>

        <div v-if="!isSidebarCollapsed" class="openclaw-health-status">
          {{ openClawHealthOk ? t('openclaw_health_ok') : t('openclaw_health_fail') }}
        </div>

        <ul v-if="!isSidebarCollapsed" class="openclaw-session-list">
          <li v-if="isOpenClawLoadingSessions" class="openclaw-session-empty">
            {{ t('openclaw_session_loading') }}
          </li>
          <li v-else-if="openClawFilteredSessions.length === 0" class="openclaw-session-empty">
            {{ t('openclaw_session_empty') }}
          </li>
          <li
            v-for="session in openClawFilteredSessions"
            :key="session.key"
            class="openclaw-session-item"
          >
            <button
              type="button"
              class="openclaw-session-button"
              :class="{ 'is-active': session.key === openClawSelectedSessionKey }"
              :aria-current="session.key === openClawSelectedSessionKey ? 'true' : 'false'"
              :aria-label="`${session.title} ${formatOpenClawTime(session.updatedAtMs)}`"
              @click="onSelectOpenClawSession(session.key)"
              @contextmenu.prevent="onRenameOpenClawSession(session.key)"
            >
              <span class="openclaw-session-title">{{ session.title }}</span>
              <span class="openclaw-session-meta">{{ formatOpenClawTime(session.updatedAtMs) }}</span>
            </button>
          </li>
        </ul>

        <a
          v-if="!isSidebarCollapsed"
          class="openclaw-dashboard-link"
          :href="openClawDashboardUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          <IconTablerExternalLink class="openclaw-dashboard-icon" />
          <span class="openclaw-dashboard-label">{{ t('openclaw_original_dashboard_label') }}</span>
        </a>
      </section>

      <section v-else class="sidebar-root">
        <SidebarThreadControls
          v-if="!isSidebarCollapsed"
          class="sidebar-thread-controls-host"
          :is-sidebar-collapsed="isSidebarCollapsed"
          :is-auto-refresh-enabled="isAutoRefreshEnabled"
          :auto-refresh-button-label="autoRefreshButtonLabel"
          :show-auto-refresh-button="false"
          :show-new-thread-button="true"
          @toggle-sidebar="setSidebarCollapsed(!isSidebarCollapsed)"
          @toggle-auto-refresh="onToggleAutoRefreshTimer"
          @start-new-thread="onStartNewThreadFromToolbar"
        >
          <button
            class="sidebar-search-toggle"
            type="button"
            :aria-pressed="isSidebarSearchVisible"
            :aria-label="t('sidebar_search_threads')"
            :title="t('sidebar_search_threads')"
            @click="toggleSidebarSearch"
          >
            <IconTablerSearch class="sidebar-search-toggle-icon" />
          </button>
        </SidebarThreadControls>

        <div v-if="!isSidebarCollapsed && isSidebarSearchVisible" class="sidebar-search-bar">
          <IconTablerSearch class="sidebar-search-bar-icon" />
          <input
            ref="sidebarSearchInputRef"
            v-model="sidebarSearchQuery"
            class="sidebar-search-input"
            type="text"
            :placeholder="t('sidebar_filter_threads')"
            @keydown="onSidebarSearchKeydown"
          />
          <button
            v-if="sidebarSearchQuery.length > 0"
            class="sidebar-search-clear"
            type="button"
            :aria-label="t('sidebar_clear_search')"
            @click="clearSidebarSearch"
          >
            <IconTablerX class="sidebar-search-clear-icon" />
          </button>
        </div>

        <SidebarThreadTree :groups="projectGroups" :project-display-name-by-id="projectDisplayNameById"
          v-if="!isSidebarCollapsed"
          :selected-thread-id="selectedThreadId" :is-loading="isLoadingThreads"
          :search-query="sidebarSearchQuery"
          @select="onSelectThread"
          @archive="onArchiveThread" @start-new-thread="onStartNewThread" @rename-project="onRenameProject"
          @remove-project="onRemoveProject" @reorder-project="onReorderProject" />

        <button
          v-if="!isSidebarCollapsed"
          class="openclaw-dashboard-link"
          type="button"
          :aria-label="t('openclaw_dashboard_label')"
          @click="onOpenOpenClawChat"
        >
          <IconTablerExternalLink class="openclaw-dashboard-icon" />
          <span class="openclaw-dashboard-label">{{ t('openclaw_dashboard_label') }}</span>
        </button>
      </section>
    </template>

    <template #content>
      <section class="content-root">
        <ContentHeader :title="contentTitle">
          <template #leading>
            <SidebarThreadControls
              v-if="isSidebarCollapsed && !isOpenClawRoute"
              class="sidebar-thread-controls-header-host"
              :is-sidebar-collapsed="isSidebarCollapsed"
              :is-auto-refresh-enabled="isAutoRefreshEnabled"
              :auto-refresh-button-label="autoRefreshButtonLabel"
              :show-auto-refresh-button="false"
              :show-new-thread-button="true"
              @toggle-sidebar="setSidebarCollapsed(!isSidebarCollapsed)"
              @toggle-auto-refresh="onToggleAutoRefreshTimer"
              @start-new-thread="onStartNewThreadFromToolbar"
            />
          </template>
          <template #actions>
            <label class="ui-locale-label" for="ui-locale-select">{{ t('app_language') }}</label>
            <select
              id="ui-locale-select"
              class="ui-locale-select"
              :value="localePreference"
              @change="onLocalePreferenceChange"
            >
              <option value="system">{{ t('app_language_system') }}</option>
              <option value="zh-CN">{{ t('app_language_zh_cn') }}</option>
              <option value="en">{{ t('app_language_en') }}</option>
            </select>
          </template>
        </ContentHeader>

        <section class="content-body">
          <template v-if="isOpenClawRoute">
            <div class="content-grid">
              <div class="openclaw-toolbar">
                <button
                  type="button"
                  class="openclaw-toolbar-button"
                  :aria-label="openClawProcessToggleLabel"
                  @click="toggleOpenClawProcessView"
                >
                  {{ openClawProcessToggleLabel }}
                </button>
                <button
                  type="button"
                  class="openclaw-toolbar-button"
                  :aria-label="t('openclaw_load_older')"
                  @click="loadOlderOpenClawHistory"
                >
                  {{ t('openclaw_load_older') }}
                </button>
                <button
                  type="button"
                  class="openclaw-toolbar-button"
                  :aria-label="t('openclaw_reset_lite')"
                  @click="resetOpenClawHistoryToLite"
                >
                  {{ t('openclaw_reset_lite') }}
                </button>
                <span class="openclaw-toolbar-tip">
                  {{ t('openclaw_history_window', { count: String(openClawHistoryLimit) }) }}
                </span>
              </div>

              <p v-if="openClawLastError.length > 0" class="content-error">
                {{ openClawLastError }}
              </p>

              <div class="content-thread">
                <ThreadConversation
                  :messages="openClawMessages"
                  :is-loading="isOpenClawLoadingMessages && openClawMessages.length === 0"
                  :active-thread-id="openClawSelectedSessionKey || '__openclaw__'"
                  :scroll-state="null"
                  :live-overlay="openClawLiveOverlay"
                  :pending-requests="openClawPendingRequests"
                  :message-actions-enabled="false"
                  @update-scroll-state="onIgnoreThreadScrollState"
                  @respond-server-request="onIgnoreServerRequest"
                  @copy-message="onCopyMessage"
                  @delete-from-message="onIgnoreMessageAction"
                  @branch-from-message="onIgnoreMessageAction"
                />
              </div>

              <p v-if="!openClawSelectedSessionKey" class="conversation-empty">
                {{ t('openclaw_no_session_selected') }}
              </p>

              <OpenClawComposer
                :session-key="openClawSelectedSessionKey"
                :disabled="isOpenClawSendingMessage"
                :placeholder="t('openclaw_send_placeholder')"
                :send-label="t('openclaw_send_button')"
                @submit="onSubmitOpenClawMessage"
              />
            </div>
          </template>

          <template v-else-if="isHomeRoute">
            <div class="content-grid">
              <div class="new-thread-empty">
                <p class="new-thread-hero">{{ t('home_hero') }}</p>
                <ComposerDropdown class="new-thread-folder-dropdown" :model-value="newThreadCwd"
                  :options="newThreadFolderOptions" :placeholder="t('home_choose_folder')"
                  :disabled="newThreadFolderOptions.length === 0" @update:model-value="onSelectNewThreadFolder" />
                <p class="new-thread-guide">{{ t('home_quick_guide') }}</p>
              </div>

              <ThreadComposer :active-thread-id="composerThreadContextId" :disabled="isSendingMessage"
                :models="availableModelIds" :selected-model="selectedModelId"
                :selected-reasoning-effort="selectedReasoningEffort" :is-turn-in-progress="false"
                :is-interrupting-turn="false" @submit="onSubmitThreadMessage"
                @update:selected-model="onSelectModel" @update:selected-reasoning-effort="onSelectReasoningEffort" />
            </div>
          </template>
          <template v-else>
            <div class="content-grid">
              <div class="content-thread">
                <ThreadConversation :messages="filteredMessages" :is-loading="isLoadingMessages"
                  :active-thread-id="composerThreadContextId" :scroll-state="selectedThreadScrollState"
                  :live-overlay="liveOverlay"
                  :pending-requests="selectedThreadServerRequests"
                  @update-scroll-state="onUpdateThreadScrollState"
                  @respond-server-request="onRespondServerRequest"
                  @copy-message="onCopyMessage"
                  @delete-from-message="onDeleteFromMessage"
                  @branch-from-message="onBranchFromMessage" />
              </div>

              <ThreadComposer :active-thread-id="composerThreadContextId"
                :disabled="isSendingMessage || isLoadingMessages" :models="availableModelIds"
                :selected-model="selectedModelId" :selected-reasoning-effort="selectedReasoningEffort"
                :is-turn-in-progress="isSelectedThreadInProgress" :is-interrupting-turn="isInterruptingTurn"
                @submit="onSubmitThreadMessage" @update:selected-model="onSelectModel"
                @update:selected-reasoning-effort="onSelectReasoningEffort" @interrupt="onInterruptTurn" />
            </div>
          </template>
        </section>
      </section>
    </template>
  </DesktopLayout>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DesktopLayout from './components/layout/DesktopLayout.vue'
import SidebarThreadTree from './components/sidebar/SidebarThreadTree.vue'
import ContentHeader from './components/content/ContentHeader.vue'
import ThreadConversation from './components/content/ThreadConversation.vue'
import ThreadComposer from './components/content/ThreadComposer.vue'
import OpenClawComposer from './components/content/OpenClawComposer.vue'
import ComposerDropdown from './components/content/ComposerDropdown.vue'
import SidebarThreadControls from './components/sidebar/SidebarThreadControls.vue'
import IconTablerSearch from './components/icons/IconTablerSearch.vue'
import IconTablerX from './components/icons/IconTablerX.vue'
import IconTablerExternalLink from './components/icons/IconTablerExternalLink.vue'
import { useDesktopState } from './composables/useDesktopState'
import { useOpenClawState } from './composables/useOpenClawState'
import { useUiI18n, type LocalePreference } from './composables/useUiI18n'
import type { ReasoningEffort, ThreadScrollState, UiServerRequest } from './types/codex'

const SIDEBAR_COLLAPSED_STORAGE_KEY = 'codex-web-local.sidebar-collapsed.v1'
const { localePreference, setLocalePreference, t } = useUiI18n()
const openClawDashboardUrl = computed(() => {
  const params = new URLSearchParams({
    gatewayUrl: 'ws://localhost:18789',
    localePref: localePreference.value,
    simple: '1',
  })
  return `http://localhost:19001/chat?${params.toString()}`
})

const {
  projectGroups,
  projectDisplayNameById,
  selectedThread,
  selectedThreadScrollState,
  selectedThreadServerRequests,
  selectedLiveOverlay,
  selectedThreadId,
  availableModelIds,
  selectedModelId,
  selectedReasoningEffort,
  messages,
  isLoadingThreads,
  isLoadingMessages,
  isSendingMessage,
  isInterruptingTurn,
  isAutoRefreshEnabled,
  autoRefreshSecondsLeft,
  refreshAll,
  selectThread,
  setThreadScrollState,
  archiveThreadById,
  deleteFromMessage,
  forkFromMessage,
  sendMessageToSelectedThread,
  sendMessageToNewThread,
  interruptSelectedThreadTurn,
  setSelectedModelId,
  setSelectedReasoningEffort,
  respondToPendingServerRequest,
  renameProject,
  removeProject,
  reorderProject,
  toggleAutoRefreshTimer,
  startPolling,
  stopPolling,
} = useDesktopState()

const {
  sessions: openClawSessions,
  selectedSession: openClawSelectedSession,
  selectedSessionKey: openClawSelectedSessionKey,
  selectedSessionTitle: openClawSelectedSessionTitle,
  messages: openClawMessages,
  showProcess: openClawShowProcess,
  historyLimit: openClawHistoryLimit,
  healthOk: openClawHealthOk,
  isLoadingSessions: isOpenClawLoadingSessions,
  isLoadingMessages: isOpenClawLoadingMessages,
  isSendingMessage: isOpenClawSendingMessage,
  liveOverlay: openClawLiveOverlay,
  lastError: openClawLastError,
  initialize: initializeOpenClaw,
  refreshHealth: refreshOpenClawHealth,
  refreshSessions: refreshOpenClawSessions,
  refreshHistory: refreshOpenClawHistory,
  selectSession: selectOpenClawSession,
  sendMessage: sendOpenClawMessage,
  createSession: createOpenClawSession,
  updateSessionTitle: updateOpenClawSessionTitle,
  toggleProcessView: toggleOpenClawProcessView,
  loadOlderHistory: loadOlderOpenClawHistory,
  resetHistoryToLite: resetOpenClawHistoryToLite,
  startPolling: startOpenClawPolling,
  stopPolling: stopOpenClawPolling,
} = useOpenClawState()

const route = useRoute()
const router = useRouter()
const isRouteSyncInProgress = ref(false)
const hasInitialized = ref(false)
const newThreadCwd = ref('')
const isSidebarCollapsed = ref(loadSidebarCollapsed())
const sidebarSearchQuery = ref('')
const isSidebarSearchVisible = ref(false)
const sidebarSearchInputRef = ref<HTMLInputElement | null>(null)
const openClawSearchQuery = ref('')
const openClawPendingRequests = ref<UiServerRequest[]>([])

const routeThreadId = computed(() => {
  const rawThreadId = route.params.threadId
  return typeof rawThreadId === 'string' ? rawThreadId : ''
})
const routeOpenClawSessionKey = computed(() => {
  const rawSession = route.query.session
  return typeof rawSession === 'string' ? rawSession.trim() : ''
})

const knownThreadIdSet = computed(() => {
  const ids = new Set<string>()
  for (const group of projectGroups.value) {
    for (const thread of group.threads) {
      ids.add(thread.id)
    }
  }
  return ids
})

const isHomeRoute = computed(() => route.name === 'home')
const isThreadRoute = computed(() => route.name === 'thread')
const isOpenClawRoute = computed(() => route.name === 'openclaw-chat')
const isCodexRoute = computed(() => isHomeRoute.value || isThreadRoute.value)
const contentTitle = computed(() => {
  if (isOpenClawRoute.value) {
    return openClawSelectedSessionTitle.value || t('openclaw_chat_title')
  }
  if (isHomeRoute.value) return t('content_new_thread')
  return selectedThread.value?.title ?? t('content_choose_thread')
})
const autoRefreshButtonLabel = computed(() =>
  isAutoRefreshEnabled.value
    ? t('auto_refresh_in', { seconds: String(autoRefreshSecondsLeft.value) })
    : t('auto_refresh_enable'),
)
const filteredMessages = computed(() =>
  messages.value.filter((message) => {
    const type = normalizeMessageType(message.messageType, message.role)
    if (type === 'worked') return true
    if (type === 'turnActivity.live' || type === 'turnError.live' || type === 'agentReasoning.live') return false
    return true
  }),
)
const liveOverlay = computed(() => selectedLiveOverlay.value)
const composerThreadContextId = computed(() => (isHomeRoute.value ? '__new-thread__' : selectedThreadId.value))
const isSelectedThreadInProgress = computed(() => !isHomeRoute.value && selectedThread.value?.inProgress === true)
const openClawProcessToggleLabel = computed(() =>
  openClawShowProcess.value ? t('openclaw_process_on') : t('openclaw_process_off'),
)
const openClawFilteredSessions = computed(() => {
  const query = openClawSearchQuery.value.trim().toLowerCase()
  if (query.length === 0) return openClawSessions.value
  return openClawSessions.value.filter((row) => {
    return (
      row.title.toLowerCase().includes(query) ||
      row.key.toLowerCase().includes(query) ||
      row.preview.toLowerCase().includes(query)
    )
  })
})
const DEFAULT_WORKSPACE_NAME = 'codex'

const newThreadFolderOptions = computed(() => {
  const options: Array<{ value: string; label: string }> = []
  const seenCwds = new Set<string>()

  for (const group of projectGroups.value) {
    const cwd = group.threads[0]?.cwd?.trim() ?? ''
    if (!cwd || seenCwds.has(cwd)) continue
    seenCwds.add(cwd)
    options.push({
      value: cwd,
      label: projectDisplayNameById.value[group.projectName] ?? group.projectName,
    })
  }

  if (options.length === 0) {
    options.push({ value: DEFAULT_WORKSPACE_NAME, label: DEFAULT_WORKSPACE_NAME })
  }

  return options
})

onMounted(() => {
  window.addEventListener('keydown', onWindowKeyDown)
  void initialize()
})

onUnmounted(() => {
  window.removeEventListener('keydown', onWindowKeyDown)
  stopPolling()
  stopOpenClawPolling()
})

function toggleSidebarSearch(): void {
  isSidebarSearchVisible.value = !isSidebarSearchVisible.value
  if (isSidebarSearchVisible.value) {
    nextTick(() => sidebarSearchInputRef.value?.focus())
  } else {
    sidebarSearchQuery.value = ''
  }
}

function clearSidebarSearch(): void {
  sidebarSearchQuery.value = ''
  sidebarSearchInputRef.value?.focus()
}

function onSidebarSearchKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    isSidebarSearchVisible.value = false
    sidebarSearchQuery.value = ''
  }
}

function onSelectThread(threadId: string): void {
  if (!threadId) return
  if (route.name === 'thread' && routeThreadId.value === threadId) return
  void router.push({ name: 'thread', params: { threadId } })
}

function onArchiveThread(threadId: string): void {
  void archiveThreadById(threadId)
}

function onStartNewThread(projectName: string): void {
  const projectGroup = projectGroups.value.find((group) => group.projectName === projectName)
  const projectCwd = projectGroup?.threads[0]?.cwd?.trim() ?? ''
  if (projectCwd) {
    newThreadCwd.value = projectCwd
  }
  if (isHomeRoute.value) return
  void router.push({ name: 'home' })
}

function onStartNewThreadFromToolbar(): void {
  const cwd = selectedThread.value?.cwd?.trim() ?? ''
  if (cwd) {
    newThreadCwd.value = cwd
  }
  if (isHomeRoute.value) return
  void router.push({ name: 'home' })
}

function onRenameProject(payload: { projectName: string; displayName: string }): void {
  renameProject(payload.projectName, payload.displayName)
}

function onRemoveProject(projectName: string): void {
  removeProject(projectName)
}

function onReorderProject(payload: { projectName: string; toIndex: number }): void {
  reorderProject(payload.projectName, payload.toIndex)
}

function onUpdateThreadScrollState(payload: { threadId: string; state: ThreadScrollState }): void {
  setThreadScrollState(payload.threadId, payload.state)
}

function onRespondServerRequest(payload: { id: number; result?: unknown; error?: { code?: number; message: string } }): void {
  void respondToPendingServerRequest(payload)
}

function onCopyMessage(messageId: string): void {
  const row = filteredMessages.value.find((message) => message.id === messageId)
  if (!row || row.text.trim().length === 0) return
  if (typeof navigator === 'undefined' || !navigator.clipboard?.writeText) return
  void navigator.clipboard.writeText(row.text)
}

function onDeleteFromMessage(messageId: string): void {
  if (typeof window !== 'undefined') {
    const shouldContinue = window.confirm(t('delete_turn_confirm'))
    if (!shouldContinue) return
  }
  void (async () => {
    try {
      await deleteFromMessage(messageId)
    } catch (error) {
      if (typeof window !== 'undefined') {
        window.alert(error instanceof Error ? error.message : t('delete_message_failed'))
      }
    }
  })()
}

function onBranchFromMessage(messageId: string): void {
  void (async () => {
    try {
      await forkFromMessage(messageId)
    } catch (error) {
      if (typeof window !== 'undefined') {
        window.alert(error instanceof Error ? error.message : t('branch_message_failed'))
      }
    }
  })()
}

function onLocalePreferenceChange(event: Event): void {
  const target = event.target as HTMLSelectElement | null
  const value = target?.value
  if (value === 'system' || value === 'zh-CN' || value === 'en') {
    setLocalePreference(value as LocalePreference)
  }
}

function onToggleAutoRefreshTimer(): void {
  toggleAutoRefreshTimer()
}

function setSidebarCollapsed(nextValue: boolean): void {
  if (isSidebarCollapsed.value === nextValue) return
  isSidebarCollapsed.value = nextValue
  saveSidebarCollapsed(nextValue)
}

function onWindowKeyDown(event: KeyboardEvent): void {
  if (event.defaultPrevented) return
  if (!event.ctrlKey && !event.metaKey) return
  if (event.shiftKey || event.altKey) return
  if (event.key.toLowerCase() !== 'b') return
  event.preventDefault()
  setSidebarCollapsed(!isSidebarCollapsed.value)
}

function onSubmitThreadMessage(text: string): void {
  if (isHomeRoute.value) {
    void submitFirstMessageForNewThread(text)
    return
  }
  void sendMessageToSelectedThread(text)
}

function onSelectNewThreadFolder(cwd: string): void {
  newThreadCwd.value = cwd.trim()
}

function onSelectModel(modelId: string): void {
  setSelectedModelId(modelId)
}

function onSelectReasoningEffort(effort: ReasoningEffort | ''): void {
  setSelectedReasoningEffort(effort)
}

function onInterruptTurn(): void {
  void interruptSelectedThreadTurn()
}

function formatOpenClawTime(updatedAtMs: number): string {
  if (!updatedAtMs || updatedAtMs <= 0) return t('time_na')
  const date = new Date(updatedAtMs)
  if (Number.isNaN(date.getTime())) return t('time_na')
  return date.toLocaleString()
}

function onOpenOpenClawChat(): void {
  const query = openClawSelectedSessionKey.value
    ? { session: openClawSelectedSessionKey.value }
    : undefined
  void router.push({ name: 'openclaw-chat', query })
}

function onBackToCodexRoute(): void {
  if (selectedThreadId.value) {
    void router.push({ name: 'thread', params: { threadId: selectedThreadId.value } })
    return
  }
  void router.push({ name: 'home' })
}

function onToggleOpenClawSearch(): void {
  openClawSearchQuery.value = ''
}

function onSelectOpenClawSession(sessionKey: string): void {
  if (!sessionKey) return
  void (async () => {
    await selectOpenClawSession(sessionKey)
    if (isOpenClawRoute.value) {
      await router.replace({ name: 'openclaw-chat', query: { session: sessionKey } })
    }
  })()
}

function onRefreshOpenClaw(): void {
  void (async () => {
    await refreshOpenClawHealth()
    await refreshOpenClawSessions(openClawSelectedSessionKey.value)
    await refreshOpenClawHistory()
  })()
}

function onCreateOpenClawSession(): void {
  void (async () => {
    const sessionKey = await createOpenClawSession()
    if (sessionKey) {
      await router.replace({ name: 'openclaw-chat', query: { session: sessionKey } })
    }
  })()
}

function onRenameOpenClawSession(sessionKey: string): void {
  if (!sessionKey || typeof window === 'undefined') return
  const current = openClawSelectedSession.value?.title || sessionKey
  const nextTitle = window.prompt(t('openclaw_rename_session'), current)?.trim() || ''
  if (!nextTitle) return
  void (async () => {
    try {
      await updateOpenClawSessionTitle(sessionKey, nextTitle)
    } catch (error) {
      window.alert(error instanceof Error ? error.message : t('openclaw_rename_session_failed'))
    }
  })()
}

function onSubmitOpenClawMessage(text: string): void {
  void sendOpenClawMessage(text)
}

function onIgnoreThreadScrollState(): void {
  // No-op for OpenClaw chat mode.
}

function onIgnoreServerRequest(): void {
  // No-op for OpenClaw chat mode.
}

function onIgnoreMessageAction(): void {
  // No-op for OpenClaw chat mode.
}

function loadSidebarCollapsed(): boolean {
  if (typeof window === 'undefined') return false
  const stored = window.localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY)
  if (stored === null) return true
  return stored === '1'
}

function saveSidebarCollapsed(value: boolean): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, value ? '1' : '0')
}

function normalizeMessageType(rawType: string | undefined, role: string): string {
  const normalized = (rawType ?? '').trim()
  if (normalized.length > 0) {
    return normalized
  }
  return role.trim() || 'message'
}

async function initialize(): Promise<void> {
  await refreshAll()
  try {
    await initializeOpenClaw(routeOpenClawSessionKey.value)
  } catch {
    // Keep Codex UI available even if OpenClaw bootstrap is temporarily unavailable.
  }
  hasInitialized.value = true
  await syncThreadSelectionWithRoute()
  startPolling()
  if (isOpenClawRoute.value) {
    startOpenClawPolling()
  }
}

async function syncThreadSelectionWithRoute(): Promise<void> {
  if (!isCodexRoute.value) return
  if (isRouteSyncInProgress.value) return
  isRouteSyncInProgress.value = true

  try {
    if (route.name === 'home') {
      if (selectedThreadId.value !== '') {
        await selectThread('')
      }
      return
    }

    if (route.name === 'thread') {
      const threadId = routeThreadId.value
      if (!threadId) return

      if (!knownThreadIdSet.value.has(threadId)) {
        await router.replace({ name: 'home' })
        return
      }

      if (selectedThreadId.value !== threadId) {
        await selectThread(threadId)
      }
      return
    }

  } finally {
    isRouteSyncInProgress.value = false
  }
}

watch(
  () =>
    [
      route.name,
      routeThreadId.value,
      isLoadingThreads.value,
      knownThreadIdSet.value.has(routeThreadId.value),
      selectedThreadId.value,
    ] as const,
  async () => {
    if (!hasInitialized.value) return
    await syncThreadSelectionWithRoute()
  },
)

watch(
  () => selectedThreadId.value,
  async (threadId) => {
    if (!hasInitialized.value) return
    if (isRouteSyncInProgress.value) return
    if (!isCodexRoute.value) return
    if (isHomeRoute.value) return

    if (!threadId) {
      if (route.name !== 'home') {
        await router.replace({ name: 'home' })
      }
      return
    }

    if (route.name === 'thread' && routeThreadId.value === threadId) return
    await router.replace({ name: 'thread', params: { threadId } })
  },
)

watch(
  () => isOpenClawRoute.value,
  (isActive) => {
    if (!hasInitialized.value) return
    if (isActive) {
      startOpenClawPolling()
      if (routeOpenClawSessionKey.value) {
        void selectOpenClawSession(routeOpenClawSessionKey.value)
      } else if (openClawSelectedSessionKey.value) {
        void router.replace({ name: 'openclaw-chat', query: { session: openClawSelectedSessionKey.value } })
      }
      return
    }
    stopOpenClawPolling()
  },
)

watch(
  () => routeOpenClawSessionKey.value,
  (sessionKey) => {
    if (!hasInitialized.value) return
    if (!isOpenClawRoute.value) return
    if (!sessionKey) return
    if (sessionKey === openClawSelectedSessionKey.value) return
    void selectOpenClawSession(sessionKey)
  },
)

watch(
  () => openClawSelectedSessionKey.value,
  (sessionKey) => {
    if (!hasInitialized.value) return
    if (!isOpenClawRoute.value) return
    if (!sessionKey) return
    if (routeOpenClawSessionKey.value === sessionKey) return
    void router.replace({ name: 'openclaw-chat', query: { session: sessionKey } })
  },
)

watch(
  () => newThreadFolderOptions.value,
  (options) => {
    if (options.length === 0) {
      newThreadCwd.value = ''
      return
    }
    const hasSelected = options.some((option) => option.value === newThreadCwd.value)
    if (!hasSelected) {
      newThreadCwd.value = options[0].value
    }
  },
  { immediate: true },
)

async function submitFirstMessageForNewThread(text: string): Promise<void> {
  try {
    const threadId = await sendMessageToNewThread(text, newThreadCwd.value)
    if (!threadId) return
    await router.replace({ name: 'thread', params: { threadId } })
  } catch {
    // Error is already reflected in state.
  }
}
</script>

<style scoped>
@reference "tailwindcss";

.sidebar-root {
  @apply min-h-full py-4 px-2 flex flex-col gap-2 select-none;
}

.sidebar-root input,
.sidebar-root textarea {
  @apply select-text;
}

.content-root {
  @apply h-full min-h-0 w-full flex flex-col overflow-y-hidden overflow-x-visible bg-white;
}

.sidebar-thread-controls-host {
  @apply mt-1 -translate-y-px px-2 pb-1;
}

.sidebar-search-toggle {
  @apply h-6.75 w-6.75 rounded-md border border-transparent bg-transparent text-zinc-600 flex items-center justify-center transition hover:border-zinc-200 hover:bg-zinc-50;
}

.sidebar-search-toggle[aria-pressed='true'] {
  @apply border-zinc-300 bg-zinc-100 text-zinc-700;
}

.sidebar-search-toggle-icon {
  @apply w-4 h-4;
}

.sidebar-search-bar {
  @apply flex items-center gap-1.5 mx-2 px-2 py-1 rounded-md border border-zinc-200 bg-white transition-colors focus-within:border-zinc-400;
}

.sidebar-search-bar-icon {
  @apply w-3.5 h-3.5 text-zinc-400 shrink-0;
}

.sidebar-search-input {
  @apply flex-1 min-w-0 bg-transparent text-sm text-zinc-800 placeholder-zinc-400 outline-none border-none p-0;
}

.sidebar-search-clear {
  @apply w-4 h-4 rounded text-zinc-400 flex items-center justify-center transition hover:text-zinc-600;
}

.sidebar-search-clear-icon {
  @apply w-3.5 h-3.5;
}

.sidebar-thread-controls-header-host {
  @apply ml-1;
}

.content-body {
  @apply flex-1 min-h-0 w-full flex flex-col gap-3 pt-1 pb-4 overflow-y-hidden overflow-x-visible;
}

.content-error {
  @apply m-0 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700;
}

.content-grid {
  @apply flex-1 min-h-0 flex flex-col gap-3;
}

.content-thread {
  @apply flex-1 min-h-0;
}

.new-thread-empty {
  @apply flex-1 min-h-0 flex flex-col items-center justify-center gap-0.5 px-6;
}

.new-thread-hero {
  @apply m-0 text-[2.5rem] font-normal leading-[1.05] text-zinc-900;
}

.new-thread-folder-dropdown {
  @apply text-[2.5rem] text-zinc-500;
}

.new-thread-folder-dropdown :deep(.composer-dropdown-trigger) {
  @apply h-auto text-[2.5rem] leading-[1.05];
}

.new-thread-folder-dropdown :deep(.composer-dropdown-value) {
  @apply leading-[1.05];
}

.new-thread-folder-dropdown :deep(.composer-dropdown-chevron) {
  @apply h-5 w-5 mt-0;
}

.new-thread-guide {
  @apply mt-3 max-w-xl text-center text-sm leading-6 text-zinc-500;
}

.openclaw-dashboard-link {
  @apply mt-auto mx-2 mb-1 flex items-center gap-2 rounded-md px-2.5 py-2 text-sm font-medium text-orange-700 bg-orange-50 border border-orange-200 transition no-underline hover:bg-orange-100 hover:border-orange-300;
}

.openclaw-dashboard-icon {
  @apply w-4 h-4 shrink-0;
}

.openclaw-dashboard-label {
  @apply truncate;
}

.openclaw-sidebar-actions {
  @apply mx-2 mt-1 mb-1 flex flex-col gap-2;
}

.openclaw-sidebar-button {
  @apply w-full rounded-md border border-zinc-200 bg-white px-2.5 py-2 text-left text-sm text-zinc-700 transition hover:bg-zinc-50 hover:border-zinc-300;
}

.openclaw-health-status {
  @apply mx-2 mb-1 rounded-md border border-zinc-200 bg-zinc-50 px-2.5 py-2 text-xs text-zinc-600;
}

.openclaw-session-list {
  @apply list-none m-0 px-2 pb-2 flex-1 min-h-0 overflow-y-auto overflow-x-hidden space-y-1;
}

.openclaw-session-empty {
  @apply rounded-md border border-dashed border-zinc-200 bg-white px-2.5 py-2 text-xs text-zinc-500;
}

.openclaw-session-item {
  @apply m-0;
}

.openclaw-session-button {
  @apply w-full rounded-md border border-transparent bg-white px-2.5 py-2 text-left transition hover:border-zinc-200 hover:bg-zinc-50;
}

.openclaw-session-button.is-active {
  @apply border-orange-200 bg-orange-50;
}

.openclaw-session-title {
  @apply block truncate text-sm text-zinc-800;
}

.openclaw-session-meta {
  @apply mt-0.5 block truncate text-[11px] text-zinc-500;
}

.openclaw-toolbar {
  @apply mx-auto w-full max-w-175 px-6 flex flex-wrap items-center gap-2;
}

.openclaw-toolbar-button {
  @apply rounded-md border border-zinc-200 bg-white px-2.5 py-1.5 text-xs text-zinc-700 transition hover:bg-zinc-50 hover:border-zinc-300;
}

.openclaw-toolbar-tip {
  @apply text-xs text-zinc-500;
}

.ui-locale-label {
  @apply text-xs text-zinc-500;
}

.ui-locale-select {
  @apply h-8 rounded-md border border-zinc-200 bg-white px-2 text-xs text-zinc-700;
}

</style>
