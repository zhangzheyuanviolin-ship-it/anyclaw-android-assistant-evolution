<template>
  <form ref="composerRootRef" class="openclaw-composer" @submit.prevent="onSubmit">
    <div class="openclaw-composer-shell">
      <textarea
        ref="composerInputRef"
        v-model="draft"
        class="openclaw-composer-input"
        rows="2"
        :placeholder="placeholder"
        :disabled="disabled || !sessionKey"
        :aria-label="placeholder"
        @focus="onFocusInput"
        @keydown.enter.exact.prevent="onSubmit"
      />

      <div class="openclaw-composer-actions">
        <button
          class="openclaw-composer-action"
          type="button"
          :aria-label="abortLabel"
          :disabled="!sessionKey || abortDisabled"
          @click="onAbortRun"
        >
          {{ abortLabel }}
        </button>
        <div class="openclaw-composer-attach-wrap">
          <button
            class="openclaw-composer-attach"
            type="button"
            :aria-label="attachLabel"
            :disabled="disabled || !sessionKey"
            @click="toggleAttachmentMenu"
          >
            {{ attachLabel }}
          </button>

          <div v-if="isAttachmentMenuOpen" class="openclaw-composer-attach-menu">
            <button
              class="openclaw-composer-attach-menu-item"
              type="button"
              :aria-label="attachCameraLabel"
              @click="openCameraPicker"
            >
              {{ attachCameraLabel }}
            </button>
            <button
              class="openclaw-composer-attach-menu-item"
              type="button"
              :aria-label="attachGalleryLabel"
              @click="openGalleryPicker"
            >
              {{ attachGalleryLabel }}
            </button>
            <button
              class="openclaw-composer-attach-menu-item"
              type="button"
              :aria-label="attachFilesLabel"
              @click="openFilesPicker"
            >
              {{ attachFilesLabel }}
            </button>
          </div>
        </div>
        <button
          class="openclaw-composer-submit"
          type="submit"
          :aria-label="sendLabel"
          :disabled="disabled || !sessionKey || (draft.trim().length === 0 && attachments.length === 0)"
        >
          {{ sendLabel }}
        </button>
      </div>
    </div>

    <div v-if="attachments.length > 0" class="openclaw-composer-attachments" :aria-label="attachLabel">
      <div
        v-for="attachment in attachments"
        :key="attachment.id"
        class="openclaw-composer-attachment-row"
      >
        <span class="openclaw-composer-attachment-name">
          {{ attachment.name }}
          <span class="openclaw-composer-attachment-type">
            {{ attachment.type === 'image' ? imageTagLabel : fileTagLabel }}
          </span>
        </span>
        <button
          class="openclaw-composer-attachment-remove"
          type="button"
          :aria-label="`${removeAttachmentLabel} ${attachment.name}`"
          @click="removeAttachment(attachment.id)"
        >
          {{ removeAttachmentLabel }}
        </button>
      </div>
    </div>

    <input
      ref="cameraPickerRef"
      class="openclaw-composer-hidden-input"
      type="file"
      accept="image/*"
      capture="environment"
      @change="onSelectCameraFiles"
    />
    <input
      ref="galleryPickerRef"
      class="openclaw-composer-hidden-input"
      type="file"
      accept="image/*"
      multiple
      @change="onSelectGalleryFiles"
    />
    <input
      ref="filesPickerRef"
      class="openclaw-composer-hidden-input"
      type="file"
      accept="*/*"
      multiple
      @change="onSelectGenericFiles"
    />
  </form>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type {
  ClaudeComposerAttachment,
  ClaudeComposerImageAttachment,
  ClaudeComposerSubmitPayload,
} from '../../types/claude'

const props = defineProps<{
  sessionKey: string
  disabled?: boolean
  placeholder: string
  sendLabel: string
  attachLabel: string
  abortLabel: string
  abortDisabled?: boolean
  attachCameraLabel: string
  attachGalleryLabel: string
  attachFilesLabel: string
  removeAttachmentLabel: string
  imageTagLabel: string
  fileTagLabel: string
}>()

const emit = defineEmits<{
  submit: [payload: ClaudeComposerSubmitPayload]
  'abort-run': []
}>()

