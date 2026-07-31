package com.ai.itops.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ai.itops.rag.config.KnowledgeProperties;
import com.ai.itops.rag.entity.DocumentIngestOutbox;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentIngestOutboxMapper;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 独立 dispatcher：轮询 outbox，将待投递事件异步推入 Redis Stream。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fish.knowledge.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestOutboxDispatcher {

    private final DocumentIngestOutboxMapper documentIngestOutboxMapper;
    private final DocumentMetadataMapper documentMetadataMapper;
    private final DocumentIngestOutboxService documentIngestOutboxService;
    private final KnowledgeProperties knowledgeProperties;

    @Scheduled(fixedDelayString = "${fish.knowledge.outbox.dispatcher-fixed-delay-ms:5000}")
    public void dispatchDueEvents() {
        try {
            runDispatchLoop();
        } catch (Exception e) {
            log.warn("[DocOutbox] dispatcher 调度失败: {}", e.getMessage());
        }
    }

    void runDispatchLoop() {
        KnowledgeProperties.OutboxProperties props = knowledgeProperties.getOutbox();
        int batchSize = Math.max(1, props.getDispatcherBatchSize());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dispatchingExpireAt = now.minusSeconds(Math.max(5, props.getDispatchingTimeoutSeconds()));
        List<DocumentIngestOutbox> batch = documentIngestOutboxMapper.selectDueForDispatch(now, dispatchingExpireAt, batchSize);
        for (DocumentIngestOutbox row : batch) {
            dispatchOne(row, dispatchingExpireAt);
        }
    }

    void dispatchOne(DocumentIngestOutbox row, LocalDateTime dispatchingExpireAt) {
        if (row == null || row.getId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int claimed = documentIngestOutboxMapper.claimForDispatch(
                row.getId(),
                row.getAttemptCount() == null ? 0 : row.getAttemptCount(),
                now,
                dispatchingExpireAt
        );
        if (claimed == 0) {
            return;
        }

        try {
            RecordId recordId = documentIngestOutboxService.publish(row);
            documentIngestOutboxMapper.markPublished(row.getId(), recordId == null ? null : recordId.getValue(), LocalDateTime.now());
            log.info("[DocOutbox] 已投递 taskId={}, attempt={}", row.getTaskId(), safeAttempt(row) + 1);
        } catch (Exception e) {
            handleFailure(row, e, LocalDateTime.now());
        }
    }

    private void handleFailure(DocumentIngestOutbox row, Exception e, LocalDateTime now) {
        String message = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 2000);
        int currentAttempt = safeAttempt(row) + 1;
        KnowledgeProperties.OutboxProperties props = knowledgeProperties.getOutbox();
        if (currentAttempt >= Math.max(1, props.getMaxAttempts())) {
            documentIngestOutboxMapper.markDead(row.getId(), message, now);
            markDocumentFailedIfStillPending(row.getTaskId(), message, now);
            log.error("[DocOutbox] 投递进入 DLQ taskId={}, attempt={}, error={}", row.getTaskId(), currentAttempt, message);
            return;
        }
        LocalDateTime nextRetryAt = now.plusSeconds(computeBackoffSeconds(currentAttempt));
        documentIngestOutboxMapper.markRetry(row.getId(), nextRetryAt, message, now);
        log.warn("[DocOutbox] 投递失败，等待重试 taskId={}, attempt={}, nextRetryAt={}, error={}",
                row.getTaskId(), currentAttempt, nextRetryAt, message);
    }

    private void markDocumentFailedIfStillPending(String taskId, String error, LocalDateTime now) {
        documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                .eq(DocumentMetadata::getTaskId, taskId)
                .eq(DocumentMetadata::getStatus, DocumentMetadata.STATUS_PENDING)
                .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED)
                .set(DocumentMetadata::getErrorMsg, truncate("事件投递进入 DLQ: " + error, 2000))
                .set(DocumentMetadata::getUpdatedAt, now));
    }

    long computeBackoffSeconds(int attempt) {
        KnowledgeProperties.OutboxProperties props = knowledgeProperties.getOutbox();
        long base = Math.max(1, props.getRetryBaseSeconds());
        long cap = Math.max(base, props.getRetryMaxSeconds());
        int exponent = Math.max(0, Math.min(20, attempt - 1));
        long delay;
        try {
            delay = Math.multiplyExact(base, 1L << exponent);
        } catch (ArithmeticException ex) {
            delay = cap;
        }
        return Math.min(cap, delay);
    }

    private static int safeAttempt(DocumentIngestOutbox row) {
        return row.getAttemptCount() == null ? 0 : Math.max(0, row.getAttemptCount());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
