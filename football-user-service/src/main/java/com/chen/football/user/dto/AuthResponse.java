package com.chen.football.user.dto;

public record AuthResponse(
        boolean ok,
        String message,
        Long userId,
        String username,
        String email,
        String role,
        String token,
        String refreshToken,
        boolean captchaRequired,
        String captchaId,
        String captchaQuestion
) {
    public static AuthResponse successRegister(Long userId, String username, String role) {
        return new AuthResponse(true, null, userId, username, null, role, null, null, false, null, null);
    }

    public static AuthResponse successLogin(Long userId, String username, String role, String token, String refreshToken) {
        return new AuthResponse(true, null, userId, username, null, role, token, refreshToken, false, null, null);
    }

    public static AuthResponse failure(String message) {
        return new AuthResponse(false, message, null, null, null, null, null, null, false, null, null);
    }

    public static AuthResponse failureWithCaptcha(String message, String captchaId, String captchaQuestion) {
        return new AuthResponse(false, message, null, null, null, null, null, null, true, captchaId, captchaQuestion);
    }
}
