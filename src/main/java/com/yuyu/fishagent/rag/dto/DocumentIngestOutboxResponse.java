package com.yuyu.fishagent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档摄入 DLQ / outbox 观测视图。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIngestOutboxResponse {

    private String taskId;

    private String status;

    private Integer attemptCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime publishedAt;

    private LocalDateTime deadLetteredAt;

    private String lastError;

    private LocalDateTime updatedAt;
}
