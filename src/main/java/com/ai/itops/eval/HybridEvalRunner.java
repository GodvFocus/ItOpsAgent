package com.ai.itops.eval;

import com.ai.itops.rag.pipeline.fusion.RagScoreFusion;
import com.ai.itops.rag.pipeline.recall.RagRecall;
import com.ai.itops.rag.pipeline.rerank.RagReranker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实召回端到端评测器。
 *
 * <p>每个 case 都调用传入的 DocumentSearcher，因此可以直接接入 Milvus/ES 实现；
 * 不再把 golden candidates 当作“召回结果”。四个 variant 共用同一轮真实召回，
 * 再分别计算 dense-only、lexical-only、hybrid、hybrid+rerank 的指标、延迟和估算成本。</p>
 */
public final class HybridEvalRunner {

    private final int rrfK;
    private final int candidatePoolSize;
    private final CostModel costModel;

    public HybridEvalRunner() {
        this(60, 50, CostModel.zero());
    }

    public HybridEvalRunner(int rrfK, int candidatePoolSize, CostModel costModel) {
        this.rrfK = Math.max(1, rrfK);
        this.candidatePoolSize = Math.max(1, candidatePoolSize);
        this.costModel = costModel == null ? CostModel.zero() : costModel;
    }

    public HybridEvalReport run(List<GoldenSet.Case> cases,
                                List<RagRecall.DocumentSearcher> searchers,
                                RagReranker reranker,
                                int k,
                                int perLegK) {
        List<GoldenSet.Case> safeCases = cases == null ? List.of() : cases;
        List<RagRecall.DocumentSearcher> safeSearchers = searchers == null
                ? List.of()
                : searchers.stream().filter(java.util.Objects::nonNull).toList();
        int topK = Math.max(1, k);
        int legK = Math.max(1, perLegK);
        EnumMap<HybridEvalReport.Variant, Accumulator> accumulators = new EnumMap<>(HybridEvalReport.Variant.class);
        for (HybridEvalReport.Variant variant : HybridEvalReport.Variant.values()) {
            accumulators.put(variant, new Accumulator());
        }

        for (GoldenSet.Case item : safeCases) {
            evaluateCase(item, safeSearchers, reranker, topK, legK, accumulators);
        }
        Map<HybridEvalReport.Variant, HybridEvalReport.VariantReport> reports = new LinkedHashMap<>();
        for (Map.Entry<HybridEvalReport.Variant, Accumulator> entry : accumulators.entrySet()) {
            reports.put(entry.getKey(), entry.getValue().report(safeCases.size()));
        }
        return new HybridEvalReport(safeCases.size(), reports);
    }

    private void evaluateCase(GoldenSet.Case item,
                              List<RagRecall.DocumentSearcher> searchers,
                              RagReranker reranker,
                              int k,
                              int perLegK,
                              EnumMap<HybridEvalReport.Variant, Accumulator> accumulators) {
        List<List<RagRecall.RecallHit>> lexicalBatches = new ArrayList<>();
        List<List<RagRecall.RecallHit>> denseBatches = new ArrayList<>();

        long lexicalStart = System.nanoTime();
        for (RagRecall.DocumentSearcher searcher : searchers) {
            lexicalBatches.add(safeTextSearch(searcher, item.query(), perLegK));
        }
        long lexicalLatency = elapsedMs(lexicalStart);

        long denseStart = System.nanoTime();
        for (RagRecall.DocumentSearcher searcher : searchers) {
            denseBatches.add(safeVectorSearch(searcher, item.query(), perLegK));
        }
        long denseLatency = elapsedMs(denseStart);

        List<RagRecall.RecallHit> lexical = fuse(lexicalBatches, k);
        List<RagRecall.RecallHit> dense = fuse(denseBatches, k);
        List<List<RagRecall.RecallHit>> hybridBatches = new ArrayList<>(lexicalBatches);
        hybridBatches.addAll(denseBatches);
        long fusionStart = System.nanoTime();
        List<RagRecall.RecallHit> hybrid = fuse(hybridBatches, k);
        long fusionLatency = elapsedMs(fusionStart);

        long rerankStart = System.nanoTime();
        List<RagRecall.RecallHit> hybridRerank = reranker == null
                ? hybrid
                : safeRerank(reranker, item.query(), hybrid, k);
        long rerankLatency = reranker == null ? 0L : elapsedMs(rerankStart);

        Map<String, Integer> relevance = relevanceMap(item);
        record(accumulators.get(HybridEvalReport.Variant.LEXICAL_ONLY), lexical, relevance, k,
                lexicalLatency + fusionLatency, searchers.size(), 0, 0);
        record(accumulators.get(HybridEvalReport.Variant.DENSE_ONLY), dense, relevance, k,
                denseLatency + fusionLatency, 0, searchers.size(), 0);
        record(accumulators.get(HybridEvalReport.Variant.HYBRID), hybrid, relevance, k,
                lexicalLatency + denseLatency + fusionLatency,
                searchers.size(), searchers.size(), 0);
        record(accumulators.get(HybridEvalReport.Variant.HYBRID_RERANK), hybridRerank, relevance, k,
                lexicalLatency + denseLatency + fusionLatency + rerankLatency,
                searchers.size(), searchers.size(), reranker == null ? 0 : 1);
    }

