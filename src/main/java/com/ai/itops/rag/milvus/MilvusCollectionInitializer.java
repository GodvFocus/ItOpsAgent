package com.ai.itops.rag.milvus;

import com.ai.itops.rag.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Milvus Collection 初始化。与客户端装配分离，避免连接失败时阻塞应用启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusCollectionInitializer {

    private final MilvusServiceClient milvusClient;
    private final MilvusProperties properties;

    @PostConstruct
    public void init() {
        List<String> collections = Arrays.asList(
                properties.getUserKnowledgeCollection(),
                properties.getPublicKnowledgeCollection(),
                properties.getUserMemoryCollection()
        );
        boolean allSucceeded = true;
        for (String name : collections) {
            try {
                ensureCollection(name);
            } catch (Exception e) {
                allSucceeded = false;
                log.warn("Milvus collection [{}] 初始化失败，将在首次使用时重试: {}", name, e.getMessage());
            }
        }
        if (allSucceeded) {
            log.info("所有 Milvus collection 初始化完成");
        }
    }

    private static final List<String> REQUIRED_FIELDS = List.of("workspace_id", "visibility", "source_type", "superseded");

    private void ensureCollection(String name) {
        HasCollectionParam has = HasCollectionParam.newBuilder().withCollectionName(name).build();
        if (milvusClient.hasCollection(has).getData()) {
            log.debug("Milvus collection 已存在: {}", name);
            checkSchema(name);
            loadCollection(name);
            return;
        }

        int dim = properties.getEmbeddingDimension();

        List<FieldType> fields = Arrays.asList(
                FieldType.newBuilder()
                        .withName("id")
                        .withDataType(DataType.VarChar)
                        .withPrimaryKey(true)
                        .withMaxLength(128)
                        .build(),
                FieldType.newBuilder()
                        .withName("content")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .addTypeParam("enable_analyzer", "true")
                        .build(),
                FieldType.newBuilder()
                        .withName("embedding")
                        .withDataType(DataType.FloatVector)
                        .withDimension(dim)
                        .build(),
                FieldType.newBuilder()
                        .withName("doc_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build(),
                FieldType.newBuilder()
                        .withName("chunk_index")
                        .withDataType(DataType.Int64)
                        .build(),
                FieldType.newBuilder()
                        .withName("doc_name")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(512)
                        .build(),
                FieldType.newBuilder()
                        .withName("file_type")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
                        .build(),
                FieldType.newBuilder()
                        .withName("page_number")
                        .withDataType(DataType.Int64)
                        .build(),
                FieldType.newBuilder()
                        .withName("token_count")
                        .withDataType(DataType.Int64)
                        .build(),
                FieldType.newBuilder()
                        .withName("authority")
                        .withDataType(DataType.Float)
                        .build(),
                FieldType.newBuilder()
                        .withName("doc_created_at")
                        .withDataType(DataType.Int64)
                        .build(),
                FieldType.newBuilder()
                        .withName("context_prefix")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(2048)
                        .build(),
                FieldType.newBuilder()
                        .withName("contextualized_content")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .build(),
                FieldType.newBuilder()
                        .withName("ready")
                        .withDataType(DataType.Bool)
                        .build(),
                FieldType.newBuilder()
                        .withName("created_at")
                        .withDataType(DataType.Int64)
                        .build(),
                FieldType.newBuilder()
                        .withName("user_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build(),
                FieldType.newBuilder()
                        .withName("workspace_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .build(),
                FieldType.newBuilder()
                        .withName("visibility")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
                        .build(),
                FieldType.newBuilder()
                        .withName("source_type")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
                        .build(),
                FieldType.newBuilder()
                        .withName("superseded")
                        .withDataType(DataType.Bool)
                        .build()
        );

        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(name)
                .withFieldTypes(fields)
                .withDescription("Fish-Agent knowledge: " + name)
                .build();
        milvusClient.createCollection(param);

        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(name)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":128}")
                .build();
        milvusClient.createIndex(indexParam);

        loadCollection(name);

        log.info("已创建 Milvus collection: {}", name);
    }

    /**
     * 检查已有 collection schema 是否包含必要字段，防止旧版本无字段的 collection 导致查询失败。
     */
    private void checkSchema(String name) {
        try {
            var resp = milvusClient.describeCollection(
                    DescribeCollectionParam.newBuilder().withCollectionName(name).build());
            var schema = resp.getData().getSchema();
            var fieldNames = schema.getFieldsList().stream()
                    .map(f -> f.getName())
                    .toList();
            List<String> missing = REQUIRED_FIELDS.stream()
                    .filter(f -> !fieldNames.contains(f))
                    .toList();
            if (!missing.isEmpty()) {
                log.warn("Milvus collection [{}] Schema 缺少字段 {}，查询将失败。"
                                + "请手动执行 Drop Collection 后重启重建:\n\t"
                                + "milvusClient.dropCollection(\"{}\")",
                        name, missing, name);
            }
        } catch (Exception e) {
            log.debug("Milvus collection [{}] Schema 检查异常: {}", name, e.getMessage());
        }
    }

    private void loadCollection(String name) {
        LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
                .withCollectionName(name)
                .build();
        milvusClient.loadCollection(loadParam);
        log.info("已加载 Milvus collection: {}", name);
    }
}
