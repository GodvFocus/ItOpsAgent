package com.yuyu.fishagent.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuyu.fishagent.rag.dto.DocumentMetadataPageResponse;
import com.yuyu.fishagent.rag.dto.DocumentMetadataResponse;
import com.yuyu.fishagent.rag.entity.DocumentMetadata;
import com.yuyu.fishagent.rag.mapper.DocumentMetadataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库任务查询与删除：与 {@link KnowledgeIngestionService}（写入）解耦。
 * <p>删除顺序：RustFS 原文件 → MySQL 元数据。
 * ES 切片已迁移至 Milvus，Python Worker 侧负责 Milvus 写入/删除，Java 侧暂不直接操作 Milvus。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeManageService {

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final DocumentMetadataMapper documentMetadataMapper;
    private final ObjectProvider<RustFsService> rustFsProvider;
    private final ChunkClusterService chunkClusterService;

    /**
     * 当前用户可见的上传任务（按更新时间倒序）。
     */
    public DocumentMetadataPageResponse listForCurrentUser(Long userId, long page, long size) {
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        Page<DocumentMetadata> p = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        documentMetadataMapper.selectPage(p, Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getUserId, userId)
                .orderByDesc(DocumentMetadata::getUpdatedAt));
        return toPageResponse(p);
    }

    /**
     * 管理员：全部上传任务。
     */
    public DocumentMetadataPageResponse listAll(long page, long size) {
        Page<DocumentMetadata> p = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        documentMetadataMapper.selectPage(p, Wrappers.<DocumentMetadata>lambdaQuery()
                .orderByDesc(DocumentMetadata::getUpdatedAt));
        return toPageResponse(p);
    }

    /**
     * 按 taskId 删除：本人或管理员。
     * <p>删除顺序：RustFS 原文件 → MySQL 元数据。Milvus 切片由 Python Worker 侧管理。</p>
     */
    public void deleteByTaskId(String taskId, Long actorUserId, boolean actorIsAdmin) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId.trim()));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "任务不存在");
        }
        if (!actorIsAdmin && (actorUserId == null || !actorUserId.equals(row.getUserId()))) {
            throw new ResponseStatusException(FORBIDDEN, "无权删除该文档");
        }

        // TODO: ES → Milvus 迁移后，Milvus 切片删除由 Python Worker 侧负责
        deleteRustFsQuietly(row);
        chunkClusterService.evictClusterCache(row.getTaskId());
        documentMetadataMapper.delete(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, row.getTaskId()));
        log.info("[KnowledgeManage] 已删除文档任务 taskId={}, scope={}", row.getTaskId(), row.getScopeType());
    }

    private void deleteRustFsQuietly(DocumentMetadata row) {
        RustFsService rust = rustFsProvider.getIfAvailable();
        if (rust == null) {
            log.debug("[KnowledgeManage] RustFS 未启用，跳过对象删除 taskId={}", row.getTaskId());
            return;
        }
        String path = row.getMinioPath();
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            rust.deleteDocObject(path.trim());
        } catch (Exception e) {
            log.warn("[KnowledgeManage] RustFS 删除失败 path={}: {}", path, e.getMessage());
        }
    }

    private static DocumentMetadataPageResponse toPageResponse(Page<DocumentMetadata> p) {
        var records = p.getRecords().stream()
                .map(KnowledgeManageService::toResponse)
                .toList();
        return new DocumentMetadataPageResponse(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private static DocumentMetadataResponse toResponse(DocumentMetadata m) {
        return new DocumentMetadataResponse(
                m.getTaskId(),
                m.getFileName(),
                m.getFileSize() == null ? 0L : m.getFileSize(),
                m.getScopeType(),
                m.getStatus(),
                m.getChunkCount(),
                m.getErrorMsg(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().format(ISO_LOCAL),
                m.getUpdatedAt() == null ? null : m.getUpdatedAt().format(ISO_LOCAL)
        );
    }
}
