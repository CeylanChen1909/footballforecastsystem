package com.chen.football.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {

    private final String jwtIssuer;
    private final byte[] jwtSecretBytes;
    /** access token 有效期（秒），默认 2 小时；由 refresh token 续期 */
    private final long accessTokenTtlSeconds;

    public JwtUtil(
            @Value("${security.jwt.issuer:footballforecastsystem}") String jwtIssuer,
            @Value("${security.jwt.secret}") String jwtSecret,
            @Value("${security.jwt.expire-seconds:7200}") long accessTokenTtlSeconds
    ) {
        this.jwtIssuer = jwtIssuer;
        this.jwtSecretBytes = requireSecureSecret(jwtSecret);
        this.accessTokenTtlSeconds = Math.max(300, accessTokenTtlSeconds);
    }

    public String generateToken(Long userId, String username) { return generateToken(userId, username, "USER"); }

    public String generateToken(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(jwtIssuer)
                .subject(String.valueOf(userId))
                .claims(Map.of("username", username, "role", role == null ? "USER" : role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenTtlSeconds)))
                .signWith(Keys.hmacShaKeyFor(jwtSecretBytes))
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(jwtSecretBytes))
                .requireIssuer(jwtIssuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) { return Long.parseLong(parseToken(token).getSubject()); }
    public String extractUsername(String token) { return parseToken(token).get("username", String.class); }
    public String extractRole(String token) { return parseToken(token).get("role", String.class); }

    private static byte[] requireSecureSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("security.jwt.secret must be configured");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("security.jwt.secret must be at least 32 bytes for HS256");
        }
        return bytes;
    }
}
