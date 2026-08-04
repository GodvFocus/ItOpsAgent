package com.ai.itops.auth.context;

/**
 * 当前登录用户上下文，存入 Redis 会话并在请求线程写入 {@link UserContextHolder}。
 *
 * @param userId   用户主键
 * @param username 登录账号
 * @param nickname 展示昵称
 * @param role     角色枚举名（如 USER）
 */
public record UserContext(Long userId, String workspaceId, String username, String nickname, String role,
                          String workspaceRole) {

    /** 兼容历史 Redis Session，旧会话没有 workspaceRole 时由业务层重新查询成员关系。 */
    public UserContext(Long userId, String workspaceId, String username, String nickname, String role) {
        this(userId, workspaceId, username, nickname, role, null);
    }
}
