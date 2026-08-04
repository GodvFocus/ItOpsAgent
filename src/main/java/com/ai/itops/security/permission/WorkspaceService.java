package com.ai.itops.security.permission;

import com.ai.itops.security.permission.entity.Workspace;
import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.ai.itops.security.permission.mapper.WorkspaceMapper;
import com.ai.itops.security.permission.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** Workspace 生命周期服务，创建 Workspace 与 OWNER 成员必须同事务提交。 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(rollbackFor = Exception.class)
    public Workspace create(String name) {
        Long userId = currentUserProvider.getCurrentUserId();
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace 名称不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID().toString().replace("-", ""));
        workspace.setName(name.trim());
        workspace.setOwnerId(userId);
        workspace.setCreatedBy(userId);
        workspace.setStatus("ACTIVE");
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspaceMapper.insert(workspace);

        WorkspaceMember owner = new WorkspaceMember();
        owner.setWorkspaceId(workspace.getId());
        owner.setUserId(userId);
        owner.setRole(WorkspaceRole.OWNER);
        owner.setStatus(MemberStatus.ACTIVE);
        owner.setJoinedAt(now);
        owner.setCreatedAt(now);
        owner.setUpdatedAt(now);
        workspaceMemberMapper.insert(owner);
        return workspace;
    }

    /** 兼容历史默认 Workspace：新注册用户首次登录时补充为普通成员。 */
    @Transactional(rollbackFor = Exception.class)
    public void ensureDefaultMembership(Long userId) {
        if (userId == null) {
            return;
        }
        Workspace workspace = workspaceMapper.selectById("default");
        LocalDateTime now = LocalDateTime.now();
        if (workspace == null) {
            workspace = new Workspace("default", "默认工作区", userId, "ACTIVE", userId, now, now);
            workspaceMapper.insert(workspace);
            WorkspaceMember owner = new WorkspaceMember(null, "default", userId,
                    WorkspaceRole.OWNER, MemberStatus.ACTIVE, now, now, now);
            workspaceMemberMapper.insert(owner);
            return;
        }
        WorkspaceMember member = workspaceMemberMapper.selectByWorkspaceAndUser("default", userId);
        if (member == null) {
            WorkspaceMember viewer = new WorkspaceMember(null, "default", userId,
                    WorkspaceRole.VIEWER, MemberStatus.ACTIVE, now, now, now);
            workspaceMemberMapper.insert(viewer);
        }
    }

    /** 查询登录时使用的当前 Workspace 角色；无有效 membership 时返回 null。 */
    public String findActiveRole(Long userId, String workspaceId) {
        if (userId == null || workspaceId == null || workspaceId.isBlank()) {
            return null;
        }
        Workspace workspace = workspaceMapper.selectById(workspaceId.trim());
        WorkspaceMember member = workspaceMemberMapper.selectByWorkspaceAndUser(workspaceId.trim(), userId);
        if (workspace == null || !"ACTIVE".equalsIgnoreCase(workspace.getStatus())
                || member == null || member.getStatus() != MemberStatus.ACTIVE || member.getRole() == null) {
            return null;
        }
        return member.getRole().name();
    }
}
