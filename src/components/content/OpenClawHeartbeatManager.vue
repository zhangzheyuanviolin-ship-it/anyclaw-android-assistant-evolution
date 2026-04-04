<template>
  <div class="heartbeat-manager-root">
    <p v-if="loading" class="heartbeat-manager-muted">正在读取心跳配置…</p>

    <div v-else class="heartbeat-manager-card">
      <p class="heartbeat-manager-title">心跳管理</p>

      <p v-if="!editorOpen" class="heartbeat-manager-summary">
        当前状态：{{ enabled ? '已开启' : '已关闭' }}，当前间隔：{{ every }}，当前模板：{{ activeProfileName }}
      </p>

      <p v-if="editorOpen" class="heartbeat-manager-summary">
        在此管理心跳提醒模板，选择后会作为当前生效内容写入配置与 HEARTBEAT.md。
      </p>

      <p v-if="errorText" class="heartbeat-manager-error">{{ errorText }}</p>
      <p v-if="noticeText" class="heartbeat-manager-notice">{{ noticeText }}</p>

      <template v-if="!editorOpen">
        <div class="heartbeat-manager-row">
          <button
            type="button"
            class="heartbeat-manager-button"
            :disabled="saving"
            @click="enabled = !enabled"
          >
            {{ enabled ? '关闭心跳' : '开启心跳' }}
          </button>
          <button
            type="button"
            class="heartbeat-manager-button"
            :disabled="saving"
            @click="openEditor"
          >
            提醒内容编辑
          </button>
        </div>

        <div class="heartbeat-manager-intervals" role="group" aria-label="心跳间隔选择">
          <button
            v-for="item in intervalOptions"
            :key="item"
            type="button"
            class="heartbeat-manager-button"
            :class="{ 'is-active': every === item }"
            :disabled="saving"
            @click="every = item"
          >
            {{ item }}
          </button>
        </div>

        <div class="heartbeat-manager-row">
          <button
            type="button"
            class="heartbeat-manager-button"
            :disabled="saving"
            @click="saveAll(false)"
          >
            {{ saving ? '保存中…' : '保存设置' }}
          </button>
          <button
            type="button"
            class="heartbeat-manager-button"
            :disabled="saving"
            @click="saveAll(true)"
          >
            {{ saving ? '处理中…' : '保存并重启网关' }}
          </button>
          <button
            type="button"
            class="heartbeat-manager-button"
            :disabled="saving"
            @click="emit('back-chat')"
          >
            返回聊天
          </button>
        </div>
      </template>

      <template v-else>
        <div class="heartbeat-manager-row">
          <button
            type="button"
            class="heartbeat-manager-button"
            :disabled="saving"
            @click="editorOpen = false"
          >
            返回心跳管理
          </button>
          <button
            type="button"
            class="heartbeat-manager-button"
            :disabled="saving"
            @click="startCreate"
          >
            新建模板
          </button>
        </div>

        <div class="heartbeat-manager-profile-list">
          <div
            v-for="row in profiles"
            :key="row.id"
            class="heartbeat-manager-profile-row"
          >
            <div class="heartbeat-manager-profile-title">
              <span>{{ row.name }}</span>
              <span v-if="row.id === activeProfileId" class="heartbeat-manager-tag">已选中</span>
            </div>
            <div class="heartbeat-manager-row">
              <button
                type="button"
                class="heartbeat-manager-button"
                :disabled="saving"
                @click="selectProfile(row.id)"
              >
                选中
              </button>
              <button
                type="button"
                class="heartbeat-manager-button"
                :disabled="saving"
                @click="startEdit(row.id)"
              >
                编辑
              </button>
              <button
                type="button"
                class="heartbeat-manager-button"
                :disabled="saving || profiles.length <= 1"
                @click="removeProfile(row.id)"
              >
                删除
              </button>
            </div>
          </div>
        </div>

        <div class="heartbeat-manager-form">
          <label class="heartbeat-manager-label">
            模板名称
            <input v-model="editingName" class="heartbeat-manager-input" type="text" />
          </label>
          <label class="heartbeat-manager-label">
            心跳提示词内容
            <textarea v-model="editingPrompt" class="heartbeat-manager-textarea" rows="5" />
          </label>
          <label class="heartbeat-manager-label">
            HEARTBEAT.md 内容
            <textarea v-model="editingDocument" class="heartbeat-manager-textarea" rows="8" />
          </label>
          <div class="heartbeat-manager-row">
            <button
              type="button"
              class="heartbeat-manager-button"
              :disabled="saving"
              @click="saveProfileForm"
            >
              保存模板
            </button>
            <button
              type="button"
              class="heartbeat-manager-button"
              :disabled="saving"
              @click="saveAll(false)"
            >
              保存全部
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getOpenClawHeartbeatConfig,
  saveOpenClawHeartbeatConfig,
  type OpenClawHeartbeatProfile,
} from '../../api/openclawGateway'

