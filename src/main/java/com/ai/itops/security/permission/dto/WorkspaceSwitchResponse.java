package com.ai.itops.security.permission.dto;

/** Workspace 切换成功后的 Session 投影，前端仅使用服务端返回的角色和 Workspace 信息。 */
public record WorkspaceSwitchResponse(WorkspaceSummaryResponse currentWorkspace, String workspaceRole) {
}
