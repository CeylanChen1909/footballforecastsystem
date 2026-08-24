package com.chen.football.user.dto;

import java.time.LocalDateTime;

public record MeResponse(
        boolean guest,
        Long userId,
        String username,
        String nickname,
        String email,
        Boolean emailVerified,
        String avatarData,
        LocalDateTime nicknameUpdatedAt,
        String role,
        LocalDateTime createdAt,
        boolean loggedIn
) {
    public static MeResponse anonymous() {
        return new MeResponse(true, null, null, null, null, false, null, null, null, null, false);
    }

    public static MeResponse authenticated(Long userId, String username, String nickname, String email,
                                           Boolean emailVerified, String avatarData, LocalDateTime nicknameUpdatedAt,
                                           String role, LocalDateTime createdAt) {
        return new MeResponse(false, userId, username, nickname, email, emailVerified, avatarData, nicknameUpdatedAt, role, createdAt, true);
    }
}
