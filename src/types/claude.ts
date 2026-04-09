export type ClaudeSessionSummary = {
  key: string
  title: string
  updatedAtMs: number
  preview: string
  modelProvider: string
  model: string
}

export type ClaudeHistoryRequest = {
  sessionKey: string
  limit?: number
}

export type ClaudeContentItem = {
  type: string
  text?: string
}

export type ClaudeHistoryMessage = {
  role: string
  timestamp?: number
  content?: ClaudeContentItem[]
  toolName?: string
  isError?: boolean
}

export type ClaudeHistoryResponse = {
  sessionKey: string
  messages: ClaudeHistoryMessage[]
}

export type ClaudeSendRequest = {
  sessionKey: string
  message?: string
  attachmentPaths?: string[]
  allowSharedStorage?: boolean
  dangerousMode?: boolean
}

export type ClaudeSendResponse = {
  ok: boolean
  runId: string
}

export type ClaudeRunWaitRequest = {
  runId: string
  timeoutMs?: number
}

export type ClaudeRunWaitResponse = {
  ok: boolean
  runId: string
  status: string
  completed: boolean
  result?: unknown
  error?: unknown
}

export type ClaudeLocalFileAttachment = {
  id: string
  type: 'file'
  name: string
  mimeType: string
  sizeBytes: number
  file: File
}

export type ClaudeComposerImageAttachment = {
  id: string
  type: 'image'
  name: string
  mimeType: string
  sizeBytes: number
  dataUrl: string
  file: File
}

export type ClaudeComposerAttachment =
  | ClaudeComposerImageAttachment
  | ClaudeLocalFileAttachment

export type ClaudeComposerSubmitPayload = {
  text: string
  attachments: ClaudeComposerAttachment[]
}
