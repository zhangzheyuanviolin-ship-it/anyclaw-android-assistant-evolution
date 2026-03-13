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
  message: string
  deliver?: boolean
}

export type OpenClawSendResponse = {
  ok: boolean
  runId: string
}
