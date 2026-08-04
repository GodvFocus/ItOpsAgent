<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js'
import { ElMessage } from 'element-plus'
import {
  Tools,
  DocumentCopy,
  Check,
  ArrowDown,
  ArrowUp
} from '@element-plus/icons-vue'
import type { ChatMessage, SourceRef, StructuredAnswerItem, StructuredAnswerStep } from '@/types/chat'
import { formatRelativeTime } from '@/utils/time'

const props = defineProps<{
  msg: ChatMessage
  isLast?: boolean
  streaming?: boolean
}>()

marked.setOptions({
  breaks: true,
  gfm: true
})

const renderer = new marked.Renderer()
renderer.code = ({ text, lang }: { text: string; lang?: string }) => {
  const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
  const out = hljs.highlight(text, { language }).value
  const label = language !== 'plaintext' ? language : ''
  return `<div class="code-block"><div class="code-header"><span class="code-lang">${label}</span><button class="code-copy-btn" type="button">复制</button></div><pre><code class="hljs language-${language}">${out}</code></pre></div>`
}

const isUser = computed(() => props.msg.role === 'user')
const isTool = computed(() => props.msg.role === 'tool')
const isAssistant = computed(() => !isUser.value && !isTool.value)
const structuredAnswer = computed(() => props.msg.structuredAnswer ?? null)
const hasStructuredAnswer = computed(() => isAssistant.value && !!structuredAnswer.value)
const displaySources = computed(() => structuredAnswer.value?.evidences ?? props.msg.sources ?? [])

const html = computed(() => {
  if (!props.msg.content) return ''
  return marked.parse(props.msg.content, { renderer }) as string
})

const toolPayload = computed(() => {
  if (!isTool.value) return ''
  const raw = props.msg.content
  if (!raw) return ''
  try {
    const obj = JSON.parse(raw)
    return JSON.stringify(obj, null, 2)
  } catch {
    return raw
  }
})

const toolExpanded = ref(false)
const copied = ref(false)
const selectedEvidenceId = ref<string>('')

const friendlyToolName = computed(() => {
  const name = props.msg.toolName || 'unknown'
  const map: Record<string, string> = {
    knowledge_search: '知识库检索',
    web_search: '网页搜索',
    code_interpreter: '代码执行',
    memory_search: '记忆检索'
  }
  return map[name] || name
})

const showCursor = computed(
  () => isAssistant.value && props.isLast === true && props.streaming === true && !hasStructuredAnswer.value
)

const sourceMap = computed(() => {
  const map = new Map<string, SourceRef>()
  for (const source of displaySources.value) {
    if (source.evidenceId) {
      map.set(source.evidenceId, source)
    }
  }
  return map
})

const activeEvidence = computed(() => {
  if (!displaySources.value.length) return null
  if (selectedEvidenceId.value && sourceMap.value.has(selectedEvidenceId.value)) {
    return sourceMap.value.get(selectedEvidenceId.value) ?? null
  }
  return displaySources.value[0]
})

watch(
  displaySources,
  (sources) => {
    if (!sources.length) {
      selectedEvidenceId.value = ''
      return
    }
    if (!selectedEvidenceId.value || !sourceMap.value.has(selectedEvidenceId.value)) {
      selectedEvidenceId.value = sources[0].evidenceId ?? ''
    }
  },
  { immediate: true }
)

function evidenceKindLabel(kind?: SourceRef['kind']): string {
  const map: Record<string, string> = {
    DOC: '笔记',
    CARD: '卡片',
    PUBLIC: '公开',
    CONFLUENCE: 'Confluence',
    MEMORY: '记忆'
  }
  return map[kind ?? 'DOC'] || '证据'
}

function formatEvidenceRefs(ids: string[]): string[] {
  return ids?.filter(Boolean) ?? []
}

function selectEvidence(id?: string): void {
  if (!id) return
  selectedEvidenceId.value = id
}

async function copy() {
  const text = isTool.value
    ? toolPayload.value
    : (structuredAnswer.value?.renderedMarkdown || props.msg.content)
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    ElMessage.warning('复制失败：浏览器剪贴板不可用')
  }
}

function handleMarkdownClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (!target.classList.contains('code-copy-btn')) return
  const codeBlock = target.closest('.code-block')
  const code = codeBlock?.querySelector('code')
  if (!code) return
  navigator.clipboard.writeText(code.textContent || '').then(() => {
    target.textContent = '✓'
    setTimeout(() => {
      target.textContent = '复制'
    }, 1500)
  }).catch(() => {
    ElMessage.warning('复制失败')
  })
}

function itemText(item: StructuredAnswerItem | StructuredAnswerStep): string {
  if (item.title && item.detail) return `${item.title}：${item.detail}`
  return item.title || item.detail
}
</script>

<template>
  <div class="row" :class="{ user: isUser, tool: isTool }">
    <div class="bubble" :class="{ user: isUser, tool: isTool, assistant: isAssistant }">
      <template v-if="isTool">
        <div class="tool-card">
          <div class="tool-badge">
            <el-icon class="tool-icon"><Tools /></el-icon>
          </div>
          <div class="tool-body">
            <div class="tool-title" @click="toolExpanded = !toolExpanded">
              <span class="tool-name">{{ friendlyToolName }}</span>
              <el-icon class="caret">
                <ArrowDown v-if="toolExpanded" />
                <ArrowUp v-else />
              </el-icon>
            </div>
            <Transition name="collapse">
              <div v-if="toolExpanded && toolPayload" class="tool-payload">{{ toolPayload }}</div>
            </Transition>
          </div>
        </div>
      </template>

      <div v-else-if="isUser" class="plain">{{ msg.content }}</div>

      <template v-else>
        <div v-if="hasStructuredAnswer" class="structured-answer">
          <section class="answer-section">
            <div class="section-title">判断</div>
            <div class="section-body">{{ structuredAnswer?.judgement.summary }}</div>
            <div
              v-if="structuredAnswer?.judgement.evidenceIds?.length"
              class="evidence-links"
            >
              <button
                v-for="id in formatEvidenceRefs(structuredAnswer!.judgement.evidenceIds)"
                :key="id"
                class="evidence-ref"
                type="button"
                @click="selectEvidence(id)"
              >
                {{ id }}
              </button>
            </div>
          </section>

          <section v-if="structuredAnswer?.possibleCauses?.length" class="answer-section">
            <div class="section-title">可能原因</div>
            <ol class="answer-list">
              <li
                v-for="(item, index) in structuredAnswer!.possibleCauses"
                :key="`cause-${index}`"
              >
                <div class="section-body">{{ itemText(item) }}</div>
                <div v-if="item.evidenceIds.length" class="evidence-links">
                  <button
                    v-for="id in item.evidenceIds"
                    :key="id"
                    class="evidence-ref"
                    type="button"
                    @click="selectEvidence(id)"
                  >
                    {{ id }}
                  </button>
                </div>
              </li>
            </ol>
          </section>

          <section v-if="structuredAnswer?.steps?.length" class="answer-section">
            <div class="section-title">建议步骤</div>
            <ol class="answer-list">
              <li
                v-for="(step, index) in structuredAnswer!.steps"
                :key="`step-${index}`"
              >
                <div class="section-body">{{ itemText(step) }}</div>
                <div v-if="step.evidenceIds.length" class="evidence-links">
                  <button
                    v-for="id in step.evidenceIds"
                    :key="id"
                    class="evidence-ref"
                    type="button"
                    @click="selectEvidence(id)"
                  >
                    {{ id }}
                  </button>
                </div>
              </li>
            </ol>
          </section>

          <section v-if="structuredAnswer?.riskWarnings?.length" class="answer-section">
            <div class="section-title">风险提示</div>
            <ul class="answer-list bullet">
              <li
                v-for="(item, index) in structuredAnswer!.riskWarnings"
                :key="`risk-${index}`"
              >
                <div class="section-body">{{ itemText(item) }}</div>
                <div v-if="item.evidenceIds.length" class="evidence-links">
                  <button
                    v-for="id in item.evidenceIds"
                    :key="id"
                    class="evidence-ref"
                    type="button"
                    @click="selectEvidence(id)"
                  >
                    {{ id }}
                  </button>
                </div>
              </li>
            </ul>
          </section>

          <section v-if="structuredAnswer?.missingInformation?.length" class="answer-section">
            <div class="section-title">缺失信息</div>
            <ul class="answer-list bullet">
              <li
                v-for="(item, index) in structuredAnswer!.missingInformation"
                :key="`missing-${index}`"
              >
                <div class="section-body">{{ item }}</div>
              </li>
            </ul>
          </section>

          <section v-if="displaySources.length" class="answer-section evidence-section">
            <div class="section-title">证据</div>
            <div class="evidence-grid">
              <button
                v-for="source in displaySources"
                :key="source.evidenceId || source.label"
                class="source-chip"
                :class="{ active: (activeEvidence?.evidenceId || '') === (source.evidenceId || '') }"
                type="button"
                @click="selectEvidence(source.evidenceId)"
              >
                <span class="chip-id">{{ source.evidenceId || 'E?' }}</span>
                <span class="chip-label">{{ source.label }}</span>
              </button>
            </div>
            <div v-if="activeEvidence" class="evidence-card">
              <div class="evidence-head">
                <span class="chip-id">{{ activeEvidence.evidenceId || 'E?' }}</span>
                <span class="evidence-kind">{{ evidenceKindLabel(activeEvidence.kind) }}</span>
                <span v-if="activeEvidence.timeText" class="evidence-time">{{ activeEvidence.timeText }}</span>
              </div>
              <div class="evidence-label">{{ activeEvidence.label }}</div>
              <div class="evidence-snippet">{{ activeEvidence.snippet || '暂无片段预览' }}</div>
              <a
                v-if="activeEvidence.url"
                class="evidence-url"
                :href="activeEvidence.url"
                target="_blank"
                rel="noopener noreferrer"
              >
                打开来源页面
              </a>
            </div>
          </section>
        </div>

        <div
          v-else-if="msg.content"
          class="markdown-body"
          :class="{ streaming: showCursor }"
          v-html="html"
          @click="handleMarkdownClick"
        />
        <div v-else class="placeholder">
          <span class="dot" />
          <span class="dot" />
          <span class="dot" />
        </div>

        <div v-if="!hasStructuredAnswer && displaySources.length" class="sources">
          <button
            v-for="source in displaySources"
            :key="source.evidenceId || source.label"
            class="source-chip"
            type="button"
          >
            <span class="chip-id">{{ source.evidenceId || 'E?' }}</span>
            <span class="chip-label">{{ source.label }}</span>
          </button>
        </div>
      </template>

      <button
        v-if="!isUser && (msg.content || toolPayload)"
        class="copy-btn"
        :class="{ ok: copied }"
        @click="copy"
        :title="copied ? '已复制' : '复制'"
      >
        <el-icon>
          <Check v-if="copied" />
          <DocumentCopy v-else />
        </el-icon>
      </button>
      <time
        v-if="msg.createdAt"
        class="msg-time"
        :datetime="new Date(msg.createdAt).toISOString()"
      >
        {{ formatRelativeTime(msg.createdAt) }}
      </time>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  margin: 10px 0;
  animation: bubble-in 0.25s ease-out;
}

