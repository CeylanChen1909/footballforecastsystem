package com.chen.football.user.dto;

import java.util.List;
import java.util.Map;

public record FavoriteListResponse<T>(
        List<T> items,
        int total
) {
    public static <T> FavoriteListResponse<T> of(List<T> items) {
        return new FavoriteListResponse<>(items, items == null ? 0 : items.size());
    }
}
