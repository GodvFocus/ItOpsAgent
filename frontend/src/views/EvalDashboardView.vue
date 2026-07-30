<script setup lang="ts">
/**
 * 评测看板页：集中展示真实工程指标，避免只能靠 curl 或浏览器开发者工具查看后端报表。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataAnalysis, RefreshRight, Warning, TrendCharts } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import AppHeader from '@/components/AppHeader.vue'
import DrawerSidebar from '@/components/DrawerSidebar.vue'
import { fetchEngineeringMetrics, type EngineeringMetricsReport, type RetrievalVariant } from '@/api/eval'

defineOptions({ name: 'EvalDashboardView' })

type MetricTone = 'default' | 'ok' | 'warn'

const router = useRouter()
const route = useRoute()

const loading = ref(false)
const errorText = ref('')
const report = ref<EngineeringMetricsReport | null>(null)
const k = ref(readPositiveInt(route.query.k, 5))
const perLegK = ref(readPositiveInt(route.query.perLegK, 10))

const variantLabels: Record<RetrievalVariant, string> = {
  DENSE_ONLY: 'Dense Only',
  LEXICAL_ONLY: 'Lexical Only',
  HYBRID: 'Hybrid',
  HYBRID_RERANK: 'Hybrid + Rerank'
}

const variantRows = computed(() => {
  const variants = report.value?.retrieval.variants ?? {}
  return (Object.entries(variants) as Array<[RetrievalVariant, EngineeringMetricsReport['retrieval']['primary']]>)
    .map(([variant, metrics]) => ({
      variant,
      label: variantLabels[variant] ?? variant,
      metrics,
      isPrimary: variant === report.value?.retrieval.primaryVariant
    }))
    .sort((left, right) => Number(right.isPrimary) - Number(left.isPrimary))
})

const generatedAtText = computed(() => {
  const value = report.value?.generatedAt
  if (!value) {
    return '尚未执行'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(value)
})

const coverageStats = computed(() => {
  if (!report.value) {
    return []
  }
  return [
    metricCard('Router Accuracy', ratio(report.value.routing.accuracy), '排障路由命中率', toneByRatio(report.value.routing.accuracy)),
    metricCard('Router F1', ratio(report.value.routing.f1), 'TROUBLESHOOTING_AGENT 二分类 F1', toneByRatio(report.value.routing.f1)),
    metricCard('工具选择准确率', ratio(report.value.tools.selectionAccuracy), 'expectedTools 与实际工具集一致', toneByRatio(report.value.tools.selectionAccuracy)),
    metricCard('工具参数准确率', ratio(report.value.tools.parameterAccuracy), '按 golden case 校验实际调用工具', toneByRatio(report.value.tools.parameterAccuracy)),
    metricCard('Citation Accuracy', ratio(report.value.citations.accuracy), '引用结果中命中的相关证据占比', toneByRatio(report.value.citations.accuracy)),
    metricCard('Citation Coverage', ratio(report.value.citations.coverage), '相关证据被引用覆盖的比例', toneByRatio(report.value.citations.coverage))
  ]
})

const latencyStats = computed(() => {
  if (!report.value) {
    return []
  }
  return [
    metricCard('P95 TTFT', millis(report.value.latency.ttftP95Ms), '首个有效输出节点延迟', toneByLatency(report.value.latency.ttftP95Ms, 1500)),
    metricCard('完整响应 P95', millis(report.value.latency.fullResponseP95Ms), '单轮 turn 总耗时', toneByLatency(report.value.latency.fullResponseP95Ms, 8000)),
    metricCard('失败恢复 P95', millis(report.value.latency.failureRecoveryP95Ms), '同 session 内从失败到下一次成功的恢复时长', toneByLatency(report.value.latency.failureRecoveryP95Ms, 15000)),
    metricCard('平均模型调用', fixed(report.value.efficiency.averageModelCallsPerTurn, 2), '每个 turn 的 Prompt 次数', 'default'),
    metricCard('平均 Prompt Tokens', fixed(report.value.efficiency.averagePromptTokensPerTurn, 0), '仅统计 Prompt 侧估算 token', 'default'),
    metricCard('平均 Token 成本', usd(report.value.efficiency.averageEstimatedPromptCostUsd), '当前只按 Prompt 成本估算', 'default')
  ]
})

const sampleStats = computed(() => {
  if (!report.value) {
    return []
  }
  return [
    sampleCard('排障用例', report.value.samples.troubleshootingCaseCount, '用于路由 / 工具 / 引用评测'),
    sampleCard('RAG 用例', report.value.samples.ragCaseCount, '用于 Recall / MRR / nDCG 评测'),
    sampleCard('Turn Trace', report.value.samples.turnTraceCount, '用于真实时延与模型调用成本'),
    sampleCard('RAG Trace', report.value.samples.ragTraceCount, '用于实际检索链路样本补充')
  ]
})

onMounted(() => {
  void runEvaluation(false)
})

async function runEvaluation(showToast = true) {
  loading.value = true
  errorText.value = ''
  syncQuery()
  try {
    report.value = await fetchEngineeringMetrics({
      k: k.value,
      perLegK: perLegK.value
    })
    if (showToast) {
      ElMessage.success('评测已完成')
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测执行失败'
    errorText.value = message
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}

function syncQuery() {
  void router.replace({
    path: '/eval',
    query: {
      k: String(k.value),
      perLegK: String(perLegK.value)
    }
  })
}

function goChat() {
  void router.push('/chat')
}

function percentBarWidth(value: number): string {
  const safeValue = Math.max(0, Math.min(1, Number.isFinite(value) ? value : 0))
  return `${safeValue * 100}%`
}

function metricCard(title: string, value: string, caption: string, tone: MetricTone) {
  return { title, value, caption, tone }
}

function sampleCard(title: string, value: number, caption: string) {
  return { title, value: String(value), caption }
}

function ratio(value: number): string {
  return `${(clamp01(value) * 100).toFixed(1)}%`
}

function millis(value: number): string {
  return `${Math.round(Math.max(0, value))} ms`
}

function usd(value: number): string {
  return `$${Math.max(0, value).toFixed(4)}`
}

function fixed(value: number, digits: number): string {
  return Number.isFinite(value) ? value.toFixed(digits) : '0'
}

function toneByRatio(value: number): MetricTone {
  if (value >= 0.9) {
    return 'ok'
  }
  if (value >= 0.75) {
    return 'default'
  }
  return 'warn'
}

function toneByLatency(value: number, warnThreshold: number): MetricTone {
  if (value <= 0) {
    return 'warn'
  }
  if (value <= warnThreshold) {
    return 'ok'
  }
  return 'warn'
}

function clamp01(value: number): number {
  if (!Number.isFinite(value)) {
    return 0
  }
  return Math.max(0, Math.min(1, value))
}

function readPositiveInt(rawValue: unknown, fallback: number): number {
  const text = typeof rawValue === 'string' ? rawValue : Array.isArray(rawValue) ? rawValue[0] : ''
  const parsed = Number.parseInt(text ?? '', 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}
</script>

<template>
  <div class="eval-view">
    <AppHeader :show-back="true" @back="goChat" />
    <DrawerSidebar />

    <main class="eval-page" v-loading="loading">
      <section class="hero-panel">
        <div class="hero-copy">
          <div class="hero-tag">
            <el-icon><DataAnalysis /></el-icon>
            实时评测看板
          </div>
          <h1>建立真实工程指标</h1>
          <p>
            这里直接消费 `/api/eval/engineering`，把离线 golden set 与线上 trace 样本并排展示。
            目标不是“看起来有指标”，而是让路由、工具、召回、时延和成本能一起判断。
          </p>
        </div>

        <div class="hero-controls">
          <div class="control-grid">
            <label class="control-field">
              <span>K</span>
              <el-input-number v-model="k" :min="1" :max="20" :step="1" controls-position="right" />
            </label>
            <label class="control-field">
              <span>Per Leg K</span>
              <el-input-number v-model="perLegK" :min="1" :max="50" :step="1" controls-position="right" />
            </label>
          </div>

          <div class="control-actions">
            <button class="run-btn primary" type="button" :disabled="loading" @click="runEvaluation(true)">
              <el-icon><TrendCharts /></el-icon>
              {{ loading ? '评测执行中…' : '重新评测' }}
            </button>
            <button class="run-btn" type="button" :disabled="loading" @click="runEvaluation(false)">
              <el-icon><RefreshRight /></el-icon>
              静默刷新
            </button>
          </div>

          <div class="hero-meta">
            <span>最近生成：{{ generatedAtText }}</span>
            <span>路由：{{ route.path }}</span>
          </div>
        </div>
      </section>

      <section v-if="errorText" class="error-banner">
        <el-icon><Warning /></el-icon>
        <span>{{ errorText }}</span>
      </section>

      <section class="board-section">
        <div class="section-head">
          <h2>样本基线</h2>
          <span>决定这些数字是否可信的不是样式，而是样本量。</span>
        </div>
        <div class="stats-grid samples">
          <article v-for="item in sampleStats" :key="item.title" class="metric-card sample-card">
            <span class="metric-title">{{ item.title }}</span>
            <strong class="metric-value">{{ item.value }}</strong>
            <p class="metric-caption">{{ item.caption }}</p>
          </article>
        </div>
      </section>

      <section class="board-section">
        <div class="section-head">
          <h2>正确性与引用</h2>
          <span>这组指标回答“是否答对、是否用对工具、是否引对证据”。</span>
        </div>
        <div class="stats-grid">
          <article v-for="item in coverageStats" :key="item.title" class="metric-card" :data-tone="item.tone">
            <span class="metric-title">{{ item.title }}</span>
            <strong class="metric-value">{{ item.value }}</strong>
            <p class="metric-caption">{{ item.caption }}</p>
          </article>
        </div>
      </section>

      <section class="board-section">
        <div class="section-head">
          <h2>时延与成本</h2>
          <span>这组指标回答“链路多快、失败多久恢复、模型调用有多贵”。</span>
        </div>
        <div class="stats-grid">
          <article v-for="item in latencyStats" :key="item.title" class="metric-card" :data-tone="item.tone">
            <span class="metric-title">{{ item.title }}</span>
            <strong class="metric-value">{{ item.value }}</strong>
            <p class="metric-caption">{{ item.caption }}</p>
          </article>
        </div>
      </section>

      <section class="board-section">
        <div class="section-head">
          <h2>召回变体对比</h2>
          <span>同一份 golden set 下，对比 Dense / Lexical / Hybrid / Hybrid+Rerank 的收益和代价。</span>
        </div>

        <div class="variant-list">
          <article v-for="row in variantRows" :key="row.variant" class="variant-card" :class="{ primary: row.isPrimary }">
            <div class="variant-top">
              <div>
                <div class="variant-label">{{ row.label }}</div>
                <div class="variant-subtitle">
                  <span v-if="row.isPrimary" class="pill">主展示版本</span>
                  <span>Latency {{ millis(row.metrics.averageLatencyMs) }}</span>
                </div>
              </div>
              <div class="variant-cost">{{ usd(row.metrics.averageEstimatedCostUsd) }}</div>
            </div>

            <div class="variant-bars">
              <div class="variant-bar">
                <span>Recall@K</span>
                <div class="bar-track">
                  <div class="bar-fill" :style="{ width: percentBarWidth(row.metrics.recallAtK) }" />
                </div>
                <strong>{{ ratio(row.metrics.recallAtK) }}</strong>
              </div>
              <div class="variant-bar">
                <span>MRR</span>
                <div class="bar-track">
                  <div class="bar-fill alt" :style="{ width: percentBarWidth(row.metrics.mrr) }" />
                </div>
                <strong>{{ ratio(row.metrics.mrr) }}</strong>
              </div>
              <div class="variant-bar">
                <span>nDCG@K</span>
                <div class="bar-track">
                  <div class="bar-fill warn" :style="{ width: percentBarWidth(row.metrics.ndcgAtK) }" />
                </div>
                <strong>{{ ratio(row.metrics.ndcgAtK) }}</strong>
              </div>
            </div>

            <div class="variant-metrics">
              <span>Precision@K {{ ratio(row.metrics.precisionAtK) }}</span>
              <span>Recall@K {{ ratio(row.metrics.recallAtK) }}</span>
              <span>MRR {{ fixed(row.metrics.mrr, 3) }}</span>
              <span>nDCG@K {{ fixed(row.metrics.ndcgAtK, 3) }}</span>
            </div>
          </article>
        </div>
      </section>

      <section class="board-section board-section--notes">
        <div class="section-head">
          <h2>执行说明</h2>
          <span>看板会明确告诉你哪些数字是估算、哪些数字当前没有样本支撑。</span>
        </div>

        <div class="notes-wrap">
          <ul v-if="report?.notes?.length" class="notes-list">
            <li v-for="note in report.notes" :key="note">{{ note }}</li>
          </ul>
          <div v-else class="empty-note">当前没有额外说明。</div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.eval-view {
  min-height: 100vh;
}

.eval-page {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  overflow-y: auto;
  padding: 72px 24px 40px;
  background:
    radial-gradient(circle at top right, rgba(255, 255, 255, 0.06), transparent 28%),
    linear-gradient(180deg, var(--bg-base) 0%, var(--bg-sunken) 100%);
}

.hero-panel,
.board-section {
  width: min(1180px, 100%);
  margin: 0 auto 20px;
  border: 1px solid var(--border);
  border-radius: 24px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.035), rgba(255, 255, 255, 0.015));
  box-shadow: var(--shadow-md);
}

.hero-panel {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(320px, 0.9fr);
  gap: 20px;
  padding: 28px;
}

.hero-copy h1,
.section-head h2 {
  margin: 0;
  color: var(--text-primary);
}

.hero-copy h1 {
  font-size: clamp(28px, 4vw, 40px);
  line-height: 1.05;
  margin-top: 12px;
}

.hero-copy p,
.section-head span,
.metric-caption,
.hero-meta,
.empty-note,
.notes-list {
  color: var(--text-secondary);
}

.hero-copy p {
  max-width: 62ch;
  margin: 14px 0 0;
  line-height: 1.7;
}

.hero-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--border-bright);
  border-radius: 999px;
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.04);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.hero-controls {
  padding: 18px;
  border-radius: 20px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.04), transparent),
    var(--bg-surface);
  border: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.control-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.control-field {
  display: grid;
  gap: 8px;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
}

.control-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.run-btn {
  min-height: 42px;
  padding: 0 16px;
  border-radius: 14px;
  border: 1px solid var(--border-bright);
  background: transparent;
  color: var(--text-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  transition: transform var(--transition-fast), background var(--transition-fast), border-color var(--transition-fast);
}

.run-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  background: var(--bg-hover);
}

.run-btn.primary {
  background: var(--accent);
  color: var(--bubble-user-text);
  border-color: var(--accent);
}

.run-btn.primary:hover:not(:disabled) {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
}

.run-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.hero-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
}

.error-banner {
  width: min(1180px, 100%);
  margin: 0 auto 20px;
  padding: 14px 16px;
  border: 1px solid rgba(143, 107, 107, 0.45);
  border-radius: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(143, 107, 107, 0.12);
  color: var(--text-primary);
}

.board-section {
  padding: 24px 24px 26px;
}

.section-head {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}

.section-head span {
  text-align: right;
  max-width: 58ch;
  font-size: 13px;
  line-height: 1.6;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.stats-grid.samples {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.metric-card {
  position: relative;
  overflow: hidden;
  min-height: 148px;
  padding: 18px;
  border: 1px solid var(--border);
  border-radius: 18px;
  background: var(--bg-elevated);
}

.metric-card::after {
  content: '';
  position: absolute;
  inset: auto -18% -36% auto;
  width: 110px;
  height: 110px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  filter: blur(4px);
}

.metric-card[data-tone='ok'] {
  border-color: rgba(107, 143, 113, 0.42);
}

.metric-card[data-tone='warn'] {
  border-color: rgba(143, 107, 107, 0.4);
}

.metric-title {
  display: inline-block;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-secondary);
}

.metric-value {
  display: block;
  margin-top: 24px;
  color: var(--text-primary);
  font-size: clamp(26px, 3.3vw, 38px);
  line-height: 1;
}

.metric-caption {
  margin: 14px 0 0;
  font-size: 13px;
  line-height: 1.6;
  max-width: 28ch;
}

.sample-card .metric-value {
  margin-top: 30px;
}

.variant-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.variant-card {
  padding: 18px;
  border-radius: 18px;
  border: 1px solid var(--border);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.03), transparent),
    var(--bg-elevated);
}

.variant-card.primary {
  border-color: var(--border-bright);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.04) inset;
}

.variant-top {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 12px;
}

.variant-label {
  color: var(--text-primary);
  font-size: 20px;
  font-weight: 700;
}

.variant-subtitle {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 12px;
}

.pill {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--text-primary);
}

.variant-cost {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
}

.variant-bars {
  margin-top: 18px;
  display: grid;
  gap: 10px;
}

.variant-bar {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr) 64px;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}

.bar-track {
  height: 10px;
  border-radius: 999px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.08);
}

.bar-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #8fb2c6 0%, #d7ebf6 100%);
}

.bar-fill.alt {
  background: linear-gradient(90deg, #9cb88a 0%, #dff4cc 100%);
}

.bar-fill.warn {
  background: linear-gradient(90deg, #b8a26f 0%, #f4dfb0 100%);
}

.variant-bar strong {
  color: var(--text-primary);
  text-align: right;
}

.variant-metrics {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.variant-metrics span {
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-size: 12px;
}

.board-section--notes {
  margin-bottom: 0;
}

.notes-wrap {
  border: 1px dashed var(--border-bright);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.015);
  padding: 16px 18px;
}

.notes-list {
  margin: 0;
  padding-left: 18px;
  line-height: 1.8;
}

.empty-note {
  font-size: 13px;
}

@media (max-width: 1080px) {
  .hero-panel,
  .stats-grid,
  .stats-grid.samples,
  .variant-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .hero-panel {
    grid-template-columns: 1fr;
  }

  .section-head {
    flex-direction: column;
    align-items: start;
  }

  .section-head span {
    text-align: left;
  }
}

@media (max-width: 720px) {
  .eval-page {
    padding: 64px 14px 24px;
  }

  .board-section,
  .hero-panel {
    padding: 18px;
    border-radius: 20px;
  }

  .stats-grid,
  .stats-grid.samples,
  .variant-list,
  .control-grid {
    grid-template-columns: 1fr;
  }

  .variant-bar {
    grid-template-columns: 1fr;
  }

  .variant-bar strong {
    text-align: left;
  }

  .control-actions {
    flex-direction: column;
  }

  .run-btn {
    width: 100%;
  }
}
</style>
