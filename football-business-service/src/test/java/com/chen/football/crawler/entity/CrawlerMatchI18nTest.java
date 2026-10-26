package com.chen.football.crawler.entity;

import com.chen.football.common.i18n.LangContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The legacy /api/crawler/matches endpoint serialises CrawlerMatch directly.
 * These tests pin the additive display contract so the raw canonical values
 * never change while English clients get a renderable league/team name.
 */
class CrawlerMatchI18nTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearLanguage() {
        LangContext.clear();
    }

    @Test
    void englishResponseAddsEnglishDisplayFieldsButKeepsCanonicalValues() throws Exception {
        CrawlerMatch match = match("意甲", "利物浦", "比利亚雷亚尔");

        LangContext.set("en");
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(match));

        assertEquals("意甲", json.path("leagueName").asText());
        assertEquals("Serie A", json.path("leagueDisplayName").asText());
        assertEquals("Serie A", json.path("leagueNameEn").asText());
        assertEquals("意甲", json.path("leagueNameZh").asText());

        assertEquals("利物浦", json.path("homeTeamName").asText());
        assertEquals("Liverpool", json.path("homeTeamDisplayName").asText());
        assertEquals("Liverpool", json.path("homeTeamNameEn").asText());
        assertEquals("利物浦", json.path("homeTeamNameZh").asText());

        assertEquals("比利亚雷亚尔", json.path("awayTeamName").asText());
        assertEquals("Villarreal", json.path("awayTeamDisplayName").asText());
        assertEquals("Villarreal", json.path("awayTeamNameEn").asText());
        assertEquals("比利亚雷亚尔", json.path("awayTeamNameZh").asText());
    }

    @Test
    void chineseResponseUsesChineseDisplayAndKeepsCanonicalEnglishFields() throws Exception {
        CrawlerMatch match = match("意甲", "利物浦", "比利亚雷亚尔");

        LangContext.set("zh");
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(match));

        assertEquals("意甲", json.path("leagueName").asText());
        assertEquals("意甲", json.path("leagueDisplayName").asText());
        assertEquals("Serie A", json.path("leagueNameEn").asText());
        assertEquals("意甲", json.path("leagueNameZh").asText());

        assertEquals("利物浦", json.path("homeTeamDisplayName").asText());
        assertEquals("Liverpool", json.path("homeTeamNameEn").asText());
        assertEquals("比利亚雷亚尔", json.path("awayTeamDisplayName").asText());
        assertEquals("Villarreal", json.path("awayTeamNameEn").asText());
    }

    private CrawlerMatch match(String leagueName, String homeTeamName, String awayTeamName) {
        CrawlerMatch match = new CrawlerMatch();
        match.setId(1L);
        match.setFixtureId(1001L);
        match.setSource("bbc-scores");
        match.setLeagueId("135");
        match.setLeagueName(leagueName);
        match.setHomeTeamName(homeTeamName);
        match.setAwayTeamName(awayTeamName);
        match.setStatus("FT");
        return match;
    }
}
