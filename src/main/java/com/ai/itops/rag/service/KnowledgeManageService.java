package com.ai.itops.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ai.itops.rag.dto.DocumentMetadataPageResponse;
import com.ai.itops.rag.dto.DocumentMetadataResponse;
import com.ai.itops.rag.config.MilvusProperties;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import com.ai.itops.auth.context.UserContextHolder;
import com.ai.itops.security.permission.DefaultPermissionEvaluator;
import com.ai.itops.security.permission.PermissionEvaluator;
import com.ai.itops.security.permission.WorkspacePermission;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库任务查询与删除：与 {@link KnowledgeIngestionService}（写入）解耦。
 * <p>删除顺序：RustFS 原文件 → MySQL 元数据。
 * 文档切片现已存入 Milvus，因此删除任务时也需要同步清理对应向量，避免幽灵召回。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeManageService {

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final DocumentMetadataMapper documentMetadataMapper;
    private final ObjectProvider<RustFsService> rustFsProvider;
    private final ChunkClusterService chunkClusterService;
    private final ObjectProvider<MilvusServiceClient> milvusClientProvider;
    private final MilvusProperties milvusProperties;
    private PermissionEvaluator permissionEvaluator;

    @org.springframework.beans.factory.annotation.Autowired
    public void setPermissionEvaluator(PermissionEvaluator permissionEvaluator) {
        this.permissionEvaluator = permissionEvaluator;
    }

    /**
     * 当前用户可见的上传任务（按更新时间倒序）。
     */
    public DocumentMetadataPageResponse listForCurrentUser(Long userId, String workspaceId, long page, long size) {
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        if (permissionEvaluator != null) {
            permissionEvaluator.checkWorkspacePermission(userId, workspaceId, WorkspacePermission.DOCUMENT_READ);
        }
        Page<DocumentMetadata> p = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        documentMetadataMapper.selectPage(p, Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getWorkspaceId, workspaceId)
                .and(q -> q.eq(DocumentMetadata::getUserId, userId)
                        .or(shared -> shared.eq(DocumentMetadata::getWorkspaceId, workspaceId)
                                .eq(DocumentMetadata::getVisibility, DocumentMetadata.VISIBILITY_WORKSPACE)))
                .orderByDesc(DocumentMetadata::getUpdatedAt));
        return toPageResponse(p);
    }

    /**
     * 管理员：全部上传任务。
     */
    public DocumentMetadataPageResponse listAll(long page, long size) {
        return listAllForWorkspace(UserContextHolder.currentWorkspaceIdOrNull(), page, size);
    }

    /** 管理员在指定 Workspace 内查看文档，不能扫描全库后再在内存过滤。 */
    public DocumentMetadataPageResponse listAllForWorkspace(String workspaceId, long page, long size) {
        Long userId = UserContextHolder.currentUserIdOrNull();
        if (permissionEvaluator != null) {
            permissionEvaluator.checkWorkspacePermission(userId, workspaceId, WorkspacePermission.DOCUMENT_READ);
        }
        Page<DocumentMetadata> p = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        documentMetadataMapper.selectPage(p, Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getWorkspaceId, workspaceId)
                .orderByDesc(DocumentMetadata::getUpdatedAt));
        return toPageResponse(p);
    }

    /**
     * 按 taskId 删除：本人或管理员。
     * <p>删除顺序：RustFS 原文件 → Milvus 切片 → 本地缓存 → MySQL 元数据。</p>
     */
    public void deleteByTaskId(String taskId, Long actorUserId, boolean actorIsAdmin) {
        deleteByTaskId(taskId, actorUserId, actorIsAdmin, UserContextHolder.currentWorkspaceIdOrNull());
    }

    /** 删除时同时以 taskId 与 workspaceId 限定 SQL 范围，防止 IDOR。 */
    public void deleteByTaskId(String taskId, Long actorUserId, boolean actorIsAdmin, String workspaceId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId.trim())
                .eq(DocumentMetadata::getWorkspaceId, workspaceId));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "任务不存在");
        }
        if (permissionEvaluator != null) {
            if (permissionEvaluator instanceof DefaultPermissionEvaluator evaluator) {
                evaluator.checkDocumentPermission(actorUserId, workspaceId, taskId.trim(),
                        WorkspacePermission.DOCUMENT_DELETE);
            } else {
                permissionEvaluator.checkResourcePermission(actorUserId, workspaceId,
                        com.ai.itops.security.permission.ResourceType.DOCUMENT,
                        String.valueOf(row.getId()), WorkspacePermission.DOCUMENT_DELETE);
            }
        } else if (!actorIsAdmin && (actorUserId == null || !actorUserId.equals(row.getUserId()))) {
            throw new ResponseStatusException(FORBIDDEN, "无权删除该文档");
        }

        deleteRustFsQuietly(row);
        deleteMilvusQuietly(row);
        chunkClusterService.evictClusterCache(row.getTaskId());
        documentMetadataMapper.delete(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, row.getTaskId())
                .eq(DocumentMetadata::getWorkspaceId, workspaceId));
        log.info("[KnowledgeManage] 已删除文档任务 taskId={}, visibility={}", row.getTaskId(), row.getVisibility());
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

    private void deleteMilvusQuietly(DocumentMetadata row) {
        MilvusServiceClient milvusClient = milvusClientProvider.getIfAvailable();
        if (milvusClient == null) {
            log.debug("[KnowledgeManage] Milvus 未启用，跳过向量删除 taskId={}", row.getTaskId());
            return;
        }
        String taskId = row.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        for (String collection : resolveTargetCollections(row)) {
            try {
                milvusClient.delete(DeleteParam.newBuilder()
                        .withCollectionName(collection)
                        .withExpr(String.format("doc_id == \"%s\"", escapeMilvusLiteral(taskId.trim())))
                        .build());
            } catch (Exception e) {
                log.warn("[KnowledgeManage] Milvus 删除失败 collection={}, taskId={}: {}",
                        collection, taskId, e.getMessage());
            }
        }
    }

    private List<String> resolveTargetCollections(DocumentMetadata row) {
        String visibility = DocumentMetadata.normalizeLegacyScope(row.getVisibility());
        LinkedHashSet<String> collections = new LinkedHashSet<>();
        if (DocumentMetadata.VISIBILITY_PRIVATE.equalsIgnoreCase(visibility)) {
            collections.add(milvusProperties.getUserKnowledgeCollection());
        } else if (DocumentMetadata.VISIBILITY_WORKSPACE.equalsIgnoreCase(visibility)) {
            collections.add(milvusProperties.getPublicKnowledgeCollection());
        } else {
            // 兼容脏数据：无法判定时双删，避免残留向量继续被召回。
            collections.add(milvusProperties.getUserKnowledgeCollection());
            collections.add(milvusProperties.getPublicKnowledgeCollection());
        }
        return collections.stream()
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private static String escapeMilvusLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
                m.getUserId(),
                m.getWorkspaceId(),
                m.getVisibility(),
                m.getStatus(),
                m.getChunkCount(),
                m.getErrorMsg(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().format(ISO_LOCAL),
                m.getUpdatedAt() == null ? null : m.getUpdatedAt().format(ISO_LOCAL)
        );
    }
}
