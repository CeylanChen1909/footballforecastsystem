package com.chen.football.crawler.controller;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.config.CrawlerTaskConfig;
import com.chen.football.common.config.ApiFootballProperties;
import com.chen.football.common.config.UnderstatProperties;
import com.chen.football.crawler.service.CrawlerTaskStatusService;
import com.chen.football.crawler.service.HistoricalDataQualityService;
import com.chen.football.common.util.AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 爬虫健康与状态接口
 */
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
public class CrawlerHealthController {

    private final CrawlerProperties crawlerProperties;
    private final CrawlerTaskConfig taskConfig;
    private final CrawlerTaskStatusService taskStatusService;
    private final UnderstatProperties understatProperties;
    private final ApiFootballProperties apiFootballProperties;
    private final JdbcTemplate jdbcTemplate;
    private final HistoricalDataQualityService historicalDataQualityService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        AdminGuard.requirePermission("CRAWLER");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", crawlerProperties.isEnabled());
        data.put("requestIntervalMs", crawlerProperties.getRequestIntervalMs());
        data.put("baseUrl", crawlerProperties.getBbc() == null ? null : crawlerProperties.getBbc().getBaseUrl());
        data.put("footballUrl", crawlerProperties.getBbc() == null ? null : crawlerProperties.getBbc().getScoresPath());
        data.put("bbcEnabled", crawlerProperties.getBbc() != null && crawlerProperties.getBbc().isEnabled());
        data.put("bbcTimeoutMs", crawlerProperties.getBbc() == null ? null : crawlerProperties.getBbc().getTimeoutMs());
        data.put("worldFootballEnabled", crawlerProperties.getWorldFootball() != null && crawlerProperties.getWorldFootball().isEnabled());
        data.put("prematchSources", prematchSources());
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("timeZone", taskConfig.getTimeZone());
        schedule.put("todayIntervalSeconds", taskConfig.getTodayFixedRateMs() / 1000);
        schedule.put("scoreIntervalSeconds", taskConfig.getScoreUpdateFixedRateMs() / 1000);
        schedule.put("scoreLiveLookbackDays", taskConfig.getScoreLiveLookbackDays());
        schedule.put("scoreLookbackDays", taskConfig.getScoreLookbackDays());
        schedule.put("scoreCorrectionIntervalMinutes", taskConfig.getScoreCorrectionFixedDelayMs() / 60_000);
        schedule.put("upcomingPeriodicHours", taskConfig.getUpcomingFixedDelayMs() / 3_600_000);
        schedule.put("upcomingCron", taskConfig.getUpcomingCron());
        schedule.put("standingsIntervalMinutes", taskConfig.getStandingsFixedRateMs() / 60_000);
        schedule.put("standingsCron", taskConfig.getStandingsCron());
        schedule.put("startupWarmupEnabled", taskConfig.isStartupWarmupEnabled());
        data.put("schedule", schedule);
        data.put("taskSummary", taskStatusService.snapshot().get("summary"));
        Map<String, Object> taskSnapshot = taskStatusService.snapshot();
        data.put("tasks", taskSnapshot.get("tasks"));
        data.put("history", taskSnapshot.get("history"));
        return Map.of(
                "success", true,
                "message", "健康检查成功",
                "data", data
        );
    }

    private Map<String, Object> prematchSources() {
        Map<String, Object> understat = new LinkedHashMap<>();
        understat.put("enabled", understatProperties.isEnabled());
        understat.put("source", "Understat");
        try {
            Map<String, Object> latest = jdbcTemplate.queryForMap(
                    "SELECT MAX(fetched_at) AS fetched_at, COUNT(*) AS seasons FROM t_understat_league_cache");
            understat.put("lastFetchedAt", latest.get("fetched_at"));
            understat.put("cachedSeasons", latest.get("seasons"));
            Map<String, Object> statuses = new LinkedHashMap<>();
            jdbcTemplate.query("SELECT status, COUNT(*) AS count FROM t_understat_league_cache GROUP BY status",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            statuses.put(rs.getString("status"), rs.getInt("count")));
            understat.put("statuses", statuses);
            try {
                Map<String, Object> join = new LinkedHashMap<>();
                jdbcTemplate.query("SELECT join_status, COUNT(*) AS count FROM t_understat_match_join_audit GROUP BY join_status",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> join.put(rs.getString("join_status"), rs.getInt("count")));
                understat.put("joinAudit", join);
            } catch (Exception ignored) {
                understat.put("joinAudit", Map.of());
            }
        } catch (Exception ex) {
            understat.put("status", "NOT_INITIALIZED");
        }
        Map<String, Object> apiFootball = new LinkedHashMap<>();
        apiFootball.put("configured", apiFootballProperties.getApiKey() != null && !apiFootballProperties.getApiKey().isBlank());
        apiFootball.put("maxDailyRequests", apiFootballProperties.getMaxDailyRequests());
        apiFootball.put("lineups", "API-Football 赛前详情缓存");
        apiFootball.put("injuries", "API-Football 赛前详情缓存");
        apiFootball.put("odds", "API-Football 赛前详情缓存");
        apiFootball.put("xg", "API-Football 完赛统计 + Understat 历史增强");
        try {
            Map<String, Object> counts = new LinkedHashMap<>();
            jdbcTemplate.query("SELECT detail_type, status, COUNT(*) AS count FROM t_match_detail_snapshot GROUP BY detail_type, status",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            counts.put(rs.getString("detail_type") + ":" + rs.getString("status"), rs.getInt("count")));
            apiFootball.put("cachedDetails", counts);
        } catch (Exception ignored) {
            apiFootball.put("cachedDetails", Map.of());
        }
        return Map.of("understat", understat,
                "apiFootball", apiFootball);
    }

    /**
     * Historical training data quality is intentionally a separate endpoint:
     * the normal health response stays small while operators can inspect
     * duplicate, invalid, sample-tier and xG coverage details on demand.
     */
    @GetMapping("/data-quality")
    public Map<String, Object> dataQuality() {
        AdminGuard.requirePermission("CRAWLER");
        return Map.of("success", true, "message", "历史数据质量审计", "data", historicalDataQualityService.summary());
    }

    @GetMapping("/health/{taskName}")
    public Map<String, Object> taskHealth(@org.springframework.web.bind.annotation.PathVariable String taskName) {
        AdminGuard.requirePermission("CRAWLER");
        return Map.of(
                "success", true,
                "message", "获取成功",
                "data", taskStatusService.getTask(taskName)
        );
    }

    /** Minimal public signal for the status chip; never exposes URLs or task history. */
    @GetMapping("/public-status")
    public Map<String, Object> publicStatus() {
        Map<String, Object> snapshot = taskStatusService.snapshot();
        Object summary = snapshot.get("summary");
        String status = "UNKNOWN";
        if (summary instanceof Map<?, ?> map) {
            long total = number(map.get("total"));
            long failed = number(map.get("failed"));
            status = total == 0 ? "UNKNOWN" : failed > 0 ? "DEGRADED" : "NORMAL";
        }
        return Map.of(
                "success", true,
                "status", status,
                "updatedAt", java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8)).toString()
        );
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return 0L; }
    }
}
