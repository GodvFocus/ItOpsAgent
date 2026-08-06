package com.ai.itops.rag.tracing;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.List;

/**
 * RAG 检索全链路质量日志实体（MyBatis-Plus）。
 *
 * <p>原为 ES {@code @Document}，在 ES → Milvus 迁移中改为 MySQL 存储。
 * 表名由 {@code @TableName} 指定，主键为 {@code trace_id}。</p>
 */
@Data
@TableName(value = "itops_rag_trace", autoResultMap = true)
public class RagTraceDocument {

    @TableId
    private String traceId;

    private String userId;

    private String sessionId;

    private String originalQuery;

    private String rewrittenQuery;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> expandedQueries;

    private int recallTotalHits;

    private int recallDedupedHits;

    private int fusionTopN;

    private int rerankInputCount;

    private double rerankTopScore;

    private double rerankLowestScore;

    private boolean hydeUsed;

    private int injectedFactCount;

    private int injectedTotalChars;

    private long recallLatencyMs;

    private long rerankLatencyMs;

    private long provenanceLatencyMs;

    private long expandLatencyMs;

    private long totalLatencyMs;

    private long createdAt;

    /** 本轮真正注入上下文的事实明细（id / 来源标签 / 分数），用于 per-fact 可观测与离线评估。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<InjectedFact> injectedFacts;

    /** 单条注入事实的溯源记录。 */
    @Data
    public static class InjectedFact {
        private String id;

        private String sourceLabel;

        private Double score;
    }
}
