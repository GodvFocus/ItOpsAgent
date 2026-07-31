package com.ai.itops.eval;

import com.ai.itops.rag.pipeline.recall.RagRecall;
import com.ai.itops.rag.pipeline.rerank.RagReranker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HybridEvalRunnerTest {

    @Test
    void evaluatesFourVariantsAgainstRealSearcherOutputs() {
        AtomicReference<String> observedQuery = new AtomicReference<>();
        RagRecall.DocumentSearcher searcher = new RagRecall.DocumentSearcher() {
            @Override
            public List<RagRecall.RecallHit> searchByText(String sessionId, String query, int size) {
                observedQuery.set(query);
                return List.of(hit("lexical", 2.0, RagRecall.RecallSource.TEXT));
            }

            @Override
            public List<RagRecall.RecallHit> searchByVector(String sessionId, String query, int size) {
                return List.of(hit("dense", 0.9, RagRecall.RecallSource.VECTOR));
            }
        };
        RagReranker reranker = (query, candidates, topN) -> List.of(hit("lexical", 1.0, RagRecall.RecallSource.TEXT));
        GoldenSet.Case item = new GoldenSet.Case("e2e-1", "真实用户 query", List.of(
                new GoldenSet.Candidate("lexical", "关键词命中", 2.0, "公开", 1.0, null, 3),
                new GoldenSet.Candidate("dense", "语义命中", 0.9, "公开", 1.0, null, 1)));

        HybridEvalReport report = new HybridEvalRunner(
                60, 10, new HybridEvalRunner.CostModel(0.01, 0.02, 0.03))
                .run(List.of(item), List.of(searcher), reranker, 1, 3);

        assertThat(observedQuery).hasValue("真实用户 query");
        assertThat(report.caseCount()).isEqualTo(1);
        assertThat(report.variants()).containsKeys(
                HybridEvalReport.Variant.DENSE_ONLY,
                HybridEvalReport.Variant.LEXICAL_ONLY,
                HybridEvalReport.Variant.HYBRID,
                HybridEvalReport.Variant.HYBRID_RERANK);
        assertThat(report.variants().get(HybridEvalReport.Variant.LEXICAL_ONLY)
                .metrics().recallAtK()).isEqualTo(0.5);
        assertThat(report.variants().get(HybridEvalReport.Variant.HYBRID_RERANK)
                .averageEstimatedCostUsd()).isEqualTo(0.06);
        assertThat(report.variants().get(HybridEvalReport.Variant.HYBRID_RERANK)
                .averageRerankCalls()).isEqualTo(1.0);
    }

    private static RagRecall.RecallHit hit(String id, double score, RagRecall.RecallSource source) {
        return new RagRecall.RecallHit(id, id, score, source, "公开", 1.0, null, null, null);
    }
}
