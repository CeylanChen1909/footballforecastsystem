package com.chen.football.prediction.service;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.dto.MatchPredictionRequest;
import com.chen.football.common.dto.MatchPredictionResponse;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-5: the 5-minute scan must not re-queue a fixture whose snapshot is
 * already fresh, and a successful generation must keep its distributed lock
 * for 10 minutes as a debounce instead of releasing it immediately.
 * Async generation is awaited with Mockito's timeout() verification.
 */
class MatchPredictionPrecomputeScheduleTest {

    private CrawlerMatch match(long id, LocalDateTime updatedAt) {
        CrawlerMatch m = new CrawlerMatch();
        m.setId(id);
        m.setFixtureId(null); // BBC rows: effective id falls back to the local id
        m.setLeagueId("39");
        m.setLeagueName("英超");
        m.setSource("bbc-scores");
        m.setHomeTeamName("Arsenal");
        m.setAwayTeamName("Chelsea");
        m.setStatus("NS");
        m.setMatchTime(LocalDateTime.now().plusHours(5));
        m.setUpdatedAt(updatedAt);
        return m;
    }

    @Test
    void freshSnapshotIsSkippedAndStaleOneIsQueuedWithDebounce() {
        LocalDateTime now = LocalDateTime.now();
        CrawlerMatch fresh = match(1L, now.minusHours(1));
        CrawlerMatch stale = match(2L, now.minusHours(1));

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // readPredictionStatuses(): fixture 1 already has a fresh READY row
        // (generated after the match row was last updated); fixture 2 has none.
        // NOTE: Mockito expands varargs, so inv.getArgument(1) is the FIRST
        // vararg element, not the array — use getArguments() instead.
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            Object[] args = inv.getArguments();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                if (Long.valueOf(1L).equals(args[i])) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("fixture_id", 1L);
                    row.put("status", "READY");
                    row.put("generated_at", Timestamp.valueOf(now));
                    row.put("updated_at", Timestamp.valueOf(now));
                    row.put("feature_version", "prematch-v1-gated");
                    // readPredictionStatuses() also runs qualityTier(), which
                    // dereferences model_version — a real row always has one.
                    row.put("model_version", "baseline-elo-poisson-v2");
                    row.put("feature_complete", 1);
                    row.put("feature_status", "COMPLETE");
                    rows.add(row);
                }
            }
            return rows;
        });
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        CrawlerProperties crawlerProperties = mock(CrawlerProperties.class);
        when(crawlerProperties.isPrimaryOnly()).thenReturn(true);
        when(crawlerProperties.getPrimarySource()).thenReturn("bbc-scores");

        PersistencePredictionService predictionService = mock(PersistencePredictionService.class);
        when(predictionService.predictOnly(any(MatchPredictionRequest.class)))
                .thenReturn(new MatchPredictionResponse(2L, "HOME_WIN", 0.6, 0.2, 0.2,
                        "baseline-elo-poisson-v2", "test"));

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        AtomicReference<String> token = new AtomicReference<>();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            token.set(inv.getArgument(1));
            return Boolean.TRUE;
        });
        when(valueOps.get(anyString())).thenAnswer(inv -> token.get());

        MatchPredictionPrecomputeService service = new MatchPredictionPrecomputeService(
                jdbcTemplate, new ObjectMapper(), mock(CrawlerMatchMapper.class),
                predictionService, crawlerProperties, redisTemplate);

        int queued = service.schedule(List.of(fresh, stale));

        assertEquals(1, queued, "only the stale fixture may be queued");
        // generateOne runs on the 2-thread pool: only the stale fixture is
        // ever predicted, and its lock is kept for 10 minutes afterwards.
        verify(predictionService, timeout(3000).times(1)).predictOnly(any(MatchPredictionRequest.class));
        // expire() lives on the template itself, not on ValueOperations.
        verify(redisTemplate, timeout(3000)).expire(eq("football:prediction:precompute:2"), eq(Duration.ofMinutes(10)));
    }
}
