package com.chen.football.common.dto;

import com.chen.football.common.i18n.I18nText;

import java.util.List;
import java.util.Map;

public record FixtureListResponse(
        List<Map<String, Object>> response,
        int results,
        String error
) {
    public static FixtureListResponse empty(String error) {
        return new FixtureListResponse(List.of(), 0, I18nText.translate(error));
    }
}
