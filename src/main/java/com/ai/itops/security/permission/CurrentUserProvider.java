package com.ai.itops.security.permission;

/** 从可信认证上下文读取当前用户，业务层不得从请求体推断操作者。 */
public interface CurrentUserProvider {

    Long getCurrentUserId();

    default String getCurrentWorkspaceId() {
        return com.ai.itops.auth.context.UserContextHolder.currentWorkspaceIdOrNull();
    }
}
