package com.ai.itops.security.permission.dto;

import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.ai.itops.auth.entity.SysUser;

import java.time.LocalDateTime;

/** Workspace 成员对外视图。 */
public record WorkspaceMemberResponse(Long userId, String workspaceId, String role, String status,
                                      LocalDateTime joinedAt, String username, String nickname) {

    public static WorkspaceMemberResponse from(WorkspaceMember member) {
        return new WorkspaceMemberResponse(member.getUserId(), member.getWorkspaceId(),
                member.getRole() == null ? null : member.getRole().name(),
                member.getStatus() == null ? null : member.getStatus().name(), member.getJoinedAt(), null, null);
    }

    public static WorkspaceMemberResponse from(WorkspaceMember member, SysUser user) {
        WorkspaceMemberResponse base = from(member);
        return new WorkspaceMemberResponse(base.userId(), base.workspaceId(), base.role(), base.status(),
                base.joinedAt(), user == null ? null : user.getUsername(),
                user == null ? null : user.getNickname());
    }
}