.row.user {
  justify-content: flex-end;
}

.bubble {
  position: relative;
  max-width: 75%;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  word-break: break-word;
}

.bubble.user {
  background: var(--bg-surface);
  color: var(--text-primary);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  border-bottom-right-radius: 4px;
}

.bubble.assistant {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--radius);
  border-bottom-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}

.bubble.tool {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  color: var(--text-primary);
  max-width: 85%;
  padding: 12px 14px;
  box-shadow: var(--shadow-sm);
}

.structured-answer {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.answer-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-title {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--text-secondary);
  text-transform: uppercase;
}

.section-body {
  white-space: pre-wrap;
  line-height: 1.7;
}

.answer-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.answer-list.bullet {
  padding-left: 18px;
}

.evidence-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.evidence-ref,
.source-chip {
  border: 1px solid var(--border);
  background: var(--bg-sunken);
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}

.evidence-ref:hover,
.source-chip:hover,
.source-chip.active {
  border-color: var(--border-bright);
  color: var(--text-primary);
  background: var(--bg-hover);
}

.evidence-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.source-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
}

.chip-id {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  opacity: 0.8;
}

.chip-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-card {
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-sunken);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.evidence-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 12px;
}

.evidence-kind,
.evidence-time {
  opacity: 0.8;
}

