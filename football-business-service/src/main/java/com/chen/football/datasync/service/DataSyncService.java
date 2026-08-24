package com.chen.football.datasync.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.common.client.ApiFootballClient;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.dto.MatchStatus;
import com.chen.football.common.service.DistributedLockService;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.datasync.entity.FixtureCacheEntity;
import com.chen.football.datasync.entity.TeamCacheEntity;
import com.chen.football.datasync.mapper.FixtureCacheMapper;
import com.chen.football.datasync.mapper.TeamCacheMapper;
import com.chen.football.datasync.mapper.TeamFormMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DataSyncService {

    private static final Logger log = LoggerFactory.getLogger(DataSyncService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ApiFootballClient apiClient;
    private final TeamCacheMapper teamCacheMapper;
    private final FixtureCacheMapper fixtureCacheMapper;
    private final TeamFormMapper teamFormMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DistributedLockService lockService;
    private final CrawlerMatchMapper crawlerMatchMapper;
    private final CrawlerProperties crawlerProperties;

    @Value("${sync.season:0}")
    private int configuredSeason;

    @Value("${sync.leagues:39,140,135,78,61,88,94,40}")
    private String leaguesConfig;


    public DataSyncService(ApiFootballClient apiClient,
                           TeamCacheMapper teamCacheMapper,
                           FixtureCacheMapper fixtureCacheMapper,
                           TeamFormMapper teamFormMapper,
                           JdbcTemplate jdbcTemplate,
                           DistributedLockService lockService,
                           CrawlerMatchMapper crawlerMatchMapper,
                           CrawlerProperties crawlerProperties) {
        this.apiClient = apiClient;
        this.teamCacheMapper = teamCacheMapper;
        this.fixtureCacheMapper = fixtureCacheMapper;
        this.teamFormMapper = teamFormMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.lockService = lockService;
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.crawlerProperties = crawlerProperties;
    }

    public void syncAllLeagues() {
        if (crawlerProperties.isPrimaryOnly()) {
            log.info("[DataSync] 外部 API 全量同步已停用，当前仅使用主爬虫源 {}；仅回填本地预测特征",
                    crawlerProperties.getPrimarySource());
            syncCrawlerFeatures();
            return;
        }
        String lockToken = lockService.tryLock("datasync:all-leagues", Duration.ofMinutes(30));
        if (lockToken == null) {
            log.warn("[DataSync] Full sync skipped because another job is running");
            return;
        }
        try {
            log.info("[DataSync] Starting full data sync...");
            String[] leagueIds = leaguesConfig.split(",");
            int synced = 0;
            for (String leagueIdStr : leagueIds) {
                try {
                    int leagueId = Integer.parseInt(leagueIdStr.trim());
                    if (!productionLeagueIds().contains(leagueId)) {
                        log.info("[DataSync] Skip league {}: outside production prediction scope", leagueId);
                        continue;
                    }
                    syncTeams(leagueId);
                    syncFixtures(leagueId);
                    synced++;
                    Thread.sleep(2000); // 避免 API 限流
                } catch (Exception e) {
                    log.error("[DataSync] Failed to sync league {}: {}", leagueIdStr, e.getMessage());
                }
            }
            // 爬虫赛程是前台和预测的事实来源；无论外部 API 是否限额，都从该表回填预测特征。
            syncCrawlerFeatures();
            log.info("[DataSync] Sync completed for {}/{} leagues", synced, leagueIds.length);
        } finally {
            lockService.unlock("datasync:all-leagues", lockToken);
        }
    }

    public void syncTeams(int leagueId) {
        log.info("[DataSync] Syncing teams for league {}", leagueId);
        try {
            Map<String, Object> raw = apiClient.getTeams(leagueId, resolveSeason()).block();
            if (raw == null || !raw.containsKey("response")) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> response = (List<Map<String, Object>>) raw.get("response");
            int saved = 0;
            for (Map<String, Object> item : response) {
                try {
                    Map<String, Object> team = (Map<String, Object>) item.get("team");
                    if (team == null) continue;
                    Long teamId = toLong(team.get("id"));
                    if (teamId == null) continue;

                    TeamCacheEntity existing = teamCacheMapper.selectOne(
                            new LambdaQueryWrapper<TeamCacheEntity>().eq(TeamCacheEntity::getTeamId, teamId));

                    TeamCacheEntity entity = new TeamCacheEntity();
                    entity.setTeamId(teamId);
                    entity.setTeamName(str(team.get("name")));
                    entity.setTeamLogo(str(team.get("logo")));
                    entity.setLeagueId((long) leagueId);
                    entity.setEloRating(resolveElo(teamId));

                    Map<String, Object> venue = (Map<String, Object>) item.get("venue");
                    if (venue != null) {
                        entity.setVenue(str(venue.get("name")));
                        entity.setCountry(str(venue.get("country")));
                    }

                    if (existing == null) {
                        entity.setCreatedAt(LocalDateTime.now(BUSINESS_ZONE));
                        teamCacheMapper.insert(entity);
                    } else {
                        entity.setId(existing.getId());
                        entity.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
                        teamCacheMapper.updateById(entity);
                    }
                    saved++;
                } catch (Exception e) {
                    log.warn("[DataSync] Failed to save team: {}", e.getMessage());
                }
            }
            log.info("[DataSync] Saved {} teams for league {}", saved, leagueId);
        } catch (Exception e) {
            log.error("[DataSync] syncTeams failed: {}", e.getMessage());
        }
    }

    /**
     * 从 crawler_matches 回填近期战绩和动态 ELO。
     * t_team_form 只接受数字 ID，非数字第三方 ID 仍由预测服务直接从 crawler_matches 读取。
     */
    @Transactional
    public void syncCrawlerFeatures() {
        String lockToken = lockService.tryLock("datasync:crawler-features", Duration.ofMinutes(15));
        if (lockToken == null) {
            log.warn("[DataSync] crawler feature sync skipped because another job is running");
            return;
        }
        try {
            // When primary-only mode is enabled, disabled provider rows must
            // not leak back into ELO/form features.  The source is a trusted
            // server-side configuration value, escaped before interpolation.
            String sourceFilter = primarySourceFilter();
            // Rebuild the derived table atomically from the currently visible
            // source.  Deleting only rows that still join to the primary
            // provider leaves stale forms from a disabled provider behind
            // (and can also collide with the fixture unique key on INSERT).
            jdbcTemplate.update("DELETE FROM t_team_form");
            int forms = jdbcTemplate.update(("""
                    INSERT INTO t_team_form
                      (team_id, fixture_id, is_home, opponent_id, goals, conceded, yellow_cards, red_cards, result, match_time)
                    SELECT CAST(home_team_id AS UNSIGNED), fixture_id, 1, CAST(away_team_id AS UNSIGNED),
                           home_score, away_score, 0, 0,
                           CASE WHEN home_score > away_score THEN 0 WHEN home_score = away_score THEN 1 ELSE 2 END,
                           match_time
                    FROM crawler_matches
                    WHERE status = 'FT' AND fixture_id IS NOT NULL
                      AND home_score IS NOT NULL AND away_score IS NOT NULL
                      AND home_team_id REGEXP '^[0-9]+$' AND away_team_id REGEXP '^[0-9]+$' %s
                    UNION ALL
                    SELECT CAST(away_team_id AS UNSIGNED), fixture_id, 0, CAST(home_team_id AS UNSIGNED),
                           away_score, home_score, 0, 0,
                           CASE WHEN away_score > home_score THEN 0 WHEN away_score = home_score THEN 1 ELSE 2 END,
                           match_time
                    FROM crawler_matches
                    WHERE status = 'FT' AND fixture_id IS NOT NULL
                      AND home_score IS NOT NULL AND away_score IS NOT NULL
                      AND home_team_id REGEXP '^[0-9]+$' AND away_team_id REGEXP '^[0-9]+$' %s
                    """).formatted(sourceFilter, sourceFilter));

            List<Map<String, Object>> teams = jdbcTemplate.queryForList(("""
                    SELECT team_id, MAX(team_name) AS team_name
                    FROM (
                    SELECT home_team_id AS team_id, home_team_name AS team_name FROM crawler_matches WHERE 1=1 %s
                      UNION ALL
                      SELECT away_team_id AS team_id, away_team_name AS team_name FROM crawler_matches WHERE 1=1 %s
                    ) t
                    WHERE team_id REGEXP '^[0-9]+$'
                    GROUP BY team_id
                    """).formatted(sourceFilter, sourceFilter));
            int teamsUpdated = 0;
            for (Map<String, Object> team : teams) {
                String teamId = String.valueOf(team.get("team_id"));
                long numericId;
                try { numericId = Long.parseLong(teamId); } catch (Exception ignored) { continue; }
                double elo = calculateElo(teamId);
                String teamName = team.get("team_name") == null ? "球队" + teamId : String.valueOf(team.get("team_name"));
                teamsUpdated += jdbcTemplate.update("""
                        INSERT INTO t_team_cache (team_id, team_name, elo_rating, created_at, updated_at)
                        VALUES (?, ?, ?, NOW(), NOW())
                        ON DUPLICATE KEY UPDATE team_name = VALUES(team_name), elo_rating = VALUES(elo_rating), updated_at = NOW()
                        """, numericId, teamName, elo);
            }
            log.info("[DataSync] crawler feature backfill completed: teamForms={}, teams={}", forms, teamsUpdated);
        } catch (Exception e) {
            log.error("[DataSync] crawler feature backfill failed: {}", e.getMessage(), e);
        } finally {
            lockService.unlock("datasync:crawler-features", lockToken);
        }
    }

    private double resolveElo(Long teamId) {
        if (teamId == null) return 1500.0;
        return calculateElo(String.valueOf(teamId));
    }

    private double calculateElo(String targetTeamId) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(("""
                    SELECT home_team_id, away_team_id, home_score, away_score
                    FROM crawler_matches
                    WHERE status = 'FT' AND home_score IS NOT NULL AND away_score IS NOT NULL
                      %s
                    ORDER BY match_time ASC
                    """).formatted(primarySourceFilter()));
        } catch (Exception e) {
            return 1500.0;
        }
        Map<String, Double> ratings = new HashMap<>();
        boolean found = false;
        for (Map<String, Object> row : rows) {
            String home = String.valueOf(row.get("home_team_id"));
            String away = String.valueOf(row.get("away_team_id"));
            if (home == null || away == null || !home.matches("\\d+") || !away.matches("\\d+")) continue;
            int homeGoals = ((Number) row.get("home_score")).intValue();
            int awayGoals = ((Number) row.get("away_score")).intValue();
            double homeElo = ratings.getOrDefault(home, 1500.0);
            double awayElo = ratings.getOrDefault(away, 1500.0);
            double expected = 1.0 / (1.0 + Math.pow(10, -((homeElo + 50.0) - awayElo) / 400.0));
            double actual = homeGoals > awayGoals ? 1.0 : homeGoals == awayGoals ? 0.5 : 0.0;
            double delta = 20.0 * (actual - expected);
            ratings.put(home, homeElo + delta);
            ratings.put(away, awayElo - delta);
            if (targetTeamId.equals(home) || targetTeamId.equals(away)) found = true;
        }
        return found ? ratings.getOrDefault(targetTeamId, 1500.0) : 1500.0;
    }

    private String primarySourceFilter() {
        if (!crawlerProperties.isPrimaryOnly() || crawlerProperties.getPrimarySource() == null
                || crawlerProperties.getPrimarySource().isBlank()) return "";
        return " AND source = '" + crawlerProperties.getPrimarySource().replace("'", "''") + "'";
    }

    /** Keep the production scope configurable instead of duplicating a second
     * hard-coded league list in the feature synchronizer. */
    private Set<Integer> productionLeagueIds() {
        Set<Integer> ids = new java.util.LinkedHashSet<>();
        String configured = leaguesConfig == null ? "" : leaguesConfig;
        for (String value : configured.split(",")) {
            try { ids.add(Integer.parseInt(value.trim())); } catch (NumberFormatException ignored) { }
        }
        return ids.isEmpty() ? Set.of(39, 140, 135, 78, 61, 88, 94, 40) : ids;
    }

    public void syncFixtures(int leagueId) {
        log.info("[DataSync] Syncing fixtures for league {}", leagueId);
        try {
            Map<String, Object> raw = apiClient.getFixtures(leagueId, resolveSeason(), null).block();
            if (raw == null || !raw.containsKey("response")) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> response = (List<Map<String, Object>>) raw.get("response");
            int saved = 0;
            for (Map<String, Object> item : response) {
                try {
                    Map<String, Object> fixture = (Map<String, Object>) item.get("fixture");
                    if (fixture == null) continue;
                    Long fixtureId = toLong(fixture.get("id"));
                    if (fixtureId == null) continue;

                    Map<String, Object> league = (Map<String, Object>) item.get("league");
                    Map<String, Object> teams = (Map<String, Object>) item.get("teams");
                    Map<String, Object> goals = (Map<String, Object>) item.get("goals");

                    FixtureCacheEntity existing = fixtureCacheMapper.selectOne(
                            new LambdaQueryWrapper<FixtureCacheEntity>()
                                    .eq(FixtureCacheEntity::getFixtureId, fixtureId));

                    FixtureCacheEntity entity = new FixtureCacheEntity();
                    entity.setFixtureId(fixtureId);
                    if (league != null) {
                        entity.setLeagueId(toLong(league.get("id")));
                        entity.setLeagueName(str(league.get("name")));
                    }
                    if (teams != null) {
                        Map<String, Object> home = (Map<String, Object>) teams.get("home");
                        Map<String, Object> away = (Map<String, Object>) teams.get("away");
                        if (home != null) {
                            entity.setHomeTeamId(toLong(home.get("id")));
                            entity.setHomeTeamName(str(home.get("name")));
                        }
                        if (away != null) {
                            entity.setAwayTeamId(toLong(away.get("id")));
                            entity.setAwayTeamName(str(away.get("name")));
                        }
                    }
                    if (fixture.get("timestamp") != null) {
                        long ts = ((Number) fixture.get("timestamp")).longValue();
                        entity.setMatchTime(LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(ts), ZoneId.of("Asia/Shanghai")));
                    }
                    Map<String, Object> status = (Map<String, Object>) fixture.get("status");
                    if (status != null) {
                        entity.setStatus(str(status.get("short")));
                    }
                    if (goals != null) {
                        entity.setHomeGoals(toInt(goals.get("home")));
                        entity.setAwayGoals(toInt(goals.get("away")));
                    }
                    entity.setRound(str(fixture.get("round")));

                    if (existing == null) {
                        entity.setCreatedAt(LocalDateTime.now(BUSINESS_ZONE));
                        fixtureCacheMapper.insert(entity);
                    } else {
                        entity.setId(existing.getId());
                        entity.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
                        fixtureCacheMapper.updateById(entity);
                    }
                    syncCrawlerMatch(fixtureId, fixture, teams, entity);
                    saved++;
                } catch (Exception e) {
                    log.warn("[DataSync] Failed to save fixture: {}", e.getMessage());
                }
            }
            log.info("[DataSync] Saved {} fixtures for league {}", saved, leagueId);
        } catch (Exception e) {
            log.error("[DataSync] syncFixtures failed: {}", e.getMessage());
        }
    }

    /**
     * DataSync 原先只写 t_fixture_cache，而前台赛程读取 crawler_matches，导致
     * 定时同步成功但用户看不到新比赛。这里将同一份 API-Football 结果同步到前台
     * 使用的表，并按 fixtureId 合并已有的爬虫记录，避免重复比赛。
     */
    @SuppressWarnings("unchecked")
    private void syncCrawlerMatch(Long fixtureId,
                                  Map<String, Object> fixture,
                                  Map<String, Object> teams,
                                  FixtureCacheEntity cache) {
        try {
            Long leagueId = cache == null ? null : cache.getLeagueId();
            if (leagueId == null || !productionLeagueIds().contains(leagueId.intValue())) {
                return;
            }
            Map<String, Object> home = teams == null ? null : (Map<String, Object>) teams.get("home");
            Map<String, Object> away = teams == null ? null : (Map<String, Object>) teams.get("away");
            Map<String, Object> venue = null;
            Object rawVenue = fixture == null ? null : fixture.get("venue");
            if (rawVenue instanceof Map<?, ?>) {
                venue = (Map<String, Object>) rawVenue;
            }

            // 数据同步只允许按第三方 fixture_id 精确合并，不能回退本地主键；
            // 否则 fixture=123 可能覆盖恰好 id=123 的其他来源比赛。
            CrawlerMatch match = crawlerMatchMapper.findByFixtureIdAndSource(fixtureId, "api-football");
            if (match == null) {
                match = new CrawlerMatch();
                match.setCreatedAt(LocalDateTime.now(BUSINESS_ZONE));
            }
            match.setFixtureId(fixtureId);
            match.setExternalMatchId(String.valueOf(fixtureId));
            match.setSource("api-football");
            match.setLeagueId(cache.getLeagueId() == null ? null : String.valueOf(cache.getLeagueId()));
            match.setLeagueName(cache.getLeagueName());
            match.setRound(cache.getRound());
            match.setVenue(venue == null ? null : str(venue.get("name")));
            match.setHomeTeamId(cache.getHomeTeamId() == null ? null : String.valueOf(cache.getHomeTeamId()));
            match.setHomeTeamName(cache.getHomeTeamName());
            match.setHomeTeamLogo(home == null ? null : str(home.get("logo")));
            match.setAwayTeamId(cache.getAwayTeamId() == null ? null : String.valueOf(cache.getAwayTeamId()));
            match.setAwayTeamName(cache.getAwayTeamName());
            match.setAwayTeamLogo(away == null ? null : str(away.get("logo")));
            match.setHomeScore(cache.getHomeGoals());
            match.setAwayScore(cache.getAwayGoals());
            match.setStatus(MatchStatus.normalize(cache.getStatus()));
            match.setMatchTime(cache.getMatchTime());
            match.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
            if (match.getId() == null) {
                crawlerMatchMapper.insert(match);
            } else {
                crawlerMatchMapper.updateById(match);
            }
        } catch (Exception e) {
            log.warn("[DataSync] Failed to mirror fixture {} to crawler_matches: {}", fixtureId, e.getMessage());
        }
    }

    /**
     * 验证已结束比赛的预测结果准确性
     * 比赛结束后（status=FT），自动对比实际结果和预测结果
     */
    @Transactional
    public void verifyPredictions() {
        String lockToken = lockService.tryLock("datasync:verify", Duration.ofMinutes(10));
        if (lockToken == null) {
            log.warn("[DataSync] Verify skipped because another verify job is running");
            return;
        }
        try {
            log.info("[DataSync] Verifying predictions...");
            // 校验也读取 crawler_matches，避免 t_fixture_cache 未同步时准确率永远不更新。
            List<Map<String, Object>> completedFixtures = jdbcTemplate.queryForList(
                    "SELECT fixture_id, home_score, away_score FROM crawler_matches "
                            + "WHERE status IN ('FT', 'FINISHED', 'MATCH_FINISHED') "
                            + "AND fixture_id IS NOT NULL AND home_score IS NOT NULL AND away_score IS NOT NULL");
            int updatedPredictions = 0;

            for (Map<String, Object> fixture : completedFixtures) {
                Integer homeGoals = toInt(fixture.get("home_score"));
                Integer awayGoals = toInt(fixture.get("away_score"));
                Long fixtureId = toLong(fixture.get("fixture_id"));
                if (fixtureId == null) continue;
                String actualLabel = toActualResultLabel(homeGoals, awayGoals);

                int updated = jdbcTemplate.update(
                        "UPDATE t_prediction " +
                                "SET actual_result = ?, is_correct = CASE WHEN result_label = ? THEN 1 ELSE 0 END, verified_at = NOW() " +
                                "WHERE fixture_id = ? AND verified_at IS NULL",
                        actualLabel,
                        actualLabel,
                        fixtureId
                );
                updatedPredictions += updated;
            }

            log.info("[DataSync] Verified {} fixtures, updated {} prediction rows",
                    completedFixtures.size(), updatedPredictions);
        } catch (Exception e) {
            log.error("[DataSync] verifyPredictions failed: {}", e.getMessage());
        } finally {
            lockService.unlock("datasync:verify", lockToken);
        }
    }

    private String toActualResultLabel(Integer homeGoals, Integer awayGoals) {
        if (homeGoals == null || awayGoals == null) {
            return null;
        }
        if (homeGoals > awayGoals) {
            return "HOME_WIN";
        }
        if (homeGoals < awayGoals) {
            return "AWAY_WIN";
        }
        return "DRAW";
    }

    // ==================== 工具方法 ====================

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private String str(Object v) {
        return v == null ? null : v.toString();
    }

    private int resolveSeason() {
        return configuredSeason > 0 ? configuredSeason : Year.now().getValue();
    }
}
