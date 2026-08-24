package com.chen.football.user.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;

/**
 * 基于 Redis 的简单限流（INCR + EXPIRE）。
 */
@Service
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final boolean failClosed;
    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();

    public RateLimitService(StringRedisTemplate redisTemplate,
                            @Value("${security.rate-limit.fail-closed:false}") boolean failClosed) {
        this.redisTemplate = redisTemplate;
        this.failClosed = failClosed;
    }

    /**
     * @param key            限流键（如 register:127.0.0.1）
     * @param max            窗口内最大次数
     * @param windowSeconds  窗口秒数
     */
    public boolean isAllowed(String key, int max, int windowSeconds) {
        String redisKey = "rate:limit:" + key;
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
            }
            return count != null && count <= max;
        } catch (RuntimeException unavailable) {
            // In production, a distributed limiter outage must fail closed so
            // attackers cannot bypass registration/login/email protections by
            // forcing Redis unavailable. Local fallback remains useful for
            // explicitly configured development environments.
            if (failClosed) return false;
            long now = Instant.now().getEpochSecond();
            Window window = localWindows.compute(key, (ignored, current) -> {
                if (current == null || now - current.startedAt >= windowSeconds) return new Window(now, 1);
                return new Window(current.startedAt, current.count + 1);
            });
            return window.count <= max;
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000L)
    void evictLocalWindows() {
        long now = Instant.now().getEpochSecond();
        localWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= 300);
    }

    private record Window(long startedAt, long count) {}
}
