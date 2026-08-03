package com.ai.itops.security.permission;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 固定角色到权限的唯一映射，避免权限判断散落在各业务 Controller。 */
public final class RolePermissionMapping {

    private static final Set<WorkspacePermission> READ_ONLY = EnumSet.of(
            WorkspacePermission.WORKSPACE_READ,
            WorkspacePermission.MEMBER_READ,
            WorkspacePermission.KNOWLEDGE_BASE_READ,
            WorkspacePermission.DOCUMENT_READ,
            WorkspacePermission.DIAGNOSIS_EXECUTE);

    private static final Map<WorkspaceRole, Set<WorkspacePermission>> MAPPING = Map.of(
            WorkspaceRole.OWNER, EnumSet.allOf(WorkspacePermission.class),
            WorkspaceRole.ADMIN, EnumSet.complementOf(EnumSet.of(
                    WorkspacePermission.WORKSPACE_DELETE,
                    WorkspacePermission.OWNERSHIP_TRANSFER)),
            WorkspaceRole.EDITOR, editorPermissions(),
            WorkspaceRole.VIEWER, READ_ONLY);

    private RolePermissionMapping() {
    }

    public static boolean allows(WorkspaceRole role, WorkspacePermission permission) {
        return role != null && permission != null
                && MAPPING.getOrDefault(role, Set.of()).contains(permission);
    }

    public static Set<WorkspacePermission> permissionsOf(WorkspaceRole role) {
        return MAPPING.getOrDefault(role, Set.of());
    }

    private static Set<WorkspacePermission> editorPermissions() {
        EnumSet<WorkspacePermission> permissions = EnumSet.copyOf(READ_ONLY);
        permissions.add(WorkspacePermission.DOCUMENT_UPLOAD);
        permissions.add(WorkspacePermission.DOCUMENT_UPDATE);
        permissions.add(WorkspacePermission.DOCUMENT_DELETE);
        return permissions;
    }
}
