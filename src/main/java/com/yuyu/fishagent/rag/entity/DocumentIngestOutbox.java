package com.yuyu.fishagent.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档摄入事务外盒：业务任务写库后，由独立 dispatcher 异步投递 Redis Stream。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("document_ingest_outbox")
public class DocumentIngestOutbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRY = "RETRY";
    public static final String STATUS_DISPATCHING = "DISPATCHING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DEAD = "DEAD";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private String taskId;

    @TableField("stream_key")
    private String streamKey;

    @TableField("minio_path")
    private String minioPath;

    @TableField("workspace_id")
    private String workspaceId;

    private String visibility;

    @TableField("user_id")
    private Long userId;

    @TableField("file_name")
    private String fileName;

    @TableField("file_size")
    private Long fileSize;

    @TableField("trace_id")
    private String traceId;

    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    @TableField("published_at")
    private LocalDateTime publishedAt;

    @TableField("dead_lettered_at")
    private LocalDateTime deadLetteredAt;

    @TableField("stream_record_id")
    private String streamRecordId;

    @TableField("last_error")
    private String lastError;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
