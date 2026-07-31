package com.ai.itops.eval;

import com.ai.itops.rag.config.RagProperties;
import com.ai.itops.rag.pipeline.recall.ProvenanceBooster;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import com.ai.itops.rag.pipeline.rerank.RagReranker;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 离线评测 runner。
 *
 * <p>保留稳定的 provenance 离线评测，并通过 {@link #runEndToEnd} 接入真实 Milvus/ES
 * DocumentSearcher 结果，避免把预置 candidates 误当成召回结果。</p>
 */
public class EvalRunner {

    private final RagProperties ragProperties;
    private final ProvenanceBooster booster;

    public EvalRunner() {
        this(defaultProperties());
    }

    public EvalRunner(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.booster = new ProvenanceBooster(ragProperties);
    }

    /**
     * 使用真实检索器跑四种召回消融：dense-only、lexical-only、hybrid、hybrid+rerank。
     * 调用方需在执行前放置好当前用户/ workspace 上下文，检索器会据此执行权限过滤。
     */
    public HybridEvalReport runEndToEnd(List<GoldenSet.Case> cases,
                                        List<RagRecall.DocumentSearcher> searchers,
                                        RagReranker reranker,
                                        int k,
                                        int perLegK) {
        return new HybridEvalRunner(
                ragProperties.getFusion().getRrfK(),
                ragProperties.getFusion().getCandidatePoolSize(),
                HybridEvalRunner.CostModel.zero())
                .run(cases, searchers, reranker, k, perLegK);
    }

    public EvalReport run(List<GoldenSet.Case> cases, int k) {
        if (cases == null || cases.isEmpty()) {
            RetrievalMetrics.Result zero = new RetrievalMetrics.Result(0.0, 0.0, 0.0, 0.0);
            return new EvalReport(0, zero, zero);
        }
        double baselinePrecision = 0.0;
        double baselineMrr = 0.0;
        double baselineRecall = 0.0;
        double baselineNdcg = 0.0;
        double provenancePrecision = 0.0;
        double provenanceMrr = 0.0;
        double provenanceRecall = 0.0;
        double provenanceNdcg = 0.0;
        long now = System.currentTimeMillis();

        for (GoldenSet.Case item : cases) {
            Map<String, Integer> relevance = relevanceMap(item.candidates());
            RetrievalMetrics.Result baseline = RetrievalMetrics.evaluate(
                    item.candidates().stream()
                            .sorted(Comparator.comparingDouble(GoldenSet.Candidate::score).reversed())
                            .map(GoldenSet.Candidate::id)
                            .toList(),
                    relevance,
                    k);
            RetrievalMetrics.Result provenance = RetrievalMetrics.evaluate(
                    booster.boost(toHits(item.candidates()), now).stream()
                            .map(RagRecall.RecallHit::id)
                            .toList(),
                    relevance,
                    k);
            baselinePrecision += baseline.precisionAtK();
            baselineMrr += baseline.mrr();
            baselineRecall += baseline.recallAtK();
            baselineNdcg += baseline.ndcgAtK();
            provenancePrecision += provenance.precisionAtK();
            provenanceMrr += provenance.mrr();
            provenanceRecall += provenance.recallAtK();
            provenanceNdcg += provenance.ndcgAtK();
        }

        int n = cases.size();
        return new EvalReport(
                n,
                new RetrievalMetrics.Result(baselinePrecision / n, baselineRecall / n, baselineMrr / n, baselineNdcg / n),
                new RetrievalMetrics.Result(provenancePrecision / n, provenanceRecall / n, provenanceMrr / n, provenanceNdcg / n));
    }

    private static List<RagRecall.RecallHit> toHits(List<GoldenSet.Candidate> candidates) {
        return candidates.stream()
                .map(c -> new RagRecall.RecallHit(c.id(), c.content(), c.score(), RagRecall.RecallSource.TEXT,
                        c.sourceLabel(), c.authority(), c.createdAt(), null, null))
                .toList();
    }

    private static Map<String, Integer> relevanceMap(List<GoldenSet.Candidate> candidates) {
        Map<String, Integer> relevance = new LinkedHashMap<>();
        for (GoldenSet.Candidate candidate : candidates) {
            relevance.put(candidate.id(), candidate.relevance());
        }
        return relevance;
    }

    private static RagProperties defaultProperties() {
        RagProperties properties = new RagProperties();
        properties.getProvenance().setEnabled(true);
        return properties;
    }
}
