import { ref, onBeforeUnmount } from 'vue'

export type DictationState = 'idle' | 'recording' | 'transcribing'

export function useDictation(options: {
  onTranscript: (text: string) => void
  onError?: (error: unknown) => void
}) {
  const state = ref<DictationState>('idle')
  const isSupported = ref(typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia)

  let mediaRecorder: MediaRecorder | null = null
  let mediaStream: MediaStream | null = null
  let chunks: Blob[] = []

  async function startRecording() {
    if (state.value !== 'idle' || !isSupported.value) return

    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1 } })
      chunks = []
      mediaRecorder = new MediaRecorder(mediaStream)
      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunks.push(e.data)
      }
      mediaRecorder.onstop = () => transcribe()
      mediaRecorder.start()
      state.value = 'recording'
    } catch (error) {
      cleanup()
      options.onError?.(error)
    }
  }

  function stopRecording() {
    if (state.value !== 'recording' || !mediaRecorder) return
    if (mediaRecorder.state !== 'inactive') mediaRecorder.stop()
  }

  async function transcribe() {
    if (chunks.length === 0) { cleanup(); return }

    state.value = 'transcribing'
    const mimeType = mediaRecorder?.mimeType || chunks[0]?.type || 'audio/webm'
    const blob = new Blob(chunks, { type: mimeType })
    cleanup()

    try {
      const ext = mimeType.split(/[/;]/)[1] ?? 'webm'
      const boundary = `----codex-transcribe-${crypto.randomUUID()}`
      const fileBytes = new Uint8Array(await blob.arrayBuffer())
      const encoder = new TextEncoder()

      const parts: Uint8Array[] = []
      parts.push(encoder.encode(`--${boundary}\r\n`))
      parts.push(encoder.encode(`Content-Disposition: form-data; name="file"; filename="codex.${ext}"\r\n`))
      parts.push(encoder.encode(`Content-Type: ${mimeType}\r\n\r\n`))
      parts.push(fileBytes)
      parts.push(encoder.encode(`\r\n--${boundary}--\r\n`))

      let totalLen = 0
      for (const p of parts) totalLen += p.byteLength
      const body = new Uint8Array(totalLen)
      let offset = 0
      for (const p of parts) { body.set(p, offset); offset += p.byteLength }

      const response = await fetch('/codex-api/transcribe', {
        method: 'POST',
        headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
        body,
      })

      if (!response.ok) throw new Error(`Transcription failed: ${response.status}`)
      const data = (await response.json()) as { text?: string }
      const text = (data.text ?? '').trim()
      if (text.length > 0) options.onTranscript(text)
    } catch (error) {
      options.onError?.(error)
    } finally {
      state.value = 'idle'
    }
  }

  function cleanup() {
    if (mediaRecorder) {
      mediaRecorder.ondataavailable = null
      mediaRecorder.onstop = null
      mediaRecorder = null
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach((t) => t.stop())
      mediaStream = null
    }
    chunks = []
  }

  onBeforeUnmount(cleanup)

  return { state, isSupported, startRecording, stopRecording }
}
