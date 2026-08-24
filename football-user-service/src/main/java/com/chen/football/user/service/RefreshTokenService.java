package com.chen.football.user.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh Token 管理：不透明随机串存 Redis，支持续期（轮换）与注销。
 */
@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:token:";
    private static final Duration TTL = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final Map<String, TokenEntry> localTokens = new ConcurrentHashMap<>();

    public RefreshTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 生成并保存 refresh token，返回明文 token */
    public String issue(Long userId, String username, String role) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String payload = userId + ":" + username + ":" + role;
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + token, payload, TTL);
        } catch (RuntimeException unavailable) {
            localTokens.put(token, new TokenEntry(payload, Instant.now().plus(TTL)));
        }
        return token;
    }

    /** 校验并返回 payload（userId:username:role），无效返回 null */
    public String validate(String token) {
        if (token == null || token.isBlank()) return null;
        String normalized = token.trim();
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + normalized);
        } catch (RuntimeException unavailable) {
            TokenEntry entry = localTokens.get(normalized);
            if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
                localTokens.remove(normalized);
                return null;
            }
            return entry.payload();
        }
    }

    /** 注销（退出登录 / 轮换后作废旧 token） */
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            String normalized = token.trim();
            try { redisTemplate.delete(KEY_PREFIX + normalized); } catch (RuntimeException ignored) { }
            localTokens.remove(normalized);
        }
    }

    /** 撤销账号的全部 refresh token，用于“退出其他设备”。 */
    public int revokeAllForUser(Long userId, String keepToken) {
        if (userId == null) return 0;
        try {
            int count = 0;
            try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build())) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    if (keepToken != null && !keepToken.isBlank() && key.equals(KEY_PREFIX + keepToken.trim())) continue;
                    String payload = redisTemplate.opsForValue().get(key);
                    if (payload != null && payload.startsWith(userId + ":")) {
                        redisTemplate.delete(key);
                        count++;
                    }
                }
            }
            return count;
        } catch (RuntimeException unavailable) {
            int removed = 0;
            for (var entry : localTokens.entrySet()) {
                if (entry.getValue().payload().startsWith(userId + ":")
                        && !entry.getKey().equals(keepToken)) { localTokens.remove(entry.getKey()); removed++; }
            }
            return removed;
        }
    }

    /** 返回不含 token 明文的会话摘要，供账号安全页展示。 */
    public List<Map<String, Object>> listForUser(Long userId, String currentToken) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        if (userId == null) return sessions;
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build())) {
            String currentKey = currentToken == null ? "" : KEY_PREFIX + currentToken.trim();
            while (cursor.hasNext()) {
                String key = cursor.next();
                String payload = redisTemplate.opsForValue().get(key);
                if (payload == null || !payload.startsWith(userId + ":")) continue;
                Map<String, Object> session = new LinkedHashMap<>();
                session.put("current", key.equals(currentKey));
                session.put("expiresInSeconds", Math.max(0L, redisTemplate.getExpire(key)));
                session.put("label", key.equals(currentKey) ? "当前设备" : "其他设备");
                sessions.add(session);
            }
            sessions.sort((a, b) -> Boolean.compare(!Boolean.TRUE.equals(a.get("current")), !Boolean.TRUE.equals(b.get("current"))));
            return sessions;
        } catch (RuntimeException unavailable) {
            for (var entry : localTokens.entrySet()) {
                TokenEntry value = entry.getValue();
                if (value.expiresAt().isBefore(java.time.Instant.now())) { localTokens.remove(entry.getKey()); continue; }
                if (!value.payload().startsWith(userId + ":")) continue;
                Map<String, Object> session = new LinkedHashMap<>();
                session.put("current", entry.getKey().equals(currentToken));
                session.put("expiresInSeconds", Math.max(0L, java.time.Duration.between(java.time.Instant.now(), value.expiresAt()).getSeconds()));
                session.put("label", entry.getKey().equals(currentToken) ? "当前设备" : "其他设备");
                sessions.add(session);
            }
            return sessions;
        }
    }

    private record TokenEntry(String payload, Instant expiresAt) { }
}
