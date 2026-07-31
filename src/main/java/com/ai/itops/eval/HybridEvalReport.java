package com.ai.itops.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/** 真实召回端到端评测报告。 */
public record HybridEvalReport(int caseCount, Map<Variant, VariantReport> variants) {

    public HybridEvalReport {
        variants = variants == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(variants));
    }

    public enum Variant {
        DENSE_ONLY,
        LEXICAL_ONLY,
        HYBRID,
        HYBRID_RERANK
    }

    public record VariantReport(RetrievalMetrics.Result metrics,
                                double averageLatencyMs,
                                double averageEstimatedCostUsd,
                                double averageLexicalCalls,
                                double averageDenseCalls,
                                double averageRerankCalls) {
    }
}
