package com.ai.itops.common.ratelimit;

import com.ai.itops.common.config.RateLimitProperties;
import com.ai.itops.common.redis.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    @Test
    void shouldReleaseLockByOwnerTokenInsteadOfPlainDelete() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(RedisKeys.mutexSession("sid")), anyString(), eq(Duration.ofSeconds(120))))
                .thenReturn(true);
        when(redis.execute(any(DefaultRedisScript.class), eq(List.of(RedisKeys.mutexSession("sid"))), anyString()))
                .thenReturn(1L);

        RateLimitService service = new RateLimitService(new RateLimitProperties(), redis);
        RateLimitService.SessionLockHandle handle = service.tryAcquireSessionLock(7L, "sid");

        assertThat(handle).isNotNull();
        assertThat(handle.isManaged()).isTrue();
        service.releaseSessionLock(7L, handle);

        verify(redis, never()).delete(anyString());
        verify(redis).execute(any(DefaultRedisScript.class), eq(List.of(RedisKeys.mutexSession("sid"))), eq(handle.requestToken()));
    }

    @Test
    void shouldReturnNullWhenSessionLockIsAlreadyHeld() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(RedisKeys.mutexSession("sid")), anyString(), eq(Duration.ofSeconds(120))))
                .thenReturn(false);

        RateLimitService service = new RateLimitService(new RateLimitProperties(), redis);

        assertThat(service.tryAcquireSessionLock(7L, "sid")).isNull();
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailableDuringAcquire() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(RedisKeys.mutexSession("sid")), anyString(), eq(Duration.ofSeconds(120))))
                .thenThrow(new RuntimeException("redis down"));

        RateLimitService service = new RateLimitService(new RateLimitProperties(), redis);
        RateLimitService.SessionLockHandle handle = service.tryAcquireSessionLock(7L, "sid");

        assertThat(handle).isNotNull();
        assertThat(handle.isManaged()).isFalse();
        assertThat(service.refreshSessionLock(7L, handle)).isTrue();
        service.releaseSessionLock(7L, handle);

        verify(redis, never()).execute(any(DefaultRedisScript.class), eq(List.of(RedisKeys.mutexSession("sid"))), anyString());
    }

    @Test
    void shouldReportLostOwnershipWhenWatchdogCanNotRenew() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(RedisKeys.mutexSession("sid")), anyString(), eq(Duration.ofSeconds(120))))
                .thenReturn(true);
        when(redis.execute(
                any(DefaultRedisScript.class),
                eq(List.of(RedisKeys.mutexSession("sid"))),
                anyString(),
                eq("120")))
                .thenReturn(0L);

        RateLimitService service = new RateLimitService(new RateLimitProperties(), redis);
        RateLimitService.SessionLockHandle handle = service.tryAcquireSessionLock(7L, "sid");

        assertThat(service.refreshSessionLock(7L, handle)).isFalse();
    }
}
