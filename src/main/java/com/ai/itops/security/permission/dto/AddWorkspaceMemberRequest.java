package com.ai.itops.security.permission.dto;

import com.ai.itops.security.permission.WorkspaceRole;

/** 添加已有用户为 Workspace 成员。 */
public record AddWorkspaceMemberRequest(Long userId, WorkspaceRole role) {
}
