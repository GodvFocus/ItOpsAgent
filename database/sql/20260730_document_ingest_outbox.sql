CREATE TABLE IF NOT EXISTS `document_ingest_outbox`
(
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_id`          VARCHAR(64)  NOT NULL COMMENT '关联 document_metadata.task_id，保证每个文档任务只有一条活跃 outbox 记录',
    `stream_key`       VARCHAR(128) NOT NULL COMMENT '目标 Redis Stream 键，便于后续切流/回放',
    `minio_path`       VARCHAR(512) NOT NULL COMMENT 'RustFS/MinIO 中的原始文件路径',
    `workspace_id`     VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '工作区隔离维度',
    `visibility`       VARCHAR(16)  NOT NULL COMMENT '文档可见性：PRIVATE / WORKSPACE',
    `user_id`          BIGINT       NOT NULL COMMENT '上传者用户 ID',
    `file_name`        VARCHAR(255) NOT NULL COMMENT '原始文件名，供 Worker / 观测排障',
    `file_size`        BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    `trace_id`         VARCHAR(128) NOT NULL COMMENT '跨 Java / Python 的链路追踪 ID',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RETRY / DISPATCHING / PUBLISHED / DEAD',
    `attempt_count`    INT          NOT NULL DEFAULT 0 COMMENT '累计投递次数',
    `next_retry_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次允许投递时间',
    `published_at`     DATETIME              DEFAULT NULL COMMENT '成功 XADD 的时间',
    `dead_lettered_at` DATETIME              DEFAULT NULL COMMENT '进入 DLQ 的时间',
    `stream_record_id` VARCHAR(64)           DEFAULT NULL COMMENT '成功写入 Redis Stream 后返回的 RecordId',
    `last_error`       TEXT                  DEFAULT NULL COMMENT '最近一次投递失败原因',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_task_id` (`task_id`),
    KEY `idx_outbox_status_retry` (`status`, `next_retry_at`),
    KEY `idx_outbox_status_updated` (`status`, `updated_at`),
    KEY `idx_outbox_dead_lettered_at` (`dead_lettered_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文档摄入事务外盒（Transactional Outbox）';
