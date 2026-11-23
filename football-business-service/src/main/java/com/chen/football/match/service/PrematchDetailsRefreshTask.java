package com.chen.football.match.service;

import com.chen.football.common.client.ApiFootballClient;
import com.chen.football.common.client.ApiFootballClient.BudgetBucket;
import com.chen.football.common.config.ApiFootballProperties;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.service.IdentityNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Opportunistically fills provider-backed prematch details. Primary BBC rows
 * are resolved by team/date to a provider fixture only for metadata; the
 * provider fixture is never inserted as a second match authority. A bounded
 * historical queue also backfills post-match statistics for rolling features.
 */
@Slf4j
@Component
public class PrematchDetailsRefreshTask {
    private final JdbcTemplate jdbcTemplate;
    private final MatchDetailsService matchDetailsService;
    private final ApiFootballClient apiFootballClient;
    private final ApiFootballProperties apiFootballProperties;
    private final CrawlerProperties crawlerProperties;
    private final boolean enabled;
    private final int maxFixturesPerRun;
    private final int maxHistoricalPerRun;
    private final int maxPrimaryPerRun;
    private final int maxPrimaryHistoricalPerRun;
    /**
     * P0-2: historical statistics run at most once per calendar day (Asia/Shanghai).
     * In-memory only, single instance: a restart may allow one extra round that
     * day, bounded to {@code maxPrimaryHistoricalPerRun} fixtures either way.
     */
    private volatile LocalDate lastHistoricalRunDate;

