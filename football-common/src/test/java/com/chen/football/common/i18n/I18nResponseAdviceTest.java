package com.chen.football.common.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Map-based crawler payloads must keep canonical En/Zh fields stable. */
class I18nResponseAdviceTest {

    private final I18nResponseAdvice advice = new I18nResponseAdvice();

    @AfterEach
    void clearLanguage() {
        LangContext.clear();
    }

    @Test
    void chineseResponseDoesNotTurnLeagueNameEnIntoChinese() {
        LangContext.set("zh");
        Map<?, ?> localized = localize();

        assertEquals("意甲", localized.get("leagueDisplayName"));
        assertEquals("Serie A", localized.get("leagueNameEn"));
        assertEquals("意甲", localized.get("leagueNameZh"));
        assertEquals("Liverpool", localized.get("homeTeamNameEn"));
        assertEquals("利物浦", localized.get("homeTeamNameZh"));
    }

    @Test
    void englishResponseLocalizesDisplayFieldOnly() {
        LangContext.set("en");
        Map<?, ?> localized = localize();

        assertEquals("Serie A", localized.get("leagueDisplayName"));
        assertEquals("Serie A", localized.get("leagueNameEn"));
        assertEquals("意甲", localized.get("leagueNameZh"));
    }

    private Map<?, ?> localize() {
        Object body = advice.beforeBodyWrite(Map.of(
                "leagueName", "意甲",
                "leagueDisplayName", "意甲",
                "leagueNameEn", "Serie A",
                "leagueNameZh", "意甲",
                "homeTeamNameEn", "Liverpool",
                "homeTeamNameZh", "利物浦"), null, null, null, null, null);
        return (Map<?, ?>) body;
    }
}