const draft = ref('')
const composerInputRef = ref<HTMLTextAreaElement | null>(null)
const composerRootRef = ref<HTMLFormElement | null>(null)
const cameraPickerRef = ref<HTMLInputElement | null>(null)
const galleryPickerRef = ref<HTMLInputElement | null>(null)
const filesPickerRef = ref<HTMLInputElement | null>(null)
const isAttachmentMenuOpen = ref(false)
const attachments = ref<ClaudeComposerAttachment[]>([])

function moveCursorToEnd(): void {
  const input = composerInputRef.value
  if (!input) return
  const length = input.value.length
  requestAnimationFrame(() => {
    try {
      input.setSelectionRange(length, length)
    } catch {
      // Ignore unsupported selection operations.
    }
  })
}

function onFocusInput(): void {
  moveCursorToEnd()
}

function generateAttachmentId(): string {
  return `att_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

function showAttachmentError(message: string): void {
  if (typeof window !== 'undefined') {
    window.alert(message)
  }
}

function normalizeImageMimeType(file: File): string {
  const normalized = file.type.trim()
  if (normalized.startsWith('image/')) return normalized
  const lowerName = file.name.toLowerCase()
  if (lowerName.endsWith('.png')) return 'image/png'
  if (lowerName.endsWith('.jpg') || lowerName.endsWith('.jpeg')) return 'image/jpeg'
  if (lowerName.endsWith('.webp')) return 'image/webp'
  return 'image/png'
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(new Error(`读取附件失败：${file.name}`))
    reader.onload = () => {
      const dataUrl = typeof reader.result === 'string' ? reader.result.trim() : ''
      if (!dataUrl.startsWith('data:')) {
        reject(new Error(`附件编码失败：${file.name}`))
        return
      }
      resolve(dataUrl)
    }
    reader.readAsDataURL(file)
  })
}

async function toImageAttachment(file: File): Promise<ClaudeComposerImageAttachment> {
  const dataUrl = await readFileAsDataUrl(file)
  return {
    id: generateAttachmentId(),
    file,
    type: 'image',
    name: file.name || 'image',
    mimeType: normalizeImageMimeType(file),
    sizeBytes: file.size,
    dataUrl,
  }
}

function toFileAttachment(file: File): ClaudeComposerAttachment {
  return {
    id: generateAttachmentId(),
    type: 'file',
    name: file.name || 'file',
    mimeType: file.type || 'application/octet-stream',
    sizeBytes: file.size,
    file,
  }
}

async function onFilesSelected(
  files: FileList | null,
  source: 'camera' | 'gallery' | 'files',
  pickerRef: HTMLInputElement | null,
): Promise<void> {
  if (!files || files.length === 0) {
    if (pickerRef) pickerRef.value = ''
    return
  }

  const nextRows: ClaudeComposerAttachment[] = []
  const failures: string[] = []

  for (const file of Array.from(files)) {
    try {
      if (source === 'files') {
        nextRows.push(toFileAttachment(file))
      } else {
        nextRows.push(await toImageAttachment(file))
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '附件处理失败：' + (file.name || '未知文件')
      failures.push(message)
    }
  }

  try {
    attachments.value = [...attachments.value, ...nextRows]
  } finally {
    if (pickerRef) pickerRef.value = ''
    isAttachmentMenuOpen.value = false
  }

  if (failures.length > 0) {
    showAttachmentError('以下附件处理失败（其余附件已保留）：\n' + failures.join('\n'))
  }
}

function removeAttachment(attachmentId: string): void {
  attachments.value = attachments.value.filter((row) => row.id !== attachmentId)
}

function toggleAttachmentMenu(): void {
  if (props.disabled || !props.sessionKey) return
  isAttachmentMenuOpen.value = !isAttachmentMenuOpen.value
}

function closeAttachmentMenu(): void {
  isAttachmentMenuOpen.value = false
}

function openCameraPicker(): void {
  cameraPickerRef.value?.click()
}

function openGalleryPicker(): void {
  galleryPickerRef.value?.click()
}

function openFilesPicker(): void {
  filesPickerRef.value?.click()
}

function onSelectCameraFiles(event: Event): void {
  const target = event.target as HTMLInputElement | null
  void onFilesSelected(target?.files ?? null, 'camera', target)
}

function onSelectGalleryFiles(event: Event): void {
  const target = event.target as HTMLInputElement | null
  void onFilesSelected(target?.files ?? null, 'gallery', target)
}

function onSelectGenericFiles(event: Event): void {
  const target = event.target as HTMLInputElement | null
  void onFilesSelected(target?.files ?? null, 'files', target)
}

function onAbortRun(): void {
  if (!props.sessionKey || props.abortDisabled) return
  emit('abort-run')
}

function onSubmit(): void {
  const text = draft.value.trim()
  const hasAttachments = attachments.value.length > 0
  if ((!text && !hasAttachments) || props.disabled || !props.sessionKey) return
  emit('submit', {
    text,
    attachments: [...attachments.value],
  })
  draft.value = ''
  attachments.value = []
  isAttachmentMenuOpen.value = false
}

function onWindowPointerDown(event: PointerEvent): void {
  if (!isAttachmentMenuOpen.value) return
  const root = composerRootRef.value
  if (!root) return
  const target = event.target
  if (target instanceof Node && root.contains(target)) return
  closeAttachmentMenu()
}

watch(
  () => props.sessionKey,
  () => {
    draft.value = ''
    attachments.value = []
    closeAttachmentMenu()
    void nextTick(() => moveCursorToEnd())
  },
)

onMounted(() => {
  window.addEventListener('pointerdown', onWindowPointerDown)
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', onWindowPointerDown)
})
</script>

<style scoped>
@reference "tailwindcss";

.openclaw-composer {
  @apply w-full max-w-175 mx-auto px-6;
}

.openclaw-composer-shell {
  @apply rounded-2xl border border-zinc-300 bg-white p-3 shadow-sm flex flex-col gap-2;
}

.openclaw-composer-input {
  @apply w-full min-w-0 h-16 max-h-48 rounded-xl border-0 bg-transparent px-1 py-2 text-sm text-zinc-900 outline-none resize-y;
}

.openclaw-composer-input:disabled {
  @apply bg-zinc-100 text-zinc-500 cursor-not-allowed;
}

.openclaw-composer-submit {
  @apply shrink-0 inline-flex h-9 items-center justify-center rounded-full border-0 px-4 bg-zinc-900 text-white text-sm transition hover:bg-black disabled:cursor-not-allowed disabled:bg-zinc-200 disabled:text-zinc-500;
}

.openclaw-composer-actions {
  @apply w-full flex items-center gap-2 flex-wrap;
}

.openclaw-composer-action {
  @apply shrink-0 inline-flex h-9 items-center justify-center rounded-full border border-zinc-300 bg-white px-3 text-xs text-zinc-700 transition hover:bg-zinc-50 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-400;
}

.openclaw-composer-attach-wrap {
  @apply relative shrink-0;
}

.openclaw-composer-attach {
  @apply inline-flex h-9 items-center justify-center rounded-full border border-zinc-300 bg-white px-3 text-xs text-zinc-700 transition hover:bg-zinc-50 disabled:cursor-not-allowed disabled:bg-zinc-100 disabled:text-zinc-400;
}

.openclaw-composer-attach-menu {
  @apply absolute right-0 bottom-full mb-2 z-20 min-w-36 rounded-lg border border-zinc-200 bg-white p-1 shadow-md;
}

.openclaw-composer-attach-menu-item {
  @apply w-full rounded-md px-2.5 py-2 text-left text-xs text-zinc-700 transition hover:bg-zinc-100;
}

.openclaw-composer-attachments {
  @apply mt-2 rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 space-y-1;
}

.openclaw-composer-attachment-row {
  @apply flex items-center justify-between gap-2;
}

.openclaw-composer-attachment-name {
  @apply min-w-0 truncate text-xs text-zinc-700;
}

.openclaw-composer-attachment-type {
  @apply ml-2 rounded-full bg-zinc-200 px-2 py-0.5 text-[10px] text-zinc-700;
}

.openclaw-composer-attachment-remove {
  @apply shrink-0 rounded-md border border-zinc-300 bg-white px-2 py-1 text-[11px] text-zinc-700 transition hover:bg-zinc-100;
}

.openclaw-composer-hidden-input {
  @apply hidden;
}
</style>
