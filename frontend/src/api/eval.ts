import { authFetch } from './http'

export type RetrievalVariant = 'DENSE_ONLY' | 'LEXICAL_ONLY' | 'HYBRID' | 'HYBRID_RERANK'

export interface EngineeringMetricsReport {
  generatedAt: number
  samples: {
    troubleshootingCaseCount: number
    ragCaseCount: number
    turnTraceCount: number
    ragTraceCount: number
  }
  routing: {
    accuracy: number
    f1: number
  }
  tools: {
    selectionAccuracy: number
    parameterAccuracy: number
    averageCallsPerCase: number
  }
  retrieval: {
    primaryVariant: RetrievalVariant
    primary: RankingMetrics
    variants: Partial<Record<RetrievalVariant, RankingMetrics>>
  }
  citations: {
    accuracy: number
    coverage: number
  }
  latency: {
    ttftP95Ms: number
    fullResponseP95Ms: number
    failureRecoveryP95Ms: number
  }
  efficiency: {
    averageModelCallsPerTurn: number
    averagePromptTokensPerTurn: number
    averageEstimatedPromptCostUsd: number
  }
  notes: string[]
}

export interface RankingMetrics {
  precisionAtK: number
  recallAtK: number
  mrr: number
  ndcgAtK: number
  averageLatencyMs: number
  averageEstimatedCostUsd: number
}

async function parseError(response: Response): Promise<string> {
  const data = await response.json().catch(() => ({}))
  return (data as { message?: string })?.message ?? `HTTP ${response.status}`
}

/**
 * 统一工程评测：把路由、工具、召回、引用、时延和成本汇总为一份看板报表。
 */
export async function fetchEngineeringMetrics(params: { k?: number; perLegK?: number } = {}): Promise<EngineeringMetricsReport> {
  const query = new URLSearchParams()
  if (params.k != null) {
    query.set('k', String(params.k))
  }
  if (params.perLegK != null) {
    query.set('perLegK', String(params.perLegK))
  }
  const suffix = query.toString() ? `?${query}` : ''
  const response = await authFetch(`/api/eval/engineering${suffix}`)
  if (!response.ok) {
    throw new Error(await parseError(response))
  }
  return (await response.json()) as EngineeringMetricsReport
}
