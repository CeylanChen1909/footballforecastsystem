package com.chen.football.crawler.service;

import com.chen.football.common.service.RuntimeSchemaPolicy;
import com.chen.football.prediction.service.HistoricalMatchCacheService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Recomputable audit for historical training rows.  This service deliberately
 * never mutates crawler_matches: it only records whether a row is usable,
 * duplicated, and how much point-in-time xG enrichment is available.
 */
@Slf4j
@Service
public class HistoricalDataQualityService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ROWS = 50_000;

    private final JdbcTemplate jdbcTemplate;
    private final HistoricalMatchCacheService historicalMatchCacheService;

    public HistoricalDataQualityService(JdbcTemplate jdbcTemplate,
                                        HistoricalMatchCacheService historicalMatchCacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.historicalMatchCacheService = historicalMatchCacheService;
    }

    @PostConstruct
    void ensureSchema() {
        if (!RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS t_match_data_quality (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      fixture_id BIGINT NULL, source VARCHAR(64) NOT NULL,
                      league_id VARCHAR(64) NULL, league_name VARCHAR(128) NULL,
                      canonical_key VARCHAR(255) NOT NULL,
                      quality_status VARCHAR(24) NOT NULL,
                      quality_score DECIMAL(6,4) NOT NULL DEFAULT 0,
                      issue_codes VARCHAR(1000) NULL,
                      home_sample_size INT NOT NULL DEFAULT 0,
                      away_sample_size INT NOT NULL DEFAULT 0,
                      xg_home_available BOOLEAN NOT NULL DEFAULT FALSE,
                      xg_away_available BOOLEAN NOT NULL DEFAULT FALSE,
                      checked_at DATETIME NOT NULL, source_updated_at DATETIME NULL,
                      PRIMARY KEY (id), UNIQUE KEY uk_match_quality_fixture_source (fixture_id, source),
                      KEY idx_match_quality_status (quality_status, checked_at),
                      KEY idx_match_quality_league (league_id, league_name, checked_at),
                      KEY idx_match_quality_canonical (canonical_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        } catch (Exception ex) {
            log.warn("历史数据质量表初始化失败，保留采集链路继续运行: {}", ex.getMessage());
        }
    }

    @Scheduled(initialDelayString = "${data-quality.initial-delay-ms:150000}",
            fixedDelayString = "${data-quality.fixed-delay-ms:1800000}")
    public void refresh() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, fixture_id, source, league_id, league_name,
                           home_team_name, away_team_name, match_time, status,
                           home_score, away_score, updated_at
                    FROM crawler_matches
                    WHERE match_time IS NOT NULL
                      AND status IN ('FT','FINISHED','COMPLETED','AET','PEN')
                    ORDER BY match_time DESC, id DESC
                    LIMIT ?
                    """, MAX_ROWS);
            // The live table is intentionally compact; training also consumes
            // the read-only football-data cache. Audit both surfaces together
            // so coverage and duplicate reports describe the real dataset.
            rows.addAll(historicalMatchCacheService.allFinishedBefore(null));
            rows.sort((left, right) -> {
                LocalDateTime l = time(left.get("match_time")), r = time(right.get("match_time"));
                if (l == null && r == null) return 0;
                if (l == null) return 1;
                if (r == null) return -1;
                return r.compareTo(l);
            });
            if (rows.size() > MAX_ROWS) rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
            if (rows.isEmpty()) return;
            Map<String, Integer> canonicalCounts = new HashMap<>();
            Map<String, Integer> teamSamples = new HashMap<>();
            Set<String> uniqueSampleKeys = new HashSet<>();
            for (Map<String, Object> row : rows) {
                String key = canonicalKey(row);
                canonicalCounts.merge(key, 1, Integer::sum);
                if (isFinished(row) && validScore(row) && uniqueSampleKeys.add(key)) {
                    teamSamples.merge(teamKey(row.get("league_id"), row.get("league_name"), row.get("home_team_name")), 1, Integer::sum);
                    teamSamples.merge(teamKey(row.get("league_id"), row.get("league_name"), row.get("away_team_name")), 1, Integer::sum);
                }
            }
            Map<String, List<Long>> xgTimes = loadXgTimes();
            LocalDateTime checkedAt = LocalDateTime.now(BUSINESS_ZONE);
            int written = 0;
            for (Map<String, Object> row : rows) {
                String canonical = canonicalKey(row);
                String homeTeam = text(row.get("home_team_name"));
                String awayTeam = text(row.get("away_team_name"));
                LocalDateTime matchTime = time(row.get("match_time"));
                boolean homeXg = hasXg(xgTimes, homeTeam, matchTime);
                boolean awayXg = hasXg(xgTimes, awayTeam, matchTime);
                List<String> issues = new ArrayList<>();
                if (homeTeam.isBlank()) issues.add("MISSING_HOME_TEAM");
                if (awayTeam.isBlank()) issues.add("MISSING_AWAY_TEAM");
                if (matchTime == null) issues.add("MISSING_TIME");
                if (!isFinished(row)) issues.add("NOT_FINISHED");
                if (!validScore(row)) issues.add("INVALID_SCORE");
                if (canonicalCounts.getOrDefault(canonical, 0) > 1) issues.add("DUPLICATE_CANONICAL");
                if (!homeXg) issues.add("XG_MISSING_HOME");
                if (!awayXg) issues.add("XG_MISSING_AWAY");
                boolean invalid = issues.contains("MISSING_HOME_TEAM") || issues.contains("MISSING_AWAY_TEAM")
                        || issues.contains("MISSING_TIME") || issues.contains("NOT_FINISHED") || issues.contains("INVALID_SCORE");
                boolean duplicate = issues.contains("DUPLICATE_CANONICAL");
                String status = invalid ? "INVALID" : duplicate ? "DUPLICATE" : (homeXg && awayXg) ? "ENRICHED" : (homeXg || awayXg) ? "XG_PARTIAL" : "VALID";
                double score = invalid ? 0.0 : (homeXg && awayXg ? 1.0 : (homeXg || awayXg ? 0.75 : 0.55));
                int homeSample = teamSamples.getOrDefault(teamKey(row.get("league_id"), row.get("league_name"), homeTeam), 0);
                int awaySample = teamSamples.getOrDefault(teamKey(row.get("league_id"), row.get("league_name"), awayTeam), 0);
                Long fixtureId = number(row.get("fixture_id"));
                if (fixtureId == null || fixtureId <= 0) fixtureId = number(row.get("id"));
                jdbcTemplate.update("""
                        INSERT INTO t_match_data_quality
                          (fixture_id, source, league_id, league_name, canonical_key, quality_status,
                           quality_score, issue_codes, home_sample_size, away_sample_size,
                           xg_home_available, xg_away_available, checked_at, source_updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE league_id=VALUES(league_id), league_name=VALUES(league_name),
                          canonical_key=VALUES(canonical_key), quality_status=VALUES(quality_status),
                          quality_score=VALUES(quality_score), issue_codes=VALUES(issue_codes),
                          home_sample_size=VALUES(home_sample_size), away_sample_size=VALUES(away_sample_size),
                          xg_home_available=VALUES(xg_home_available), xg_away_available=VALUES(xg_away_available),
                          checked_at=VALUES(checked_at), source_updated_at=VALUES(source_updated_at)
                        """, fixtureId, text(row.get("source"), "unknown"), text(row.get("league_id")),
                        text(row.get("league_name")), canonical, status, score,
                        String.join(",", issues), homeSample, awaySample, homeXg, awayXg,
                        checkedAt, time(row.get("updated_at")));
                written++;
            }
            log.info("[DataQuality] audited {} historical matches (xgRows={})", written, xgTimes.values().stream().mapToInt(List::size).sum());
        } catch (Exception ex) {
            log.warn("[DataQuality] historical audit skipped: {}", ex.getMessage());
        }
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "AVAILABLE");
        try {
            Map<String, Object> totals = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) total,
                      COALESCE(SUM(quality_status IN ('VALID','ENRICHED','XG_PARTIAL')),0) valid,
                      COALESCE(SUM(quality_status='ENRICHED'),0) enriched,
                      COALESCE(SUM(quality_status='XG_PARTIAL'),0) xg_partial,
                      COALESCE(SUM(quality_status='DUPLICATE'),0) duplicates,
                      COALESCE(SUM(quality_status='INVALID'),0) invalid,
                      MAX(checked_at) last_checked
                    FROM t_match_data_quality
                    WHERE checked_at=(SELECT MAX(checked_at) FROM t_match_data_quality)
                    """);
            result.put("totals", totals);
            long total = longValue(totals.get("total"));
            long enriched = longValue(totals.get("enriched"));
            long partial = longValue(totals.get("xg_partial"));
            result.put("xgCoverage", total == 0 ? 0.0 : Math.round((enriched + partial * 0.5) * 10000.0 / total) / 100.0);
            result.put("byLeague", jdbcTemplate.queryForList("""
                    SELECT COALESCE(NULLIF(league_id,''), league_name) league,
                           COUNT(*) matches,
                           SUM(quality_status IN ('VALID','ENRICHED','XG_PARTIAL')) valid,
                           SUM(quality_status='ENRICHED') xg_complete,
                           SUM(quality_status='XG_PARTIAL') xg_partial,
                           SUM(quality_status IN ('DUPLICATE','INVALID')) rejected,
                           ROUND(AVG(home_sample_size), 1) avg_home_samples,
                           ROUND(AVG(away_sample_size), 1) avg_away_samples
                    FROM t_match_data_quality
                    WHERE checked_at=(SELECT MAX(checked_at) FROM t_match_data_quality)
                    GROUP BY COALESCE(NULLIF(league_id,''), league_name)
                    ORDER BY matches DESC
                    """));
            result.put("sampleTiers", sampleTiers());
        } catch (Exception ex) {
            result.put("status", "NOT_INITIALIZED");
            result.put("message", "历史质量审计尚未运行或表未初始化");
        }
        return result;
    }

    private Map<String, Object> sampleTiers() {
        Map<String, Object> tiers = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT league_id, league_name, home_team_name team_name, source FROM crawler_matches
                    WHERE status IN ('FT','FINISHED','COMPLETED') AND home_score IS NOT NULL AND away_score IS NOT NULL
                    UNION ALL
                    SELECT league_id, league_name, away_team_name team_name, source FROM crawler_matches
                    WHERE status IN ('FT','FINISHED','COMPLETED') AND home_score IS NOT NULL AND away_score IS NOT NULL
                    """);
            Map<String, Integer> liveCounts = new HashMap<>();
            for (Map<String, Object> row : rows) {
                liveCounts.merge(teamKey(row.get("league_id"), row.get("league_name"), row.get("team_name")), 1, Integer::sum);
            }
            Map<String, Integer> cacheCounts = new HashMap<>();
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> row : historicalMatchCacheService.allFinishedBefore(null)) {
                String canonical = canonicalKey(row);
                if (!seen.add(canonical)) continue;
                cacheCounts.merge(teamKey(row.get("league_id"), row.get("league_name"), row.get("home_team_name")), 1, Integer::sum);
                cacheCounts.merge(teamKey(row.get("league_id"), row.get("league_name"), row.get("away_team_name")), 1, Integer::sum);
            }
            tiers.put("thresholds", Map.of("robust", 10, "ready", 5, "limited", 3));
            tiers.put("trainingCache", tierCounts(cacheCounts));
            tiers.put("liveTable", tierCounts(liveCounts));
            tiers.put("teams", cacheCounts.size());
        } catch (Exception ex) { tiers.put("status", "UNAVAILABLE"); }
        return tiers;
    }

    private Map<String, Object> tierCounts(Map<String, Integer> counts) {
        int robust = 0, ready = 0, limited = 0, insufficient = 0;
        for (int count : counts.values()) {
            if (count >= 10) robust++; else if (count >= 5) ready++; else if (count >= 3) limited++; else insufficient++;
        }
        return Map.of("teams", counts.size(), "robust", robust, "ready", ready,
                "limited", limited, "insufficient", insufficient);
    }

    private Map<String, List<Long>> loadXgTimes() {
        Map<String, List<Long>> times = new HashMap<>();
        try {
            jdbcTemplate.query("SELECT team_name, match_time FROM t_understat_team_xg_cache",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> times
                            .computeIfAbsent(IdentityNormalizer.normalize(rs.getString("team_name")), ignored -> new ArrayList<>())
                            .add(rs.getTimestamp("match_time").getTime() / 60000L));
        } catch (Exception ignored) { }
        return times;
    }

    private boolean hasXg(Map<String, List<Long>> times, String team, LocalDateTime matchTime) {
        if (matchTime == null || team.isBlank()) return false;
        List<Long> values = times.get(IdentityNormalizer.normalize(team));
        if (values == null) {
            // BBC/football-data often use short names ("Man United") while
            // Understat uses the canonical English name. Reuse the same
            // conservative identity compatibility rule as the joiner.
            values = times.entrySet().stream()
                    .filter(entry -> IdentityNormalizer.compatible(team, entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream())
                    .toList();
        }
        if (values == null) return false;
        long target = matchTime.atZone(BUSINESS_ZONE).toInstant().toEpochMilli() / 60000L;
        return values.stream().anyMatch(value -> Math.abs(value - target) <= 12 * 60);
    }

    private String canonicalKey(Map<String, Object> row) {
        String league = canonicalLeague(row.get("league_id"), row.get("league_name"));
        String home = IdentityNormalizer.normalize(text(row.get("home_team_name")));
        String away = IdentityNormalizer.normalize(text(row.get("away_team_name")));
        LocalDateTime dt = time(row.get("match_time"));
        String slot = dt == null ? "unknown" : dt.withMinute((dt.getMinute() / 30) * 30).withSecond(0).withNano(0).toString();
        return String.join("|", league, home, away, slot);
    }

    private String teamKey(Object leagueId, Object leagueName, Object team) {
        return canonicalLeague(leagueId, leagueName) + "|" + IdentityNormalizer.normalize(text(team));
    }

    private String canonicalLeague(Object leagueId, Object leagueName) {
        String value = IdentityNormalizer.normalize(text(leagueId));
        if (value.isBlank()) value = IdentityNormalizer.normalize(text(leagueName));
        if (value.contains("premier") || value.equals("pl")) return "PL";
        if (value.contains("bundes") || value.equals("bl1")) return "BL1";
        if (value.contains("primera") || value.contains("laliga") || value.equals("pd")) return "PD";
        if (value.contains("ligue") || value.equals("fl1")) return "FL1";
        if (value.contains("seriea") || value.equals("sa")) return "SA";
        if (value.contains("eredivisie") || value.equals("ded")) return "DED";
        if (value.contains("primeira") || value.equals("ppl")) return "PPL";
        if (value.contains("championship") || value.equals("elc")) return "ELC";
        return value;
    }

    private boolean isFinished(Map<String, Object> row) {
        String status = text(row.get("status")).toUpperCase(Locale.ROOT);
        return Set.of("FT", "FINISHED", "COMPLETED", "AET", "PEN").contains(status);
    }

    private boolean validScore(Map<String, Object> row) {
        Integer home = integer(row.get("home_score")), away = integer(row.get("away_score"));
        return home != null && away != null && home >= 0 && away >= 0 && home <= 30 && away <= 30;
    }

    private LocalDateTime time(Object value) {
        if (value instanceof Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof LocalDateTime dt) return dt;
        if (value == null) return null;
        try {
            String raw = String.valueOf(value).trim().replace(' ', 'T');
            return LocalDateTime.parse(raw.length() > 19 ? raw.substring(0, 19) : raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long number(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ex) { return null; } }
    private Integer integer(Object value) { try { return value == null ? null : Integer.valueOf(String.valueOf(value)); } catch (Exception ex) { return null; } }
    private long longValue(Object value) { try { return value == null ? 0L : Long.parseLong(String.valueOf(value)); } catch (Exception ex) { return 0L; } }
    private String text(Object value) { return text(value, ""); }
    private String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value).trim(); }
}
