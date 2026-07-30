package com.yuyu.fishagent.common.ratelimit;

import com.yuyu.fishagent.common.config.RateLimitProperties;
import com.yuyu.fishagent.common.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis Lua 的对话限流服务：令牌桶、SSE 并发，以及带所有权令牌的会话互斥锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    /**
     * 令牌桶：原子 refill + consume；返回 1=放行，0=拒绝。
     */
    private static final String LUA_TOKEN_BUCKET = """
            local key    = KEYS[1]
            local cap    = tonumber(ARGV[1])
            local rate   = tonumber(ARGV[2])
            local now    = tonumber(ARGV[3])
            local need   = tonumber(ARGV[4])
            local ttl    = tonumber(ARGV[5])

            local data       = redis.call('HMGET', key, 'tokens', 'lastRefillTime')
            local tokens     = tonumber(data[1]) or cap
            local lastRefill = tonumber(data[2]) or now

            local elapsed = math.max(0, now - lastRefill)
            tokens = math.min(cap, tokens + elapsed * rate / 1000)

            local allowed = 0
            if tokens >= need then
                tokens  = tokens - need
                allowed = 1
            end

            redis.call('HSET', key, 'tokens', tokens, 'lastRefillTime', now)
            redis.call('EXPIRE', key, ttl)
            return allowed
            """;

    /**
     * SSE 并发：INCR 后若超过上限则 DECR 回滚并返回 -1。
     */
    private static final String LUA_SSE_TRY_INCREMENT = """
            local key = KEYS[1]
            local max = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])

            local cur = redis.call('INCR', key)
            redis.call('EXPIRE', key, ttl)
            if cur > max then
                redis.call('DECR', key)
                return -1
            end
            return cur
            """;

    /** SSE 结束：计数安全递减，不小于 0。 */
    private static final String LUA_SSE_DECREMENT = """
            local key = KEYS[1]
            local v = tonumber(redis.call('GET', key))
            if v == nil or v <= 0 then
                return 0
            end
            return redis.call('DECR', key)
            """;

    /**
     * 释放锁时先校验 request token，避免旧请求误删新锁。
     */
    private static final String LUA_SESSION_LOCK_COMPARE_AND_DELETE = """
            local key = KEYS[1]
            local token = ARGV[1]
            if redis.call('GET', key) == token then
                return redis.call('DEL', key)
            end
            return 0
            """;

    /**
     * watchdog 续租时同样要校验 request token，避免把别人的锁续上。
     */
    private static final String LUA_SESSION_LOCK_COMPARE_AND_EXPIRE = """
            local key = KEYS[1]
            local token = ARGV[1]
            local ttl = tonumber(ARGV[2])
            if redis.call('GET', key) == token then
                return redis.call('EXPIRE', key, ttl)
            end
            return 0
            """;

    private final RateLimitProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    private final DefaultRedisScript<Long> tokenBucketScript = script(LUA_TOKEN_BUCKET);
    private final DefaultRedisScript<Long> sseTryIncrScript = script(LUA_SSE_TRY_INCREMENT);
    private final DefaultRedisScript<Long> sseDecrScript = script(LUA_SSE_DECREMENT);
    private final DefaultRedisScript<Long> sessionLockReleaseScript = script(LUA_SESSION_LOCK_COMPARE_AND_DELETE);
    private final DefaultRedisScript<Long> sessionLockRenewScript = script(LUA_SESSION_LOCK_COMPARE_AND_EXPIRE);

    /**
     * 会话锁句柄：只在 managed=true 时对 Redis 中的锁拥有明确所有权。
     */
    public record SessionLockHandle(String sessionId, String requestToken, boolean managed) {

        public static SessionLockHandle noop(String sessionId) {
            return new SessionLockHandle(sessionId, "", false);
        }

        public static SessionLockHandle failOpen(String sessionId) {
            return new SessionLockHandle(sessionId, "", false);
        }

        public static SessionLockHandle managed(String sessionId, String requestToken) {
            return new SessionLockHandle(sessionId, requestToken, true);
        }

        public boolean isManaged() {
            return managed
                    && sessionId != null && !sessionId.isBlank()
                    && requestToken != null && !requestToken.isBlank();
        }
    }

    /**
     * 按顺序执行：令牌桶 ->（可选）占用 SSE 槽位。
     *
     * @param userId         当前用户 ID
     * @param acquireSseSlot 是否为 {@code POST /api/chat/stream}，需要占用 SSE 并发计数
     */
    public RateLimitResult evaluate(long userId, boolean acquireSseSlot) {
        if (!properties.isEnabled()) {
            return new RateLimitResult.Allowed();
        }
        RateLimitProperties.TokenBucket tb = properties.getTokenBucket();
        RateLimitProperties.SseConcurrent sc = properties.getSseConcurrent();
        try {
            List<String> tokenKey = List.of(RedisKeys.rateToken(userId));
            long now = System.currentTimeMillis();
            Long allowed = stringRedisTemplate.execute(
                    tokenBucketScript,
                    tokenKey,
                    String.valueOf(tb.capacity()),
                    String.valueOf(tb.refillRate()),
                    String.valueOf(now),
                    "1",
                    String.valueOf(tb.keyTtl()));

            if (allowed == null || allowed == 0L) {
                return new RateLimitResult.TokenBucketDenied(estimateRetryAfterSeconds(tb.refillRate()));
            }

            if (acquireSseSlot) {
                List<String> sseKey = List.of(RedisKeys.rateSse(userId));
                Long sseSlot = stringRedisTemplate.execute(
                        sseTryIncrScript,
                        sseKey,
                        String.valueOf(sc.maxConnections()),
                        String.valueOf(sc.keyTtl()));
                if (sseSlot == null || sseSlot < 0L) {
                    return new RateLimitResult.ConcurrentDenied();
                }
            }

            return new RateLimitResult.Allowed();
        } catch (Exception e) {
            log.warn("[RateLimitService] Redis 限流执行失败 userId={}, sseSlot={}, err={}",
                    userId, acquireSseSlot, e.getMessage());
            return new RateLimitResult.Allowed();
        }
    }

    /**
     * 释放 SSE 并发计数（连接结束或中途异常退出时调用）；幂等安全。
     */
    public void decrementSseConcurrent(long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            stringRedisTemplate.execute(
                    sseDecrScript,
                    Collections.singletonList(RedisKeys.rateSse(userId)));
        } catch (Exception e) {
            log.warn("[RateLimitService] SSE 计数递减失败 userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 尝试占用会话级分布式锁。
     * <p>锁值改为 request token；Redis 异常时仍沿用 fail-open，避免基础设施故障阻断全部对话。</p>
     *
     * @param userId    当前用户，仅用于日志
     * @param sessionId 会话 ID
     * @return 锁句柄；返回 {@code null} 表示已有其他请求持有锁
     */
    public SessionLockHandle tryAcquireSessionLock(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionLockHandle.noop(sessionId);
        }
        String requestToken = UUID.randomUUID().toString();
        try {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(
                    RedisKeys.mutexSession(sessionId),
                    requestToken,
                    Duration.ofSeconds(sessionLockTtlSeconds()));
            if (Boolean.TRUE.equals(ok)) {
                return SessionLockHandle.managed(sessionId, requestToken);
            }
            return null;
        } catch (Exception e) {
            log.warn("[RateLimitService] 会话锁获取异常 userId={} sid={}, fail-open: {}",
                    userId, sessionId, e.getMessage());
            return SessionLockHandle.failOpen(sessionId);
        }
    }

    /**
     * watchdog 续租。只有当前请求仍然是锁 owner 时才允许刷新 TTL。
     *
     * @return true 表示仍持有或无需管理该锁；false 表示锁已过期或被其他请求接管
     */
    public boolean refreshSessionLock(Long userId, SessionLockHandle lockHandle) {
        if (lockHandle == null || !lockHandle.isManaged()) {
            return true;
        }
        try {
            Long renewed = stringRedisTemplate.execute(
                    sessionLockRenewScript,
                    List.of(RedisKeys.mutexSession(lockHandle.sessionId())),
                    lockHandle.requestToken(),
                    String.valueOf(sessionLockTtlSeconds()));
            if (Long.valueOf(1L).equals(renewed)) {
                return true;
            }
            log.warn("[RateLimitService] 会话锁续租失败 userId={} sid={}, token 已失效或所有权已变化",
                    userId, lockHandle.sessionId());
            return false;
        } catch (Exception e) {
            log.warn("[RateLimitService] 会话锁续租异常 userId={} sid={}: {}",
                    userId, lockHandle.sessionId(), e.getMessage());
            return false;
        }
    }

    /**
     * 释放会话锁；只有 owner token 匹配时才会真正删除 key。
     */
    public void releaseSessionLock(Long userId, SessionLockHandle lockHandle) {
        if (lockHandle == null || !lockHandle.isManaged()) {
            return;
        }
        try {
            Long released = stringRedisTemplate.execute(
                    sessionLockReleaseScript,
                    List.of(RedisKeys.mutexSession(lockHandle.sessionId())),
                    lockHandle.requestToken());
            if (Long.valueOf(0L).equals(released)) {
                log.debug("[RateLimitService] 会话锁释放跳过 userId={} sid={}, key 已过期或所有权已变化",
                        userId, lockHandle.sessionId());
            }
        } catch (Exception e) {
            log.warn("[RateLimitService] 会话锁释放异常 userId={} sid={}: {}",
                    userId, lockHandle.sessionId(), e.getMessage());
        }
    }

    /**
     * 返回会话锁 watchdog 续租周期。
     */
    public Duration sessionLockWatchdogInterval() {
        RateLimitProperties.SessionMutex mutex = sessionMutex();
        int ttlSeconds = sessionLockTtlSeconds();
        int rawInterval = Math.max(1, mutex.watchdogIntervalSeconds());
        int boundedInterval = Math.min(rawInterval, Math.max(1, ttlSeconds - 1));
        return Duration.ofSeconds(boundedInterval);
    }

    private int sessionLockTtlSeconds() {
        return Math.max(5, sessionMutex().ttlSeconds());
    }

    private RateLimitProperties.SessionMutex sessionMutex() {
        RateLimitProperties.SessionMutex mutex = properties.getSessionMutex();
        return mutex == null ? new RateLimitProperties.SessionMutex(120, 40) : mutex;
    }

    private static int estimateRetryAfterSeconds(double refillRatePerSecond) {
        if (refillRatePerSecond <= 0) {
            return 60;
        }
        return Math.max(1, (int) Math.ceil(1.0 / refillRatePerSecond));
    }

    private static DefaultRedisScript<Long> script(String lua) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(lua);
        s.setResultType(Long.class);
        return s;
    }
}
