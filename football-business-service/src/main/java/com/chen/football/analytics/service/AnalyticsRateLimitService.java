package com.chen.football.analytics.service;

import com.chen.football.common.context.UserContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analytics is intentionally anonymous-friendly, but it must not be an
 * unbounded write endpoint.  This small local limiter protects the database
 * when Redis is unavailable; the gateway still provides a coarse global limit.
 */
@Service
public class AnalyticsRateLimitService {
    private static final long WINDOW_SECONDS = 60L;
    private static final int AUTHENTICATED_LIMIT = 120;
    private static final int ANONYMOUS_LIMIT = 60;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String remoteAddress) {
        Long userId = UserContext.getUserId();
        String key = userId == null
                ? "ip:" + (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress)
                : "user:" + userId;
        int limit = userId == null ? ANONYMOUS_LIMIT : AUTHENTICATED_LIMIT;
        long now = Instant.now().getEpochSecond();
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_SECONDS) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });
        return window.count <= limit;
    }

    @Scheduled(fixedDelay = 60_000L)
    void evictExpired() {
        long now = Instant.now().getEpochSecond();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_SECONDS);
    }

    private record Window(long startedAt, int count) {}
}
