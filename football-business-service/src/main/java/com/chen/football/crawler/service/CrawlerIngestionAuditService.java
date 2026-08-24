package com.chen.football.crawler.service;

import com.chen.football.common.dto.FetchResult;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable audit trail for source observations and ingestion runs.
 *
 * crawler_matches is the curated product table.  It is intentionally not a
 * raw-response store, so every normalized observation is also retained here
 * with its parser version and deterministic hash.  This makes parser changes,
 * duplicate reports and late score corrections explainable without letting a
 * second source overwrite the curated row.
 */
@Slf4j
@Service
public class CrawlerIngestionAuditService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PARSER_VERSION = "normalized-match-v2";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CrawlerIngestionAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void ensureTables() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_crawler_ingestion_run (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, task_name VARCHAR(64) NOT NULL, source VARCHAR(64) NOT NULL, " +
                    "requested_date DATE NULL, result VARCHAR(24) NOT NULL, fetched_count INT NOT NULL DEFAULT 0, " +
                    "accepted_count INT NOT NULL DEFAULT 0, rejected_count INT NOT NULL DEFAULT 0, duplicate_count INT NOT NULL DEFAULT 0, " +
                    "inserted_count INT NOT NULL DEFAULT 0, updated_count INT NOT NULL DEFAULT 0, duration_ms BIGINT NOT NULL DEFAULT 0, " +
                    "parser_version VARCHAR(64) NOT NULL, error_message VARCHAR(1000) NULL, started_at DATETIME NOT NULL, " +
                    "finished_at DATETIME NULL, PRIMARY KEY(id), KEY idx_ingestion_date(source, requested_date, started_at), " +
                    "KEY idx_ingestion_finished(task_name, finished_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_match_source_observation (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, observation_key CHAR(64) NOT NULL, canonical_key VARCHAR(255) NOT NULL, " +
                    "source VARCHAR(64) NOT NULL, external_match_id VARCHAR(128) NULL, fixture_id BIGINT NULL, league_id VARCHAR(64) NULL, " +
                    "league_name VARCHAR(128) NULL, home_team_id VARCHAR(128) NULL, home_team_name VARCHAR(160) NULL, " +
                    "away_team_id VARCHAR(128) NULL, away_team_name VARCHAR(160) NULL, match_time DATETIME NULL, status VARCHAR(32) NULL, " +
                    "home_score INT NULL, away_score INT NULL, normalized_json JSON NOT NULL, parser_version VARCHAR(64) NOT NULL, " +
                    "observed_at DATETIME NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_match_observation(observation_key), " +
                    "KEY idx_match_observation_canonical(canonical_key, observed_at), KEY idx_match_observation_source(source, observed_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception ex) {
            log.warn("采集审计表初始化失败，保留业务链路继续运行: {}", ex.getMessage());
        }
    }

    public long startRun(String taskName, String source, String requestedDate) {
        try {
            LocalDate date = requestedDate == null || requestedDate.isBlank() ? null : LocalDate.parse(requestedDate);
            LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
            jdbcTemplate.update("INSERT INTO t_crawler_ingestion_run(task_name,source,requested_date,result,parser_version,started_at) VALUES (?,?,?,?,?,?)",
                    safe(taskName, "crawl"), safe(source, "unknown"), date, "RUNNING", PARSER_VERSION, now);
            Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            return id == null ? 0L : id;
        } catch (Exception ex) {
            log.debug("写入采集运行审计失败: {}", ex.getMessage());
            return 0L;
        }
    }

    public void finishRun(long runId, String result, int fetched, int accepted, int rejected, int duplicate,
                          int inserted, int updated, long durationMs, String error) {
        if (runId <= 0) return;
        try {
            jdbcTemplate.update("UPDATE t_crawler_ingestion_run SET result=?,fetched_count=?,accepted_count=?,rejected_count=?,duplicate_count=?,inserted_count=?,updated_count=?,duration_ms=?,error_message=?,finished_at=? WHERE id=?",
                    safe(result, "SUCCESS"), Math.max(0, fetched), Math.max(0, accepted), Math.max(0, rejected), Math.max(0, duplicate),
                    Math.max(0, inserted), Math.max(0, updated), Math.max(0, durationMs), truncate(error), LocalDateTime.now(BUSINESS_ZONE), runId);
        } catch (Exception ex) {
            log.debug("更新采集运行审计失败: {}", ex.getMessage());
        }
    }

    public void observe(CrawlerMatch match) {
        if (match == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", match.getSource());
            payload.put("externalMatchId", match.getExternalMatchId());
            payload.put("fixtureId", match.getFixtureId());
            payload.put("leagueId", match.getLeagueId());
            payload.put("leagueName", match.getLeagueName());
            payload.put("homeTeamId", match.getHomeTeamId());
            payload.put("homeTeamName", match.getHomeTeamName());
            payload.put("awayTeamId", match.getAwayTeamId());
            payload.put("awayTeamName", match.getAwayTeamName());
            payload.put("matchTime", match.getMatchTime());
            payload.put("status", match.getStatus());
            payload.put("homeScore", match.getHomeScore());
            payload.put("awayScore", match.getAwayScore());
            String json = objectMapper.writeValueAsString(payload);
            String observationKey = sha256(json);
            String canonicalKey = canonicalKey(match);
            jdbcTemplate.update("INSERT INTO t_match_source_observation(observation_key,canonical_key,source,external_match_id,fixture_id,league_id,league_name,home_team_id,home_team_name,away_team_id,away_team_name,match_time,status,home_score,away_score,normalized_json,parser_version,observed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE observed_at=VALUES(observed_at),normalized_json=VALUES(normalized_json),parser_version=VALUES(parser_version)",
                    observationKey, canonicalKey, match.getSource(), match.getExternalMatchId(), match.getFixtureId(), match.getLeagueId(), match.getLeagueName(),
                    match.getHomeTeamId(), match.getHomeTeamName(), match.getAwayTeamId(), match.getAwayTeamName(), match.getMatchTime(), match.getStatus(),
                    match.getHomeScore(), match.getAwayScore(), json, PARSER_VERSION, LocalDateTime.now(BUSINESS_ZONE));
        } catch (JsonProcessingException ex) {
            log.debug("序列化比赛观测失败: {}", ex.getMessage());
        } catch (Exception ex) {
            log.debug("保存比赛来源观测失败: {}", ex.getMessage());
        }
    }

    public Map<String, Object> qualitySummary(String source, String from, String to) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("from", from);
        result.put("to", to);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT requested_date, result, fetched_count, accepted_count, rejected_count, duplicate_count, inserted_count, updated_count, duration_ms, finished_at FROM t_crawler_ingestion_run WHERE source=? AND requested_date BETWEEN ? AND ? ORDER BY requested_date, started_at DESC", source, LocalDate.parse(from), LocalDate.parse(to));
            result.put("status", "AVAILABLE");
            result.put("runs", rows);
            Map<String, Object> aggregate = new LinkedHashMap<>();
            int fetched = 0, accepted = 0, rejected = 0, duplicates = 0, inserted = 0, updated = 0;
            int failed = 0, empty = 0;
            for (Map<String, Object> row : rows) {
                fetched += integer(row.get("fetched_count"));
                accepted += integer(row.get("accepted_count"));
                rejected += integer(row.get("rejected_count"));
                duplicates += integer(row.get("duplicate_count"));
                inserted += integer(row.get("inserted_count"));
                updated += integer(row.get("updated_count"));
                String state = String.valueOf(row.getOrDefault("result", ""));
                if ("FAILED".equals(state)) failed++;
                if ("EMPTY".equals(state)) empty++;
            }
            aggregate.put("runCount", rows.size());
            aggregate.put("fetched", fetched);
            aggregate.put("accepted", accepted);
            aggregate.put("rejected", rejected);
            aggregate.put("duplicates", duplicates);
            aggregate.put("inserted", inserted);
            aggregate.put("updated", updated);
            aggregate.put("failedRuns", failed);
            aggregate.put("emptyRuns", empty);
            aggregate.put("acceptanceRate", fetched == 0 ? null : Math.round(accepted * 10000.0 / fetched) / 100.0);
            result.put("aggregate", aggregate);
            try {
                LocalDate fromDate = LocalDate.parse(from);
                LocalDate toExclusive = LocalDate.parse(to).plusDays(1);
                Integer observations = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_match_source_observation WHERE source=? AND observed_at >= ? AND observed_at < ?",
                        Integer.class, source, fromDate.atStartOfDay(), toExclusive.atStartOfDay());
                result.put("observationCount", observations == null ? 0 : observations);
            } catch (Exception ignored) {
                result.put("observationCount", null);
            }
        } catch (Exception ex) {
            result.put("status", "UNAVAILABLE");
            result.put("runs", List.of());
            result.put("message", "采集审计尚未初始化或暂时不可用");
        }
        return result;
    }

    private int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }

    public static String canonicalKey(CrawlerMatch match) {
        String league = IdentityNormalizer.normalize(match.getLeagueId());
        if (league.isBlank()) league = IdentityNormalizer.normalize(match.getLeagueName());
        String home = IdentityNormalizer.normalize(match.getHomeTeamName());
        String away = IdentityNormalizer.normalize(match.getAwayTeamName());
        String date = match.getMatchTime() == null ? "unknown" : match.getMatchTime().toLocalDate().toString();
        String slot = match.getMatchTime() == null
                ? "event:" + IdentityNormalizer.normalize(match.getExternalMatchId())
                : match.getMatchTime().withSecond(0).withNano(0).toLocalTime().toString();
        return String.join("|", league, home, away, date, slot);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String safe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String truncate(String value) { return value == null ? null : value.substring(0, Math.min(1000, value.length())); }
}
