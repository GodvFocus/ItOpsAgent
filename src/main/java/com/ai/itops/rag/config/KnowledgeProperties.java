package com.ai.itops.rag.config;

import com.ai.itops.common.redis.RedisKeys;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库索引与入队配置：{@code fish.knowledge.*}。
 * <p>{@code scope_type=PRIVATE} 的文档切片写入 {@link #userKnowledgeIndexName}（用户个人知识库）；
 * {@code PUBLIC} 写入 {@link #publicIndexName}（组织/公共知识库）。与 {@code fish-user-memory}（对话事实，Java 写入）分离。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.knowledge")
public class KnowledgeProperties {

    /**
     * 孤儿 PROCESSING 任务补偿（Java 定时任务）：{@code fish.knowledge.compensation.*}。
     * <p>用于 Worker 崩溃后清理 ES 残留并将 MySQL 标记失败。</p>
     */
    @Data
    public static class CompensationProperties {
        /** 是否启用补偿调度；false 时不注册补偿 Bean（见 {@code @ConditionalOnProperty}）。 */
        private boolean enabled = true;
        /** 超过该分钟数仍停留在 PROCESSING 则视为孤儿。 */
        private int timeoutMinutes = 10;
    }

    /**
     * 文档摄入 Transactional Outbox 参数。
     */
    @Data
    public static class OutboxProperties {
        /** 总开关：关闭后 dispatcher 不注册。 */
        private boolean enabled = true;
        /** 单轮最多拉取多少条待投递事件。 */
        private int dispatcherBatchSize = 20;
        /** 后台 dispatcher 的固定轮询间隔（毫秒）。 */
        private long dispatcherFixedDelayMs = 5_000L;
        /** 最大投递次数，达到后进入 DLQ。 */
        private int maxAttempts = 8;
        /** 指数退避起始秒数。 */
        private int retryBaseSeconds = 5;
        /** 指数退避上限秒数。 */
        private int retryMaxSeconds = 300;
        /** DISPATCHING 超过该秒数仍未完成，允许其他轮次重新认领。 */
        private int dispatchingTimeoutSeconds = 60;
        /** DLQ 列表接口最大返回条数。 */
        private int dlqListLimit = 100;
    }

    /** Milvus 公有知识库 Collection 名。 */
    private String publicIndexName = "itops_public_knowledge";

    /**
     * 用户上传文档切片 Collection（与对话长期事实 Collection itops_user_memory 分离）。
     * 环境变量 {@code KNOWLEDGE_USER_INDEX} 与 Python Worker 对齐。
     */
    private String userKnowledgeIndexName = "itops_user_knowledge";

    /**
     * 文档解析任务投递的 Redis Stream 键名（与 Python Worker 约定一致）。
     */
    private String documentIngestStreamKey = RedisKeys.DOC_STREAM;

    /** 孤儿任务补偿参数。 */
    private CompensationProperties compensation = new CompensationProperties();

    /** 可靠事件投递参数。 */
    private OutboxProperties outbox = new OutboxProperties();
}
