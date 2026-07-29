package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.rag.config.MilvusProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
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
 * 用户私有文档知识库 Milvus 检索：BM25 全文 + BGE-M3 向量，user_id 强制隔离。
 *
 * <p>作为 {@link RagRecall.DocumentSearcher} 的 Milvus 实现，完成 ES → Milvus 迁移的召回层切换。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserKnowledgeMilvusSearcher implements RagRecall.DocumentSearcher {

    private final MilvusServiceClient milvusClient;
    private final MilvusClientV2 milvusClientV2;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MilvusProperties milvusProperties;
    private final RagProperties ragProperties;

    /**
     * lexical 检索：先在 Milvus 按租户过滤，再使用真实 query 对候选内容排序。
     */
    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        String uid = currentUserIdString();
        String workspaceId = currentWorkspaceId();
        if (uid == null || workspaceId == null) {
            return List.of();
        }
        if (subQueryText == null || subQueryText.isBlank()) {
            return List.of();
        }

        String collection = milvusProperties.getUserKnowledgeCollection();
        int limit = Math.max(1, size);

        try {
            // user_id 强制隔离 + 只召回已入库就绪的切片
            String expr = String.format("user_id == \"%s\" and workspace_id == \"%s\" and ready == true",
                    uid, escape(workspaceId));
            if (milvusProperties.getBm25().isEnabled()
                    && MilvusNativeBm25Support.serverSupportsBm25(milvusClientV2.getServerVersion())) {
                try {
                    List<MilvusNativeBm25Support.NativeHit> hits = MilvusNativeBm25Support.search(
                            milvusClientV2,
                            milvusProperties.getBm25().getUserKnowledgeCollection(),
                            milvusProperties.getBm25().getSparseField(),
                            subQueryText,
                            expr,
                            limit,
                            List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"));
                    return hits.stream().map(hit -> mapNativeHit(hit, RagRecall.RecallSource.TEXT)).toList();
                } catch (Exception nativeError) {
                    log.warn("[UserKnowledgeMilvus] 原生 BM25 查询失败，回退到兼容路径: {}", nativeError.getMessage());
                }
            }
            List<String> fields = List.of("id", "content", "contextualized_content", "doc_id", "chunk_index", "doc_name", "authority");
            List<io.milvus.response.QueryResultsWrapper.RowRecord> rows = MilvusLexicalSearchSupport.scan(
                    milvusClient, collection, expr, fields,
                    ragProperties.getRecall().getLexicalMaxScanRows(),
                    ragProperties.getRecall().getLexicalBatchSize());
            return MilvusLexicalSearchSupport.rank(rows, subQueryText, limit,
                    row -> MilvusHitMapper.fromQueryRow(row, RagRecall.RecallSource.TEXT),
                    UserKnowledgeMilvusSearcher::searchableText);
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
        String workspaceId = currentWorkspaceId();
        if (uid == null || workspaceId == null) {
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
        String collection = useBm25Collection()
                ? milvusProperties.getBm25().getUserKnowledgeCollection()
                : milvusProperties.getUserKnowledgeCollection();

        try {
            List<List<Float>> queryVectors = List.of(toFloatList(vector));
            String expr = String.format("user_id == \"%s\" and workspace_id == \"%s\" and ready == true",
                    uid, escape(workspaceId));

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

    private static String currentWorkspaceId() {
        return UserContextHolder.currentWorkspaceIdOrNull();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String searchableText(io.milvus.response.QueryResultsWrapper.RowRecord row) {
        return String.valueOf(row.get("content")) + " " + String.valueOf(row.get("contextualized_content"));
    }

    private static RagRecall.RecallHit mapNativeHit(MilvusNativeBm25Support.NativeHit hit,
                                                      RagRecall.RecallSource source) {
        Double authority = hit.number("authority");
        Long chunk = hit.longNumber("chunk_index");
        return new RagRecall.RecallHit(
                hit.id(), hit.string("content"), hit.score(), source,
                SourceAuthority.labelForKnowledge(authority, false), authority, null,
                hit.string("doc_id"), chunk == null ? null : chunk.intValue(), hit.string("doc_name"));
    }

    private boolean useBm25Collection() {
        return milvusProperties.getBm25().isEnabled()
                && MilvusNativeBm25Support.serverSupportsBm25(milvusClientV2.getServerVersion());
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
