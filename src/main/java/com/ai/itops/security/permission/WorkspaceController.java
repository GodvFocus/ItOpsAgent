package com.ai.itops.security.permission;

import com.ai.itops.security.permission.dto.AddWorkspaceMemberRequest;
import com.ai.itops.security.permission.dto.CreateWorkspaceRequest;
import com.ai.itops.security.permission.dto.TransferOwnershipRequest;
import com.ai.itops.security.permission.dto.UpdateWorkspaceMemberRoleRequest;
import com.ai.itops.security.permission.dto.WorkspaceMemberResponse;
import com.ai.itops.security.permission.dto.WorkspaceResponse;
import com.ai.itops.auth.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Workspace 创建和成员管理接口，操作者身份始终来自认证上下文。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceMemberService memberService;
    private final SysUserMapper sysUserMapper;

    @PostMapping
    public WorkspaceResponse create(@RequestBody CreateWorkspaceRequest request) {
        return WorkspaceResponse.from(workspaceService.create(request == null ? null : request.name()));
    }

    @GetMapping("/{workspaceId}/members")
    public List<WorkspaceMemberResponse> members(@PathVariable String workspaceId) {
        return memberService.list(workspaceId).stream()
                .map(member -> WorkspaceMemberResponse.from(member, sysUserMapper.selectById(member.getUserId())))
                .toList();
    }

    @PostMapping("/{workspaceId}/members")
    public WorkspaceMemberResponse add(@PathVariable String workspaceId,
                                       @RequestBody AddWorkspaceMemberRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("成员请求不能为空");
        }
        return WorkspaceMemberResponse.from(memberService.add(workspaceId, request.userId(), request.role()));
    }

    @PatchMapping("/{workspaceId}/members/{userId}/role")
    public void changeRole(@PathVariable String workspaceId, @PathVariable Long userId,
                           @RequestBody UpdateWorkspaceMemberRoleRequest request) {
        memberService.changeRole(workspaceId, userId, request == null ? null : request.role());
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    public void remove(@PathVariable String workspaceId, @PathVariable Long userId) {
        memberService.remove(workspaceId, userId);
    }

    @PostMapping("/{workspaceId}/ownership/transfer")
    public void transfer(@PathVariable String workspaceId, @RequestBody TransferOwnershipRequest request) {
        memberService.transferOwnership(workspaceId, request == null ? null : request.newOwnerId());
    }
}
