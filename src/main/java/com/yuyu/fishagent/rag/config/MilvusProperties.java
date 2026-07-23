package com.yuyu.fishagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus 连接与 Collection 配置，对应 {@code fish.milvus.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.milvus")
public class MilvusProperties {

    /** Milvus gRPC 地址。 */
    private String uri = "milvus-standalone:19530";

    /** Milvus 认证 token（可选）。 */
    private String token = "";

    /** 用户文档知识 Collection。 */
    private String userKnowledgeCollection = "itops_user_knowledge";

    /** 公共文档知识 Collection。 */
    private String publicKnowledgeCollection = "itops_public_knowledge";

    /** 长期对话记忆 Collection。 */
    private String userMemoryCollection = "itops_user_memory";

    /** ANN 检索的 nprobe 参数。 */
    private int nprobe = 16;

    /** 向量维度（BGE-M3 = 1024）。 */
    private int embeddingDimension = 1024;
}
