package com.ai.itops.security.permission;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 面向业务 Service 的简洁鉴权门面，统一从认证上下文获取操作者。 */
@Service
@RequiredArgsConstructor
public class WorkspacePermissionService {

    private final CurrentUserProvider currentUserProvider;
    private final PermissionEvaluator permissionEvaluator;

    public Long requireCurrentUserId() {
        Long userId = currentUserProvider.getCurrentUserId();
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        return userId;
    }

    public void check(String workspaceId, WorkspacePermission permission) {
        permissionEvaluator.checkWorkspacePermission(requireCurrentUserId(), workspaceId, permission);
    }

    public void checkDocument(String workspaceId, String taskId, WorkspacePermission permission) {
        if (!(permissionEvaluator instanceof DefaultPermissionEvaluator evaluator)) {
            permissionEvaluator.checkResourcePermission(requireCurrentUserId(), workspaceId,
                    ResourceType.DOCUMENT, taskId, permission);
            return;
        }
        evaluator.checkDocumentPermission(requireCurrentUserId(), workspaceId, taskId, permission);
    }
}
