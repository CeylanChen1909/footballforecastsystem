package com.chen.football.crawler.controller;

import com.chen.football.common.client.ApiFootballClient;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrawlerControllerHeadToHeadTest {

    @Test
    void ChineseAndEnglishRequestsProduceTheSameSummary() {
        MatchCrawlerService matchCrawlerService = mock(MatchCrawlerService.class);
        MatchRecommendationService recommendationService = mock(MatchRecommendationService.class);
        when(recommendationService.displayScore(org.mockito.ArgumentMatchers.any())).thenReturn(50);
        CrawlerMatch match = englishFixture();
        when(matchCrawlerService.getHeadToHead("Liverpool", "Villarreal", 10)).thenReturn(List.of(match));

        CrawlerController controller = new CrawlerController(
                matchCrawlerService,
                recommendationService,
                mock(StandingCrawlerService.class),
                mock(CrawlerTeamMapper.class),
                mock(CrawlerStandingMapper.class),
                mock(CrawlerMatchMapper.class),
                new LeagueNameNormalizer(),
                mock(DataSourceManager.class),
                mock(MatchPredictionPrecomputeService.class),
                mock(ApiFootballClient.class),
                mock(IdentityMappingService.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                mock(EspnSquadCrawlerService.class),
                mock(RedisCacheService.class));

        Map<String, Object> chinese = controller.getHeadToHead("利物浦", "比利亚雷亚尔", 10);
        Map<String, Object> english = controller.getHeadToHead("Liverpool", "Villarreal", 10);

        assertEquals(summary(chinese), summary(english));
        assertEquals(1, summary(chinese).get("total"));
        assertEquals(1, summary(chinese).get("homeWins"));
        assertEquals(0, summary(chinese).get("awayWins"));
        assertEquals(0, summary(chinese).get("draws"));
        verify(matchCrawlerService, org.mockito.Mockito.times(2))
                .getHeadToHead("Liverpool", "Villarreal", 10);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> response) {
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return (Map<String, Object>) data.get("summary");
    }

    private CrawlerMatch englishFixture() {
        CrawlerMatch match = new CrawlerMatch();
        match.setId(77L);
        match.setExternalMatchId("bbc-77");
        match.setSource("bbc-scores");
        match.setLeagueId("39");
        match.setLeagueName("英超");
        match.setHomeTeamName("Liverpool");
        match.setAwayTeamName("Villarreal");
        match.setHomeScore(2);
        match.setAwayScore(1);
        match.setStatus("FT");
        match.setMatchTime(LocalDateTime.of(2026, 5, 1, 20, 0));
        return match;
    }
}
