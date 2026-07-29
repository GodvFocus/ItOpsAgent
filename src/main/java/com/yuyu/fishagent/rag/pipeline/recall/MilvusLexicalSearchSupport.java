package com.yuyu.fishagent.rag.pipeline.recall;

import io.milvus.client.MilvusServiceClient;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.param.dml.QueryIteratorParam;
import io.milvus.response.QueryResultsWrapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Milvus 2.4.x 的 lexical 兼容层。
 *
 * <p>当前 SDK/Collection 没有 sparse BM25 Function，因此不能把标量 query 当成全文检索。
 * 这里先用 Milvus 的权限过滤结果作为真实候选集，再使用用户 query 做 lexical 排序；
 * 等 Collection 升级到原生 BM25 后，只需要替换本类，召回编排和评测契约不变。</p>
 */
public final class MilvusLexicalSearchSupport {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}_./:-]+|[\\p{IsHan}]");

    private MilvusLexicalSearchSupport() {
    }

    /** 从 Milvus 过滤后的真实数据集中取候选，避免 QueryParam 返回任意前 N 条。 */
    public static List<QueryResultsWrapper.RowRecord> scan(
            MilvusServiceClient client,
            String collection,
            String expression,
            List<String> outFields,
            int maxRows,
            int batchSize) {
        if (client == null || collection == null || collection.isBlank() || maxRows <= 0) {
            return List.of();
        }
        QueryIterator iterator = client.queryIterator(QueryIteratorParam.newBuilder()
                .withCollectionName(collection)
                .withExpr(expression)
                .withOutFields(outFields)
                .withBatchSize((long) Math.max(1, batchSize))
                .withLimit((long) maxRows)
                .build()).getData();
        if (iterator == null) {
            return List.of();
        }
        List<QueryResultsWrapper.RowRecord> rows = new ArrayList<>();
        try {
            while (rows.size() < maxRows) {
                List<QueryResultsWrapper.RowRecord> batch = iterator.next();
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                int remaining = maxRows - rows.size();
                rows.addAll(batch.subList(0, Math.min(remaining, batch.size())));
            }
            return rows;
        } finally {
            iterator.close();
        }
    }

    /** 将真实 Milvus 行按 query 做 lexical 排序，并只返回确实命中 query 的行。 */
    public static List<RagRecall.RecallHit> rank(
            List<QueryResultsWrapper.RowRecord> rows,
            String query,
            int size,
            Function<QueryResultsWrapper.RowRecord, RagRecall.RecallHit> mapper,
            Function<QueryResultsWrapper.RowRecord, String> searchableText) {
        if (rows == null || rows.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        Map<String, RagRecall.RecallHit> best = new LinkedHashMap<>();
        for (QueryResultsWrapper.RowRecord row : rows) {
            RagRecall.RecallHit hit = mapper.apply(row);
            if (hit == null || hit.content() == null || hit.content().isBlank()) {
                continue;
            }
            double score = score(query, searchableText.apply(row), queryTokens);
            if (score <= 0.0) {
                continue;
            }
            String key = RagRecall.dedupKey(hit);
            if (key != null) {
                best.merge(key, hit.withScore(score), (left, right) ->
                        left.score() >= right.score() ? left : right);
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(RagRecall.RecallHit::score).reversed()
                        .thenComparing(hit -> hit.id() == null ? "" : hit.id()))
                .limit(Math.max(0, size))
                .toList();
    }

    /** 对外暴露确定性的 lexical 评分，便于离线测试和后续替换原生 BM25。 */
    public static double score(String query, String searchableText) {
        return score(query, searchableText, tokenize(query));
    }

    private static double score(String query, String searchableText, List<String> queryTokens) {
        if (searchableText == null || searchableText.isBlank()) {
            return 0.0;
        }
        String corpus = searchableText.toLowerCase(Locale.ROOT);
        double score = 0.0;
        for (String token : queryTokens) {
            int occurrences = countOccurrences(corpus, token);
            if (occurrences > 0) {
                score += Math.min(3, occurrences) * (token.length() >= 2 ? 2.0 : 1.0);
            }
        }
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.length() >= 2 && corpus.contains(normalizedQuery)) {
            score += queryTokens.size() * 2.0;
        }
        return score;
    }

    static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return TOKEN_PATTERN.matcher(value.toLowerCase(Locale.ROOT)).results()
                .map(match -> match.group())
                .filter(token -> token.length() >= 2 || token.codePoints().anyMatch(cp ->
                        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN))
                .distinct()
                .toList();
    }

    private static int countOccurrences(String corpus, String token) {
        int count = 0;
        int from = 0;
        while ((from = corpus.indexOf(token, from)) >= 0) {
            count++;
            from += Math.max(1, token.length());
        }
        return count;
    }
}