    private List<RagRecall.RecallHit> fuse(List<List<RagRecall.RecallHit>> batches, int k) {
        return RagScoreFusion.fuseByRrf(batches, rrfK, Math.max(k, candidatePoolSize));
    }

    private static List<RagRecall.RecallHit> safeTextSearch(RagRecall.DocumentSearcher searcher,
                                                              String query,
                                                              int size) {
        try {
            return searcher.searchByText(null, query, size);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<RagRecall.RecallHit> safeVectorSearch(RagRecall.DocumentSearcher searcher,
                                                                String query,
                                                                int size) {
        try {
            return searcher.searchByVector(null, query, size);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static List<RagRecall.RecallHit> safeRerank(RagReranker reranker,
                                                         String query,
                                                         List<RagRecall.RecallHit> candidates,
                                                         int topK) {
        try {
            return reranker.rerank(query, candidates, topK);
        } catch (RuntimeException ignored) {
            return candidates.stream().limit(topK).toList();
        }
    }

    private static void record(Accumulator accumulator,
                               List<RagRecall.RecallHit> ranked,
                               Map<String, Integer> relevance,
                               int k,
                               double latencyMs,
                               int lexicalCalls,
                               int denseCalls,
                               int rerankCalls) {
        RetrievalMetrics.Result metrics = RetrievalMetrics.evaluate(
                ranked.stream().map(RagRecall.RecallHit::id).toList(), relevance, k);
        accumulator.add(metrics, latencyMs, lexicalCalls, denseCalls, rerankCalls);
    }

    private static Map<String, Integer> relevanceMap(GoldenSet.Case item) {
        Map<String, Integer> relevance = new LinkedHashMap<>();
        if (item.candidates() != null) {
            for (GoldenSet.Candidate candidate : item.candidates()) {
                relevance.put(candidate.id(), candidate.relevance());
            }
        }
        return relevance;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    public record CostModel(double lexicalCallUsd, double denseCallUsd, double rerankCallUsd) {

        public static CostModel zero() {
            return new CostModel(0.0, 0.0, 0.0);
        }
    }

    private final class Accumulator {
        private double precision;
        private double recall;
        private double mrr;
        private double ndcg;
        private double latency;
        private double lexicalCalls;
        private double denseCalls;
        private double rerankCalls;

        private void add(RetrievalMetrics.Result metrics, double latencyMs,
                         int lexicalCallCount, int denseCallCount, int rerankCallCount) {
            precision += metrics.precisionAtK();
            recall += metrics.recallAtK();
            mrr += metrics.mrr();
            ndcg += metrics.ndcgAtK();
            latency += latencyMs;
            lexicalCalls += lexicalCallCount;
            denseCalls += denseCallCount;
            rerankCalls += rerankCallCount;
        }

        private HybridEvalReport.VariantReport report(int caseCount) {
            if (caseCount <= 0) {
                return new HybridEvalReport.VariantReport(
                        new RetrievalMetrics.Result(0.0, 0.0, 0.0, 0.0), 0.0, 0.0, 0.0, 0.0, 0.0);
            }
            double count = caseCount;
            double estimatedCost = (lexicalCalls * costModel.lexicalCallUsd
                    + denseCalls * costModel.denseCallUsd
                    + rerankCalls * costModel.rerankCallUsd) / count;
            return new HybridEvalReport.VariantReport(
                    new RetrievalMetrics.Result(precision / count, recall / count, mrr / count, ndcg / count),
                    latency / count,
                    estimatedCost,
                    lexicalCalls / count,
                    denseCalls / count,
                    rerankCalls / count);
        }
    }
}
