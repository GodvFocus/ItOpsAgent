package com.ai.itops.security.permission;

import com.ai.itops.auth.mapper.SysUserMapper;
import com.ai.itops.security.permission.entity.Workspace;
import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.ai.itops.security.permission.mapper.WorkspaceMapper;
import com.ai.itops.security.permission.mapper.WorkspaceMemberMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceMemberServiceTest {

    @Mock WorkspaceMemberMapper memberMapper;
    @Mock WorkspaceMapper workspaceMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock PermissionEvaluator permissionEvaluator;
    @Mock CurrentUserProvider currentUserProvider;
    @Mock PermissionAuditService auditService;

    @Test
    void ordinaryRemovalCannotRemoveOwner() {
        WorkspaceMemberService service = service();
        when(currentUserProvider.getCurrentUserId()).thenReturn(2L);
        when(workspaceMapper.selectByIdForUpdate("ws")).thenReturn(
                new Workspace("ws", "WS", 1L, "ACTIVE", 1L, null, null));
        when(memberMapper.selectByWorkspaceAndUserForUpdate("ws", 1L)).thenReturn(
                new WorkspaceMember(1L, "ws", 1L, WorkspaceRole.OWNER,
                        MemberStatus.ACTIVE, null, null, null));

        assertThatThrownBy(() -> service.remove("ws", 1L))
                .isInstanceOf(PermissionDeniedException.class);
        verify(memberMapper).selectByWorkspaceAndUserForUpdate("ws", 1L);
    }

    @Test
    void ownershipTransferUpdatesBothMembersAndWorkspace() {
        WorkspaceMemberService service = service();
        when(currentUserProvider.getCurrentUserId()).thenReturn(1L);
        when(workspaceMapper.selectByIdForUpdate("ws")).thenReturn(
                new Workspace("ws", "WS", 1L, "ACTIVE", 1L, null, null));
        when(memberMapper.selectByWorkspaceAndUserForUpdate("ws", 1L)).thenReturn(
                new WorkspaceMember(1L, "ws", 1L, WorkspaceRole.OWNER,
                        MemberStatus.ACTIVE, null, null, null));
        when(memberMapper.selectByWorkspaceAndUserForUpdate("ws", 2L)).thenReturn(
                new WorkspaceMember(2L, "ws", 2L, WorkspaceRole.EDITOR,
                        MemberStatus.ACTIVE, null, null, null));
        when(workspaceMapper.updateOwner(eq("ws"), eq(2L), eq(1L), any())).thenReturn(1);

        service.transferOwnership("ws", 2L);

        verify(memberMapper, org.mockito.Mockito.times(2)).updateById(any(WorkspaceMember.class));
        verify(workspaceMapper).updateOwner(eq("ws"), eq(2L), eq(1L), any());
    }

    private WorkspaceMemberService service() {
        return new WorkspaceMemberService(memberMapper, workspaceMapper, sysUserMapper,
                permissionEvaluator, currentUserProvider, auditService);
    }
}