const emit = defineEmits<{
  'back-chat': []
}>()

const intervalOptions = ['1m', '2m', '5m', '10m', '30m', '1h', '2h']

const loading = ref(true)
const saving = ref(false)
const editorOpen = ref(false)
const errorText = ref('')
const noticeText = ref('')

const enabled = ref(true)
const every = ref('20m')
const activeProfileId = ref('')
const profiles = ref<OpenClawHeartbeatProfile[]>([])

const editingProfileId = ref('')
const editingName = ref('')
const editingPrompt = ref('')
const editingDocument = ref('')

const activeProfileName = computed(() => {
  const row = profiles.value.find((item) => item.id === activeProfileId.value)
  return row?.name || '未选择'
})

function generateProfileId(): string {
  return `hb_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

function normalizeProfiles(nextProfiles: OpenClawHeartbeatProfile[]): OpenClawHeartbeatProfile[] {
  const output: OpenClawHeartbeatProfile[] = []
  const seen = new Set<string>()
  for (const row of nextProfiles) {
    const id = row.id.trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    output.push({
      id,
      name: row.name.trim() || id,
      prompt: row.prompt,
      document: row.document,
      updatedAtMs: row.updatedAtMs > 0 ? row.updatedAtMs : Date.now(),
    })
  }
  return output
}

function ensureAtLeastOneProfile(): void {
  if (profiles.value.length > 0) {
    if (!profiles.value.some((row) => row.id === activeProfileId.value)) {
      activeProfileId.value = profiles.value[0].id
    }
    return
  }
  const now = Date.now()
  const fallback: OpenClawHeartbeatProfile = {
    id: generateProfileId(),
    name: '默认心跳模板',
    prompt: 'Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. Do not infer or repeat old tasks from prior chats. If nothing needs attention, reply HEARTBEAT_OK.',
    document: '# HEARTBEAT.md\n1) 检查当前任务是否仍需继续。\n2) 若无阻塞任务，回复 HEARTBEAT_OK。\n3) 若存在阻塞，简要说明根因并继续执行。',
    updatedAtMs: now,
  }
  profiles.value = [fallback]
  activeProfileId.value = fallback.id
}

async function loadConfig(): Promise<void> {
  loading.value = true
  errorText.value = ''
  noticeText.value = ''
  try {
    const payload = await getOpenClawHeartbeatConfig()
    enabled.value = payload.enabled
    every.value = payload.every || '20m'
    profiles.value = normalizeProfiles(payload.profiles)
    activeProfileId.value = payload.activeProfileId
    ensureAtLeastOneProfile()
    startEdit(activeProfileId.value)
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : '读取心跳配置失败'
  } finally {
    loading.value = false
  }
}

function openEditor(): void {
  editorOpen.value = true
  if (!activeProfileId.value && profiles.value[0]) {
    activeProfileId.value = profiles.value[0].id
  }
  startEdit(activeProfileId.value)
}

function startCreate(): void {
  editingProfileId.value = ''
  editingName.value = ''
  editingPrompt.value = ''
  editingDocument.value = ''
}

function startEdit(profileId: string): void {
  const row = profiles.value.find((item) => item.id === profileId)
  if (!row) {
    startCreate()
    return
  }
  editingProfileId.value = row.id
  editingName.value = row.name
  editingPrompt.value = row.prompt
  editingDocument.value = row.document
}

function selectProfile(profileId: string): void {
  if (!profiles.value.some((row) => row.id === profileId)) return
  activeProfileId.value = profileId
  startEdit(profileId)
  noticeText.value = '已切换当前模板，请记得保存设置。'
}

function saveProfileForm(): void {
  const name = editingName.value.trim()
  const prompt = editingPrompt.value.trim()
  const documentText = editingDocument.value.trim()
  if (!name) {
    errorText.value = '模板名称不能为空'
    return
  }
  if (!prompt) {
    errorText.value = '心跳提示词内容不能为空'
    return
  }
  if (!documentText) {
    errorText.value = 'HEARTBEAT.md 内容不能为空'
    return
  }

  const now = Date.now()
  const profileId = editingProfileId.value.trim() || generateProfileId()
  const next: OpenClawHeartbeatProfile = {
    id: profileId,
    name,
    prompt,
    document: documentText,
    updatedAtMs: now,
  }

  const index = profiles.value.findIndex((row) => row.id === profileId)
  if (index >= 0) {
    const rows = [...profiles.value]
    rows[index] = next
    profiles.value = rows
  } else {
    profiles.value = [...profiles.value, next]
  }
  activeProfileId.value = profileId
  editingProfileId.value = profileId
  errorText.value = ''
  noticeText.value = '模板已更新，请点击保存设置生效。'
}

function removeProfile(profileId: string): void {
  if (profiles.value.length <= 1) {
    errorText.value = '至少需要保留一个模板'
    return
  }
  profiles.value = profiles.value.filter((row) => row.id !== profileId)
  if (!profiles.value.some((row) => row.id === activeProfileId.value)) {
    activeProfileId.value = profiles.value[0]?.id || ''
  }
  startEdit(activeProfileId.value)
  noticeText.value = '模板已删除，请点击保存设置生效。'
}

async function saveAll(restartGateway: boolean): Promise<void> {
  if (saving.value) return
  errorText.value = ''
  noticeText.value = ''
  ensureAtLeastOneProfile()
  if (!intervalOptions.includes(every.value)) {
    errorText.value = '请选择有效的心跳间隔'
    return
  }
  if (!profiles.value.some((row) => row.id === activeProfileId.value)) {
    errorText.value = '请先选择有效模板'
    return
  }

  saving.value = true
  try {
    const response = await saveOpenClawHeartbeatConfig({
      enabled: enabled.value,
      every: every.value,
      activeProfileId: activeProfileId.value,
      profiles: profiles.value,
      restartGateway,
    })
    if (!response.ok) {
      throw new Error('保存失败：网关未确认配置更新')
    }
    enabled.value = response.enabled
    every.value = response.every
    activeProfileId.value = response.activeProfileId
    noticeText.value = restartGateway
      ? (response.restartTriggered ? '已保存并请求重启网关。' : `已保存，但网关重启未确认成功：${response.restartOutput || '请手动重启网关'}`)
      : '已保存心跳设置，若需立即生效请点击“保存并重启网关”。'
    await loadConfig()
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : '保存心跳配置失败'
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void loadConfig()
})
</script>

<style scoped>
@reference "tailwindcss";

.heartbeat-manager-root {
  @apply mx-auto w-full max-w-175 px-6;
}

.heartbeat-manager-card {
  @apply rounded-2xl border border-zinc-300 bg-white p-4 shadow-sm flex flex-col gap-3;
}

.heartbeat-manager-title {
  @apply m-0 text-base font-semibold text-zinc-900;
}

.heartbeat-manager-summary {
  @apply m-0 text-sm text-zinc-700;
}

.heartbeat-manager-muted {
  @apply m-0 text-sm text-zinc-500;
}

.heartbeat-manager-error {
  @apply m-0 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700 whitespace-pre-wrap;
}

.heartbeat-manager-notice {
  @apply m-0 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700 whitespace-pre-wrap;
}

.heartbeat-manager-row {
  @apply flex flex-wrap items-center gap-2;
}

.heartbeat-manager-intervals {
  @apply flex flex-wrap items-center gap-2;
}

.heartbeat-manager-button {
  @apply inline-flex h-9 items-center justify-center rounded-full border border-zinc-300 bg-white px-3 text-xs text-zinc-700 transition hover:bg-zinc-50 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-400;
}

.heartbeat-manager-button.is-active {
  @apply border-orange-300 bg-orange-50 text-orange-700;
}

.heartbeat-manager-profile-list {
  @apply flex flex-col gap-2;
}

.heartbeat-manager-profile-row {
  @apply rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 flex flex-col gap-2;
}

.heartbeat-manager-profile-title {
  @apply flex items-center gap-2 text-sm text-zinc-800;
}

.heartbeat-manager-tag {
  @apply rounded-full bg-orange-100 px-2 py-0.5 text-[10px] text-orange-700;
}

.heartbeat-manager-form {
  @apply flex flex-col gap-2;
}

.heartbeat-manager-label {
  @apply flex flex-col gap-1 text-xs text-zinc-700;
}

.heartbeat-manager-input {
  @apply h-9 rounded-lg border border-zinc-300 px-2 text-sm text-zinc-900 outline-none;
}

.heartbeat-manager-textarea {
  @apply rounded-lg border border-zinc-300 px-2 py-2 text-sm text-zinc-900 outline-none resize-y;
}
</style>
