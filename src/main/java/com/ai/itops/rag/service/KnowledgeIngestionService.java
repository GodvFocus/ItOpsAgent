package com.ai.itops.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ai.itops.rag.config.KnowledgeProperties;
import com.ai.itops.rag.dto.MultipartPartInfo;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 知识库上传：写入 RustFS、落库 {@code document_metadata} 与事务 outbox；Redis Stream 投递由独立 dispatcher 完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    private final ObjectProvider<RustFsService> rustFsProvider;
    private final DocumentMetadataMapper documentMetadataMapper;
    private final DocumentIngestOutboxService documentIngestOutboxService;

    /**
     * 用户上传私有文档：scope PRIVATE，对象键前缀 {@code user/{userId}/}。
     *
     * @param stream      文件流（调用方负责关闭）
     * @param size        字节长度（须与流一致）
     * @return {@code task_id}（UUID），供前端轮询与 Stream 追踪
     */
    public String ingestUserFile(Long userId, String workspaceId, String originalFilename,
                                 InputStream stream, long size, String contentType) throws Exception {
        return ingest(userId, workspaceId, originalFilename, stream, size, contentType,
                DocumentMetadata.VISIBILITY_PRIVATE,
                "workspace/" + safeWorkspaceSegment(workspaceId) + "/user/" + userId + "/");
    }

    /**
     * 管理员上传公共文档：scope PUBLIC，对象键前缀 {@code admin/}。
     */
    public String ingestAdminFile(Long userId, String workspaceId, String originalFilename,
                                  InputStream stream, long size, String contentType) throws Exception {
        return ingest(userId, workspaceId, originalFilename, stream, size, contentType,
                DocumentMetadata.VISIBILITY_WORKSPACE,
                "workspace/" + safeWorkspaceSegment(workspaceId) + "/shared/");
    }

    private String ingest(Long userId, String workspaceId, String originalFilename, InputStream stream,
                          long size, String contentType, String visibility, String objectKeyPrefix) throws Exception {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (stream == null || size <= 0) {
            throw new IllegalArgumentException("文件内容为空或大小无效");
        }
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs == null) {
            throw new IllegalArgumentException("知识库上传需要开启 fish.rustfs.enabled=true 并正确配置对象存储");
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        String safeName = sanitizeFileName(originalFilename);
        String minioPath = objectKeyPrefix + taskId + "_" + safeName;

        rustFs.putDocObject(minioPath, stream, size, contentType);

        LocalDateTime now = LocalDateTime.now();
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId(taskId);
        row.setUserId(userId);
        row.setWorkspaceId(normalizeWorkspaceId(workspaceId));
        row.setFileName(originalFilename == null || originalFilename.isBlank() ? safeName : originalFilename.trim());
        row.setFileSize(size);
        row.setMinioPath(minioPath);
        row.setVisibility(visibility);
        row.setStatus(DocumentMetadata.STATUS_PENDING);
        row.setErrorMsg(null);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        try {
            documentIngestOutboxService.createPendingTaskWithOutbox(
                    row,
                    documentIngestOutboxService.currentTraceId(taskId)
            );
        } catch (Exception e) {
            log.warn("[KnowledgeIngestion] 任务/Outbox 落库失败，回滚删除对象 path={}: {}", minioPath, e.getMessage());
            try {
                rustFs.deleteDocObject(minioPath);
            } catch (Exception ex) {
                log.warn("[KnowledgeIngestion] 回滚删除对象失败 path={}: {}", minioPath, ex.getMessage());
            }
            throw e;
        }

        log.info("[KnowledgeIngestion] 已提交任务并写入 Outbox taskId={}, path={}, scope={}",
                taskId, minioPath, visibility);
        return taskId;
    }

    /**
     * 分片上传初始化：仅落库 PENDING；分片暂存 {@code staging/{taskId}/{partNumber}}，完成后 compose 到 {@code minioPath}。
     * <p>约定 {@code uploadId} 与 {@code taskId} 相同（MinIO Java SDK 8.5.x 未暴露 CreateMultipartUpload API，改用 staging + compose）。</p>
     */
    public MultipartInitResult initMultipartUpload(Long userId, String originalFilename, long fileSize, String contentType,
                                                   String workspaceId, String visibility, String objectKeyPrefix) throws Exception {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (fileSize <= 0) {
            throw new IllegalArgumentException("fileSize 无效");
        }
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs == null) {
            throw new IllegalArgumentException("知识库上传需要开启 fish.rustfs.enabled=true 并正确配置对象存储");
        }
        // contentType 在 init 阶段记录用途有限；最终对象 MIME 由 compose 结果决定，必要时可后续扩展元数据表字段。
        if (contentType != null) {
            log.debug("[KnowledgeIngestion] multipart init contentType={}", contentType);
        }

        String taskId = UUID.randomUUID().toString().replace("-", "");
        String safeName = sanitizeFileName(originalFilename);
        String minioPath = objectKeyPrefix + taskId + "_" + safeName;

        LocalDateTime now = LocalDateTime.now();
        DocumentMetadata row = new DocumentMetadata();
        row.setTaskId(taskId);
        row.setUserId(userId);
        row.setWorkspaceId(normalizeWorkspaceId(workspaceId));
        row.setFileName(originalFilename == null || originalFilename.isBlank() ? safeName : originalFilename.trim());
        row.setFileSize(fileSize);
        row.setMinioPath(minioPath);
        row.setVisibility(visibility);
        row.setStatus(DocumentMetadata.STATUS_PENDING);
        row.setErrorMsg(null);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        documentIngestOutboxService.createPendingTask(row);

        log.info("[KnowledgeIngestion] multipart 已初始化 taskId={}, path={}", taskId, minioPath);
        return new MultipartInitResult(taskId, taskId, minioPath);
    }

    /**
     * 上传单个分片至 staging。
     */
    public String uploadMultipartPart(DocumentMetadata row, String uploadId, String minioPath, int partNumber,
                                      InputStream data, long size) throws Exception {
        assertRowMatches(row, minioPath);
        requireUploadIdMatchesTask(uploadId, row.getTaskId());
        if (partNumber < 1) {
            throw new IllegalArgumentException("partNumber 必须从 1 开始");
        }
        if (data == null || size <= 0) {
            throw new IllegalArgumentException("分片内容无效");
        }
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs == null) {
            throw new IllegalArgumentException("RustFS 不可用");
        }
        String stagingKey = stagingPartKey(row.getTaskId(), partNumber);
        return rustFs.putDocObject(stagingKey, data, size, "application/octet-stream");
    }

    /**
     * compose 合并 staging 分片为最终对象并投递 Redis Stream。
     */
    public void completeMultipartUpload(DocumentMetadata row, String uploadId, String minioPath,
                                        List<MultipartPartInfo> parts) throws Exception {
        assertRowMatches(row, minioPath);
        requireUploadIdMatchesTask(uploadId, row.getTaskId());
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs == null) {
            throw new IllegalArgumentException("RustFS 不可用");
        }
        if (documentIngestOutboxService.hasOutbox(row.getTaskId())) {
            log.info("[KnowledgeIngestion] multipart complete 幂等跳过，outbox 已存在 taskId={}", row.getTaskId());
            return;
        }

        boolean finalObjectExists = rustFs.docObjectExists(minioPath);
        if (!finalObjectExists) {
            if (parts == null || parts.isEmpty()) {
                throw new IllegalArgumentException("parts 不能为空");
            }

            try {
                List<MultipartPartInfo> sorted = new ArrayList<>(parts);
                sorted.sort(Comparator.comparingInt(MultipartPartInfo::getPartNumber));
                List<String> sourceKeys = new ArrayList<>(sorted.size());
                for (MultipartPartInfo p : sorted) {
                    sourceKeys.add(stagingPartKey(row.getTaskId(), p.getPartNumber()));
                }
                rustFs.composeDocObject(sourceKeys, minioPath);
            } catch (Exception e) {
                log.error("[KnowledgeIngestion] compose 合并失败 taskId={}: {}", row.getTaskId(), e.getMessage());
                throw new IllegalArgumentException("合并分片失败，请重新上传或联系管理员", e);
            }
            try {
                rustFs.deleteObjectsByPrefix(stagingPrefix(row.getTaskId()));
            } catch (Exception e) {
                log.warn("[KnowledgeIngestion] compose 成功后清理 staging 失败 taskId={}: {}", row.getTaskId(), e.getMessage());
            }
        } else {
            log.info("[KnowledgeIngestion] 检测到最终对象已存在，跳过重复 compose taskId={}", row.getTaskId());
        }

        boolean enqueued = documentIngestOutboxService.enqueueExistingTask(
                row,
                documentIngestOutboxService.currentTraceId(row.getTaskId())
        );
        if (!enqueued) {
            log.info("[KnowledgeIngestion] multipart complete 幂等跳过，outbox 已存在 taskId={}", row.getTaskId());
            return;
        }

        log.info("[KnowledgeIngestion] multipart 已完成并写入 Outbox taskId={}, path={}", row.getTaskId(), minioPath);
    }

    /**
     * 取消分片上传：删除 staging 对象并将任务标为失败。
     */
    public void abortMultipartUpload(DocumentMetadata row, String uploadId, String minioPath) throws Exception {
        assertRowMatches(row, minioPath);
        requireUploadIdMatchesTask(uploadId, row.getTaskId());
        RustFsService rustFs = rustFsProvider.getIfAvailable();
        if (rustFs != null) {
            try {
                rustFs.deleteObjectsByPrefix(stagingPrefix(row.getTaskId()));
            } catch (Exception e) {
                log.warn("[KnowledgeIngestion] abort 清理 staging taskId={}: {}", row.getTaskId(), e.getMessage());
            }
        }
        documentMetadataMapper.update(null, Wrappers.<DocumentMetadata>lambdaUpdate()
                .eq(DocumentMetadata::getId, row.getId())
                .set(DocumentMetadata::getStatus, DocumentMetadata.STATUS_FAILED)
                .set(DocumentMetadata::getErrorMsg, truncate("上传已取消", 2000))
                .set(DocumentMetadata::getUpdatedAt, LocalDateTime.now()));
    }

    private static String stagingPrefix(String taskId) {
        return "staging/" + taskId + "/";
    }

    private static String stagingPartKey(String taskId, int partNumber) {
        return stagingPrefix(taskId) + partNumber;
    }

    private static void requireUploadIdMatchesTask(String uploadId, String taskId) {
        if (uploadId == null || taskId == null || !uploadId.trim().equals(taskId)) {
            throw new IllegalArgumentException("uploadId 与任务不匹配");
        }
    }

    private static void assertRowMatches(DocumentMetadata row, String minioPath) {
        if (row == null || minioPath == null || minioPath.isBlank()) {
            throw new IllegalArgumentException("任务或路径无效");
        }
        if (!DocumentMetadata.STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalArgumentException("任务状态不允许该操作");
        }
        if (!minioPath.trim().equals(row.getMinioPath())) {
            throw new IllegalArgumentException("minio_path 与任务不匹配");
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "upload.bin";
        }
        String s = name.replace('\\', '_').replace('/', '_').trim();
        if (s.length() > 200) {
            s = s.substring(0, 200);
        }
        return s.isBlank() ? "upload.bin" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String normalizeWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return "default";
        }
        return workspaceId.trim();
    }

    private static String safeWorkspaceSegment(String workspaceId) {
        return normalizeWorkspaceId(workspaceId).replace('/', '_').replace('\\', '_');
    }
}
