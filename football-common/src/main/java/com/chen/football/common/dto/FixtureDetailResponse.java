package com.chen.football.common.dto;

import com.chen.football.common.i18n.I18nText;

import java.util.Map;

public record FixtureDetailResponse(
        Long fixtureId,
        Map<String, Object> response,
        int results,
        String error
) {
    public static FixtureDetailResponse empty(Long fixtureId, String error) {
        return new FixtureDetailResponse(fixtureId, Map.of(), 0, I18nText.translate(error));
    }
}
