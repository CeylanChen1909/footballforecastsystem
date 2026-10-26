package com.chen.football.crawler;

import com.chen.football.common.i18n.LangContext;
import com.chen.football.crawler.service.StandingZoneRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandingZoneRulesTest {

    @BeforeEach
    void useChineseForLegacyAssertions() {
        LangContext.set("zh");
    }

    @AfterEach
    void clearLanguage() {
        LangContext.clear();
    }

    @Test
    void frenchLeagueUsesQualificationAndConferenceSlotsInsteadOfTopFourShortcut() {
        assertEquals("CHAMPIONS_LEAGUE", StandingZoneRules.resolve("法甲", "2026", 3, 18).code());
        assertEquals("CHAMPIONS_LEAGUE_QUALIFYING", StandingZoneRules.resolve("法甲", "2026", 4, 18).code());
        assertEquals("EUROPA_LEAGUE", StandingZoneRules.resolve("法甲", "2026", 5, 18).code());
        assertEquals("CONFERENCE_LEAGUE_QUALIFYING", StandingZoneRules.resolve("法甲", "2026", 6, 18).code());
        assertEquals("RELEGATION_PLAYOFF", StandingZoneRules.resolve("法甲", "2026", 16, 18).code());
        assertEquals("RELEGATION", StandingZoneRules.resolve("法甲", "2026", 17, 18).code());
    }

    @Test
    void leagueSpecificRulesCoverEnglishPremierLeagueAndChampionship() {
        assertEquals("CHAMPIONS_LEAGUE", StandingZoneRules.resolve("英超", "2026", 5, 20).code());
        assertEquals("EUROPA_LEAGUE", StandingZoneRules.resolve("英超", "2026", 6, 20).code());
        assertEquals("CONFERENCE_LEAGUE_QUALIFYING", StandingZoneRules.resolve("英超", "2026", 8, 20).code());
        assertEquals("CHAMPIONS_LEAGUE", StandingZoneRules.resolve("荷甲", "2026", 2, 18).code());
        assertEquals("CHAMPIONS_LEAGUE", StandingZoneRules.resolve("葡超", "2026", 2, 18).code());
        assertEquals("CHAMPIONS_LEAGUE", StandingZoneRules.resolve("比甲", "2026", 1, 16).code());
        assertEquals("PROMOTION", StandingZoneRules.resolve("英冠", "2025/2026", 2, 24).code());
        assertEquals("PROMOTION_PLAYOFF", StandingZoneRules.resolve("英冠", "2025/2026", 4, 24).code());
    }

    @Test
    void unknownLeagueDoesNotFallBackToAFalseTopFourChampionsZone() {
        assertEquals("", StandingZoneRules.resolve("未知联赛", "2026", 1, 20).code());
        assertEquals("", StandingZoneRules.resolve("未知联赛", "2026", 4, 20).code());
    }

    @Test
    void describeExposesTheRuleSetForAUserFacingLegend() {
        Map<String, Object> description = StandingZoneRules.describe("法甲", "2026", 18);
        assertEquals("法甲", description.get("league"));
        assertTrue(String.valueOf(description.get("note")).contains("欧冠资格赛"));
        List<?> zones = (List<?>) description.get("zones");
        assertTrue(zones.stream().anyMatch(zone -> ((Map<?, ?>) zone).get("code").equals("CHAMPIONS_LEAGUE_QUALIFYING")));
    }

    @Test
    void englishModeLocalizesNotesLabelsAndLeagueNames() {
        LangContext.set("en");
        Map<String, Object> description = StandingZoneRules.describe("法甲", "2026", 18);
        assertEquals("Ligue 1", description.get("league"));
        assertTrue(String.valueOf(description.get("note")).contains("Champions League qualifying"));
        List<?> zones = (List<?>) description.get("zones");
        assertTrue(zones.stream().anyMatch(zone -> "UEFA Champions League qualifying".equals(((Map<?, ?>) zone).get("label"))));
        assertEquals("UEFA Champions League", StandingZoneRules.resolve("英超", "2026", 1, 20).label());
    }

    @Test
    void uclAndUnlDoNotReuseDomesticChampionsZones() {
        assertEquals("", StandingZoneRules.resolve("欧冠", "2026", 1, 36).code());
        assertEquals("", StandingZoneRules.resolve("欧国联", "2026", 1, 4).code());
        assertEquals("", StandingZoneRules.resolve("bbc-champions-league", "2026", 4, 36).code());
        Map<String, Object> ucl = StandingZoneRules.describe("欧冠", "2026", 36);
        assertTrue(String.valueOf(ucl.get("note")).contains("联赛阶段"));
        Map<String, Object> unl = StandingZoneRules.describe("欧国联", "2026", 4);
        assertTrue(String.valueOf(unl.get("note")).contains("多组"));
    }
}
