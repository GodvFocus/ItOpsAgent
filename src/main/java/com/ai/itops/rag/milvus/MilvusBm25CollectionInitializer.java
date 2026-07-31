package com.ai.itops.rag.milvus;

import com.ai.itops.rag.config.MilvusProperties;
import com.ai.itops.rag.pipeline.recall.MilvusNativeBm25Support;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 创建原生 BM25 所需的独立 collection。
 *
 * <p>该组件只创建带 {@code _bm25} 后缀的目标 collection，旧 collection 永不由它删除或改写。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusBm25CollectionInitializer {

    private final MilvusClientV2 client;
    private final MilvusProperties properties;

    @PostConstruct
    public void init() {
        if (!properties.getBm25().isEnabled()) {
            log.info("原生 BM25 未启用，保留 Milvus 2.4 兼容 lexical 回退路径");
            return;
        }
        if (!MilvusNativeBm25Support.serverSupportsBm25(client.getServerVersion())) {
            log.warn("Milvus Server [{}] 低于 2.5，暂不创建原生 BM25 collection；请升级 Server 后重启");
            return;
        }

        List<String> names = Arrays.asList(
                properties.getBm25().getUserKnowledgeCollection(),
                properties.getBm25().getPublicKnowledgeCollection(),
                properties.getBm25().getUserMemoryCollection());
        for (String name : names) {
            try {
                ensureCollection(name);
            } catch (Exception e) {
                log.warn("原生 BM25 collection [{}] 初始化失败，将继续使用旧 collection: {}", name, e.getMessage());
            }
        }
    }

    private void ensureCollection(String name) {
        if (Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder().collectionName(name).build()))) {
            client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
            log.info("原生 BM25 collection 已存在并加载: {}", name);
            return;
        }

        int dim = properties.getEmbeddingDimension();
        String sparseField = properties.getBm25().getSparseField();
        CreateCollectionReq.CollectionSchema schema = MilvusClientV2.CreateSchema();
        schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar)
                .isPrimaryKey(true).maxLength(128).build());
        schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar)
                .maxLength(65535).enableAnalyzer(true).build());
        schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector)
                .dimension(dim).build());
        schema.addField(AddFieldReq.builder().fieldName(sparseField).dataType(DataType.SparseFloatVector).build());
        addMetadataFields(schema);
        schema.addFunction(CreateCollectionReq.Function.builder()
                .name(properties.getBm25().getFunctionName())
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of("content"))
                .outputFieldNames(List.of(sparseField))
                .build());

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(name)
                .description("Fish-Agent native BM25: " + name)
                .collectionSchema(schema)
                .build());

        createIndex(name, IndexParam.builder().fieldName("embedding")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("nlist", 128)).build());
        createIndex(name, IndexParam.builder().fieldName(sparseField)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .extraParams(Map.of(
                        "inverted_index_algo", "DAAT_MAXSCORE",
                        "bm25_k1", properties.getBm25().getK1(),
                        "bm25_b", properties.getBm25().getB())).build());
        client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
        log.info("已创建并加载原生 BM25 collection: {}", name);
    }

    private void createIndex(String collection, IndexParam indexParam) {
        client.createIndex(CreateIndexReq.builder()
                .collectionName(collection)
                .indexParams(List.of(indexParam))
                .sync(true)
                .build());
    }

    private static void addMetadataFields(CreateCollectionReq.CollectionSchema schema) {
        schema.addField(AddFieldReq.builder().fieldName("doc_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("chunk_index").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("doc_name").dataType(DataType.VarChar).maxLength(512).build());
        schema.addField(AddFieldReq.builder().fieldName("file_type").dataType(DataType.VarChar).maxLength(32).build());
        schema.addField(AddFieldReq.builder().fieldName("page_number").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("token_count").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("authority").dataType(DataType.Float).build());
        schema.addField(AddFieldReq.builder().fieldName("doc_created_at").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("context_prefix").dataType(DataType.VarChar).maxLength(2048).build());
        schema.addField(AddFieldReq.builder().fieldName("contextualized_content").dataType(DataType.VarChar).maxLength(65535).build());
        schema.addField(AddFieldReq.builder().fieldName("ready").dataType(DataType.Bool).build());
        schema.addField(AddFieldReq.builder().fieldName("created_at").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("user_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("workspace_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("visibility").dataType(DataType.VarChar).maxLength(32).build());
        schema.addField(AddFieldReq.builder().fieldName("source_type").dataType(DataType.VarChar).maxLength(32).build());
        schema.addField(AddFieldReq.builder().fieldName("superseded").dataType(DataType.Bool).build());
    }
}
