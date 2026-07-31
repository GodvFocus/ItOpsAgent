package com.ai.itops.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实工程指标总览报告。
 *
 * <p>把离线 golden set 正确性指标和线上 trace 样本指标聚合为一份可直接展示的报表，
 * 避免只盯单一 Recall 或单一耗时指标。</p>
 */
public record EngineeringMetricsReport(long generatedAt,
                                       SampleStats samples,
                                       RoutingMetrics routing,
                                       ToolMetrics tools,
                                       RetrievalMetrics retrieval,
                                       CitationMetrics citations,
                                       LatencyMetrics latency,
                                       EfficiencyMetrics efficiency,
                                       List<String> notes) {

    public EngineeringMetricsReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public record SampleStats(int troubleshootingCaseCount,
                              int ragCaseCount,
                              int turnTraceCount,
                              int ragTraceCount) {
    }

    public record RoutingMetrics(double accuracy,
                                 double f1) {
    }

    public record ToolMetrics(double selectionAccuracy,
                              double parameterAccuracy,
                              double averageCallsPerCase) {
    }

    public record RetrievalMetrics(HybridEvalReport.Variant primaryVariant,
                                   RankingMetrics primary,
                                   Map<HybridEvalReport.Variant, RankingMetrics> variants) {

        public RetrievalMetrics {
            variants = variants == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(variants));
        }
    }

    public record RankingMetrics(double precisionAtK,
                                 double recallAtK,
                                 double mrr,
                                 double ndcgAtK,
                                 double averageLatencyMs,
                                 double averageEstimatedCostUsd) {
    }

    public record CitationMetrics(double accuracy,
                                  double coverage) {
    }

    public record LatencyMetrics(double ttftP95Ms,
                                 double fullResponseP95Ms,
                                 double failureRecoveryP95Ms) {
    }

    public record EfficiencyMetrics(double averageModelCallsPerTurn,
                                    double averagePromptTokensPerTurn,
                                    double averageEstimatedPromptCostUsd) {
    }
}
