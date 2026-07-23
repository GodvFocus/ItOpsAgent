package com.yuyu.fishagent.rag.pipeline.recall;

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
        if (scores == null) return out;

        for (int i = 0; i < scores.size(); i++) {
            SearchResultsWrapper.IDScore idScore = scores.get(i);
            try {
                String id = String.valueOf(idScore.getStrID());
                List<?> contentList = results.getFieldData("content", i);
                String content = contentList != null && !contentList.isEmpty()
                        ? String.valueOf(contentList.get(0)) : null;
                if (content == null || content.isBlank()) continue;

                List<?> docIdList = results.getFieldData("doc_id", i);
                String docId = docIdList != null && !docIdList.isEmpty()
                        ? String.valueOf(docIdList.get(0)) : null;
                List<?> chunkIdxList = results.getFieldData("chunk_index", i);
                Integer chunkIndex = chunkIdxList != null && !chunkIdxList.isEmpty()
                        ? ((Long) chunkIdxList.get(0)).intValue() : null;
                List<?> docNameList = results.getFieldData("doc_name", i);
                String docName = docNameList != null && !docNameList.isEmpty()
                        ? String.valueOf(docNameList.get(0)) : null;
                List<?> authList = results.getFieldData("authority", i);
                Double authority = authList != null && !authList.isEmpty()
                        ? (Double) authList.get(0) : null;

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

                out.add(new RagRecall.RecallHit(
                        id, content.trim(), 0.0, source,
                        SourceAuthority.labelForKnowledge(authority, false),
                        authority, null,
                        row.get("doc_id") != null ? String.valueOf(row.get("doc_id")) : null,
                        chunkIndex,
                        row.get("doc_name") != null ? String.valueOf(row.get("doc_name")) : null));
            } catch (Exception e) {
                log.debug("Milvus query 结果映射跳过: {}", e.getMessage());
            }
        }
        return out;
    }
}
