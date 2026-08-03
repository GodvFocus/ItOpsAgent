package com.ai.itops.security.permission;

/** 业务层统一鉴权入口，Controller 粗粒度校验不能替代这里的资源归属校验。 */
public interface PermissionEvaluator {

    void checkWorkspacePermission(Long userId, String workspaceId, WorkspacePermission permission);

    void checkResourcePermission(Long userId, String workspaceId, ResourceType resourceType,
                                 String resourceId, WorkspacePermission permission);

    default void checkWorkspacePermission(Long userId, Long workspaceId, WorkspacePermission permission) {
        checkWorkspacePermission(userId, workspaceId == null ? null : String.valueOf(workspaceId), permission);
    }

    default void checkResourcePermission(Long userId, Long workspaceId, ResourceType resourceType,
                                         Long resourceId, WorkspacePermission permission) {
        checkResourcePermission(userId,
                workspaceId == null ? null : String.valueOf(workspaceId), resourceType,
                resourceId == null ? null : String.valueOf(resourceId), permission);
    }
}
