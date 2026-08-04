package com.ai.itops.security.permission;

import com.ai.itops.auth.context.UserContext;
import com.ai.itops.auth.session.RedisSessionManager;
import com.ai.itops.security.permission.dto.WorkspaceSummaryResponse;
import com.ai.itops.security.permission.dto.WorkspaceSwitchResponse;
import com.ai.itops.security.permission.entity.Workspace;
import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.ai.itops.security.permission.mapper.WorkspaceMapper;
import com.ai.itops.security.permission.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** 负责 Workspace 列表、当前 Workspace 和 Redis Session 切换，所有身份字段均来自服务端会话和数据库。 */
@Service
@RequiredArgsConstructor
public class WorkspaceSessionService {

    private static final String INACCESSIBLE_WORKSPACE = "Workspace 不存在或当前用户无权访问";

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final RedisSessionManager redisSessionManager;

    public List<WorkspaceSummaryResponse> listForUser(Long userId) {
        requireUser(userId);
        return workspaceMapper.selectActiveByUserId(userId).stream()
                .map(workspace -> WorkspaceSummaryResponse.from(workspace,
                        activeMember(workspace.getId(), userId).getRole()))
                .toList();
    }

    public WorkspaceSwitchResponse current(Long userId, String workspaceId) {
        requireUser(userId);
        Workspace workspace = requireAccessibleWorkspace(workspaceId, userId);
        WorkspaceMember member = activeMember(workspace.getId(), userId);
        return response(workspace, member);
    }

    /** 先读取 token 中的 userId，再校验数据库 membership，最后原子性地重写同一 Redis Session。 */
    public WorkspaceSwitchResponse switchWorkspace(String token, String workspaceId) {
        UserContext context = redisSessionManager.getSession(token)
                .orElseThrow(() -> new IllegalStateException("未登录或会话已过期"));
        Workspace workspace = requireAccessibleWorkspace(workspaceId, context.userId());
        WorkspaceMember member = activeMember(workspace.getId(), context.userId());
        redisSessionManager.updateWorkspace(token, workspace.getId(), member.getRole());
        return response(workspace, member);
    }

    private WorkspaceSwitchResponse response(Workspace workspace, WorkspaceMember member) {
        WorkspaceSummaryResponse summary = WorkspaceSummaryResponse.from(workspace, member.getRole());
        return new WorkspaceSwitchResponse(summary, member.getRole().name());
    }

    private Workspace requireAccessibleWorkspace(String workspaceId, Long userId) {
        requireUser(userId);
        if (workspaceId == null || workspaceId.isBlank()) {
            throw inaccessible();
        }
        String normalizedId = workspaceId.trim();
        Workspace workspace = workspaceMapper.selectById(normalizedId);
        WorkspaceMember member = memberMapper.selectByWorkspaceAndUser(normalizedId, userId);
        if (workspace == null || !"ACTIVE".equalsIgnoreCase(workspace.getStatus())
                || member == null || member.getStatus() != MemberStatus.ACTIVE || member.getRole() == null) {
            // 对不存在 Workspace、非成员、DISABLED 成员和已禁用 Workspace 使用统一 404，避免枚举资源。
            throw inaccessible();
        }
        return workspace;
    }

    private WorkspaceMember activeMember(String workspaceId, Long userId) {
        WorkspaceMember member = memberMapper.selectByWorkspaceAndUser(workspaceId, userId);
        if (member == null || member.getStatus() != MemberStatus.ACTIVE || member.getRole() == null) {
            throw inaccessible();
        }
        return member;
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
    }

    private static ResponseStatusException inaccessible() {
        return new ResponseStatusException(NOT_FOUND, INACCESSIBLE_WORKSPACE);
    }
}
