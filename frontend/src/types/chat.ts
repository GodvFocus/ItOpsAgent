/**
 * 与后端 DTO 对齐的前端类型定义。
 */
export interface ChatMessage {
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  createdAt?: number
  /**
   * 工具气泡专用，后端持久化结构里没有此字段；
   * 前端在 SSE `event: tool` 收到时本地构造，仅用于展示。
   */
  toolName?: string
  /**
   * 兼容旧版本来源展示。
   * 新版本优先使用 `structuredAnswer.evidences`。
   */
  sources?: SourceRef[]
  /**
   * 结构化回答主对象。
   * 历史回放与实时 SSE 都走同一展示模型。
   */
  structuredAnswer?: StructuredAnswer | null
}

/**
 * 统一证据模型，与后端 `SourceRef` 对齐。
 */
export interface SourceRef {
  label: string
  kind?: 'MEMORY' | 'DOC' | 'CARD' | 'PUBLIC'
  docId?: string | null
  chunkIndex?: number | null
  snippet: string
  memory: boolean
  timeText: string
  evidenceId?: string
}

export interface StructuredAnswerJudgement {
  summary: string
  evidenceIds: string[]
}

export interface StructuredAnswerItem {
  title: string
  detail: string
  evidenceIds: string[]
}

export interface StructuredAnswerStep {
  title: string
  detail: string
  evidenceIds: string[]
}

export interface StructuredAnswer {
  judgement: StructuredAnswerJudgement
  possibleCauses: StructuredAnswerItem[]
  steps: StructuredAnswerStep[]
  riskWarnings: StructuredAnswerItem[]
  missingInformation: string[]
  evidences: SourceRef[]
  renderedMarkdown: string
}

/**
 * 与后端 `SessionInfo` 对应。
 */
export interface SessionInfo {
  sessionId: string
  title: string
  messageCount: number
  updatedAt: number
}
