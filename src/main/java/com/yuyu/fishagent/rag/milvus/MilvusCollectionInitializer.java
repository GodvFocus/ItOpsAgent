package com.yuyu.fishagent.rag.milvus;

import com.yuyu.fishagent.rag.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
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

    private void ensureCollection(String name) {
        HasCollectionParam has = HasCollectionParam.newBuilder().withCollectionName(name).build();
        if (milvusClient.hasCollection(has).getData()) {
            log.debug("Milvus collection 已存在: {}", name);
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

        log.info("已创建 Milvus collection: {}", name);
    }
}
