package com.ai.itops.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功响应：token + 用户概要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    private Long userId;

    private String workspaceId;

    private String nickname;

    private String role;

    /** 当前 Workspace 内的角色，与系统级 role 分开维护。 */
    private String workspaceRole;
}
