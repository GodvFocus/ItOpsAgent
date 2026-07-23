package com.yuyu.fishagent.rag.milvus;

import com.yuyu.fishagent.rag.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.grpc.DataType;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Milvus 客户端装配与 Collection 初始化。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MilvusClientConfig {

    private final MilvusProperties properties;

    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withUri(properties.getUri());
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.withToken(properties.getToken());
        }
        return new MilvusServiceClient(builder.build());
    }

    @PostConstruct
    public void initCollections() {
        MilvusServiceClient client = milvusServiceClient();
        List<String> collections = Arrays.asList(
                properties.getUserKnowledgeCollection(),
                properties.getPublicKnowledgeCollection(),
                properties.getUserMemoryCollection()
        );
        for (String name : collections) {
            ensureCollection(client, name);
        }
    }

    private void ensureCollection(MilvusServiceClient client, String name) {
        HasCollectionParam has = HasCollectionParam.newBuilder().withCollectionName(name).build();
        if (client.hasCollection(has).getData()) {
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
        client.createCollection(param);

        // 为 embedding 字段创建 IVF_FLAT 索引
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(name)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":128}")
                .build();
        client.createIndex(indexParam);

        log.info("已创建 Milvus collection: {}", name);
    }
}
