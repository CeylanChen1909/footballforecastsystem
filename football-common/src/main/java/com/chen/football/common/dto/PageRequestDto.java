package com.chen.football.common.dto;

import java.time.LocalDateTime;

public record PageRequestDto(
        int page,
        int size,
        String keyword,
        String status
) {
    public static PageRequestDto of(Integer page, Integer size, String keyword, String status) {
        return new PageRequestDto(normalizePage(page), normalizeSize(size), normalize(keyword), normalize(status));
    }

    private static int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int normalizeSize(Integer size) {
        if (size == null || size < 1) return 20;
        return Math.min(size, 100);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
