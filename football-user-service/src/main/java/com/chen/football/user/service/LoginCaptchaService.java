package com.chen.football.user.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;

/** 登录失败达到阈值后启用的轻量数学验证码，避免引入第三方图形验证码依赖。 */
@Service
public class LoginCaptchaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CAPTCHA_PREFIX = "security:login-captcha:";
    private static final String FAILURE_PREFIX = "security:login-failure:";
    private final StringRedisTemplate redisTemplate;
    private final RateLimitService rateLimitService;
    private final Map<String, CaptchaEntry> localCaptchas = new ConcurrentHashMap<>();
    private final Map<String, FailureEntry> localFailures = new ConcurrentHashMap<>();

    public LoginCaptchaService(StringRedisTemplate redisTemplate, RateLimitService rateLimitService) {
        this.redisTemplate = redisTemplate;
        this.rateLimitService = rateLimitService;
    }

    public Map<String, Object> issue(String ip) {
        if (!rateLimitService.isAllowed("captcha:" + ip, 20, 60)) return Map.of("ok", false, "message", "验证码请求过于频繁");
        int a = 1 + RANDOM.nextInt(9);
        int b = 1 + RANDOM.nextInt(9);
        String id = UUID.randomUUID().toString();
        try {
            redisTemplate.opsForValue().set(CAPTCHA_PREFIX + id, String.valueOf(a + b), Duration.ofMinutes(5));
        } catch (RuntimeException unavailable) {
            localCaptchas.put(id, new CaptchaEntry(String.valueOf(a + b), Instant.now().plusSeconds(300)));
        }
        return Map.of("ok", true, "captchaId", id, "question", a + " + " + b + " = ?", "expiresInSeconds", 300);
    }

    public boolean isRequired(String ip, String account) {
        try {
            String value = redisTemplate.opsForValue().get(failureKey(ip, account));
            try { return value != null && Integer.parseInt(value) >= 3; } catch (NumberFormatException ignored) { return false; }
        } catch (RuntimeException unavailable) {
            FailureEntry entry = localFailures.get(failureKey(ip, account));
            return entry != null && !entry.expiresAt().isBefore(Instant.now()) && entry.count() >= 3;
        }
    }

    public boolean verifyAndConsume(String id, String answer) {
        if (id == null || id.isBlank() || answer == null || answer.isBlank()) return false;
        String key = CAPTCHA_PREFIX + id.trim();
        String expected;
        try {
            expected = redisTemplate.opsForValue().get(key);
            redisTemplate.delete(key);
        } catch (RuntimeException unavailable) {
            CaptchaEntry entry = localCaptchas.remove(id.trim());
            expected = entry != null && !entry.expiresAt().isBefore(Instant.now()) ? entry.answer() : null;
        }
        return expected != null && expected.equals(answer.trim());
    }

    public boolean recordFailure(String ip, String account) {
        String key = failureKey(ip, account);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) redisTemplate.expire(key, Duration.ofMinutes(15));
            return count != null && count >= 3;
        } catch (RuntimeException unavailable) {
            Instant now = Instant.now();
            FailureEntry entry = localFailures.compute(key, (ignored, current) -> {
                if (current == null || current.expiresAt().isBefore(now)) return new FailureEntry(1, now.plusSeconds(900));
                return new FailureEntry(current.count() + 1, current.expiresAt());
            });
            return entry.count() >= 3;
        }
    }

    public void clearFailures(String ip, String account) {
        String key = failureKey(ip, account);
        try { redisTemplate.delete(key); } catch (RuntimeException ignored) { }
        localFailures.remove(key);
    }

    /** Redis outage fallback must not turn every captcha request into a
     * permanent in-process map entry. */
    @Scheduled(fixedDelay = 60_000L)
    void evictExpiredLocalState() {
        Instant now = Instant.now();
        localCaptchas.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        localFailures.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String failureKey(String ip, String account) { return FAILURE_PREFIX + ip + ":" + (account == null ? "" : account.trim().toLowerCase()); }

    private record CaptchaEntry(String answer, Instant expiresAt) { }
    private record FailureEntry(int count, Instant expiresAt) { }
}
