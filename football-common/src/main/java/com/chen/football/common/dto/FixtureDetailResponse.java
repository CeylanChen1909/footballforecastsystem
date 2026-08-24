package com.chen.football.common.dto;

import java.util.Map;

public record FixtureDetailResponse(
        Long fixtureId,
        Map<String, Object> response,
        int results,
        String error
) {
    public static FixtureDetailResponse empty(Long fixtureId, String error) {
        return new FixtureDetailResponse(fixtureId, Map.of(), 0, error);
    }
}
