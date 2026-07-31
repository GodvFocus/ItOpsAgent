package com.ai.itops.rag.pipeline.recall;

import com.ai.itops.auth.context.UserContextHolder;
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
 * 公共知识库 Milvus 检索：不带 user_id 过滤，全员可见。
 * <p>与 {@link UserKnowledgeMilvusSearcher} 的区别仅在于：不按用户过滤，仅过滤 ready == true。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicKnowledgeMilvusSearcher implements RagRecall.DocumentSearcher {

    private final MilvusServiceClient milvusClient;
    private final MilvusClientV2 milvusClientV2;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MilvusProperties milvusProperties;
    private final RagProperties ragProperties;

    /**
     * lexical 检索：先在 Milvus 按 workspace 过滤，再使用真实 query 对候选内容排序。
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
            String expr = String.format("workspace_id == \"%s\" and ready == true", escape(workspaceId));
            if (milvusProperties.getBm25().isEnabled()
                    && MilvusNativeBm25Support.serverSupportsBm25(milvusClientV2.getServerVersion())) {
                try {
                    List<MilvusNativeBm25Support.NativeHit> hits = MilvusNativeBm25Support.search(
                            milvusClientV2,
                            milvusProperties.getBm25().getPublicKnowledgeCollection(),
                            milvusProperties.getBm25().getSparseField(),
                            subQueryText,
                            expr,
                            limit,
                            List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"));
                    return hits.stream().map(hit -> mapNativeHit(hit, RagRecall.RecallSource.TEXT)).toList();
                } catch (Exception nativeError) {
                    log.warn("[PublicKnowledgeMilvus] 原生 BM25 查询失败，回退到兼容路径: {}", nativeError.getMessage());
                }
            }
            List<String> fields = List.of("id", "content", "contextualized_content", "doc_id", "chunk_index", "doc_name", "authority");
            List<io.milvus.response.QueryResultsWrapper.RowRecord> rows = MilvusLexicalSearchSupport.scan(
                    milvusClient, collection, expr, fields,
                    ragProperties.getRecall().getLexicalMaxScanRows(),
                    ragProperties.getRecall().getLexicalBatchSize());
            return MilvusLexicalSearchSupport.rank(rows, subQueryText, limit,
                    row -> MilvusHitMapper.fromQueryRow(row, RagRecall.RecallSource.TEXT),
                    PublicKnowledgeMilvusSearcher::searchableText);
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
        String collection = useBm25Collection()
                ? milvusProperties.getBm25().getPublicKnowledgeCollection()
                : milvusProperties.getPublicKnowledgeCollection();

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
}
