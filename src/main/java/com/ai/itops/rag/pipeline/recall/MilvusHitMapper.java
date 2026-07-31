package com.ai.itops.rag.pipeline.recall;

import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 检索结果 → RagRecall.RecallHit 的映射工具。
 * <p>所有 Milvus Searcher 共用。</p>
 */
@Slf4j
public final class MilvusHitMapper {

    private MilvusHitMapper() {}

    /**
     * 从 Milvus search（向量检索）结果映射。
     */
    public static List<RagRecall.RecallHit> fromSearch(SearchResultsWrapper results,
                                                        RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        if (results == null) return out;

        List<SearchResultsWrapper.IDScore> scores = results.getIDScore(0);
        List<QueryResultsWrapper.RowRecord> rows = results.getRowRecords();
        if (scores == null || rows == null) return out;

        int count = Math.min(scores.size(), rows.size());
        for (int i = 0; i < count; i++) {
            SearchResultsWrapper.IDScore idScore = scores.get(i);
            QueryResultsWrapper.RowRecord row = rows.get(i);
            try {
                String id = row.get("id") == null ? String.valueOf(idScore.getStrID()) : String.valueOf(row.get("id"));
                String content = row.get("content") == null ? null : String.valueOf(row.get("content"));
                if (content == null || content.isBlank()) continue;

                String docId = row.get("doc_id") == null ? null : String.valueOf(row.get("doc_id"));
                Object chunkValue = row.get("chunk_index");
                Integer chunkIndex = chunkValue instanceof Number number ? number.intValue() : null;
                String docName = row.get("doc_name") == null ? null : String.valueOf(row.get("doc_name"));
                Object authorityValue = row.get("authority");
                Double authority = authorityValue instanceof Number number ? number.doubleValue() : null;

                out.add(new RagRecall.RecallHit(
                        id, content.trim(), (double) idScore.getScore(), source,
                        SourceAuthority.labelForKnowledge(authority, false),
                        authority, null, docId, chunkIndex, docName));
            } catch (Exception e) {
                log.debug("Milvus 结果映射跳过 index={}: {}", i, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 从 Milvus query（标量/BM25 全文检索）结果映射。
     */
    public static List<RagRecall.RecallHit> fromQuery(List<QueryResultsWrapper.RowRecord> records,
                                                       RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        if (records == null) return out;

        for (QueryResultsWrapper.RowRecord row : records) {
            try {
                String id = String.valueOf(row.get("id"));
                String content = String.valueOf(row.get("content"));
                if (content == null || content.isBlank()) continue;

                Double authority = row.get("authority") instanceof Number
                        ? ((Number) row.get("authority")).doubleValue() : null;
                Object chunkIdxObj = row.get("chunk_index");
                Integer chunkIndex = chunkIdxObj instanceof Number
                        ? ((Number) chunkIdxObj).intValue() : null;

                out.add(fromQueryRow(row, source));
            } catch (Exception e) {
                log.debug("Milvus query 结果映射跳过: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 将单条 Milvus query 行映射为命中，供 lexical 排序复用。 */
    public static RagRecall.RecallHit fromQueryRow(QueryResultsWrapper.RowRecord row,
                                                    RagRecall.RecallSource source) {
        String id = String.valueOf(row.get("id"));
        String content = String.valueOf(row.get("content"));
        Double authority = row.get("authority") instanceof Number
                ? ((Number) row.get("authority")).doubleValue() : null;
        Object chunkIdxObj = row.get("chunk_index");
        Integer chunkIndex = chunkIdxObj instanceof Number
                ? ((Number) chunkIdxObj).intValue() : null;
        return new RagRecall.RecallHit(
                id, content.trim(), 0.0, source,
                SourceAuthority.labelForKnowledge(authority, false),
                authority, null,
                row.get("doc_id") != null ? String.valueOf(row.get("doc_id")) : null,
                chunkIndex,
                row.get("doc_name") != null ? String.valueOf(row.get("doc_name")) : null);
    }
}
