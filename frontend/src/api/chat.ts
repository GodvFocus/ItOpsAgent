/**
 * 与后端 ChatController 的 HTTP / SSE 通信封装。
 *
 * 设计取舍：
 * - 使用原生 fetch + ReadableStream，而不是 EventSource。
 * - `answer` 事件承载结构化回答，`chunk` 仍用于流式即时反馈。
 */

import type {
  ChatMessage,
  SessionInfo,
  SourceRef,
  StructuredAnswer
} from '@/types/chat'
import { authFetch } from './http'

export interface SseHandlers {
  onChunk?: (delta: string) => void
  onTool?: (toolName: string, payload?: string) => void
  onSession?: (sessionId: string) => void
  onSources?: (sources: SourceRef[]) => void
  onAnswer?: (answer: StructuredAnswer) => void
  onDone?: () => void
  onError?: (msg: string) => void
}

export interface StreamOptions {
  sessionId?: string
  message: string
  signal?: AbortSignal
}

interface SseEvent {
  event: string
  data: string
}

async function* parseSse(reader: ReadableStreamDefaultReader<Uint8Array>): AsyncGenerator<SseEvent> {
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let sepIndex: number
    while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
      const raw = buffer.slice(0, sepIndex)
      buffer = buffer.slice(sepIndex + 2)
      const ev = parseFrame(raw)
      if (ev) yield ev
    }
  }
  if (buffer.trim().length > 0) {
    const ev = parseFrame(buffer)
    if (ev) yield ev
  }
}

function parseFrame(raw: string): SseEvent | null {
  const lines = raw.split(/\r?\n/)
  let event = 'message'
  const dataLines: string[] = []
  for (const line of lines) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (dataLines.length === 0 && event === 'message') return null
  return { event, data: dataLines.join('\n') }
}

export async function streamChat(opts: StreamOptions, handlers: SseHandlers): Promise<void> {
  let resp: Response
  try {
    resp = await authFetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      },
      body: JSON.stringify({
        sessionId: opts.sessionId ?? '',
        message: opts.message
      }),
      signal: opts.signal
    })
  } catch (e: any) {
    if (e?.name === 'AbortError') return
    handlers.onError?.(`网络错误：${e?.message ?? e}`)
    return
  }

  if (!resp.ok || !resp.body) {
    const text = await safeText(resp)
    if (resp.status === 409) {
      try {
        const body = JSON.parse(text) as { code?: string; message?: string }
        if (body.code === 'SESSION_LOCKED') {
          handlers.onError?.(body.message || '该会话正在生成中，请稍后再试')
          return
        }
      } catch {
        /* fallback */
      }
    }
    handlers.onError?.(`请求失败 (${resp.status})`)
    return
  }

  const reader = resp.body.getReader()
  try {
    for await (const ev of parseSse(reader)) {
      switch (ev.event) {
        case 'chunk':
          handlers.onChunk?.(ev.data)
          break
        case 'tool': {
          let toolName = ev.data
          let payload: string | undefined
          try {
            const parsed = JSON.parse(ev.data)
            toolName = parsed?.name ?? ev.data
            payload = parsed?.payload
          } catch {
            /* ignore */
          }
          handlers.onTool?.(toolName, payload)
          break
        }
        case 'session':
          handlers.onSession?.(ev.data)
          break
        case 'sources':
          try {
            handlers.onSources?.(JSON.parse(ev.data) as SourceRef[])
          } catch {
            /* ignore */
          }
          break
        case 'answer':
          try {
            handlers.onAnswer?.(JSON.parse(ev.data) as StructuredAnswer)
          } catch {
            /* ignore */
          }
          break
        case 'done':
          handlers.onDone?.()
          return
        case 'error':
          handlers.onError?.(ev.data)
          return
        default:
          break
      }
    }
    handlers.onDone?.()
  } catch (e: any) {
    if (e?.name === 'AbortError') return
    handlers.onError?.(`流读取失败：${e?.message ?? e}`)
  } finally {
    try {
      reader.releaseLock()
    } catch {
      /* ignore */
    }
  }
}

async function safeText(resp: Response): Promise<string> {
  try {
    return await resp.text()
  } catch {
    return ''
  }
}

export async function listSessions(): Promise<SessionInfo[]> {
  const r = await authFetch('/api/chat/sessions')
  if (!r.ok) throw new Error(`列出会话失败 HTTP ${r.status}`)
  return r.json()
}

export async function getHistory(sessionId: string): Promise<ChatMessage[]> {
  const r = await authFetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}`)
  if (!r.ok) throw new Error(`加载历史失败 HTTP ${r.status}`)
  return r.json()
}

export async function deleteSession(sessionId: string): Promise<void> {
  const r = await authFetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE'
  })
  if (!r.ok) throw new Error(`删除会话失败 HTTP ${r.status}`)
}

export async function renameSession(sessionId: string, title: string): Promise<void> {
  const r = await authFetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title })
  })
  if (!r.ok) throw new Error(`重命名失败 HTTP ${r.status}`)
}
