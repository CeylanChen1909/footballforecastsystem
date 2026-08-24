package com.chen.football.match.service;

import com.chen.football.common.client.UnderstatClient;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.config.UnderstatProperties;
import com.chen.football.crawler.service.IdentityNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Imports historical match-level xG from Understat into the existing detail
 * snapshot store.  Understat is never allowed to create/update a fixture row;
 * the BBC/API-Football row remains the sole match identity.
 */
@Slf4j
@Component
public class UnderstatEnrichmentRefreshTask {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Map<String, String> LEAGUES = Map.of(
            "PL", "EPL",
            "PD", "La_liga",
            "BL1", "Bundesliga",
            "SA", "Serie_A",
            "FL1", "Ligue_1"
    );

    private final JdbcTemplate jdbcTemplate;
    private final UnderstatClient understatClient;
    private final UnderstatProperties properties;
    private final CrawlerProperties crawlerProperties;
    private final ObjectMapper objectMapper;

    public UnderstatEnrichmentRefreshTask(JdbcTemplate jdbcTemplate,
                                          UnderstatClient understatClient,
                                          UnderstatProperties properties,
                                          CrawlerProperties crawlerProperties,
                                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.understatClient = understatClient;
        this.properties = properties;
        this.crawlerProperties = crawlerProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void ensureSchema() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_understat_league_cache (
                  league_code VARCHAR(16) NOT NULL,
                  season INT NOT NULL,
                  payload_json MEDIUMTEXT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'EMPTY',
                  error_message VARCHAR(1000) NULL,
                  fetched_at DATETIME NOT NULL,
                  PRIMARY KEY (league_code, season),
                  KEY idx_understat_fetched (fetched_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_understat_team_xg_cache (
                  league_code VARCHAR(16) NOT NULL,
                  season INT NOT NULL,
                  source_match_id VARCHAR(32) NOT NULL,
                  team_id VARCHAR(32) NOT NULL,
                  team_name VARCHAR(255) NOT NULL,
                  match_time DATETIME NOT NULL,
                  xg DECIMAL(8,4) NOT NULL,
                  xga DECIMAL(8,4) NOT NULL,
                  fetched_at DATETIME NOT NULL,
                  PRIMARY KEY (league_code, season, source_match_id, team_id),
                  KEY idx_understat_team_time (team_name, match_time),
                  KEY idx_understat_xg_time (match_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_understat_match_join_audit (
                  league_code VARCHAR(16) NOT NULL,
                  season INT NOT NULL,
                  source_match_id VARCHAR(64) NOT NULL,
                  home_team_name VARCHAR(255) NULL,
                  away_team_name VARCHAR(255) NULL,
                  match_time DATETIME NULL,
                  join_status VARCHAR(24) NOT NULL,
                  fixture_id BIGINT NULL,
                  message VARCHAR(255) NULL,
                  checked_at DATETIME NOT NULL,
                  PRIMARY KEY (league_code, season, source_match_id),
                  KEY idx_understat_join_status (join_status, checked_at),
                  KEY idx_understat_join_league (league_code, season, join_status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Scheduled(initialDelayString = "${understat.initial-delay-ms:120000}",
            fixedDelayString = "${understat.fixed-delay-ms:21600000}")
    public void refresh() {
        if (!properties.isEnabled()) {
            log.debug("[Understat] enrichment disabled");
            return;
        }
        int requests = 0;
        int imported = 0;
        for (Map.Entry<String, String> league : LEAGUES.entrySet()) {
            for (Integer season : safeSeasons()) {
                if (requests >= Math.max(1, properties.getMaxRequestsPerRun())) break;
                if (hasFreshCache(league.getKey(), season)) continue;
                try {
                    Map<String, Object> payload = understatClient.getLeagueData(league.getValue(), season).block();
                    requests++;
                    if (payload == null || !(payload.get("dates") instanceof List<?>)) {
                        saveCache(league.getKey(), season, payload, "EMPTY", "Understat 未返回 dates");
                        continue;
                    }
                    int teamRows = saveTeamXgRows(league.getKey(), season, payload);
                    int count = importLeague(league.getKey(), season, payload);
                    imported += count;
                    saveCache(league.getKey(), season, payload, "NORMAL",
                            count > 0 ? null : "源数据已缓存，未匹配到本地主源比赛");
                    log.info("[Understat] {} {} cached {} team xG rows, imported {} fixture snapshots", league.getKey(), season, teamRows, count);
                } catch (Exception ex) {
                    requests++;
                    String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    saveCache(league.getKey(), season, null, classify(message), message);
                    log.warn("[Understat] {} {} failed: {}", league.getKey(), season, message, ex);
                }
            }
            if (requests >= Math.max(1, properties.getMaxRequestsPerRun())) break;
        }
        if (requests > 0 || imported > 0) {
            log.info("[Understat] refresh completed requests={}, imported={}", requests, imported);
        }
    }

    /** Prime the cache after startup; subsequent runs remain budget/TTL bounded. */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        java.util.concurrent.CompletableFuture.runAsync(this::refresh);
    }

    private int importLeague(String leagueCode, int season, Map<String, Object> payload) {
        Object rawDates = payload.get("dates");
        if (!(rawDates instanceof List<?> dates)) return 0;
        LocalDateTime from = LocalDateTime.of(season, 7, 1, 0, 0);
        LocalDateTime to = from.plusYears(1).plusMonths(2);
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList("""
                    SELECT id, fixture_id, league_id, league_name, home_team_id, home_team_name,
                           away_team_id, away_team_name, match_time, source
                    FROM crawler_matches
                    WHERE source = ? AND match_time >= ? AND match_time < ?
                    ORDER BY match_time ASC
                    """, crawlerProperties.getPrimarySource(), from, to);
        } catch (Exception ex) {
            log.debug("[Understat] local fixture query skipped: {}", ex.getMessage());
            return 0;
        }
        int imported = 0;
        int limit = Math.max(1, properties.getMaxRowsPerSeason());
        for (Object raw : dates) {
            if (imported >= limit || !(raw instanceof Map<?, ?> item)) continue;
            if (!isResult(item)) continue;
            Map<?, ?> home = map(item.get("h"));
            Map<?, ?> away = map(item.get("a"));
            Map<?, ?> xg = map(item.get("xG"));
            String homeName = text(home.get("title"));
            String awayName = text(away.get("title"));
            double homeXg = decimal(xg.get("h"));
            double awayXg = decimal(xg.get("a"));
            if (homeName.isBlank() || awayName.isBlank() || homeXg <= 0 || awayXg <= 0) continue;
            LocalDateTime kickoff = parseProviderTime(item.get("datetime"));
            if (kickoff == null) continue;
            Map<String, Object> match = findMatch(rows, homeName, awayName, kickoff, leagueCode);
            String sourceMatchId = text(item.get("id"));
            if (match == null) {
                saveJoinAudit(leagueCode, season, sourceMatchId, homeName, awayName, kickoff,
                        "NOT_IN_PRIMARY_TABLE", null, "历史赛季不写入主爬虫比赛表；该条 xG 已保留在 team_xg_cache");
                continue;
            }
            Long publicFixtureId = number(match.get("fixture_id"));
            if (publicFixtureId == null || publicFixtureId <= 0) publicFixtureId = number(match.get("id"));
            if (publicFixtureId == null || publicFixtureId <= 0) {
                saveJoinAudit(leagueCode, season, sourceMatchId, homeName, awayName, kickoff,
                        "INVALID_FIXTURE", null, "主爬虫记录缺少 fixture_id");
                continue;
            }
            saveJoinAudit(leagueCode, season, sourceMatchId, homeName, awayName, kickoff,
                    "MATCHED", publicFixtureId, null);
            List<Map<String, Object>> snapshot = snapshot(match, homeXg, awayXg);
            saveSnapshot(publicFixtureId, snapshot);
            imported++;
        }
        return imported;
    }

    private void saveJoinAudit(String leagueCode, int season, String sourceMatchId,
                               String homeName, String awayName, LocalDateTime kickoff,
                               String status, Long fixtureId, String message) {
        if (sourceMatchId == null || sourceMatchId.isBlank()) return;
        try {
            jdbcTemplate.update("""
                    INSERT INTO t_understat_match_join_audit
                      (league_code, season, source_match_id, home_team_name, away_team_name,
                       match_time, join_status, fixture_id, message, checked_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE home_team_name=VALUES(home_team_name),
                      away_team_name=VALUES(away_team_name), match_time=VALUES(match_time),
                      join_status=VALUES(join_status), fixture_id=VALUES(fixture_id),
                      message=VALUES(message), checked_at=VALUES(checked_at)
                    """, leagueCode, season, sourceMatchId, homeName, awayName, kickoff,
                    status, fixtureId, message);
        } catch (Exception ex) {
            log.debug("[Understat] join audit save failed {} {} {}: {}", leagueCode, season, sourceMatchId, ex.getMessage());
        }
    }

    /** Persist team-level historical xG even when the local primary source
     * does not retain the same season's fixture identities. */
    private int saveTeamXgRows(String leagueCode, int season, Map<String, Object> payload) {
        Object rawDates = payload.get("dates");
        if (!(rawDates instanceof List<?> dates)) return 0;
        int rows = 0;
        int limit = Math.max(1, properties.getMaxRowsPerSeason());
        for (Object raw : dates) {
            if (rows >= limit || !(raw instanceof Map<?, ?> item) || !isResult(item)) continue;
            Map<?, ?> home = map(item.get("h"));
            Map<?, ?> away = map(item.get("a"));
            Map<?, ?> xg = map(item.get("xG"));
            LocalDateTime kickoff = parseProviderTime(item.get("datetime"));
            String sourceMatchId = text(item.get("id"));
            String homeId = text(home.get("id")), awayId = text(away.get("id"));
            String homeName = text(home.get("title")), awayName = text(away.get("title"));
            double homeXg = decimal(xg.get("h")), awayXg = decimal(xg.get("a"));
            if (kickoff == null || sourceMatchId.isBlank() || homeId.isBlank() || awayId.isBlank()
                    || homeName.isBlank() || awayName.isBlank() || homeXg <= 0 || awayXg <= 0) continue;
            saveTeamXgRow(leagueCode, season, sourceMatchId, homeId, homeName, kickoff, homeXg, awayXg);
            saveTeamXgRow(leagueCode, season, sourceMatchId, awayId, awayName, kickoff, awayXg, homeXg);
            rows += 2;
        }
        return rows;
    }

    private void saveTeamXgRow(String leagueCode, int season, String sourceMatchId, String teamId,
                               String teamName, LocalDateTime kickoff, double xg, double xga) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO t_understat_team_xg_cache
                      (league_code, season, source_match_id, team_id, team_name, match_time, xg, xga, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE team_name=VALUES(team_name), match_time=VALUES(match_time),
                      xg=VALUES(xg), xga=VALUES(xga), fetched_at=VALUES(fetched_at)
                    """, leagueCode, season, sourceMatchId, teamId, teamName, kickoff, xg, xga);
        } catch (Exception ex) {
            log.debug("[Understat] team xG cache save failed {} {} {}: {}", leagueCode, season, sourceMatchId, ex.getMessage());
        }
    }

    private Map<String, Object> findMatch(List<Map<String, Object>> rows,
                                          String homeName,
                                          String awayName,
                                          LocalDateTime kickoff,
                                          String leagueCode) {
        Map<String, Object> best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Map<String, Object> row : rows) {
            String rowLeague = leagueKey(row.get("league_id"), row.get("league_name"));
            if (!leagueCode.equals(rowLeague)) continue;
            LocalDateTime matchTime = time(row.get("match_time"));
            if (matchTime == null) continue;
            long distance = Math.abs(Duration.between(kickoff, matchTime).toMinutes());
            // Understat commonly exposes UTC while the app stores Asia/Shanghai.
            if (distance > 12 * 60) continue;
            boolean sameHome = IdentityNormalizer.compatible(homeName, text(row.get("home_team_name")));
            boolean sameAway = IdentityNormalizer.compatible(awayName, text(row.get("away_team_name")));
            if (sameHome && sameAway && distance < bestDistance) {
                best = row;
                bestDistance = distance;
            }
        }
        return best;
    }

    private List<Map<String, Object>> snapshot(Map<String, Object> match, double homeXg, double awayXg) {
        Map<String, Object> home = new LinkedHashMap<>();
        home.put("team", Map.of("id", text(match.get("home_team_id")), "name", text(match.get("home_team_name"))));
        home.put("statistics", List.of(Map.of("type", "Expected Goals", "value", homeXg)));
        Map<String, Object> away = new LinkedHashMap<>();
        away.put("team", Map.of("id", text(match.get("away_team_id")), "name", text(match.get("away_team_name"))));
        away.put("statistics", List.of(Map.of("type", "Expected Goals", "value", awayXg)));
        return List.of(home, away);
    }

    private void saveSnapshot(Long fixtureId, List<Map<String, Object>> payload) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO t_match_detail_snapshot
                      (fixture_id, detail_type, source, payload_json, status, error_message, fetched_at)
                    VALUES (?, 'xg', 'understat', ?, 'NORMAL', NULL, NOW())
                    ON DUPLICATE KEY UPDATE payload_json=VALUES(payload_json), status='NORMAL',
                      error_message=NULL, fetched_at=VALUES(fetched_at)
                    """, fixtureId, objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            log.debug("[Understat] xG snapshot save failed fixture={}: {}", fixtureId, ex.getMessage());
        }
    }

    private boolean hasFreshCache(String league, int season) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT fetched_at, status FROM t_understat_league_cache WHERE league_code=? AND season=?",
                    league, season);
            String status = String.valueOf(row.getOrDefault("status", ""));
            // A failed/quota-limited attempt must be retried on the next run;
            // otherwise the TTL would turn a transient outage into a 30-day
            // permanent blind spot.
            if ("REQUEST_FAILED".equalsIgnoreCase(status) || "QUOTA_LIMITED".equalsIgnoreCase(status)) {
                return false;
            }
            Object fetchedValue = row.get("fetched_at");
            Instant fetched = fetchedValue instanceof Timestamp ts ? ts.toInstant()
                    : fetchedValue instanceof LocalDateTime ldt ? ldt.atZone(BUSINESS_ZONE).toInstant()
                    : fetchedValue instanceof java.util.Date date ? date.toInstant() : null;
            return fetched != null && fetched.plus(Duration.ofHours(Math.max(1, properties.getCacheTtlHours())))
                    .isAfter(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void saveCache(String league, int season, Map<String, Object> payload, String status, String error) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO t_understat_league_cache
                      (league_code, season, payload_json, status, error_message, fetched_at)
                    VALUES (?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE payload_json=VALUES(payload_json), status=VALUES(status),
                      error_message=VALUES(error_message), fetched_at=VALUES(fetched_at)
                    """, league, season, payload == null ? null : objectMapper.writeValueAsString(payload), status, error);
        } catch (Exception ignored) { }
    }

    private List<Integer> safeSeasons() {
        return properties.getSeasons() == null ? List.of() : properties.getSeasons().stream()
                .filter(Objects::nonNull).filter(value -> value >= 2010 && value <= 2100).distinct().toList();
    }

    private boolean isResult(Map<?, ?> item) {
        Object result = item.get("isResult");
        return result == null || Boolean.parseBoolean(String.valueOf(result));
    }

    private Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private Long number(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ex) { return null; } }

    private double decimal(Object value) {
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value).replace(",", "").trim()); }
        catch (Exception ex) { return 0; }
    }

    private LocalDateTime parseProviderTime(Object value) {
        String text = text(value);
        if (text.isBlank()) return null;
        try {
            String normalized = text.replace("Z", "+00:00");
            if (normalized.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                return OffsetDateTime.parse(normalized).atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
            }
            if (normalized.contains("T")) {
                try { return LocalDateTime.parse(normalized); } catch (DateTimeParseException ignored) { }
            }
            try { return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
            catch (DateTimeParseException ignored) { return LocalDateTime.parse(normalized); }
        } catch (Exception ex) { return null; }
    }

    private LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime local) return local;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        return parseProviderTime(value);
    }

    private String leagueKey(Object id, Object name) {
        String byId = mapLeague(IdentityNormalizer.normalize(text(id)));
        return !byId.isBlank() ? byId : mapLeague(IdentityNormalizer.normalize(text(name)));
    }

    private String mapLeague(String normalized) {
        return switch (normalized) {
            case "39", "pl", "premierleague", "bbcpremierleague", "英超" -> "PL";
            case "140", "pd", "laliga", "bbcspanishlaliga", "primeradivision", "西甲" -> "PD";
            case "78", "bl1", "bundesliga", "bbcgermanbundesliga", "德甲" -> "BL1";
            case "135", "sa", "seriea", "bbcitalianseriea", "意甲" -> "SA";
            case "61", "fl1", "ligue1", "bbcfrenchligueone", "法甲" -> "FL1";
            default -> "";
        };
    }

    private String classify(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return normalized.contains("limit") || normalized.contains("quota") || normalized.contains("403")
                ? "QUOTA_LIMITED" : "REQUEST_FAILED";
    }
}
