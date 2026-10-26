package com.chen.football.user.dto;

import com.chen.football.common.i18n.I18nText;

public record ActionResponse(
        boolean ok,
        String message
) {
    public static ActionResponse success() {
        return new ActionResponse(true, null);
    }

    public static ActionResponse success(String message) {
        return new ActionResponse(true, I18nText.translate(message));
    }

    public static ActionResponse fail(String message) {
        return new ActionResponse(false, I18nText.translate(message));
    }
}
