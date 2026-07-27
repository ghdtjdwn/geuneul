package com.geuneul.global.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisFixedWindowRateLimiterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("Lua 결과 1/0을 shared allow/deny로 변환한다")
    void mapsAtomicScriptResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(), any(), any())).thenReturn(1L, 0L);
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(redis, CLOCK);

        assertThat(limiter.tryAcquire("reports", "c:1", 3, 10)).contains(true);
        assertThat(limiter.tryAcquire("reports", "c:1", 3, 10)).contains(false);
    }

    @Test
    @DisplayName("Redis 장애는 empty로 내려 호출자가 bounded local limiter로 폴백하게 한다")
    void redisFailureUsesCallerFallback() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("offline"));

        assertThat(new RedisFixedWindowRateLimiter(redis, CLOCK)
                .tryAcquire("reports", "c:1", 3, 10)).isEmpty();
    }
}
