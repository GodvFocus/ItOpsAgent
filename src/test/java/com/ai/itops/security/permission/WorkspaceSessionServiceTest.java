package com.ai.itops.security.permission;

import com.ai.itops.auth.context.UserContext;
import com.ai.itops.auth.session.RedisSessionManager;
import com.ai.itops.security.permission.entity.Workspace;
import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.ai.itops.security.permission.mapper.WorkspaceMapper;
import com.ai.itops.security.permission.mapper.WorkspaceMemberMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceSessionServiceTest {

    @Mock WorkspaceMapper workspaceMapper;
    @Mock WorkspaceMemberMapper memberMapper;
    @Mock RedisSessionManager redisSessionManager;

    @Test
    void switchUpdatesSameSessionAndKeepsSystemFields() {
        WorkspaceSessionService service = service();
        UserContext old = new UserContext(7L, "old", "alice", "Alice", "ADMIN", "VIEWER");
        Workspace target = new Workspace("target", "目标 Workspace", 9L, "ACTIVE", 9L, null, null);
        WorkspaceMember member = member(7L, "target", WorkspaceRole.EDITOR, MemberStatus.ACTIVE);
        when(redisSessionManager.getSession("token")).thenReturn(java.util.Optional.of(old));
        when(workspaceMapper.selectById("target")).thenReturn(target);
        when(memberMapper.selectByWorkspaceAndUser("target", 7L)).thenReturn(member);

        var response = service.switchWorkspace("token", "target");

        assertThat(response.workspaceRole()).isEqualTo("EDITOR");
        assertThat(response.currentWorkspace().id()).isEqualTo("target");
        verify(redisSessionManager).updateWorkspace("token", "target", WorkspaceRole.EDITOR);
    }

    @Test
    void nonMemberIsRejectedWithoutRevealingWorkspace() {
        WorkspaceSessionService service = service();
        when(redisSessionManager.getSession("token"))
                .thenReturn(java.util.Optional.of(new UserContext(7L, "old", "alice", "Alice", "USER")));
        when(workspaceMapper.selectById("target"))
                .thenReturn(new Workspace("target", "Target", 9L, "ACTIVE", 9L, null, null));
        when(memberMapper.selectByWorkspaceAndUser("target", 7L)).thenReturn(null);

        assertThatThrownBy(() -> service.switchWorkspace("token", "target"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Workspace 不存在或当前用户无权访问");
        verify(redisSessionManager, never()).updateWorkspace(eq("token"), eq("target"), eq(WorkspaceRole.VIEWER));
    }

    @Test
    void disabledMemberIsRejected() {
        WorkspaceSessionService service = service();
        when(redisSessionManager.getSession("token"))
                .thenReturn(java.util.Optional.of(new UserContext(7L, "old", "alice", "Alice", "USER")));
        when(workspaceMapper.selectById("target"))
                .thenReturn(new Workspace("target", "Target", 9L, "ACTIVE", 9L, null, null));
        when(memberMapper.selectByWorkspaceAndUser("target", 7L))
                .thenReturn(member(7L, "target", WorkspaceRole.VIEWER, MemberStatus.DISABLED));

        assertThatThrownBy(() -> service.switchWorkspace("token", "target"))
                .isInstanceOf(ResponseStatusException.class);
        verify(redisSessionManager, never()).updateWorkspace(eq("token"), eq("target"), eq(WorkspaceRole.VIEWER));
    }

    private WorkspaceSessionService service() {
        return new WorkspaceSessionService(workspaceMapper, memberMapper, redisSessionManager);
    }

    private static WorkspaceMember member(Long userId, String workspaceId,
                                          WorkspaceRole role, MemberStatus status) {
        return new WorkspaceMember(1L, workspaceId, userId, role, status, null, null, null);
    }
}
