package com.chen.football.user.dto;

public record ActionResponse(
        boolean ok,
        String message
) {
    public static ActionResponse success() {
        return new ActionResponse(true, null);
    }

    public static ActionResponse success(String message) {
        return new ActionResponse(true, message);
    }

    public static ActionResponse fail(String message) {
        return new ActionResponse(false, message);
    }
}
