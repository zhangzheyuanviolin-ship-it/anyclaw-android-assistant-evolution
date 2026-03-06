<template>
  <form class="openclaw-composer" @submit.prevent="onSubmit">
    <div class="openclaw-composer-shell">
      <input
        v-model="draft"
        class="openclaw-composer-input"
        type="text"
        :placeholder="placeholder"
        :disabled="disabled || !sessionKey"
        :aria-label="placeholder"
        @keydown.enter.exact.prevent="onSubmit"
      />
      <button
        class="openclaw-composer-submit"
        type="submit"
        :aria-label="sendLabel"
        :disabled="disabled || !sessionKey || draft.trim().length === 0"
      >
        {{ sendLabel }}
      </button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{
  sessionKey: string
  disabled?: boolean
  placeholder: string
  sendLabel: string
}>()

const emit = defineEmits<{
  submit: [text: string]
}>()

const draft = ref('')

function onSubmit(): void {
  const text = draft.value.trim()
  if (!text || props.disabled || !props.sessionKey) return
  emit('submit', text)
  draft.value = ''
}

watch(
  () => props.sessionKey,
  () => {
    draft.value = ''
  },
)
</script>

<style scoped>
@reference "tailwindcss";

.openclaw-composer {
  @apply w-full max-w-175 mx-auto px-6;
}

.openclaw-composer-shell {
  @apply rounded-2xl border border-zinc-300 bg-white p-3 shadow-sm flex items-center gap-3;
}

.openclaw-composer-input {
  @apply flex-1 min-w-0 h-11 rounded-xl border-0 bg-transparent px-1 text-sm text-zinc-900 outline-none;
}

.openclaw-composer-input:disabled {
  @apply bg-zinc-100 text-zinc-500 cursor-not-allowed;
}

.openclaw-composer-submit {
  @apply shrink-0 inline-flex h-9 items-center justify-center rounded-full border-0 px-4 bg-zinc-900 text-white text-sm transition hover:bg-black disabled:cursor-not-allowed disabled:bg-zinc-200 disabled:text-zinc-500;
}
</style>
