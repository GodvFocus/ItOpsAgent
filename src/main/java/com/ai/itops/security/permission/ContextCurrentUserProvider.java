package com.ai.itops.security.permission;

import com.ai.itops.auth.context.UserContextHolder;
import org.springframework.stereotype.Component;

/** 适配项目现有 Redis 会话拦截器写入的 ThreadLocal 用户上下文。 */
@Component
public class ContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public Long getCurrentUserId() {
        return UserContextHolder.currentUserIdOrNull();
    }
}
