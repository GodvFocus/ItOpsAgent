package com.ai.itops.security.permission.dto;

import com.ai.itops.security.permission.WorkspaceRole;

/** 修改普通成员角色请求。 */
public record UpdateWorkspaceMemberRoleRequest(WorkspaceRole role) {
}
