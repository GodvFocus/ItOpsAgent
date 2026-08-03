package com.ai.itops.security.permission;

/** Workspace 固定角色。角色权限由 Java 代码维护，不从请求参数或数据库动态加载。 */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    EDITOR,
    VIEWER
}
