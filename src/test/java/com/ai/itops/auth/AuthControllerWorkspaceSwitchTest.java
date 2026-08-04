package com.ai.itops.auth;

import com.ai.itops.auth.dto.LoginResponse;
import com.ai.itops.auth.interceptor.GlobalAuthInterceptor;
import com.ai.itops.security.permission.WorkspaceSessionService;
import com.ai.itops.security.permission.dto.WorkspaceSummaryResponse;
import com.ai.itops.security.permission.dto.WorkspaceSwitchResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerWorkspaceSwitchTest {

    @Test
    void switchEndpointPassesOnlyTokenAndWorkspaceIdToService() {
        AuthService authService = mock(AuthService.class);
        WorkspaceSessionService workspaceSessionService = mock(WorkspaceSessionService.class);
        AuthController controller = new AuthController(authService, workspaceSessionService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(GlobalAuthInterceptor.HEADER_AUTH_TOKEN)).thenReturn("token");
        WorkspaceSwitchResponse expected = new WorkspaceSwitchResponse(
                new WorkspaceSummaryResponse("target", "Target", "ACTIVE", 1L, "EDITOR"), "EDITOR");
        when(workspaceSessionService.switchWorkspace("token", "target")).thenReturn(expected);

        assertThat(controller.switchWorkspace("target", request)).isSameAs(expected);
        verify(workspaceSessionService).switchWorkspace("token", "target");
    }
}
