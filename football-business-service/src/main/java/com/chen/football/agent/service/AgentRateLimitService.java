package com.chen.football.agent.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 轻量级 Agent 保护阀，避免单个账号或匿名客户端持续消耗外部模型额度。 */
@Service
public class AgentRateLimitService {
    private static final long WINDOW_SECONDS = 60;
    private static final String REDIS_PREFIX = "rate:agent:";
    private final StringRedisTemplate redisTemplate;
    private final int authenticatedLimit;
    private final int anonymousLimit;
    private final boolean failClosed;
    private final int dailyTokenBudget;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, TokenWindow> tokenWindows = new ConcurrentHashMap<>();

    public AgentRateLimitService(StringRedisTemplate redisTemplate,
                                 @Value("${agent.rate-limit.authenticated-per-minute:30}") int authenticatedLimit,
                                 @Value("${agent.rate-limit.anonymous-per-minute:8}") int anonymousLimit,
                                 @Value("${agent.rate-limit.fail-closed:true}") boolean failClosed,
                                 @Value("${agent.rate-limit.daily-token-budget:20000}") int dailyTokenBudget) {
        this.redisTemplate = redisTemplate;
        this.authenticatedLimit = Math.max(1, authenticatedLimit);
        this.anonymousLimit = Math.max(1, anonymousLimit);
        this.failClosed = failClosed;
        this.dailyTokenBudget = Math.max(512, dailyTokenBudget);
    }

    /** A second, daily budget protects against expensive long-context requests. */
    public boolean allowTokenBudget(Long userId, int requestedTokens) {
        String subject = userId == null ? "anonymous" : "user:" + userId;
        int amount = Math.max(128, Math.min(4096, requestedTokens));
        String key = REDIS_PREFIX + "tokens:" + subject + ":" + LocalDate.now();
        try {
            Long used = redisTemplate.opsForValue().increment(key, amount);
            if (used != null && used == amount) redisTemplate.expire(key, Duration.ofDays(2));
            if (used == null || used <= dailyTokenBudget) return used != null;
            redisTemplate.opsForValue().decrement(key, amount);
            return false;
        } catch (RuntimeException ex) {
            if (failClosed) return false;
            long day = LocalDate.now().toEpochDay();
            TokenWindow next = tokenWindows.compute(subject, (ignored, current) -> {
                if (current == null || current.day() != day) return new TokenWindow(day, amount);
                return new TokenWindow(day, current.used() + amount);
            });
            return next.used() <= dailyTokenBudget;
        }
    }

    public boolean allow(Long userId) {
        String key = userId == null ? "anonymous" : "user:" + userId;
        int limit = userId == null ? anonymousLimit : authenticatedLimit;
        try {
            Long count = redisTemplate.opsForValue().increment(REDIS_PREFIX + key);
            if (count != null && count == 1L) {
                redisTemplate.expire(REDIS_PREFIX + key, Duration.ofSeconds(WINDOW_SECONDS));
            }
            return count != null ? count <= limit : allowLocal(key, limit);
        } catch (RuntimeException ex) {
            // 生产环境宁可暂时拒绝昂贵模型请求，也不能在 Redis 故障时
            // 让每个实例各自放行一整套额度。
            return failClosed ? false : allowLocal(key, limit);
        }
    }

    private boolean allowLocal(String key, int limit) {
        long now = Instant.now().getEpochSecond();
        Window next = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_SECONDS) return new Window(now, 1);
            return new Window(current.startedAt, current.count + 1);
        });
        if (next.count <= limit) return true;
        windows.computeIfPresent(key, (ignored, current) -> current.count > limit ? new Window(current.startedAt, limit + 1) : current);
        return false;
    }

    @Scheduled(fixedDelay = 60_000L)
    void evictExpiredLocalWindows() {
        long now = Instant.now().getEpochSecond();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_SECONDS);
        long day = LocalDate.now().toEpochDay();
        tokenWindows.entrySet().removeIf(entry -> entry.getValue().day() != day);
    }

    private record Window(long startedAt, int count) {}
    private record TokenWindow(long day, long used) {}
}
