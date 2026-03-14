export type OpenClawSessionSummary = {
  key: string
  title: string
  updatedAtMs: number
  preview: string
  modelProvider: string
  model: string
}

export type OpenClawHistoryRequest = {
  sessionKey: string
  limit?: number
}

export type OpenClawContentItem = {
  type: string
  text?: string
  thinking?: string
  name?: string
  arguments?: unknown
  source?: {
    type?: string
    media_type?: string
    data?: string
  }
  image_url?: {
    url?: string
  }
  url?: string
}

export type OpenClawHistoryMessage = {
  role: string
  timestamp?: number
  content?: OpenClawContentItem[]
  toolName?: string
  isError?: boolean
}

export type OpenClawHistoryResponse = {
  sessionKey: string
  messages: OpenClawHistoryMessage[]
  thinkingLevel: string
}

export type OpenClawSendRequest = {
  sessionKey: string
  message?: string
  deliver?: boolean
  attachments?: OpenClawImageAttachment[]
}

export type OpenClawSendResponse = {
  ok: boolean
  runId: string
  sessionKey?: string
}

export type OpenClawImageAttachment = {
  type: 'image'
  mimeType: string
  content: string
  fileName?: string
}

export type OpenClawLocalFileAttachment = {
  id: string
  type: 'file'
  name: string
  mimeType: string
  sizeBytes: number
  file: File
}

export type OpenClawComposerImageAttachment = {
  id: string
  type: 'image'
  name: string
  mimeType: string
  sizeBytes: number
  dataUrl: string
}

export type OpenClawComposerAttachment =
  | OpenClawComposerImageAttachment
  | OpenClawLocalFileAttachment

export type OpenClawComposerSubmitPayload = {
  text: string
  attachments: OpenClawComposerAttachment[]
}
