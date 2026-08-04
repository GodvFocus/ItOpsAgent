package com.ai.itops.auth.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.itops.auth.context.UserContext;
import com.ai.itops.security.permission.WorkspaceRole;
import com.ai.itops.auth.config.AuthProperties;
import com.ai.itops.common.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Redis 的会话存储：token → JSON(UserContext)，TTL 与 fish.auth.session-ttl-seconds 对齐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSessionManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    /**
     * 创建新会话并写入 Redis。
     *
     * @param ctx 用户信息（不含 token）
     * @return 随机生成的 token（UUID 无横线）
     */
    public String createSession(UserContext ctx) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = sessionKey(token);
        try {
            String json = objectMapper.writeValueAsString(ctx);
            stringRedisTemplate.opsForValue().set(key, json, ttl());
            return token;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("session serialize failed", e);
        }
    }

    /**
     * 根据 token 读取会话。
     *
     * @param token 前端传入的会话令牌
     * @return 用户上下文
     */
    public Optional<UserContext> getSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String raw = stringRedisTemplate.opsForValue().get(sessionKey(token));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, UserContext.class));
        } catch (JsonProcessingException e) {
            log.warn("[RedisSession] JSON 反序列化失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 更新当前 Workspace，只替换 Workspace 字段，保留原有登录身份和系统角色。 */
    public UserContext updateWorkspace(String token, String workspaceId, WorkspaceRole workspaceRole) {
        UserContext current = getSession(token)
                .orElseThrow(() -> new IllegalStateException("未登录或会话已过期"));
        UserContext updated = new UserContext(current.userId(), workspaceId, current.username(),
                current.nickname(), current.role(), workspaceRole == null ? null : workspaceRole.name());
        try {
            stringRedisTemplate.opsForValue().set(sessionKey(token), objectMapper.writeValueAsString(updated), ttl());
            return updated;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("session serialize failed", e);
        }
    }

    /**
     * 刷新会话 TTL（活跃续期）。
     *
     * @param token 会话令牌
     */
    public void refreshTtl(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = sessionKey(token);
        Boolean expire = stringRedisTemplate.expire(key, ttl());
        if (Boolean.FALSE.equals(expire)) {
            log.debug("[RedisSession] 续期失败，key 可能已失效 {}", key);
        }
    }

    /**
     * 登出：删除 Redis 中的会话键。
     *
     * @param token 会话令牌
     */
    public void remove(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(sessionKey(token));
    }

    private String sessionKey(String token) {
        return RedisKeys.session(token);
    }

    private Duration ttl() {
        long sec = Math.max(60L, authProperties.getSessionTtlSeconds());
        return Duration.ofSeconds(sec);
    }
}
