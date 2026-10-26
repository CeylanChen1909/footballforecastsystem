package com.chen.football.prediction;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.i18n.LangContext;
import com.chen.football.crawler.service.IdentityMappingService;
import com.chen.football.prediction.controller.PredictionController;
import com.chen.football.prediction.entity.PredictionEntity;
import com.chen.football.prediction.mapper.PredictionMapper;
import com.chen.football.prediction.service.HistoricalMatchCacheService;
import com.chen.football.prediction.service.MatchPredictionPrecomputeService;
import com.chen.football.prediction.service.PersistencePredictionService;
import com.chen.football.prediction.service.PrematchFeatureService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PredictionMatchHistoryTest {

    @AfterEach
    void clearContext() {
        LangContext.clear();
        UserContext.clear();
    }

    @Test
    void unauthenticatedMatchHistoryForFixture1001IsEmptyByDesign() {
        PersistencePredictionService predictionService = mock(PersistencePredictionService.class);
        MatchPredictionPrecomputeService precomputeService = mock(MatchPredictionPrecomputeService.class);
        when(predictionService.byFixtureForUser(null, 1001L, 5)).thenReturn(List.of());
        PredictionController controller = new PredictionController(predictionService, precomputeService);

        ApiResponse<List<PredictionEntity>> response = controller.byMatch(1001L, 5);

        assertTrue(response.success());
        assertTrue(response.data().isEmpty());
        verify(predictionService).byFixtureForUser(null, 1001L, 5);
    }

    @Test
    void serviceDoesNotTouchTheDatabaseWithoutAUserId() {
        PredictionMapper predictionMapper = mock(PredictionMapper.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PersistencePredictionService service = new PersistencePredictionService(
                predictionMapper,
                jdbcTemplate,
                mock(IdentityMappingService.class),
                mock(HistoricalMatchCacheService.class),
                mock(PrematchFeatureService.class),
                new ObjectMapper(),
                "http://127.0.0.1:5001",
                false,
                "",
                new CrawlerProperties());

        assertTrue(service.byFixtureForUser(null, 1001L, 5).isEmpty());

        verifyNoInteractions(predictionMapper, jdbcTemplate);
    }

    @Test
    void i18nAddsDisplayFieldsWithoutReplacingTheRawSnapshotValues() throws Exception {
        PredictionEntity entity = new PredictionEntity();
        entity.setFixtureId(1001L);
        entity.setHomeTeamName("利物浦");
        entity.setAwayTeamName("比利亚雷亚尔");
        entity.setLeagueName("英超");
        entity.setResultLabel("HOME_WIN");
        ObjectMapper objectMapper = new ObjectMapper();

        LangContext.set("en");
        JsonNode english = objectMapper.readTree(objectMapper.writeValueAsString(entity));
        assertEquals("利物浦", english.path("homeTeamName").asText());
        assertEquals("比利亚雷亚尔", english.path("awayTeamName").asText());
        assertEquals("Liverpool", english.path("homeTeamDisplayName").asText());
        assertEquals("Villarreal", english.path("awayTeamDisplayName").asText());
        assertEquals("Premier League", english.path("leagueDisplayName").asText());

        LangContext.set("zh");
        JsonNode chinese = objectMapper.readTree(objectMapper.writeValueAsString(entity));
        assertEquals("利物浦", chinese.path("homeTeamName").asText());
        assertEquals("利物浦", chinese.path("homeTeamDisplayName").asText());
        assertEquals("比利亚雷亚尔", chinese.path("awayTeamDisplayName").asText());
        assertEquals("英超", chinese.path("leagueDisplayName").asText());
    }
}
