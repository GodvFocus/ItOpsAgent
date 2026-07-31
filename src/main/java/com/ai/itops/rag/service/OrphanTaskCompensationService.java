package com.ai.itops.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ai.itops.rag.config.KnowledgeProperties;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿 PROCESSING 任务补偿：Worker 崩溃或长时间无 ACK 时，将 MySQL 标记为 FAILED。
 * <p>ES 切片已迁移至 Milvus，Milvus 侧的孤儿切片由 Python Worker 侧负责清理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fish.knowledge.compensation.enabled", havingValue = "true", matchIfMissing = true)
public class OrphanTaskCompensationService {

    private static final String WORKER_TIMEOUT_MSG = "worker timeout";

    private final DocumentMetadataMapper documentMetadataMapper;
    private final KnowledgeProperties knowledgeProperties;

    /**
     * 每分钟执行一次；单轮调度异常不影响后续触发。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void compensateOrphanTasks() {
        try {
            runCompensation();
        } catch (Exception e) {
            log.warn("[OrphanCompensation] 本轮调度失败（已吞，不影响主业务）: {}", e.getMessage());
        }
    }

    private void runCompensation() {
        int timeoutMinutes = knowledgeProperties.getCompensation().getTimeoutMinutes();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, timeoutMinutes));

        List<DocumentMetadata> orphans = documentMetadataMapper.selectList(
                Wrappers.<DocumentMetadata>lambdaQuery()
                        .eq(DocumentMetadata::getStatus, DocumentMetadata.STATUS_PROCESSING)
                        .lt(DocumentMetadata::getUpdatedAt, cutoff));

        if (orphans.isEmpty()) {
            return;
        }

        log.info("[OrphanCompensation] 发现孤儿任务 count={}, timeoutMinutes={}, cutoff={}",
                orphans.size(), timeoutMinutes, cutoff);

        for (DocumentMetadata row : orphans) {
            try {
                // TODO: ES → Milvus 迁移后，Milvus 孤儿切片清理由 Python Worker 侧负责
                markFailedWorkerTimeout(row.getTaskId());
                log.info("[OrphanCompensation] 已补偿 taskId={} scope={} userId={}",
                        row.getTaskId(), row.getScopeType(), row.getUserId());
            } catch (Exception e) {
                log.warn("[OrphanCompensation] 单条补偿失败 taskId={}: {}", row.getTaskId(), e.getMessage());
            }
        }
    }

    private void markFailedWorkerTimeout(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                .eq(DocumentMetadata::getTaskId, taskId.trim())
                .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED)
                .set(DocumentMetadata::getErrorMsg, WORKER_TIMEOUT_MSG)
                .set(DocumentMetadata::getUpdatedAt, LocalDateTime.now()));
    }
}
