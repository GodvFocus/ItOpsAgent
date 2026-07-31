package com.ai.itops.eval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 检索排序离线评测指标。
 *
 * <p>输入为已排序的候选 id 与人工标注相关性，输出 precision@k、MRR、nDCG@k。
 * 该类不依赖 Spring/ES/LLM，方便在单测、CI 和离线脚本里复用。</p>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    public static Result evaluate(List<String> rankedIds, Map<String, Integer> relevanceById, int k) {
        int limit = Math.max(1, k);
        if (rankedIds == null || rankedIds.isEmpty() || relevanceById == null || relevanceById.isEmpty()) {
            return new Result(0.0, 0.0, 0.0, 0.0);
        }
        int considered = Math.min(limit, rankedIds.size());
        int relevantCount = 0;
        int totalRelevant = (int) relevanceById.values().stream()
                .filter(value -> value != null && value > 0)
                .count();
        double reciprocalRank = 0.0;
        double dcg = 0.0;
        for (int i = 0; i < considered; i++) {
            int relevance = Math.max(0, relevanceById.getOrDefault(rankedIds.get(i), 0));
            if (relevance > 0) {
                relevantCount++;
                if (reciprocalRank == 0.0) {
                    reciprocalRank = 1.0 / (i + 1);
                }
            }
            dcg += gain(relevance, i);
        }
        List<Integer> idealRelevance = relevanceById.values().stream()
                .map(v -> Math.max(0, v))
                .sorted(Comparator.reverseOrder())
                .limit(limit)
                .toList();
        double idealDcg = 0.0;
        for (int i = 0; i < idealRelevance.size(); i++) {
            idealDcg += gain(idealRelevance.get(i), i);
        }
        double ndcg = idealDcg == 0.0 ? 0.0 : dcg / idealDcg;
        double recall = totalRelevant == 0 ? 1.0 : relevantCount / (double) totalRelevant;
        return new Result(relevantCount / (double) limit, recall, reciprocalRank, ndcg);
    }

    private static double gain(int relevance, int zeroBasedRank) {
        if (relevance <= 0) {
            return 0.0;
        }
        return (Math.pow(2.0, relevance) - 1.0) / log2(zeroBasedRank + 2.0);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    public record Result(double precisionAtK, double recallAtK, double mrr, double ndcgAtK) {

        /** 兼容旧调用方的三参数构造。 */
        public Result(double precisionAtK, double mrr, double ndcgAtK) {
            this(precisionAtK, 0.0, mrr, ndcgAtK);
        }
    }
}
