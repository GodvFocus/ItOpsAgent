package com.ai.itops.security.permission.dto;

import com.ai.itops.security.permission.WorkspaceRole;
import com.ai.itops.security.permission.entity.Workspace;

/** 面向 Workspace selector 和权限提示的最小摘要，不包含成员或敏感配置。 */
public record WorkspaceSummaryResponse(String id, String name, String status, Long ownerId,
                                       String workspaceRole) {

    public static WorkspaceSummaryResponse from(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceSummaryResponse(workspace.getId(), workspace.getName(), workspace.getStatus(),
                workspace.getOwnerId(), role == null ? null : role.name());
    }
}
