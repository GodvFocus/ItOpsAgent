package com.yuyu.fishagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.fishagent.rag.config.RagProperties;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import com.yuyu.fishagent.rag.pipeline.rerank.RagReranker;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvalServiceTest {

    @Test
    void loadsDefaultGoldenSetAndRunsAllRetrievalVariants() {
        RagEvalProperties properties = new RagEvalProperties();
        RagEvalService service = new RagEvalService(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                properties,
                new RagProperties(),
                List.of(new EmptySearcher()),
                new PassThroughReranker());

        HybridEvalReport report = service.run(1, 1);

        assertThat(report.caseCount()).isEqualTo(20);
        assertThat(report.variants()).containsKeys(
                HybridEvalReport.Variant.DENSE_ONLY,
                HybridEvalReport.Variant.LEXICAL_ONLY,
                HybridEvalReport.Variant.HYBRID,
                HybridEvalReport.Variant.HYBRID_RERANK);
    }

    private static final class EmptySearcher implements RagRecall.DocumentSearcher {

        @Override
        public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
            return List.of();
        }

        @Override
        public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
            return List.of();
        }
    }

    private static final class PassThroughReranker implements RagReranker {

        @Override
        public List<RagRecall.RecallHit> rerank(String query, List<RagRecall.RecallHit> candidates, int topN) {
            return candidates.stream().limit(topN).toList();
        }
    }
}
