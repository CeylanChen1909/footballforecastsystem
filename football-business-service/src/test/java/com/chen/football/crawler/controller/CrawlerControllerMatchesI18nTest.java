package com.chen.football.crawler.controller;

import com.chen.football.common.client.ApiFootballClient;
import com.chen.football.common.i18n.LangContext;
import com.chen.football.common.service.RedisCacheService;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.mapper.CrawlerStandingMapper;
import com.chen.football.crawler.mapper.CrawlerTeamMapper;
import com.chen.football.crawler.service.EspnSquadCrawlerService;
import com.chen.football.crawler.service.IdentityMappingService;
import com.chen.football.crawler.service.MatchCrawlerService;
import com.chen.football.crawler.service.MatchRecommendationService;
import com.chen.football.crawler.service.StandingCrawlerService;
import com.chen.football.crawler.source.DataSourceManager;
import com.chen.football.crawler.source.LeagueNameNormalizer;
import com.chen.football.prediction.service.MatchPredictionPrecomputeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * getMatchesPage() is GET /api/crawler/matches/db/page (the paginated list
 * consumed by the public Matches page). Each item must keep the canonical
 * leagueName while exposing language-aware and language-independent fields.
 */
class CrawlerControllerMatchesI18nTest {

    @AfterEach
    void clearLanguage() {
        LangContext.clear();
    }

    @Test
    void englishPageAddsDisplayFieldsWithoutChangingLeagueName() {
        CrawlerMatch match = fixture();
        CrawlerController controller = controllerReturning(match);

        LangContext.set("en");
        Map<String, Object> response = controller.getMatchesPage(null, null, null, 1, 20);

        Map<String, Object> row = firstPageRow(response);
        assertEquals("意甲", row.get("leagueName"));
        assertEquals("Serie A", row.get("leagueDisplayName"));
        assertEquals("Serie A", row.get("leagueNameEn"));
        assertEquals("意甲", row.get("leagueNameZh"));

        @SuppressWarnings("unchecked")
        Map<String, Object> league = (Map<String, Object>) row.get("league");
        assertEquals("Serie A", league.get("name"));
        assertEquals("意甲", league.get("nameZh"));
        assertEquals("Serie A", league.get("nameEn"));

        assertEquals("利物浦", row.get("homeTeamName"));
        assertEquals("Liverpool", row.get("homeTeamDisplayName"));
        assertEquals("Villarreal", row.get("awayTeamDisplayName"));
    }

    @Test
    void chinesePageUsesChineseDisplayAndKeepsCanonicalEnglishFields() {
        CrawlerMatch match = fixture();
        CrawlerController controller = controllerReturning(match);

        LangContext.set("zh");
        Map<String, Object> response = controller.getMatchesPage(null, null, null, 1, 20);

        Map<String, Object> row = firstPageRow(response);
        assertEquals("意甲", row.get("leagueName"));
        assertEquals("意甲", row.get("leagueDisplayName"));
        assertEquals("Serie A", row.get("leagueNameEn"));
        assertEquals("意甲", row.get("leagueNameZh"));
        assertEquals("利物浦", row.get("homeTeamDisplayName"));
        assertEquals("比利亚雷亚尔", row.get("awayTeamDisplayName"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstPageRow(Map<String, Object> response) {
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("response");
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private CrawlerController controllerReturning(CrawlerMatch match) {
        MatchCrawlerService matchCrawlerService = mock(MatchCrawlerService.class);
        MatchRecommendationService recommendationService = mock(MatchRecommendationService.class);
        when(recommendationService.displayScore(any())).thenReturn(50);
        CrawlerMatchMapper matchMapper = mock(CrawlerMatchMapper.class);
        when(matchMapper.selectList(any())).thenReturn(List.of(match));
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.isSourceEnabled(anyString())).thenReturn(true);
        when(dataSourceManager.snapshot()).thenReturn(Map.of(
                "primarySource", "bbc-scores",
                "providers", List.of(Map.of(
                        "name", "bbc-scores",
                        "status", "AVAILABLE",
                        "dataAgeSeconds", 60))));

        return new CrawlerController(
                matchCrawlerService,
                recommendationService,
                mock(StandingCrawlerService.class),
                mock(CrawlerTeamMapper.class),
                mock(CrawlerStandingMapper.class),
                matchMapper,
                new LeagueNameNormalizer(),
                dataSourceManager,
                mock(MatchPredictionPrecomputeService.class),
                mock(ApiFootballClient.class),
                mock(IdentityMappingService.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                mock(EspnSquadCrawlerService.class),
                mock(RedisCacheService.class));
    }

    private CrawlerMatch fixture() {
        CrawlerMatch match = new CrawlerMatch();
        match.setId(1L);
        match.setFixtureId(1001L);
        match.setExternalMatchId("bbc-1001");
        match.setSource("bbc-scores");
        match.setLeagueId("135");
        match.setLeagueName("意甲");
        match.setHomeTeamName("利物浦");
        match.setAwayTeamName("比利亚雷亚尔");
        match.setHomeScore(2);
        match.setAwayScore(1);
        match.setStatus("FT");
        match.setMatchTime(LocalDateTime.of(2026, 5, 1, 20, 0));
        return match;
    }
}
