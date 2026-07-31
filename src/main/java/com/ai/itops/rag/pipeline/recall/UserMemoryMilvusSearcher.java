package com.ai.itops.rag.pipeline.recall;

import com.ai.itops.auth.context.UserContextHolder;
import com.ai.itops.memory.config.MemoryProperties;
import com.ai.itops.rag.config.MilvusProperties;
import com.ai.itops.rag.config.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.v2.client.MilvusClientV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户长期记忆 Milvus 检索器：BM25（标量查询）/ 向量 ANN，按 user_id + source_type=chat + superseded=false 过滤。
 * <p>作为 ES → Milvus 迁移后的记忆召回入口，替代原 ES 版记忆搜索器。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMemoryMilvusSearcher implements RagRecall.DocumentSearcher {

    private final MilvusServiceClient milvusClient;
    private final MilvusClientV2 milvusClientV2;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MemoryProperties memoryProperties;
    private final MilvusProperties milvusProperties;
    private final RagProperties ragProperties;

    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        if (!memoryProperties.isLongTermEnabled()) {
            return List.of();
        }
        String uid = currentUserIdString();
        if (uid == null || subQueryText == null || subQueryText.isBlank()) {
            return List.of();
        }

        String collection = memoryCollection();
        // 按用户 + 来源类型 + 未失效过滤
        String expr = String.format(
                "user_id == \"%s\" and source_type == \"chat\" and superseded == false", uid);

        try {
            if (milvusProperties.getBm25().isEnabled()
                    && MilvusNativeBm25Support.serverSupportsBm25(milvusClientV2.getServerVersion())) {
                try {
                    List<MilvusNativeBm25Support.NativeHit> hits = MilvusNativeBm25Support.search(
                            milvusClientV2,
                            milvusProperties.getBm25().getUserMemoryCollection(),
                            milvusProperties.getBm25().getSparseField(),
                            subQueryText,
                            expr,
                            Math.max(1, size),
                            List.of("id", "content", "created_at"));
                    return hits.stream().map(hit -> mapNativeRow(hit, RagRecall.RecallSource.TEXT)).toList();
                } catch (Exception nativeError) {
                    log.warn("[UserMemoryMilvus] 原生 BM25 查询失败，回退到兼容路径: {}", nativeError.getMessage());
                }
            }
            List<io.milvus.response.QueryResultsWrapper.RowRecord> rows = MilvusLexicalSearchSupport.scan(
                    milvusClient, collection, expr, List.of("id", "content", "created_at"),
                    ragProperties.getRecall().getLexicalMaxScanRows(),
                    ragProperties.getRecall().getLexicalBatchSize());
            return MilvusLexicalSearchSupport.rank(rows, subQueryText, Math.max(1, size),
                    row -> mapMemoryRow(row, RagRecall.RecallSource.TEXT),
                    row -> String.valueOf(row.get("content")));
        } catch (Exception e) {
            log.warn("[UserMemoryMilvus] 文本检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!memoryProperties.isLongTermEnabled() || !ragProperties.getRecall().isVectorLegEnabled()) {
            return List.of();
        }
        String uid = currentUserIdString();
        if (uid == null) {
            return List.of();
        }

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null || textToEmbed == null || textToEmbed.isBlank()) {
            return List.of();
        }

        float[] vector;
        try {
            vector = embeddingModel.embed(textToEmbed.trim());
        } catch (Exception e) {
            log.warn("[UserMemoryMilvus] embedding 失败: {}", e.getMessage());
            return List.of();
        }

        String collection = memoryCollection();
        String expr = String.format(
                "user_id == \"%s\" and source_type == \"chat\" and superseded == false", uid);

        try {
            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(List.of(toFloatList(vector)))
                    .withVectorFieldName("embedding")
                    .withTopK(Math.max(1, size))
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr(expr)
                    .withParams("{\"nprobe\":16}")
                    .withOutFields(List.of("id", "content", "created_at"))
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(
                    milvusClient.search(search).getData().getResults());
            return mapMemoryFromSearch(results, RagRecall.RecallSource.VECTOR);
        } catch (Exception e) {
            log.warn("[UserMemoryMilvus] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将 Milvus query（标量查询）结果映射为 RecallHit。
     * <p>记忆文档无 doc_id / chunk_index / authority 等知识库字段，使用固定标签"记忆"。</p>
     */
    private static RagRecall.RecallHit mapMemoryRow(io.milvus.response.QueryResultsWrapper.RowRecord row,
                                                     RagRecall.RecallSource source) {
        String id = String.valueOf(row.get("id"));
        String content = String.valueOf(row.get("content"));
        Long createdAt = row.get("created_at") instanceof Number
                ? ((Number) row.get("created_at")).longValue() : null;
        return new RagRecall.RecallHit(id, content.trim(), 0.0, source,
                "记忆", 0.8, createdAt, null, null);
    }

    private static List<RagRecall.RecallHit> mapMemoryHits(List<io.milvus.response.QueryResultsWrapper.RowRecord> records,
                                                            RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        if (records == null) {
            return out;
        }
        for (io.milvus.response.QueryResultsWrapper.RowRecord row : records) {
            try {
                String id = String.valueOf(row.get("id"));
                String content = String.valueOf(row.get("content"));
                if (content == null || content.isBlank()) {
                    continue;
                }
                Long createdAt = row.get("created_at") instanceof Number
                        ? ((Number) row.get("created_at")).longValue() : null;
                out.add(new RagRecall.RecallHit(id, content.trim(), 0.0, source,
                        "记忆", 0.8, createdAt, null, null));
            } catch (Exception e) {
                log.debug("[UserMemoryMilvus] 文本命中映射跳过: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * 将 Milvus search（向量 ANN）结果映射为 RecallHit。
     */
    private static List<RagRecall.RecallHit> mapMemoryFromSearch(SearchResultsWrapper results,
                                                                  RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        if (results == null) {
            return out;
        }
        List<SearchResultsWrapper.IDScore> scores = results.getIDScore(0);
        List<io.milvus.response.QueryResultsWrapper.RowRecord> rows = results.getRowRecords();
        if (scores == null || rows == null) {
            return out;
        }
        int count = Math.min(scores.size(), rows.size());
        for (int i = 0; i < count; i++) {
            try {
                var row = rows.get(i);
                String id = row.get("id") == null
                        ? String.valueOf(scores.get(i).getStrID()) : String.valueOf(row.get("id"));
                String content = row.get("content") == null ? null : String.valueOf(row.get("content"));
                if (content == null || content.isBlank()) {
                    continue;
                }
                Object createdAtValue = row.get("created_at");
                Long createdAt = createdAtValue instanceof Number number ? number.longValue() : null;
                out.add(new RagRecall.RecallHit(id, content.trim(),
                        (double) scores.get(i).getScore(), source,
                        "记忆", 0.8, createdAt, null, null));
            } catch (Exception e) {
                log.debug("[UserMemoryMilvus] 向量命中映射跳过: {}", e.getMessage());
            }
        }
        return out;
    }

    private static String currentUserIdString() {
        Long id = UserContextHolder.currentUserIdOrNull();
        return id == null ? null : String.valueOf(id);
    }

    private String memoryCollection() {
        return milvusProperties.getBm25().isEnabled()
                && MilvusNativeBm25Support.serverSupportsBm25(milvusClientV2.getServerVersion())
                ? milvusProperties.getBm25().getUserMemoryCollection()
                : memoryProperties.getLongTermIndexName();
    }

    private static RagRecall.RecallHit mapNativeRow(MilvusNativeBm25Support.NativeHit hit,
                                                     RagRecall.RecallSource source) {
        String content = hit.string("content");
        return new RagRecall.RecallHit(hit.id(), content == null ? "" : content.trim(), hit.score(), source,
                "记忆", 0.8, hit.longNumber("created_at"), null, null);
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) {
            values.add(v);
        }
        return values;
    }
}
