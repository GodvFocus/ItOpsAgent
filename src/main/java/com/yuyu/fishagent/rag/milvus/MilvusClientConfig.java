package com.yuyu.fishagent.rag.milvus;

import com.yuyu.fishagent.rag.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
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
}
