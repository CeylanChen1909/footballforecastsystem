package com.chen.football.common.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class DistributedLockService {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end", Long.class);

    private final StringRedisTemplate redisTemplate;

    public DistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(lockKey(key), token, ttl);
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (RuntimeException ex) {
            // A distributed lock must fail closed. The next scheduler tick
            // can retry instead of overlapping jobs during a Redis outage.
            return null;
        }
    }

    public void unlock(String key, String token) {
        try {
            if (token != null && !token.isBlank()) {
                redisTemplate.execute(UNLOCK_SCRIPT,
                        java.util.List.of(lockKey(key)), token);
            }
        } catch (RuntimeException ignored) {
            // TTL expiry is the recovery path when Redis is temporarily down.
        }
    }

    /** Extend a lease only when this process still owns it. */
    public boolean renew(String key, String token, Duration ttl) {
        try {
            if (token == null || token.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) return false;
            Long renewed = redisTemplate.execute(RENEW_SCRIPT,
                    java.util.List.of(lockKey(key)), token, String.valueOf(Math.max(1, ttl.toSeconds())));
            return Long.valueOf(1L).equals(renewed);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String lockKey(String key) {
        return "lock:" + key;
    }
}
