package com.yuyu.fishagent.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.common.trace.TraceConstants;
import com.yuyu.fishagent.rag.config.KnowledgeProperties;
import com.yuyu.fishagent.rag.dto.DocumentIngestOutboxResponse;
import com.yuyu.fishagent.rag.entity.DocumentIngestOutbox;
import com.yuyu.fishagent.rag.entity.DocumentMetadata;
import com.yuyu.fishagent.rag.mapper.DocumentIngestOutboxMapper;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 文档摄入事务外盒：负责同事务写入 outbox、重放入口和 Redis Stream payload 构建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestOutboxService {

    private final DocumentMetadataMapper documentMetadataMapper;
    private final DocumentIngestOutboxMapper documentIngestOutboxMapper;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final KnowledgeProperties knowledgeProperties;

    @Transactional(rollbackFor = Exception.class)
    public void createPendingTask(DocumentMetadata row) {
        documentMetadataMapper.insert(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void createPendingTaskWithOutbox(DocumentMetadata row, String traceId) {
        documentMetadataMapper.insert(row);
        insertOutbox(row, traceId);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean enqueueExistingTask(DocumentMetadata row, String traceId) {
        if (hasOutbox(row.getTaskId())) {
            return false;
        }
        insertOutbox(row, traceId);
        return true;
    }

    public boolean hasOutbox(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        return documentIngestOutboxMapper.selectByTaskId(taskId.trim()) != null;
    }

    public DocumentIngestOutbox requireOutbox(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        DocumentIngestOutbox row = documentIngestOutboxMapper.selectByTaskId(taskId.trim());
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "outbox 任务不存在");
        }
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public void requestReplay(String taskId) {
        DocumentIngestOutbox row = requireOutbox(taskId);
        LocalDateTime now = LocalDateTime.now();
        documentIngestOutboxMapper.resetForReplay(row.getTaskId(), now);
        documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                .eq(DocumentMetadata::getTaskId, row.getTaskId())
                .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_PENDING)
                .set(DocumentMetadata::getErrorMsg, null)
                .set(DocumentMetadata::getUpdatedAt, now));
        log.info("[DocOutbox] 已请求人工重放 taskId={}, previousStatus={}", row.getTaskId(), row.getStatus());
    }

    public List<DocumentIngestOutboxResponse> listDeadLetters(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, knowledgeProperties.getOutbox().getDlqListLimit()));
        return documentIngestOutboxMapper.selectDeadLetters(safeLimit).stream()
                .map(row -> new DocumentIngestOutboxResponse(
                        row.getTaskId(),
                        row.getStatus(),
                        row.getAttemptCount(),
                        row.getNextRetryAt(),
                        row.getPublishedAt(),
                        row.getDeadLetteredAt(),
                        row.getLastError(),
                        row.getUpdatedAt()
                ))
                .toList();
    }

    public RecordId publish(DocumentIngestOutbox row) {
        if (row == null) {
            throw new IllegalArgumentException("outbox 不能为空");
        }
        StringRedisTemplate redis = stringRedisTemplateProvider.getIfAvailable();
        if (redis == null) {
            throw new IllegalArgumentException("StringRedisTemplate 不可用（请检查 Redis 配置）");
        }
        MapRecord<String, String, String> record = StreamRecords.mapBacked(buildPayload(row))
                .withStreamKey(row.getStreamKey());
        RecordId recordId = redis.opsForStream().add(record);
        log.debug("[DocOutbox] XADD {} taskId={} -> {}", row.getStreamKey(), row.getTaskId(), recordId);
        return recordId;
    }

    public String currentTraceId(String taskId) {
        String traceId = MDC.get(TraceConstants.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            return taskId;
        }
        return traceId.trim();
    }

    Map<String, String> buildPayload(DocumentIngestOutbox row) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("task_id", row.getTaskId());
        body.put("minio_path", row.getMinioPath());
        body.put("workspace_id", normalizeWorkspaceId(row.getWorkspaceId()));
        body.put("visibility", row.getVisibility());
        body.put("user_id", String.valueOf(row.getUserId()));
        body.put("file_name", row.getFileName() == null ? "" : row.getFileName());
        body.put("file_size", String.valueOf(row.getFileSize() == null ? 0L : row.getFileSize()));
        body.put("trace_id", row.getTraceId());
        return body;
    }

    private void insertOutbox(DocumentMetadata row, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        DocumentIngestOutbox outbox = new DocumentIngestOutbox();
        outbox.setTaskId(row.getTaskId());
        outbox.setStreamKey(knowledgeProperties.getDocumentIngestStreamKey());
        outbox.setMinioPath(row.getMinioPath());
        outbox.setWorkspaceId(normalizeWorkspaceId(row.getWorkspaceId()));
        outbox.setVisibility(row.getVisibility());
        outbox.setUserId(row.getUserId());
        outbox.setFileName(row.getFileName());
        outbox.setFileSize(row.getFileSize());
        outbox.setTraceId((traceId == null || traceId.isBlank()) ? row.getTaskId() : traceId.trim());
        outbox.setStatus(DocumentIngestOutbox.STATUS_PENDING);
        outbox.setAttemptCount(0);
        outbox.setNextRetryAt(now);
        outbox.setPublishedAt(null);
        outbox.setDeadLetteredAt(null);
        outbox.setStreamRecordId(null);
        outbox.setLastError(null);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        documentIngestOutboxMapper.insert(outbox);
    }

    private static String normalizeWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return "default";
        }
        return workspaceId.trim();
    }
}
