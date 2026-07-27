package com.geuneul.global.web;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Atomic shared fixed-window counters. Callers retain their bounded in-memory limiter as Redis fallback. */
@Component
public class RedisFixedWindowRateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local minute = tonumber(redis.call('GET', KEYS[1]) or '0')
            local hour = tonumber(redis.call('GET', KEYS[2]) or '0')
            if minute >= tonumber(ARGV[1]) or (tonumber(ARGV[2]) > 0 and hour >= tonumber(ARGV[2])) then
              return 0
            end
            minute = redis.call('INCR', KEYS[1])
            if minute == 1 then redis.call('EXPIRE', KEYS[1], 70) end
            if tonumber(ARGV[2]) > 0 then
              hour = redis.call('INCR', KEYS[2])
              if hour == 1 then redis.call('EXPIRE', KEYS[2], 3700) end
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Empty means Redis was unavailable and the caller must use its local fail-safe limiter. */
    public Optional<Boolean> tryAcquire(String scope, String clientKey, int perMinute, int perHour) {
        long epoch = clock.instant().getEpochSecond();
        String identity = sha256(clientKey);
        List<String> keys = List.of(
                "rate:" + scope + ":m:" + (epoch / 60) + ":" + identity,
                "rate:" + scope + ":h:" + (epoch / 3600) + ":" + identity);
        try {
            Long result = redis.execute(SCRIPT, keys, String.valueOf(perMinute), String.valueOf(perHour));
            return result == null ? Optional.empty() : Optional.of(result == 1L);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "unknown" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
