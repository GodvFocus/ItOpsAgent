package com.ai.itops.security.permission;

import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import com.ai.itops.security.permission.entity.Workspace;
import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.ai.itops.security.permission.mapper.WorkspaceMapper;
import com.ai.itops.security.permission.mapper.WorkspaceMemberMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** 基于 Workspace 成员关系和固定角色映射执行权限判断。 */
@Service
@RequiredArgsConstructor
public class DefaultPermissionEvaluator implements PermissionEvaluator {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final DocumentMetadataMapper documentMetadataMapper;
    private PermissionAuditService auditService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setAuditService(PermissionAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void checkWorkspacePermission(Long userId, String workspaceId, WorkspacePermission permission) {
        try {
            WorkspaceMember member = requireActiveMember(userId, workspaceId);
            Workspace workspace = workspaceMapper.selectById(workspaceId);
            if (workspace == null || workspace.getStatus() == null
                    || !"ACTIVE".equalsIgnoreCase(workspace.getStatus())) {
                throw new ResponseStatusException(NOT_FOUND, "Workspace 不存在");
            }
            WorkspaceRole role = member.getRole();
            if (!RolePermissionMapping.allows(role, permission)) {
                throw new PermissionDeniedException("无权执行该 Workspace 操作");
            }
        } catch (RuntimeException e) {
            if (auditService != null) {
                auditService.record(userId, workspaceId, "WORKSPACE", workspaceId,
                        permission == null ? "UNKNOWN" : permission.name(), "FAILURE", e.getMessage());
            }
            throw e;
        }
    }

    @Override
    public void checkResourcePermission(Long userId, String workspaceId, ResourceType resourceType,
                                        String resourceId, WorkspacePermission permission) {
        checkWorkspacePermission(userId, workspaceId, permission);
        if (resourceType == ResourceType.DOCUMENT) {
            if (resourceId == null || resourceId.isBlank()) {
                throw new ResponseStatusException(NOT_FOUND, "资源不存在");
            }
            DocumentMetadata document = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                    .eq(DocumentMetadata::getId, Long.valueOf(resourceId))
                    .eq(DocumentMetadata::getWorkspaceId, workspaceId));
            if (document == null) {
                throw new ResponseStatusException(NOT_FOUND, "资源不存在");
            }
            checkEditorOwnership(userId, permission, workspaceId,
                    document.getCreatedBy() == null ? document.getUserId() : document.getCreatedBy());
        }
    }

    /** 文档接口使用 taskId 作为外部资源标识，查询时把 taskId 与 workspaceId 一起下推到 SQL。 */
    public void checkDocumentPermission(Long userId, String workspaceId, String taskId,
                                        WorkspacePermission permission) {
        checkWorkspacePermission(userId, workspaceId, permission);
        DocumentMetadata document = documentMetadataMapper.selectOne(Wrappers.<DocumentMetadata>lambdaQuery()
                .eq(DocumentMetadata::getTaskId, taskId)
                .eq(DocumentMetadata::getWorkspaceId, workspaceId));
        if (document == null) {
            throw new ResponseStatusException(NOT_FOUND, "资源不存在");
        }
        checkEditorOwnership(userId, permission, workspaceId,
                document.getCreatedBy() == null ? document.getUserId() : document.getCreatedBy());
    }

    public WorkspaceMember requireActiveMember(Long userId, String workspaceId) {
        if (userId == null || workspaceId == null || workspaceId.isBlank()) {
            throw new PermissionDeniedException("缺少有效的认证身份或 Workspace");
        }
        WorkspaceMember member = workspaceMemberMapper.selectByWorkspaceAndUser(workspaceId, userId);
        if (member == null || member.getStatus() != MemberStatus.ACTIVE) {
            throw new PermissionDeniedException("不是该 Workspace 的有效成员");
        }
        return member;
    }

    private void checkEditorOwnership(Long userId, WorkspacePermission permission,
                                      String workspaceId, Long createdBy) {
        WorkspaceMember member = requireActiveMember(userId, workspaceId);
        if (member.getRole() == WorkspaceRole.EDITOR
                && (permission == WorkspacePermission.DOCUMENT_UPDATE
                || permission == WorkspacePermission.DOCUMENT_DELETE)
                && !userId.equals(createdBy)) {
            throw new PermissionDeniedException("EDITOR 只能操作自己创建的文档");
        }
    }
}
