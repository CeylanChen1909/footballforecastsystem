package com.chen.football.common.dto;

import com.chen.football.common.i18n.I18nText;

public record ApiError(
        String code,
        String message,
        Object details
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, I18nText.translate(message), null);
    }

    public static ApiError of(String code, String message, Object details) {
        return new ApiError(code, I18nText.translate(message), details);
    }
}
