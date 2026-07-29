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

    /** 原生 BM25 Function 配置；关闭时继续使用 Milvus 2.4 兼容回退路径。 */
    private Bm25 bm25 = new Bm25();

    @Data
    public static class Bm25 {

        /** 是否切换到 Milvus 原生 BM25；需要 Milvus Server 2.5+。 */
        private boolean enabled = true;

        /** BM25 用户知识目标 collection，不覆盖旧 collection。 */
        private String userKnowledgeCollection = "itops_user_knowledge_bm25";

        /** BM25 公共知识目标 collection，不覆盖旧 collection。 */
        private String publicKnowledgeCollection = "itops_public_knowledge_bm25";

        /** BM25 长期记忆目标 collection，不覆盖旧 collection。 */
        private String userMemoryCollection = "itops_user_memory_bm25";

        /** BM25 Function 生成的稀疏向量字段名。 */
        private String sparseField = "sparse_embedding";

        /** BM25 Function 名称。 */
        private String functionName = "text_bm25";

        /** BM25 的 k1 参数。 */
        private double k1 = 1.2;

        /** BM25 的 b 参数。 */
        private double b = 0.75;
    }
}
