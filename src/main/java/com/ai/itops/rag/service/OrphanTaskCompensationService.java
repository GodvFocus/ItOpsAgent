package com.ai.itops.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ai.itops.rag.config.KnowledgeProperties;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import com.ai.itops.rag.config.MilvusProperties;
import com.ai.itops.rag.pipeline.recall.MilvusKnowledgeChunkSupport;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿 PROCESSING 任务补偿：Worker 崩溃或长时间无 ACK 时，将 MySQL 标记为 FAILED。
 * <p>任务超时后先清理对应 Milvus 文档切片，再把 MySQL 任务标记为失败；Worker 侧仍可按同一 taskId 做幂等清理。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "fish.knowledge.compensation.enabled", havingValue = "true", matchIfMissing = true)
public class OrphanTaskCompensationService {

    private static final String WORKER_TIMEOUT_MSG = "worker timeout";

    private final DocumentMetadataMapper documentMetadataMapper;
    private final KnowledgeProperties knowledgeProperties;
    private final ObjectProvider<MilvusServiceClient> milvusProvider;
    private final ObjectProvider<io.milvus.v2.client.MilvusClientV2> milvusV2Provider;
    private final MilvusProperties milvusProperties;

    public OrphanTaskCompensationService(DocumentMetadataMapper documentMetadataMapper,
                                         KnowledgeProperties knowledgeProperties) {
        this(documentMetadataMapper, knowledgeProperties, null, null, null);
    }

    @Autowired
    public OrphanTaskCompensationService(DocumentMetadataMapper documentMetadataMapper,
                                         KnowledgeProperties knowledgeProperties,
                                         ObjectProvider<MilvusServiceClient> milvusProvider,
                                         ObjectProvider<io.milvus.v2.client.MilvusClientV2> milvusV2Provider,
                                         MilvusProperties milvusProperties) {
        this.documentMetadataMapper = documentMetadataMapper;
        this.knowledgeProperties = knowledgeProperties;
        this.milvusProvider = milvusProvider;
        this.milvusV2Provider = milvusV2Provider;
        this.milvusProperties = milvusProperties;
    }

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
                deleteMilvusChunks(row);
                markFailedWorkerTimeout(row.getTaskId());
                log.info("[OrphanCompensation] 已补偿 taskId={} scope={} userId={}",
                        row.getTaskId(), row.getScopeType(), row.getUserId());
            } catch (Exception e) {
                log.warn("[OrphanCompensation] 单条补偿失败 taskId={}: {}", row.getTaskId(), e.getMessage());
            }
        }
    }

    private void deleteMilvusChunks(DocumentMetadata row) {
        MilvusServiceClient client = milvusProvider == null ? null : milvusProvider.getIfAvailable();
        if (client == null || milvusProperties == null || row == null || row.getTaskId() == null
                || row.getTaskId().isBlank()) {
            return;
        }
        boolean publicScope = DocumentMetadata.VISIBILITY_WORKSPACE.equalsIgnoreCase(row.getVisibility());
        String collection = MilvusKnowledgeChunkSupport.collection(milvusProperties,
                milvusV2Provider == null ? null : milvusV2Provider.getIfAvailable(), publicScope);
        try {
            client.delete(DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr("doc_id == \"" + escape(row.getTaskId().trim()) + "\"")
                    .build());
        } catch (Exception e) {
            log.warn("[OrphanCompensation] Milvus 孤儿切片清理失败 taskId={}, collection={}: {}",
                    row.getTaskId(), collection, e.getMessage());
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
