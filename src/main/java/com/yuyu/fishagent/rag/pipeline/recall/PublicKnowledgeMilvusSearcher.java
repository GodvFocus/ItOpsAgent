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
 * 公共知识库 Milvus 检索：不带 user_id 过滤，全员可见。
 * <p>与 {@link UserKnowledgeMilvusSearcher} 的区别仅在于：不按用户过滤，仅过滤 ready == true。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicKnowledgeMilvusSearcher implements RagRecall.DocumentSearcher {

    private final MilvusServiceClient milvusClient;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MilvusProperties milvusProperties;
    private final RagProperties ragProperties;

    /**
     * 公有知识库标量全文检索（Milvus query），仅过滤 ready == true。
     */
    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        if (subQueryText == null || subQueryText.isBlank()) {
            return List.of();
        }
        String workspaceId = currentWorkspaceId();
        if (workspaceId == null) {
            return List.of();
        }
        String collection = milvusProperties.getPublicKnowledgeCollection();
        int limit = Math.max(1, size);

        try {
            QueryParam query = QueryParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(String.format("workspace_id == \"%s\" and ready == true", escape(workspaceId)))
                    .withLimit((long) limit)
                    .withOutFields(List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"))
                    .build();
            QueryResultsWrapper results = new QueryResultsWrapper(milvusClient.query(query).getData());
            return MilvusHitMapper.fromQuery(results.getRowRecords(), RagRecall.RecallSource.TEXT);
        } catch (Exception e) {
            log.warn("[PublicKnowledgeMilvus] 文本检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 公有知识库向量检索（Milvus ANN search），仅过滤 ready == true。
     */
    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!ragProperties.getRecall().isVectorLegEnabled()) {
            return List.of();
        }
        String workspaceId = currentWorkspaceId();
        if (workspaceId == null) {
            return List.of();
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null || textToEmbed == null || textToEmbed.isBlank()) {
            return List.of();
        }

        List<Float> queryVector;
        try {
            queryVector = toFloatList(embeddingModel.embed(textToEmbed.trim()));
        } catch (Exception e) {
            log.warn("[PublicKnowledgeMilvus] embedding 失败: {}", e.getMessage());
            return List.of();
        }

        int k = Math.max(1, size);
        String collection = milvusProperties.getPublicKnowledgeCollection();

        try {
            List<List<Float>> queryVectors = List.of(queryVector);
            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(queryVectors)
                    .withVectorFieldName("embedding")
                    .withTopK(k)
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr(String.format("workspace_id == \"%s\" and ready == true", escape(workspaceId)))
                    .withParams("{\"nprobe\":" + Math.max(1, milvusProperties.getNprobe()) + "}")
                    .withOutFields(List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"))
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(
                    milvusClient.search(search).getData().getResults());
            return MilvusHitMapper.fromSearch(results, RagRecall.RecallSource.VECTOR);
        } catch (Exception e) {
            log.warn("[PublicKnowledgeMilvus] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将 {@code float[]} 转为 {@code List<Float>}，供 Milvus SDK 使用。
     */
    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) {
            values.add(v);
        }
        return values;
    }

    private static String currentWorkspaceId() {
        return UserContextHolder.currentWorkspaceIdOrNull();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
