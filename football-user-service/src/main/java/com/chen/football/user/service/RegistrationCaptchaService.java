package com.chen.football.user.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registration-only visual CAPTCHA.  The SVG is generated server-side and
 * the answer is stored server-side, so the browser never receives the answer.
 */
@Service
public class RegistrationCaptchaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String REDIS_PREFIX = "security:register-captcha:";
    private static final int LENGTH = 5;
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitService rateLimitService;
    private final Map<String, CaptchaEntry> localEntries = new ConcurrentHashMap<>();

    public RegistrationCaptchaService(StringRedisTemplate redisTemplate, RateLimitService rateLimitService) {
        this.redisTemplate = redisTemplate;
        this.rateLimitService = rateLimitService;
    }

    public Map<String, Object> issue(String ip) {
        if (!rateLimitService.isAllowed("register-captcha:" + safeIp(ip), 30, 60)) {
            return Map.of("ok", false, "message", "图形验证码请求过于频繁，请稍后再试");
        }
        localEntries.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        String answer = randomAnswer();
        String id = UUID.randomUUID().toString();
        String value = answer + "|" + fingerprint(ip);
        try {
            redisTemplate.opsForValue().set(REDIS_PREFIX + id, value, TTL);
        } catch (RuntimeException unavailable) {
            localEntries.put(id, new CaptchaEntry(answer, fingerprint(ip), Instant.now().plus(TTL)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("captchaId", id);
        result.put("image", svgDataUri(answer));
        result.put("expiresInSeconds", TTL.toSeconds());
        return result;
    }

    public boolean verifyAndConsume(String captchaId, String answer, String ip) {
        if (captchaId == null || captchaId.isBlank() || answer == null || answer.isBlank()) return false;
        String id = captchaId.trim();
        String expected = null;
        try {
            expected = redisTemplate.opsForValue().get(REDIS_PREFIX + id);
            redisTemplate.delete(REDIS_PREFIX + id);
        } catch (RuntimeException unavailable) {
            CaptchaEntry entry = localEntries.remove(id);
            if (entry != null && entry.expiresAt().isAfter(Instant.now()) && entry.ipHash().equals(fingerprint(ip))) {
                expected = entry.answer() + "|" + entry.ipHash();
            }
        }
        if (expected == null) {
            CaptchaEntry entry = localEntries.remove(id);
            if (entry != null && entry.expiresAt().isAfter(Instant.now()) && entry.ipHash().equals(fingerprint(ip))) {
                expected = entry.answer() + "|" + entry.ipHash();
            }
        }
        if (expected == null) return false;
        String[] parts = expected.split("\\|", 2);
        return parts.length == 2
                && parts[1].equals(fingerprint(ip))
                && parts[0].equalsIgnoreCase(answer.trim());
    }

    private String randomAnswer() {
        StringBuilder answer = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) answer.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        return answer.toString();
    }

    private String svgDataUri(String answer) {
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='180' height='56' viewBox='0 0 180 56'>");
        svg.append("<rect width='180' height='56' rx='8' fill='#f3f7f5'/>");
        for (int i = 0; i < 7; i++) {
            int x1 = RANDOM.nextInt(180), y1 = RANDOM.nextInt(56), x2 = RANDOM.nextInt(180), y2 = RANDOM.nextInt(56);
            svg.append("<path d='M").append(x1).append(' ').append(y1).append(" L").append(x2).append(' ').append(y2)
                    .append("' stroke='#").append(String.format("%06x", RANDOM.nextInt(0x999999)))
                    .append("' stroke-width='1' opacity='.35'/>");
        }
        for (int i = 0; i < answer.length(); i++) {
            int x = 18 + i * 33;
            int y = 36 + RANDOM.nextInt(7) - 3;
            int rotate = RANDOM.nextInt(25) - 12;
            svg.append("<text x='").append(x).append("' y='").append(y).append("' transform='rotate(")
                    .append(rotate).append(' ').append(x).append(' ').append(y)
                    .append(" )' font-family='Arial,sans-serif' font-size='25' font-weight='700' fill='#")
                    .append(String.format("%06x", 0x174f3b + RANDOM.nextInt(0x250000)))
                    .append("'>").append(answer.charAt(i)).append("</text>");
        }
        svg.append("</svg>");
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String safeIp(String ip) { return ip == null || ip.isBlank() ? "unknown" : ip.trim(); }

    private String fingerprint(String ip) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(safeIp(ip).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) result.append(String.format("%02x", digest[i]));
            return result.toString();
        } catch (Exception ignored) {
            return safeIp(ip);
        }
    }

    private record CaptchaEntry(String answer, String ipHash, Instant expiresAt) {}
}
