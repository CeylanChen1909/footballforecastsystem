package com.chen.football.match.service;

import com.chen.football.common.client.ApiFootballClient;
import com.chen.football.common.client.ApiFootballClient.BudgetBucket;
import com.chen.football.common.client.FootballDataClient;
import com.chen.football.common.config.ApiFootballProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-4: an empty/failed detail result must never overwrite a NORMAL row.
 * The service is exercised through {@link MatchDetailsService#refreshType}
 * with a mocked {@link JdbcTemplate}; every executed UPDATE is recorded so
 * the test can tell the guarded "protect NORMAL row" UPDATE apart from the
 * plain upsert. No Spring context, no database.
 */
class MatchDetailsSaveSnapshotTest {

    private static final Map<String, Object> MATCH_ROW = new HashMap<>(Map.of(
            "id", 100L, "fixture_id", 100L, "source", "api-football", "status", "NS"));

    /** Builds a service whose JdbcTemplate records every update() call. */
    private Fixture build(int failureColumnPresent, int protectUpdateCount) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ApiFootballClient apiFootballClient = mock(ApiFootballClient.class);
        FootballDataClient footballDataClient = mock(FootballDataClient.class);
        ApiFootballProperties props = new ApiFootballProperties();
        props.setApiKey("test-key");
        List<String> executedSql = new ArrayList<>();
        List<Object[]> executedArgs = new ArrayList<>();
        // NOTE: Mockito expands varargs, so inv.getArgument(1) is the FIRST
        // vararg element, not the array — use getArguments() instead.
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            Object[] all = inv.getArguments();
            executedSql.add((String) all[0]);
            executedArgs.add(java.util.Arrays.copyOfRange(all, 1, all.length));
            String sql = (String) all[0];
            // The guarded UPDATE (status='NORMAL') reports whether a NORMAL
            // row existed; the plain upsert always reports 1 row written.
            return sql.contains("status = 'NORMAL'") ? protectUpdateCount : 1;
        });
        // failure_count column probe (information_schema): 1 = present, 0 = absent.
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(failureColumnPresent);
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(new HashMap<>(MATCH_ROW));
        // loadSnapshots(): no rows — bundle() then falls back to football-data.
        doAnswer(inv -> null).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        when(footballDataClient.getMatches(anyString(), anyString(), any(), any()))
                .thenReturn(Mono.just(Map.of("response", List.of())));
        MatchDetailsService service = new MatchDetailsService(
                apiFootballClient, footballDataClient, props, jdbcTemplate, new ObjectMapper());
        return new Fixture(service, apiFootballClient, executedSql, executedArgs);
    }

    private record Fixture(MatchDetailsService service, ApiFootballClient client,
                           List<String> sql, List<Object[]> args) {}

    @Test
    void emptyResultOverNormalRowOnlyUpdatesErrorMessage() {
        Fixture f = build(0, 1); // no failure_count column; a NORMAL row exists
        when(f.client().getFixtureLineups(100L, BudgetBucket.ON_DEMAND))
                .thenReturn(Mono.just(Map.of("response", List.of())));
        f.service().refreshType(100L, "lineups", BudgetBucket.ON_DEMAND);

        // Exactly one UPDATE ran, and it is the guarded one — the plain
        // upsert (ON DUPLICATE KEY UPDATE) must never run over a NORMAL row.
        assertEquals(1, f.sql().size());
        assertTrue(f.sql().get(0).contains("status = 'NORMAL'"),
                "expected guarded protect-NORMAL UPDATE, got: " + f.sql());
        assertTrue(f.sql().stream().noneMatch(s -> s.contains("ON DUPLICATE KEY UPDATE")));
    }

    @Test
    void failedResultWithNoNormalRowFallsThroughToUpsert() {
        Fixture f = build(0, 0); // no NORMAL row to protect
        when(f.client().getFixtureLineups(100L, BudgetBucket.ON_DEMAND))
                .thenReturn(Mono.error(new RuntimeException("429 Too Many Requests from GET https://v3.football.api-sports.io/fixtures/lineups")));
        f.service().refreshType(100L, "lineups", BudgetBucket.ON_DEMAND);

        // Guarded UPDATE ran (0 rows), then the plain upsert inserted the
        // REQUEST_FAILED row so the UI shows the failure instead of UNKNOWN.
        assertTrue(f.sql().stream().anyMatch(s -> s.contains("ON DUPLICATE KEY UPDATE")),
                "expected upsert for first-time failure, got: " + f.sql());
        Object[] upsertArgs = f.args().stream()
                .filter(a -> a.length > 4 && "REQUEST_FAILED".equals(a[4]))
                .findFirst().orElseThrow();
        assertEquals("REQUEST_FAILED", upsertArgs[4]);
        // 429 is provider per-minute throttling, not daily-budget exhaustion.
        assertTrue(String.valueOf(upsertArgs[5]).contains("429"));
    }

    @Test
    void normalResultResetsFailureCountWhenColumnExists() {
        Fixture f = build(1, 0); // failure_count column present
        when(f.client().getFixtureLineups(100L, BudgetBucket.ON_DEMAND))
                .thenReturn(Mono.just(Map.of("response", List.of(Map.of("team", Map.of("name", "Arsenal"))))));
        f.service().refreshType(100L, "lineups", BudgetBucket.ON_DEMAND);

        assertTrue(f.sql().stream().anyMatch(s -> s.contains("failure_count = 0")),
                "expected NORMAL upsert to reset failure_count, got: " + f.sql());
    }
}
