package com.ai.itops.security.permission;

import com.ai.itops.auth.entity.SysUser;
import com.ai.itops.auth.mapper.SysUserMapper;
import com.ai.itops.security.permission.entity.Workspace;
import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.ai.itops.security.permission.mapper.WorkspaceMapper;
import com.ai.itops.security.permission.mapper.WorkspaceMemberMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** 成员管理和所有权转移，所有敏感操作均在服务层重新鉴权并写审计。 */
@Service
@RequiredArgsConstructor
public class WorkspaceMemberService {

    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceMapper workspaceMapper;
    private final SysUserMapper sysUserMapper;
    private final PermissionEvaluator permissionEvaluator;
    private final CurrentUserProvider currentUserProvider;
    private final PermissionAuditService auditService;

    public List<WorkspaceMember> list(String workspaceId) {
        Long operatorId = requireOperator();
        permissionEvaluator.checkWorkspacePermission(operatorId, workspaceId, WorkspacePermission.MEMBER_READ);
        return memberMapper.selectByWorkspaceId(workspaceId);
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkspaceMember add(String workspaceId, Long userId, WorkspaceRole role) {
        Long operatorId = requireOperator();
        try {
            permissionEvaluator.checkWorkspacePermission(operatorId, workspaceId, WorkspacePermission.MEMBER_INVITE);
            if (userId == null || role == null || role == WorkspaceRole.OWNER) {
                throw new IllegalArgumentException("成员用户和角色无效");
            }
            SysUser user = sysUserMapper.selectById(userId);
            if (user == null) {
                throw new ResponseStatusException(NOT_FOUND, "用户不存在");
            }
            WorkspaceMember existing = memberMapper.selectByWorkspaceAndUserForUpdate(workspaceId, userId);
            LocalDateTime now = LocalDateTime.now();
            if (existing != null && existing.getStatus() == MemberStatus.ACTIVE) {
                throw new IllegalArgumentException("用户已经是 Workspace 成员");
            }
            if (existing != null) {
                existing.setRole(role);
                existing.setStatus(MemberStatus.ACTIVE);
                existing.setJoinedAt(now);
                existing.setUpdatedAt(now);
                memberMapper.updateById(existing);
            } else {
                existing = new WorkspaceMember();
                existing.setWorkspaceId(workspaceId);
                existing.setUserId(userId);
                existing.setRole(role);
                existing.setStatus(MemberStatus.ACTIVE);
                existing.setJoinedAt(now);
                existing.setCreatedAt(now);
                existing.setUpdatedAt(now);
                memberMapper.insert(existing);
            }
            auditService.record(operatorId, workspaceId, "MEMBER", String.valueOf(userId),
                    "MEMBER_INVITE", "SUCCESS", null);
            return existing;
        } catch (RuntimeException e) {
            auditService.record(operatorId, workspaceId, "MEMBER", String.valueOf(userId),
                    "MEMBER_INVITE", "FAILURE", e.getMessage());
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeRole(String workspaceId, Long userId, WorkspaceRole newRole) {
        Long operatorId = requireOperator();
        try {
            permissionEvaluator.checkWorkspacePermission(operatorId, workspaceId,
                    WorkspacePermission.MEMBER_ROLE_UPDATE);
            if (newRole == null || newRole == WorkspaceRole.OWNER) {
                throw new IllegalArgumentException("普通角色不能变更为 OWNER");
            }
            Workspace workspace = requireWorkspaceForUpdate(workspaceId);
            WorkspaceMember target = requireMemberForUpdate(workspaceId, userId);
            if (target.getRole() == WorkspaceRole.OWNER || userId.equals(workspace.getOwnerId())) {
                throw new PermissionDeniedException("OWNER 只能通过所有权转移流程变更");
            }
            target.setRole(newRole);
            target.setUpdatedAt(LocalDateTime.now());
            memberMapper.updateById(target);
            auditService.record(operatorId, workspaceId, "MEMBER", String.valueOf(userId),
                    "MEMBER_ROLE_UPDATE", "SUCCESS", null);
        } catch (RuntimeException e) {
            auditService.record(operatorId, workspaceId, "MEMBER", String.valueOf(userId),
                    "MEMBER_ROLE_UPDATE", "FAILURE", e.getMessage());
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(String workspaceId, Long userId) {
        Long operatorId = requireOperator();
        try {
            permissionEvaluator.checkWorkspacePermission(operatorId, workspaceId, WorkspacePermission.MEMBER_REMOVE);
            Workspace workspace = requireWorkspaceForUpdate(workspaceId);
            WorkspaceMember target = requireMemberForUpdate(workspaceId, userId);
            if (target.getRole() == WorkspaceRole.OWNER || userId.equals(workspace.getOwnerId())) {
                throw new PermissionDeniedException("OWNER 必须先完成所有权转移");
            }
            if (operatorId.equals(userId)) {
                throw new PermissionDeniedException("不能通过普通接口移除自己");
            }
            memberMapper.disable(workspaceId, userId, LocalDateTime.now());
            auditService.record(operatorId, workspaceId, "MEMBER", String.valueOf(userId),
                    "MEMBER_REMOVE", "SUCCESS", null);
        } catch (RuntimeException e) {
            auditService.record(operatorId, workspaceId, "MEMBER", String.valueOf(userId),
                    "MEMBER_REMOVE", "FAILURE", e.getMessage());
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void transferOwnership(String workspaceId, Long newOwnerId) {
        Long operatorId = requireOperator();
        try {
            Workspace workspace = requireWorkspaceForUpdate(workspaceId);
            if (!operatorId.equals(workspace.getOwnerId())) {
                throw new PermissionDeniedException("只有 OWNER 可以转移所有权");
            }
            if (newOwnerId == null || operatorId.equals(newOwnerId)) {
                throw new IllegalArgumentException("新 OWNER 无效");
            }
            WorkspaceMember oldOwner = requireMemberForUpdate(workspaceId, operatorId);
            WorkspaceMember newOwner = requireMemberForUpdate(workspaceId, newOwnerId);
            if (newOwner.getStatus() != MemberStatus.ACTIVE) {
                throw new PermissionDeniedException("新 OWNER 必须是有效成员");
            }
            LocalDateTime now = LocalDateTime.now();
            oldOwner.setRole(WorkspaceRole.ADMIN);
            oldOwner.setUpdatedAt(now);
            memberMapper.updateById(oldOwner);
            newOwner.setRole(WorkspaceRole.OWNER);
            newOwner.setUpdatedAt(now);
            memberMapper.updateById(newOwner);
            int updated = workspaceMapper.updateOwner(workspaceId, newOwnerId, operatorId, now);
            if (updated != 1) {
                throw new PermissionDeniedException("Workspace 所有权状态已发生变化");
            }
            auditService.record(operatorId, workspaceId, "WORKSPACE", workspaceId,
                    "OWNERSHIP_TRANSFER", "SUCCESS", null);
        } catch (RuntimeException e) {
            auditService.record(operatorId, workspaceId, "WORKSPACE", workspaceId,
                    "OWNERSHIP_TRANSFER", "FAILURE", e.getMessage());
            throw e;
        }
    }

    private Long requireOperator() {
        Long operatorId = currentUserProvider.getCurrentUserId();
        if (operatorId == null) {
            throw new IllegalStateException("未登录");
        }
        return operatorId;
    }

    private Workspace requireWorkspaceForUpdate(String workspaceId) {
        Workspace workspace = workspaceMapper.selectByIdForUpdate(workspaceId);
        if (workspace == null) {
            throw new ResponseStatusException(NOT_FOUND, "Workspace 不存在");
        }
        return workspace;
    }

    private WorkspaceMember requireMemberForUpdate(String workspaceId, Long userId) {
        WorkspaceMember member = memberMapper.selectByWorkspaceAndUserForUpdate(workspaceId, userId);
        if (member == null || member.getStatus() == MemberStatus.DISABLED) {
            throw new ResponseStatusException(NOT_FOUND, "成员不存在");
        }
        return member;
    }
}
