package com.ai.itops.auth.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.auth.context.UserContext;
import com.ai.itops.auth.context.UserContextHolder;
import com.ai.itops.auth.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 权限细分：{@code /api/admin/**} 仅允许 {@link UserRole#ADMIN}。
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/admin/")) {
            return true;
        }
        if (isWorkspaceKnowledgeEndpoint(uri)) {
            return true;
        }
        UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            sendForbidden(response, "未登录");
            return false;
        }
        if (ctx.role() == null || !UserRole.ADMIN.name().equalsIgnoreCase(ctx.role().trim())) {
            sendForbidden(response, "需要管理员权限");
            return false;
        }
        return true;
    }

    /** Workspace 知识库接口由业务层 Workspace RBAC 校验，不能提前套用系统级 ADMIN。 */
    private static boolean isWorkspaceKnowledgeEndpoint(String uri) {
        return uri.startsWith("/api/admin/knowledge/upload")
                || "/api/admin/knowledge/documents".equals(uri);
    }

    private void sendForbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "error", "FORBIDDEN",
                "message", message == null ? "" : message));
        response.getOutputStream().write(body);
    }
}
