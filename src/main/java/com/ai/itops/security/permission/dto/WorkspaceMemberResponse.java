package com.ai.itops.security.permission.dto;

import com.ai.itops.security.permission.entity.WorkspaceMember;

import java.time.LocalDateTime;

/** Workspace 成员对外视图。 */
public record WorkspaceMemberResponse(Long userId, String workspaceId, String role, String status,
                                      LocalDateTime joinedAt) {

    public static WorkspaceMemberResponse from(WorkspaceMember member) {
        return new WorkspaceMemberResponse(member.getUserId(), member.getWorkspaceId(),
                member.getRole() == null ? null : member.getRole().name(),
                member.getStatus() == null ? null : member.getStatus().name(), member.getJoinedAt());
    }
}