.evidence-label {
  font-weight: 600;
  color: var(--text-primary);
}

.evidence-snippet {
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
  white-space: pre-wrap;
}

.sources {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--border);
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

:deep(.code-block) {
  border-radius: var(--radius-md);
  overflow: hidden;
  margin: 8px 0;
  background: var(--bg-sunken);
}

:deep(.code-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 14px;
  background: rgba(255, 255, 255, 0.06);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

:deep(.code-lang) {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
  font-family: 'JetBrains Mono', Consolas, monospace;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

:deep(.code-copy-btn) {
  margin-left: auto;
  padding: 0 8px;
  height: 22px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-family: inherit;
  font-size: 11px;
  transition: color var(--transition-fast), border-color var(--transition-fast);
}

:deep(.code-copy-btn:hover) {
  color: var(--text-primary);
  border-color: var(--border-bright);
}

.tool-card {
  display: flex;
  gap: 10px;
  width: 100%;
}

.tool-badge {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  background: var(--bg-hover);
  display: flex;
  align-items: center;
  justify-content: center;
}

.tool-badge .tool-icon {
  font-size: 14px;
  color: var(--text-secondary);
}

.tool-body {
  flex: 1;
  min-width: 0;
}

.tool-title {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.tool-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-primary);
}

.tool-title .caret {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-secondary);
}

.tool-payload {
  margin-top: 8px;
  padding: 8px 10px;
  background: var(--bg-sunken);
  border-radius: 6px;
  border-left: 2px solid var(--border-bright);
  font-size: 12px;
  line-height: 1.55;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-secondary);
}

.plain {
  white-space: pre-wrap;
  line-height: 1.6;
}

.placeholder {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  height: 18px;
}

.placeholder .dot {
  width: 6px;
  height: 6px;
  background: var(--text-secondary);
  border-radius: 50%;
  opacity: 0.4;
  animation: blink 1.2s infinite;
}

.placeholder .dot:nth-child(2) {
  animation-delay: 0.2s;
}

.placeholder .dot:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.25;
    transform: scale(0.9);
  }
  40% {
    opacity: 1;
    transform: scale(1);
  }
}

.markdown-body.streaming::after {
  content: '';
  display: inline-block;
  width: 3px;
  height: 16px;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--text-primary);
  border-radius: 2px;
  animation: caret 1s steps(2, start) infinite;
  opacity: 0.8;
}

@keyframes caret {
  to {
    visibility: hidden;
  }
}

.copy-btn {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  border: 1px solid transparent;
  background: var(--copy-btn-bg);
  color: var(--text-secondary);
  cursor: pointer;
  opacity: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.12s ease, color 0.12s ease, background 0.12s ease;
  font-size: 14px;
}

.bubble:hover .copy-btn {
  opacity: 1;
}

.copy-btn:hover {
  color: var(--text-primary);
  background: var(--bg-surface);
  border-color: var(--border);
}

.copy-btn.ok {
  opacity: 1;
  color: var(--status-ok);
  background: rgba(107, 143, 113, 0.1);
  border-color: rgba(107, 143, 113, 0.25);
}

.msg-time {
  display: block;
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 11px;
  text-align: right;
  user-select: none;
}

.bubble.user .msg-time {
  text-align: left;
}

.collapse-enter-active,
.collapse-leave-active {
  transition: max-height 0.25s ease, opacity 0.25s ease, margin-top 0.25s ease;
  overflow: hidden;
}

.collapse-enter-from,
.collapse-leave-to {
  max-height: 0;
  opacity: 0;
  margin-top: 0;
}

.collapse-enter-to,
.collapse-leave-from {
  max-height: 260px;
  opacity: 1;
  margin-top: 8px;
}
</style>
