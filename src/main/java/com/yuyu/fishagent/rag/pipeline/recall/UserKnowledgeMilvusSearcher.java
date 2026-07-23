package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.rag.config.MilvusProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户私有文档知识库 Milvus 检索：BM25 全文 + BGE-M3 向量，user_id 强制隔离。
 *
 * <p>作为 {@link RagRecall.DocumentSearcher} 的 Milvus 实现，完成 ES → Milvus 迁移的召回层切换。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserKnowledgeMilvusSearcher implements RagRecall.DocumentSearcher {

    private final MilvusServiceClient milvusClient;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MilvusProperties milvusProperties;
    private final RagProperties ragProperties;

    /**
     * BM25 全文检索：在用户私有文档知识 Collection 中按 user_id 隔离，过滤未就绪切片。
     */
    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        String uid = currentUserIdString();
        if (uid == null) {
            return List.of();
        }
        if (subQueryText == null || subQueryText.isBlank()) {
            return List.of();
        }

        String collection = milvusProperties.getUserKnowledgeCollection();
        int limit = Math.max(1, size);

        try {
            // user_id 强制隔离 + 只召回已入库就绪的切片
            String expr = String.format("user_id == \"%s\" and ready == true", uid);
            QueryParam query = QueryParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(expr)
                    .withLimit((long) limit)
                    .withOutFields(List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"))
                    .build();
            QueryResultsWrapper results = new QueryResultsWrapper(milvusClient.query(query).getData());
            return MilvusHitMapper.fromQuery(results.getRowRecords(), RagRecall.RecallSource.TEXT);
        } catch (Exception e) {
            log.warn("[UserKnowledgeMilvus] 文本检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * BGE-M3 向量相似度检索：先 embedding 再 kNN，user_id 隔离 + nprobe 可控。
     * <p>受 {@code fish.rag.recall.vector-leg-enabled} 控制，关闭时直接返回空列表。</p>
     */
    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!ragProperties.getRecall().isVectorLegEnabled()) {
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
            log.warn("[UserKnowledgeMilvus] embedding 失败: {}", e.getMessage());
            return List.of();
        }

        int k = Math.max(1, size);
        int nprobe = milvusProperties.getNprobe();
        String collection = milvusProperties.getUserKnowledgeCollection();

        try {
            List<List<Float>> queryVectors = List.of(toFloatList(vector));
            String expr = String.format("user_id == \"%s\" and ready == true", uid);

            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(queryVectors)
                    .withVectorFieldName("embedding")
                    .withTopK(k)
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr(expr)
                    .withParams("{\"nprobe\":" + Math.max(1, nprobe) + "}")
                    .withOutFields(List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"))
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(
                    milvusClient.search(search).getData().getResults());
            return MilvusHitMapper.fromSearch(results, RagRecall.RecallSource.VECTOR);
        } catch (Exception e) {
            log.warn("[UserKnowledgeMilvus] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * @return 当前登录用户 ID 的字符串形式；未登录时为 null
     */
    private static String currentUserIdString() {
        Long id = UserContextHolder.currentUserIdOrNull();
        return id == null ? null : String.valueOf(id);
    }

    /**
     * float[] → List&lt;Float&gt; 转换，供 Milvus SDK 的 kNN 查询向量入参使用。
     */
    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) {
            values.add(v);
        }
        return values;
    }
}
