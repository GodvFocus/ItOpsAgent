package com.ai.itops.security.permission;

import com.ai.itops.rag.entity.DocumentMetadata;
import com.ai.itops.rag.mapper.DocumentMetadataMapper;
import com.ai.itops.security.permission.entity.Workspace;
import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.ai.itops.security.permission.mapper.WorkspaceMapper;
import com.ai.itops.security.permission.mapper.WorkspaceMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPermissionEvaluatorTest {

    @Mock
    WorkspaceMapper workspaceMapper;
    @Mock
    WorkspaceMemberMapper memberMapper;
    @Mock
    DocumentMetadataMapper documentMetadataMapper;

    DefaultPermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultPermissionEvaluator(workspaceMapper, memberMapper, documentMetadataMapper);
        lenient().when(workspaceMapper.selectById("workspace-a"))
                .thenReturn(new Workspace("workspace-a", "A", 1L, "ACTIVE", 1L, null, null));
    }

    @Test
    void inactiveMemberCannotReadWorkspace() {
        when(memberMapper.selectByWorkspaceAndUser("workspace-a", 2L))
                .thenReturn(new WorkspaceMember(1L, "workspace-a", 2L,
                        WorkspaceRole.VIEWER, MemberStatus.DISABLED, null, null, null));

        assertThatThrownBy(() -> evaluator.checkWorkspacePermission(2L, "workspace-a",
                WorkspacePermission.WORKSPACE_READ))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void editorCannotModifyAnotherUsersDocument() {
        when(memberMapper.selectByWorkspaceAndUser("workspace-a", 2L))
                .thenReturn(new WorkspaceMember(1L, "workspace-a", 2L,
                        WorkspaceRole.EDITOR, MemberStatus.ACTIVE, null, null, null));
        DocumentMetadata document = new DocumentMetadata();
        document.setId(99L);
        document.setWorkspaceId("workspace-a");
        document.setUserId(3L);
        document.setCreatedBy(3L);
        when(documentMetadataMapper.selectOne(any())).thenReturn(document);

        assertThatThrownBy(() -> evaluator.checkResourcePermission(2L, "workspace-a", ResourceType.DOCUMENT,
                "99", WorkspacePermission.DOCUMENT_DELETE))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void crossWorkspaceDocumentIsNotFound() {
        when(memberMapper.selectByWorkspaceAndUser("workspace-a", 1L))
                .thenReturn(new WorkspaceMember(1L, "workspace-a", 1L,
                        WorkspaceRole.OWNER, MemberStatus.ACTIVE, null, null, null));
        when(documentMetadataMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> evaluator.checkResourcePermission(1L, "workspace-a", ResourceType.DOCUMENT,
                "99", WorkspacePermission.DOCUMENT_READ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("资源不存在");
    }
}
