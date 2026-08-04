import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  deleteSession as apiDeleteSession,
  getHistory,
  listSessions,
  renameSession,
  streamChat
} from '@/api/chat'
import type { ChatMessage, SessionInfo, StructuredAnswer } from '@/types/chat'

/**
 * 会话与消息状态中心。
 */
export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionInfo[]>([])
  const activeSid = ref<string>('')
  const messagesBySid = ref<Record<string, ChatMessage[]>>({})
  const streaming = ref(false)
  const errorMsg = ref<string>('')

  let abortController: AbortController | null = null

  const messages = computed<ChatMessage[]>(() => messagesBySid.value[activeSid.value] ?? [])

  function normalizeMessage(msg: ChatMessage): ChatMessage {
    if (!msg.structuredAnswer) {
      return msg
    }
    return {
      ...msg,
      content: msg.structuredAnswer.renderedMarkdown || msg.content,
      sources: msg.sources ?? msg.structuredAnswer.evidences
    }
  }

  async function refreshSessions(): Promise<void> {
    try {
      sessions.value = await listSessions()
    } catch (e: any) {
      errorMsg.value = e?.message ?? '加载会话列表失败'
    }
  }

  async function selectSession(sid: string): Promise<void> {
    if (streaming.value) return
    activeSid.value = sid
    if (!sid) return
    if (messagesBySid.value[sid]) return
    try {
      const history = await getHistory(sid)
      messagesBySid.value = { ...messagesBySid.value, [sid]: history.map(normalizeMessage) }
    } catch (e: any) {
      errorMsg.value = e?.message ?? '加载历史失败'
    }
  }

  function newSession(): void {
    if (streaming.value) return
    activeSid.value = ''
    errorMsg.value = ''
  }

  async function deleteSession(sid: string): Promise<void> {
    if (streaming.value) return
    try {
      await apiDeleteSession(sid)
      delete messagesBySid.value[sid]
      if (activeSid.value === sid) activeSid.value = ''
      await refreshSessions()
    } catch (e: any) {
      errorMsg.value = e?.message ?? '删除失败'
    }
  }

  async function rename(sid: string, title: string): Promise<void> {
    try {
      await renameSession(sid, title)
      await refreshSessions()
    } catch (e: any) {
      errorMsg.value = e?.message ?? '重命名失败'
    }
  }

  function appendMessage(sid: string, msg: ChatMessage): void {
    const list = messagesBySid.value[sid] ? [...messagesBySid.value[sid]] : []
    list.push(normalizeMessage(msg))
    messagesBySid.value = { ...messagesBySid.value, [sid]: list }
  }

  function ensureAssistantTail(sid: string): void {
    const list = messagesBySid.value[sid]
    if (!list || list.length === 0 || list[list.length - 1].role !== 'assistant') {
      appendMessage(sid, { role: 'assistant', content: '', createdAt: Date.now() })
    }
  }

  function updateLastAssistant(sid: string, mutate: (msg: ChatMessage) => void): void {
    const list = messagesBySid.value[sid]
    if (!list || list.length === 0) return
    const last = list[list.length - 1]
    if (last.role !== 'assistant') return
    mutate(last)
    messagesBySid.value = { ...messagesBySid.value, [sid]: [...list] }
  }

  function trimEmptyAssistantTail(sid: string): void {
    const list = messagesBySid.value[sid]
    if (!list || list.length === 0) return
    const last = list[list.length - 1]
    if (last.role === 'assistant' && (!last.content || !last.content.trim())) {
      messagesBySid.value = { ...messagesBySid.value, [sid]: list.slice(0, -1) }
    }
  }

  function applyStructuredAnswer(sid: string, answer: StructuredAnswer): void {
    ensureAssistantTail(sid)
    updateLastAssistant(sid, (msg) => {
      msg.structuredAnswer = answer
      msg.sources = answer.evidences
      msg.content = answer.renderedMarkdown || msg.content
    })
  }

  async function send(text: string): Promise<void> {
    const content = text.trim()
    if (!content || streaming.value) return
    errorMsg.value = ''

    const apiSid = activeSid.value
    const pendingSid = apiSid || '__pending__'
    if (!apiSid) {
      activeSid.value = '__pending__'
    }
    if (!messagesBySid.value[pendingSid]) {
      messagesBySid.value[pendingSid] = []
    }
    appendMessage(pendingSid, { role: 'user', content, createdAt: Date.now() })
    appendMessage(pendingSid, { role: 'assistant', content: '', createdAt: Date.now() })

    streaming.value = true
    abortController = new AbortController()

    let assignedSid = apiSid

    try {
      await streamChat(
        { sessionId: apiSid, message: content, signal: abortController.signal },
        {
          onSession: (sid) => {
            if (!assignedSid) {
              assignedSid = sid
              if (pendingSid !== sid && messagesBySid.value[pendingSid]) {
                messagesBySid.value[sid] = messagesBySid.value[pendingSid]
                delete messagesBySid.value[pendingSid]
              }
              activeSid.value = sid
            }
          },
          onChunk: (delta) => {
            const sid = activeSid.value
            ensureAssistantTail(sid)
            updateLastAssistant(sid, (msg) => {
              msg.content += delta
            })
          },
          onTool: (name, payload) => {
            const sid = activeSid.value
            trimEmptyAssistantTail(sid)
            appendMessage(sid, {
              role: 'tool',
              toolName: name,
              content: payload ?? '',
              createdAt: Date.now()
            })
          },
          onSources: (sources) => {
            const sid = activeSid.value
            ensureAssistantTail(sid)
            updateLastAssistant(sid, (msg) => {
              msg.sources = sources
            })
          },
          onAnswer: (answer) => {
            applyStructuredAnswer(activeSid.value, answer)
          },
          onError: (msg) => {
            errorMsg.value = msg
          }
        }
      )
    } finally {
      const sid = assignedSid || pendingSid
      trimEmptyAssistantTail(sid)
      if (!assignedSid) {
        delete messagesBySid.value.__pending__
        activeSid.value = ''
      }
      streaming.value = false
      abortController = null
      await refreshSessions()
    }
  }

  function cancel(): void {
    abortController?.abort()
    abortController = null
    streaming.value = false
  }

  function cleanup(): void {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    streaming.value = false
  }

  /** Workspace 切换后清除旧会话和检索上下文，避免跨 Workspace 残留展示。 */
  function resetWorkspaceScope(): void {
    cleanup()
    sessions.value = []
    activeSid.value = ''
    messagesBySid.value = {}
    errorMsg.value = ''
  }

  return {
    sessions,
    activeSid,
    messages,
    messagesBySid,
    streaming,
    errorMsg,
    refreshSessions,
    selectSession,
    newSession,
    deleteSession,
    rename,
    send,
    cancel,
    cleanup,
    resetWorkspaceScope
  }
})
