package com.chen.football.common.dto;

import java.util.List;
import java.util.Map;

public record FixtureListResponse(
        List<Map<String, Object>> response,
        int results,
        String error
) {
    public static FixtureListResponse empty(String error) {
        return new FixtureListResponse(List.of(), 0, error);
    }
}
