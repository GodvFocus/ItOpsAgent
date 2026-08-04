package com.ai.itops.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ai.itops.auth.context.UserContext;
import com.ai.itops.auth.context.UserContextHolder;
import com.ai.itops.auth.enums.UserRole;
import com.ai.itops.rag.dto.ChunkGroupVO;
import com.ai.itops.rag.dto.ChunkListVO;
import com.ai.itops.rag.dto.DocumentMetadataPageResponse;
import com.ai.itops.rag.dto.DocumentIngestOutboxResponse;
import com.ai.itops.rag.dto.DocumentTaskStatusResponse;
import com.ai.itops.rag.dto.KnowledgeUploadResponse;
import com.ai.itops.rag.dto.MultipartAbortRequest;
import com.ai.itops.rag.dto.MultipartCompleteRequest;
import com.ai.itops.rag.dto.MultipartInitRequest;
import com.ai.itops.rag.dto.MultipartInitResponse;
import com.ai.itops.rag.dto.MultipartPartResponse;
import com.ai.itops.rag.dto.RelatedCardVO;
import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import com.ai.itops.rag.service.ChunkClusterService;
import com.ai.itops.rag.service.DocumentIngestOutboxService;
import com.ai.itops.rag.service.KnowledgeIngestionService;
import com.ai.itops.rag.service.KnowledgeManageService;
import com.ai.itops.rag.service.MultipartInitResult;
import com.ai.itops.security.permission.PermissionEvaluator;
import com.ai.itops.security.permission.WorkspacePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 知识库上传与任务状态查询。
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeIngestionService knowledgeIngestionService;
    private final DocumentIngestOutboxService documentIngestOutboxService;
    private final KnowledgeManageService knowledgeManageService;
    private final ChunkClusterService chunkClusterService;
    private final DocumentMetadataMapper documentMetadataMapper;
    private PermissionEvaluator permissionEvaluator;

    @org.springframework.beans.factory.annotation.Autowired
    public void setPermissionEvaluator(PermissionEvaluator permissionEvaluator) {
        this.permissionEvaluator = permissionEvaluator;
    }

    @PostMapping(value = "/api/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeUploadResponse uploadUser(@RequestPart("file") MultipartFile file) throws Exception {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        String workspaceId = UserContextHolder.currentWorkspaceIdOrNull();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        try (InputStream in = file.getInputStream()) {
            String taskId = knowledgeIngestionService.ingestUserFile(
                    uid, workspaceId, file.getOriginalFilename(), in, file.getSize(), file.getContentType());
            return new KnowledgeUploadResponse(taskId);
        }
    }

    @PostMapping(value = "/api/admin/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeUploadResponse uploadAdmin(@RequestPart("file") MultipartFile file) throws Exception {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        String workspaceId = UserContextHolder.currentWorkspaceIdOrNull();
        requireWorkspacePermission(uid, workspaceId, WorkspacePermission.DOCUMENT_UPLOAD);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        try (InputStream in = file.getInputStream()) {
            String taskId = knowledgeIngestionService.ingestAdminFile(
                    uid, workspaceId, file.getOriginalFilename(), in, file.getSize(), file.getContentType());
            return new KnowledgeUploadResponse(taskId);
        }
    }

    @PostMapping(value = "/api/knowledge/upload/init", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MultipartInitResponse initMultipartUser(@RequestBody MultipartInitRequest req) throws Exception {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        String workspaceId = UserContextHolder.currentWorkspaceIdOrNull();
        MultipartInitResult r = knowledgeIngestionService.initMultipartUpload(uid, req.getFileName(), req.getFileSize(),
                req.getContentType(), workspaceId, DocumentMetadata.VISIBILITY_PRIVATE,
                "workspace/" + safeWorkspaceSegment(workspaceId) + "/user/" + uid + "/");
        return new MultipartInitResponse(r.taskId(), r.uploadId(), r.minioPath());
    }

    @PostMapping(value = "/api/admin/knowledge/upload/init", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MultipartInitResponse initMultipartAdmin(@RequestBody MultipartInitRequest req) throws Exception {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        String workspaceId = UserContextHolder.currentWorkspaceIdOrNull();
        requireWorkspacePermission(uid, workspaceId, WorkspacePermission.DOCUMENT_UPLOAD);
        MultipartInitResult r = knowledgeIngestionService.initMultipartUpload(uid, req.getFileName(), req.getFileSize(),
                req.getContentType(), workspaceId, DocumentMetadata.VISIBILITY_WORKSPACE,
                "workspace/" + safeWorkspaceSegment(workspaceId) + "/shared/");
        return new MultipartInitResponse(r.taskId(), r.uploadId(), r.minioPath());
    }

    @PostMapping(value = "/api/knowledge/upload/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MultipartPartResponse uploadChunk(
            @RequestParam String taskId,
            @RequestParam String uploadId,
            @RequestParam String minioPath,
            @RequestParam int partNumber,
            @RequestPart("chunk") MultipartFile chunk) throws Exception {
        DocumentMetadata row = loadPendingTaskForMutation(taskId.trim());
        try (InputStream in = chunk.getInputStream()) {
            String etag = knowledgeIngestionService.uploadMultipartPart(row, uploadId, minioPath.trim(), partNumber, in, chunk.getSize());
            return new MultipartPartResponse(etag);
        }
    }

    @PostMapping(value = "/api/knowledge/upload/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeUploadResponse completeMultipart(@RequestBody MultipartCompleteRequest req) throws Exception {
        DocumentMetadata row = loadPendingTaskForMutation(req.getTaskId().trim());
        knowledgeIngestionService.completeMultipartUpload(row, req.getUploadId(), req.getMinioPath().trim(), req.getParts());
        return new KnowledgeUploadResponse(row.getTaskId());
    }

    @PostMapping(value = "/api/knowledge/upload/abort", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void abortMultipart(@RequestBody MultipartAbortRequest req) throws Exception {
        DocumentMetadata row = loadPendingTaskForMutation(req.getTaskId().trim());
        knowledgeIngestionService.abortMultipartUpload(row, req.getUploadId(), req.getMinioPath().trim());
    }

    /**
     * 当前用户上传任务分页列表（知识库管理页）。
     */
    @GetMapping("/api/knowledge/documents")
    public DocumentMetadataPageResponse listMyDocuments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Long uid = UserContextHolder.currentUserIdOrNull();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        return knowledgeManageService.listForCurrentUser(uid, UserContextHolder.currentWorkspaceIdOrNull(), page, size);
    }

    /**
     * 管理员：全部上传任务。
     */
    @GetMapping("/api/admin/knowledge/documents")
    public DocumentMetadataPageResponse listAllDocuments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        requireAdmin();
        return knowledgeManageService.listAllForWorkspace(UserContextHolder.currentWorkspaceIdOrNull(), page, size);
    }

    /**
     * 删除文档任务（本人或管理员）：同步删除对象存储、Milvus 向量与 MySQL 记录。
     */
    @DeleteMapping("/api/knowledge/documents/{taskId}")
    public void deleteDocument(@PathVariable String taskId) {
        UserContext ctx = UserContextHolder.get();
        Long uid = ctx == null ? null : ctx.userId();
        knowledgeManageService.deleteByTaskId(taskId, uid, isAdmin(),
                ctx == null ? null : ctx.workspaceId());
    }

    /**
     * 文档切片主题分组：聚类结果由服务层缓存，Controller 只负责当前用户上下文透传。
     */
    @GetMapping("/api/knowledge/documents/{taskId}/chunks/groups")
    public ChunkGroupVO chunkGroups(@PathVariable String taskId) {
        UserContext ctx = UserContextHolder.get();
        return chunkClusterService.getGroups(taskId,
                ctx == null ? null : ctx.userId(),
                ctx == null ? null : ctx.workspaceId(),
                isAdmin());
    }

    /**
     * 文档切片列表：支持按主题分组和关键词筛选。
     */
    @GetMapping("/api/knowledge/documents/{taskId}/chunks")
    public ChunkListVO chunks(
            @PathVariable String taskId,
            @RequestParam(required = false) Integer groupIndex,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserContext ctx = UserContextHolder.get();
        return chunkClusterService.getChunks(taskId, groupIndex, keyword, page, size,
                ctx == null ? null : ctx.userId(),
                ctx == null ? null : ctx.workspaceId(),
                isAdmin());
    }

    /**
     * 单个切片的相似知识卡片。
     */
    @GetMapping("/api/knowledge/chunks/{taskId}/{chunkIndex}/related-cards")
    public List<RelatedCardVO> relatedCards(@PathVariable String taskId, @PathVariable Integer chunkIndex) {
        UserContext ctx = UserContextHolder.get();
        return chunkClusterService.getRelatedCards(taskId, chunkIndex,
                ctx == null ? null : ctx.userId(),
                ctx == null ? null : ctx.workspaceId(),
                isAdmin());
    }

    @GetMapping("/api/knowledge/tasks/{taskId}")
    public DocumentTaskStatusResponse taskStatus(@PathVariable String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        String workspaceId = UserContextHolder.currentWorkspaceIdOrNull();
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId.trim())
                .eq(DocumentMetadata::getWorkspaceId, workspaceId));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "任务不存在");
        }
        UserContext ctx = UserContextHolder.get();
        Long uid = ctx == null ? null : ctx.userId();
        if (permissionEvaluator != null) {
            permissionEvaluator.checkResourcePermission(uid, workspaceId,
                    com.ai.itops.security.permission.ResourceType.DOCUMENT,
                    String.valueOf(row.getId()), WorkspacePermission.DOCUMENT_READ);
        }
        boolean owner = uid != null && uid.equals(row.getUserId());
        boolean workspaceVisible = ctx != null
                && Objects.equals(ctx.workspaceId(), row.getWorkspaceId())
                && DocumentMetadata.VISIBILITY_WORKSPACE.equalsIgnoreCase(row.getVisibility());
        if (!isAdmin() && !owner && !workspaceVisible) {
            throw new ResponseStatusException(FORBIDDEN, "无权查看该任务");
        }
        return new DocumentTaskStatusResponse(row.getStatus(), row.getErrorMsg());
    }

    /**
     * 管理员查看进入 DLQ 的事件。
     */
    @GetMapping("/api/admin/knowledge/outbox/dlq")
    public List<DocumentIngestOutboxResponse> deadLetters(
            @RequestParam(defaultValue = "50") int limit) {
        requireAdmin();
        return documentIngestOutboxService.listDeadLetters(limit);
    }

    /**
     * 管理员人工重放指定任务。
     */
    @PostMapping("/api/admin/knowledge/tasks/{taskId}/replay")
    public void replayTask(@PathVariable String taskId) {
        requireAdmin();
        UserContext ctx = UserContextHolder.get();
        documentIngestOutboxService.requestReplay(taskId,
                ctx == null ? null : ctx.workspaceId(), ctx == null ? null : ctx.userId());
    }

    /**
     * 加载 PENDING 任务并校验当前用户可变更（本人或 ADMIN）。
     */
    private DocumentMetadata loadPendingTaskForMutation(String taskId) {
        String workspaceId = UserContextHolder.currentWorkspaceIdOrNull();
        DocumentMetadata row = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId)
                .eq(DocumentMetadata::getWorkspaceId, workspaceId));
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "任务不存在");
        }
        if (!DocumentMetadata.STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalArgumentException("任务状态不允许该操作");
        }
        UserContext ctx = UserContextHolder.get();
        Long uid = ctx == null ? null : ctx.userId();
        if (permissionEvaluator != null) {
            if (permissionEvaluator instanceof com.ai.itops.security.permission.DefaultPermissionEvaluator evaluator) {
                evaluator.checkDocumentPermission(uid, workspaceId, taskId, WorkspacePermission.DOCUMENT_UPDATE);
            } else {
                permissionEvaluator.checkResourcePermission(uid, workspaceId,
                        com.ai.itops.security.permission.ResourceType.DOCUMENT,
                        String.valueOf(row.getId()), WorkspacePermission.DOCUMENT_UPDATE);
            }
        }
        if (!isAdmin() && (uid == null || !Objects.equals(uid, row.getUserId()))) {
            throw new ResponseStatusException(FORBIDDEN, "无权操作该任务");
        }
        return row;
    }

    private static boolean isAdmin() {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            return false;
        }
        String role = ctx.role();
        return role != null && UserRole.ADMIN.name().equalsIgnoreCase(role.trim());
    }

    private void requireWorkspacePermission(Long userId, String workspaceId, WorkspacePermission permission) {
        if (permissionEvaluator == null) {
            throw new IllegalStateException("Workspace 权限服务未就绪");
        }
        permissionEvaluator.checkWorkspacePermission(userId, workspaceId, permission);
    }

    private static void requireAdmin() {
        if (!isAdmin()) {
            throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
    }

    private static String safeWorkspaceSegment(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return "default";
        }
        return workspaceId.trim().replace('/', '_').replace('\\', '_');
    }
}
