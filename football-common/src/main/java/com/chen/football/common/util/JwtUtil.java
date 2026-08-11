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

    public JwtUtil(
            @Value("${security.jwt.issuer:footballforecastsystem}") String jwtIssuer,
            @Value("${security.jwt.secret:change-me-in-env}") String jwtSecret
    ) {
        this.jwtIssuer = jwtIssuer;
        this.jwtSecretBytes = padSecret(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long userId, String username) { return generateToken(userId, username, "USER"); }

    public String generateToken(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(jwtIssuer)
                .subject(String.valueOf(userId))
                .claims(Map.of("username", username, "role", role == null ? "USER" : role))
                .issuedAt(Date.from(now))
                // 收藏/浏览类功能更适合更长的登录态，避免用户频繁被踢下线
                .expiration(Date.from(now.plusSeconds(60L * 60 * 24 * 7)))
                .signWith(Keys.hmacShaKeyFor(jwtSecretBytes))
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(jwtSecretBytes))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) { return Long.parseLong(parseToken(token).getSubject()); }
    public String extractUsername(String token) { return parseToken(token).get("username", String.class); }
    public String extractRole(String token) { return parseToken(token).get("role", String.class); }

    private static byte[] padSecret(byte[] src) {
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) out[i] = src[i % src.length];
        return out;
    }
}
