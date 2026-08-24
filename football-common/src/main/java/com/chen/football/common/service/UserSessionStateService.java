package com.chen.football.common.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Small cross-service account state cache.  Access tokens remain stateless,
 * but an account can still be disabled or have its role changed immediately
 * when Redis is available.  If Redis is down, JWT expiry remains the fallback
 * so a cache outage does not log out every user.
 */
@Service
public class UserSessionStateService {
    private static final String PREFIX = "auth:user-state:";
    private static final Duration TTL = Duration.ofDays(30);
    private final StringRedisTemplate redisTemplate;

    public UserSessionStateService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void markActive(Long userId, String role) {
        if (userId == null) return;
        write(userId, "ACTIVE|" + (role == null ? "USER" : role));
    }

    public void markDisabled(Long userId) {
        if (userId == null) return;
        write(userId, "DISABLED|");
    }

    public boolean isAllowed(Long userId) {
        State state = read(userId);
        return state == null || !"DISABLED".equalsIgnoreCase(state.status());
    }

    public String roleOverride(Long userId) {
        State state = read(userId);
        return state == null || state.role() == null || state.role().isBlank() ? null : state.role();
    }

    private void write(Long userId, String value) {
        try {
            redisTemplate.opsForValue().set(PREFIX + userId, value, TTL);
        } catch (RuntimeException ignored) {
            // Account operations must still succeed if Redis is momentarily down.
        }
    }

    private State read(Long userId) {
        if (userId == null) return null;
        try {
            String raw = redisTemplate.opsForValue().get(PREFIX + userId);
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.split("\\|", -1);
            return new State(parts[0], parts.length > 1 ? parts[1] : null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record State(String status, String role) { }
}
