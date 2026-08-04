package com.ai.itops.common.resilience;

/**
 * 熔断器名称统一常量。
 * <p>名称必须与 {@code application.yml} 中的 resilience4j.circuitbreaker.instances 保持一致。
 * 业务代码只引用常量，避免字符串拼写错误导致创建出未配置的默认熔断器。</p>
 */
public final class ResilienceConstants {

    private ResilienceConstants() {
    }

    /** LLM 流式调用熔断器。 */
    public static final String CB_LLM = "llm";

    /** Milvus 文本召回熔断器。 */
    public static final String CB_MILVUS_TEXT = "milvus-text";

    /** Embedding + Milvus 向量召回熔断器。 */
    public static final String CB_MILVUS_VECTOR = "milvus-vector";

    /** DashScope Reranker 精排熔断器。 */
    public static final String CB_RERANK = "rerank";

    /** Confluence 历史方案查询熔断器。 */
    public static final String CB_CONFLUENCE = "confluence";

    /** LLM 熔断时推给前端的固定降级文案。 */
    public static final String LLM_FALLBACK_MESSAGE = "服务暂时繁忙，请稍后再试";
}
