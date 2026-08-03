package com.ai.itops.security.permission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionMappingTest {

    @Test
    void fixedRoleMatrixMatchesWorkspaceRules() {
        assertThat(RolePermissionMapping.allows(WorkspaceRole.OWNER, WorkspacePermission.WORKSPACE_DELETE)).isTrue();
        assertThat(RolePermissionMapping.allows(WorkspaceRole.ADMIN, WorkspacePermission.WORKSPACE_DELETE)).isFalse();
        assertThat(RolePermissionMapping.allows(WorkspaceRole.ADMIN, WorkspacePermission.DOCUMENT_DELETE)).isTrue();
        assertThat(RolePermissionMapping.allows(WorkspaceRole.EDITOR, WorkspacePermission.DOCUMENT_UPLOAD)).isTrue();
        assertThat(RolePermissionMapping.allows(WorkspaceRole.EDITOR, WorkspacePermission.KNOWLEDGE_BASE_DELETE)).isFalse();
        assertThat(RolePermissionMapping.allows(WorkspaceRole.VIEWER, WorkspacePermission.DIAGNOSIS_EXECUTE)).isTrue();
        assertThat(RolePermissionMapping.allows(WorkspaceRole.VIEWER, WorkspacePermission.DOCUMENT_UPLOAD)).isFalse();
    }
}
