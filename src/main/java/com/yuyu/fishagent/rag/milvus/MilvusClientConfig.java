package com.yuyu.fishagent.rag.milvus;

import com.yuyu.fishagent.rag.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端装配。Collection 初始化在 {@link MilvusCollectionInitializer} 中完成。
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

    /** 原生 BM25 Function 使用 Milvus Java SDK v2 客户端。 */
    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClientV2() {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(properties.getUri());
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.token(properties.getToken());
        }
        return new MilvusClientV2(builder.build());
    }
}
