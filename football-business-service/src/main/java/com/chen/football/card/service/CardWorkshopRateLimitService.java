package com.chen.football.card.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects the Wikipedia-backed persona endpoints.  The Redis counter makes
 * the limit useful across business-service instances; the local fallback
 * still protects the process when Redis is unavailable.
 */
@Service
public class CardWorkshopRateLimitService {
    private static final long WINDOW_SECONDS = 60L;
    private static final int PREVIEW_LIMIT = 6;
    private static final int CREATE_LIMIT = 3;
    private static final int IP_PREVIEW_LIMIT = 30;
    private static final int IP_CREATE_LIMIT = 12;
    private static final String REDIS_PREFIX = "rate:card-lab:";

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();

    public CardWorkshopRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean allow(Long userId, String operation) {
        return allow(userId, operation, "");
    }

    public boolean allow(Long userId, String operation, String clientIp) {
        String key = "user:" + userId + ":" + operation;
        int limit = "create".equals(operation) ? CREATE_LIMIT : PREVIEW_LIMIT;
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        String ipKey = "ip:" + ip + ":" + operation;
        int ipLimit = "create".equals(operation) ? IP_CREATE_LIMIT : IP_PREVIEW_LIMIT;
        if (!allowKey(key, limit)) return false;
        return allowKey(ipKey, ipLimit);
    }

    private boolean allowKey(String key, int limit) {
        try {
            Long count = redisTemplate.opsForValue().increment(REDIS_PREFIX + key);
            if (count != null && count == 1L) {
                redisTemplate.expire(REDIS_PREFIX + key, Duration.ofSeconds(WINDOW_SECONDS));
            }
            return count != null ? count <= limit : allowLocal(key, limit);
        } catch (RuntimeException ignored) {
            return allowLocal(key, limit);
        }
    }

    private boolean allowLocal(String key, int limit) {
        long now = Instant.now().getEpochSecond();
        Window next = localWindows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_SECONDS) return new Window(now, 1);
            return new Window(current.startedAt, current.count + 1);
        });
        return next.count <= limit;
    }

    @Scheduled(fixedDelay = 60_000L)
    void evictExpired() {
        long now = Instant.now().getEpochSecond();
        localWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_SECONDS);
    }

    private record Window(long startedAt, int count) {}
}