    public PrematchDetailsRefreshTask(JdbcTemplate jdbcTemplate,
                                      MatchDetailsService matchDetailsService,
                                      ApiFootballClient apiFootballClient,
                                      ApiFootballProperties apiFootballProperties,
                                      CrawlerProperties crawlerProperties,
                                      @Value("${prediction.prematch-details-enabled:true}") boolean enabled,
                                      @Value("${prediction.prematch-details-max-fixtures-per-run:18}") int maxFixturesPerRun,
                                      @Value("${prediction.prematch-details-max-historical-per-run:2}") int maxHistoricalPerRun,
                                      @Value("${prediction.prematch-details-max-primary-per-run:12}") int maxPrimaryPerRun,
                                      @Value("${prediction.prematch-details-max-primary-historical-per-run:2}") int maxPrimaryHistoricalPerRun) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchDetailsService = matchDetailsService;
        this.apiFootballClient = apiFootballClient;
        this.apiFootballProperties = apiFootballProperties;
        this.crawlerProperties = crawlerProperties;
        this.enabled = enabled;
        this.maxFixturesPerRun = Math.max(1, maxFixturesPerRun);
        this.maxHistoricalPerRun = Math.max(0, maxHistoricalPerRun);
        this.maxPrimaryPerRun = Math.max(0, maxPrimaryPerRun);
        this.maxPrimaryHistoricalPerRun = Math.max(0, maxPrimaryHistoricalPerRun);
    }

    @Scheduled(initialDelayString = "${prediction.prematch-details-initial-delay-ms:30000}",
            fixedDelayString = "${prediction.prematch-details-fixed-delay-ms:1800000}")
    public void refresh() {
        if (!enabled) {
            log.info("[PrematchDetails] scheduled refresh disabled by PREMATCH_DETAILS_ENABLED");
            return;
        }
        if (crawlerProperties.isPrimaryOnly()) {
            // 主源模式仍允许“只读详情补充”：API-Football 只提供 lineups/
            // injuries/odds 快照，永远不写入 crawler_matches，也不会改变 BBC
            // 的比赛身份。内部 budget guard 会在额度不足时停止本轮补充。
            // P0-2: both sub-tasks share one per-round fixture-list cache so a
            // date is fetched at most once per round (see loadProviderFixturesByDate).
            Map<String, List<Map<String, Object>>> fixtureListByDate = new LinkedHashMap<>();
            refreshPrimarySourceDetails(fixtureListByDate);
            refreshPrimaryHistoricalStatistics(fixtureListByDate);
            log.debug("[PrematchDetails] primary-only mode, skipped provider fixture sync; primary aliases only");
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT m.fixture_id, m.match_time, MAX(CASE WHEN d.status = 'NORMAL' THEN d.fetched_at END) AS latest_detail
                    FROM crawler_matches m
                    LEFT JOIN t_match_detail_snapshot d ON d.fixture_id = m.fixture_id
                    WHERE m.fixture_id IS NOT NULL AND m.fixture_id > 0
                      AND LOWER(COALESCE(m.source, '')) = 'api-football'
                      AND m.match_time >= NOW() AND m.match_time < DATE_ADD(NOW(), INTERVAL 1 DAY)
                      AND m.status NOT IN ('FT','AET','PEN','CANC','CANCELED','CANCELLED','ABD','AWD','WO')
                    GROUP BY m.fixture_id, m.match_time
                    ORDER BY m.match_time ASC LIMIT ?
                    """, maxFixturesPerRun);
            log.info("[PrematchDetails] refreshing {} API-Football fixtures (budget bounded)", rows.size());
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            for (Map<String, Object> row : rows) {
                Long fixtureId = number(row.get("fixture_id"));
                LocalDateTime kickoff = time(row.get("match_time"));
                if (fixtureId == null) continue;
                LocalDateTime latest = time(row.get("latest_detail"));
                // P0-4: latest_detail is the newest NORMAL fetch only. A recent
                // failure/empty row no longer masquerades as "recently fetched"
                // and blocks the next retry.
                boolean recentlyFetched = latest != null && Duration.between(latest, now).toMinutes() < 30;
                // Initial collection is cheap; a final refresh inside six hours
                // captures likely lineups/odds without hammering the provider.
                if (kickoff != null && Duration.between(now, kickoff).toHours() <= 6 && !recentlyFetched) {
                    matchDetailsService.refresh(fixtureId, BudgetBucket.SCHEDULED);
                } else {
                    matchDetailsService.getDetails(fixtureId, false, BudgetBucket.SCHEDULED);
                }
            }
            Map<String, List<Map<String, Object>>> fixtureListByDate = new LinkedHashMap<>();
            refreshPrimarySourceDetails(fixtureListByDate);
            refreshPrimaryHistoricalStatistics(fixtureListByDate);
            backfillHistoricalStatistics();
        } catch (Exception ex) {
            log.warn("赛前详情回填失败: {}", ex.getMessage());
        }
    }

    /** Run once after the service is ready so the first page load does not
     * depend on waiting for the scheduler's first interval. */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        CompletableFuture.runAsync(this::refresh);
    }

    private void backfillHistoricalStatistics() {
        if (maxHistoricalPerRun <= 0) return;
        // P0-2: historical statistics run once per day, at most 2 fixtures.
        if (!beginDailyHistoricalRun()) return;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT m.fixture_id
                    FROM crawler_matches m
                    WHERE m.fixture_id IS NOT NULL AND m.fixture_id > 0
                      AND LOWER(COALESCE(m.source, '')) = 'api-football'
                      AND m.match_time >= DATE_SUB(NOW(), INTERVAL 365 DAY)
                      AND m.status IN ('FT','AET','PEN')
                      AND NOT EXISTS (
                        SELECT 1 FROM t_match_detail_snapshot d
                        WHERE d.fixture_id = m.fixture_id AND d.detail_type = 'statistics'
                          AND d.status IN ('NORMAL', 'EMPTY')
                      )
                    GROUP BY m.fixture_id, m.match_time
                    ORDER BY m.match_time DESC LIMIT ?
                    """, maxHistoricalPerRun);
            for (Map<String, Object> row : rows) {
                Long fixtureId = number(row.get("fixture_id"));
                if (fixtureId != null) matchDetailsService.refreshType(fixtureId, "statistics", BudgetBucket.SCHEDULED);
            }
        } catch (Exception ex) {
            log.debug("历史统计回填跳过: {}", ex.getMessage());
        }
    }

    /**
     * P0-2 daily gate shared by both historical-statistics paths.
     * At most one historical round per calendar day (Asia/Shanghai); later
     * rounds that day skip silently. In-memory only — a restart may allow one
     * extra round, still capped to 2 fixtures.
     */
    private boolean beginDailyHistoricalRun() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (today.equals(lastHistoricalRunDate)) {
            log.debug("[PrematchDetails] historical statistics already ran today, skipping");
            return false;
        }
        lastHistoricalRunDate = today;
        return true;
    }

    /**
     * BBC is the authoritative fixture source, but it intentionally has no
     * xG/lineup/injury/odds payload.  Resolve only the matching provider
     * fixture from API-Football and cache its prematch details under the BBC
     * row id.  No API-Football fixture is inserted into crawler_matches, so
     * this cannot create a second match or change source authority.
     */
    private void refreshPrimarySourceDetails(Map<String, List<Map<String, Object>>> fixtureListByDate) {
        if (maxPrimaryPerRun <= 0 || apiFootballProperties.getApiKey() == null || apiFootballProperties.getApiKey().isBlank()) return;
        try {
            String primarySource = crawlerProperties.getPrimarySource();
            if (primarySource == null || primarySource.isBlank()) return;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, fixture_id, match_time, home_team_name, away_team_name, source
                    FROM crawler_matches
                    WHERE match_time >= NOW() AND match_time < DATE_ADD(NOW(), INTERVAL 1 DAY)
                      AND status NOT IN ('FT','AET','PEN','CANC','CANCELED','CANCELLED','ABD','AWD','WO')
                      AND LOWER(COALESCE(source, '')) = LOWER(?)
                    ORDER BY match_time ASC LIMIT ?
                    """, primarySource, maxPrimaryPerRun);
            if (rows.isEmpty()) return;
            Map<String, List<Map<String, Object>>> providerByDate = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                LocalDateTime kickoff = time(row.get("match_time"));
                if (kickoff == null) continue;
                // BBC displays local time while API-Football's date filter is
                // UTC-oriented. Query the adjacent dates as well so a 00:xx
                // local kickoff cannot lose its provider alias.
                // P0-2: each distinct date is fetched at most once per round
                // through the shared cache (24h window ⇒ at most ~4 dates).
                for (int offset : List.of(-1, 0, 1)) {
                    String date = kickoff.toLocalDate().plusDays(offset).toString();
                    if (providerByDate.containsKey(date)) continue;
                    if (!hasApiBudget(1)) {
                        log.info("[PrematchDetails] API-Football 日额度不足，停止主源别名查询");
                        break;
                    }
                    providerByDate.put(date, loadProviderFixturesByDate(date, fixtureListByDate));
                }
            }
            int matched = 0;
            for (Map<String, Object> row : rows) {
                if (matched >= maxPrimaryPerRun) break;
                LocalDateTime kickoff = time(row.get("match_time"));
                if (kickoff == null) continue;
                List<Map<String, Object>> nearbyFixtures = new ArrayList<>();
                for (int offset : List.of(-1, 0, 1)) {
                    List<Map<String, Object>> candidates = providerByDate.get(kickoff.toLocalDate().plusDays(offset).toString());
                    if (candidates != null) nearbyFixtures.addAll(candidates);
                }
                Map<String, Object> provider = findProviderFixture(nearbyFixtures, row, kickoff);
                Long providerId = number(provider == null ? null : ((Map<?, ?>) provider.getOrDefault("fixture", Map.of())).get("id"));
                Long storageId = number(row.get("fixture_id"));
                if (storageId == null || storageId <= 0) storageId = number(row.get("id"));
                if (providerId == null || storageId == null || storageId <= 0) continue;
                // refreshAlias 最多请求 lineups/injuries/odds 三个端点，
                // 预留完整预算，避免本轮任务把日额度打穿到一半。
                if (!hasApiBudget(3)) {
                    log.info("[PrematchDetails] API-Football 日额度不足，停止主源详情补充");
                    break;
                }
                matchDetailsService.refreshAlias(storageId, providerId, List.of("lineups", "injuries", "odds"), BudgetBucket.SCHEDULED);
                matched++;
            }
            if (matched > 0) log.info("[PrematchDetails] primary-source aliases enriched: {}", matched);
        } catch (Exception ex) {
            log.debug("主爬虫赛前详情回填跳过: {}", ex.getMessage());
        }
    }

    /**
     * BBC 是主源，但没有 xG。对已完赛主源行按球队/时间解析 API-Football
     * 别名，只读取 fixtures/statistics 并保存为详情快照，从而让滚动 xG
     * 特征逐步变得可用；绝不把 API-Football 比赛写回主赛程表。
     */
    private void refreshPrimaryHistoricalStatistics(Map<String, List<Map<String, Object>>> fixtureListByDate) {
        if (maxPrimaryHistoricalPerRun <= 0 || apiFootballProperties.getApiKey() == null || apiFootballProperties.getApiKey().isBlank()) return;
        // P0-2: historical statistics run once per day, at most 2 fixtures.
        if (!beginDailyHistoricalRun()) return;
        try {
            String primarySource = crawlerProperties.getPrimarySource();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, fixture_id, match_time, home_team_name, away_team_name, source
                    FROM crawler_matches m
                    WHERE match_time >= DATE_SUB(NOW(), INTERVAL 365 DAY)
                      AND match_time < NOW()
                      AND status IN ('FT','AET','PEN')
                      AND LOWER(COALESCE(source, '')) = LOWER(?)
                      AND NOT EXISTS (
                        SELECT 1 FROM t_match_detail_snapshot d
                        WHERE d.fixture_id = COALESCE(m.fixture_id, m.id)
                          AND d.detail_type = 'statistics'
                          AND d.source = 'api-football'
                          AND d.status IN ('NORMAL', 'EMPTY')
                      )
                    ORDER BY match_time DESC LIMIT ?
                    """, primarySource, maxPrimaryHistoricalPerRun);
            for (Map<String, Object> row : rows) {
                if (!hasApiBudget(4)) break;
                LocalDateTime kickoff = time(row.get("match_time"));
                if (kickoff == null) continue;
                List<Map<String, Object>> candidates = loadProviderFixtures(kickoff, fixtureListByDate);
                Map<String, Object> provider = findProviderFixture(candidates, row, kickoff);
                Map<?, ?> fixture = provider == null || !(provider.get("fixture") instanceof Map<?, ?> value) ? Map.of() : value;
                Long providerId = number(fixture.get("id"));
                Long storageId = number(row.get("fixture_id"));
                if (storageId == null || storageId <= 0) storageId = number(row.get("id"));
                if (providerId == null || storageId == null || storageId <= 0) continue;
                matchDetailsService.refreshAlias(storageId, providerId, List.of("statistics"), BudgetBucket.SCHEDULED);
            }
        } catch (Exception ex) {
            log.debug("主源历史 xG 详情回填跳过: {}", ex.getMessage());
        }
    }

    /**
     * P0-2: one provider date-list fetch per distinct date per round, shared
     * by the prematch and the historical paths through {@code fixtureListByDate}.
     * A fetched (even empty) list is cached — an empty answer is a real answer
     * for that date; only a budget stop or an exception leaves the date
     * uncached so the next round can retry it.
     * <p>
     * Deliberately date-scoped, not league-scoped: a date query returns all
     * leagues in one call, while league+date would need one call per
     * (league, date) pair (up to 12 fixtures × distinct leagues per round).
     * BBC rows carry {@code bbc-*} league ids, so league+date would also need
     * a new BBC→API-Football league map; the cache achieves the same saving
     * (≤ ~4 date calls per round in the 24h window) without it.
     */
    private List<Map<String, Object>> loadProviderFixtures(LocalDateTime kickoff,
                                                           Map<String, List<Map<String, Object>>> fixtureListByDate) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int offset : List.of(-1, 0, 1)) {
            result.addAll(loadProviderFixturesByDate(kickoff.toLocalDate().plusDays(offset).toString(), fixtureListByDate));
        }
        return result;
    }

    private List<Map<String, Object>> loadProviderFixturesByDate(String date,
                                                                 Map<String, List<Map<String, Object>>> fixtureListByDate) {
        List<Map<String, Object>> cached = fixtureListByDate.get(date);
        if (cached != null) return cached;
        if (!hasApiBudget(1)) return List.of();
        try {
            Map<String, Object> raw = apiFootballClient.getFixturesByDate(date, BudgetBucket.SCHEDULED).block();
            Object response = raw == null ? null : raw.get("response");
            List<Map<String, Object>> fixtures = new ArrayList<>();
            if (response instanceof List<?> list) {
                for (Object item : list) if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked") Map<String, Object> fixture = (Map<String, Object>) map;
                    fixtures.add(fixture);
                }
            }
            fixtureListByDate.put(date, fixtures);
            return fixtures;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * P0-2: the budget guard now reads the <em>scheduled</em> share, not the
     * shared total — the 30 on-demand requests stay reserved for users even
     * when the scheduled share is exhausted. Falls back to the total
     * {@code remaining} when the snapshot predates the split.
     */
    private boolean hasApiBudget(int required) {
        Map<String, Object> snapshot = apiFootballClient.budgetSnapshot();
        Object scheduledRemaining = snapshot.get("scheduledRemaining");
        Object remaining = scheduledRemaining instanceof Number ? scheduledRemaining : snapshot.get("remaining");
        if (!(remaining instanceof Number value)) return true;
        int left = value.intValue();
        return left < 0 || left >= Math.max(1, required);
    }

    private Map<String, Object> findProviderFixture(List<Map<String, Object>> fixtures,
                                                     Map<String, Object> target,
                                                     LocalDateTime kickoff) {
        if (fixtures == null || fixtures.isEmpty()) return null;
        String home = IdentityNormalizer.normalize(String.valueOf(target.getOrDefault("home_team_name", "")));
        String away = IdentityNormalizer.normalize(String.valueOf(target.getOrDefault("away_team_name", "")));
        for (Map<String, Object> candidate : fixtures) {
            Map<?, ?> teams = candidate.get("teams") instanceof Map<?, ?> value ? value : Map.of();
            Map<?, ?> h = teams.get("home") instanceof Map<?, ?> value ? value : Map.of();
            Map<?, ?> a = teams.get("away") instanceof Map<?, ?> value ? value : Map.of();
            String candidateHome = IdentityNormalizer.normalize(String.valueOf(mapValue(h, "name")));
            String candidateAway = IdentityNormalizer.normalize(String.valueOf(mapValue(a, "name")));
            boolean sameTeams = IdentityNormalizer.compatible(home, candidateHome)
                    && IdentityNormalizer.compatible(away, candidateAway);
            if (!sameTeams) continue;
            LocalDateTime candidateTime = time(mapValue(candidate.get("fixture") instanceof Map<?, ?> value ? value : Map.of(), "date"));
            // A team can play more than once across adjacent provider dates;
            // never accept a candidate with an unknown time, and keep the
            // fuzzy alias window narrow enough to avoid attaching details
            // from a different fixture.
            if (candidateTime != null && Math.abs(Duration.between(candidateTime, kickoff).toHours()) <= 3) return candidate;
        }
        return null;
    }

    private Long number(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception e) { return null; } }
    private Object mapValue(Map<?, ?> map, String key) { return map == null ? null : map.get(key); }
    private LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        try {
            String text = String.valueOf(value);
            if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                return OffsetDateTime.parse(text.replace("Z", "+00:00")).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            }
            return LocalDateTime.parse(text);
        } catch (Exception e) { return null; }
    }
}
