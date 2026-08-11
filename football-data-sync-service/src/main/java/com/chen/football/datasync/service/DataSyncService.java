package com.chen.football.datasync.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.common.client.ApiFootballClient;
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

import java.time.*;
import java.util.List;
import java.util.Map;

@Service
public class DataSyncService {

    private static final Logger log = LoggerFactory.getLogger(DataSyncService.class);

    private final ApiFootballClient apiClient;
    private final TeamCacheMapper teamCacheMapper;
    private final FixtureCacheMapper fixtureCacheMapper;
    private final TeamFormMapper teamFormMapper;
    private final JdbcTemplate jdbcTemplate;

    @Value("${sync.season:2025}")
    private int currentSeason;

    @Value("${sync.leagues:39,140,135,78,61,2}")
    private String leaguesConfig;

    public DataSyncService(ApiFootballClient apiClient,
                           TeamCacheMapper teamCacheMapper,
                           FixtureCacheMapper fixtureCacheMapper,
                           TeamFormMapper teamFormMapper,
                           JdbcTemplate jdbcTemplate) {
        this.apiClient = apiClient;
        this.teamCacheMapper = teamCacheMapper;
        this.fixtureCacheMapper = fixtureCacheMapper;
        this.teamFormMapper = teamFormMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void syncAllLeagues() {
        log.info("[DataSync] Starting full data sync...");
        String[] leagueIds = leaguesConfig.split(",");
        int synced = 0;
        for (String leagueIdStr : leagueIds) {
            try {
                int leagueId = Integer.parseInt(leagueIdStr.trim());
                syncTeams(leagueId);
                syncFixtures(leagueId);
                synced++;
                Thread.sleep(2000); // 避免 API 限流
            } catch (Exception e) {
                log.error("[DataSync] Failed to sync league {}: {}", leagueIdStr, e.getMessage());
            }
        }
        log.info("[DataSync] Sync completed for {}/{} leagues", synced, leagueIds.length);
    }

    public void syncTeams(int leagueId) {
        log.info("[DataSync] Syncing teams for league {}", leagueId);
        try {
            Map<String, Object> raw = apiClient.getTeams(leagueId, currentSeason).block();
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
                    entity.setEloRating(1500.0);

                    Map<String, Object> venue = (Map<String, Object>) item.get("venue");
                    if (venue != null) {
                        entity.setVenue(str(venue.get("name")));
                        entity.setCountry(str(venue.get("country")));
                    }

                    if (existing == null) {
                        entity.setCreatedAt(LocalDateTime.now());
                        teamCacheMapper.insert(entity);
                    } else {
                        entity.setId(existing.getId());
                        entity.setUpdatedAt(LocalDateTime.now());
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

    public void syncFixtures(int leagueId) {
        log.info("[DataSync] Syncing fixtures for league {}", leagueId);
        try {
            Map<String, Object> raw = apiClient.getFixtures(leagueId, currentSeason, null).block();
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
                                Instant.ofEpochSecond(ts), ZoneId.systemDefault()));
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
                        entity.setCreatedAt(LocalDateTime.now());
                        fixtureCacheMapper.insert(entity);
                    } else {
                        entity.setId(existing.getId());
                        entity.setUpdatedAt(LocalDateTime.now());
                        fixtureCacheMapper.updateById(entity);
                    }
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
     * 验证已结束比赛的预测结果准确性
     * 比赛结束后（status=FT），自动对比实际结果和预测结果
     */
    public void verifyPredictions() {
        log.info("[DataSync] Verifying predictions...");
        try {
            LambdaQueryWrapper<FixtureCacheEntity> q = new LambdaQueryWrapper<FixtureCacheEntity>()
                    .eq(FixtureCacheEntity::getStatus, "FT")
                    .isNotNull(FixtureCacheEntity::getHomeGoals)
                    .isNotNull(FixtureCacheEntity::getAwayGoals);

            List<FixtureCacheEntity> completedFixtures = fixtureCacheMapper.selectList(q);
            int updatedPredictions = 0;

            for (FixtureCacheEntity fixture : completedFixtures) {
                String actualLabel = toActualResultLabel(fixture.getHomeGoals(), fixture.getAwayGoals());

                int updated = jdbcTemplate.update(
                        "UPDATE t_prediction_history " +
                                "SET actual_result = ?, is_correct = CASE WHEN result_label = ? THEN 1 ELSE 0 END, verified_at = NOW() " +
                                "WHERE fixture_id = ? AND verified_at IS NULL",
                        actualLabel,
                        actualLabel,
                        fixture.getFixtureId()
                );
                updatedPredictions += updated;
            }

            log.info("[DataSync] Verified {} fixtures, updated {} prediction rows",
                    completedFixtures.size(), updatedPredictions);
        } catch (Exception e) {
            log.error("[DataSync] verifyPredictions failed: {}", e.getMessage());
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
}
