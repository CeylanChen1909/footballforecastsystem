package com.chen.football.analytics.service;

import com.chen.football.common.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

/** 轻量行为埋点，失败不影响主业务。 */
@Service
@RequiredArgsConstructor
public class AnalyticsEventService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    @Value("${analytics.enabled:true}")
    private boolean enabled;
    @Value("${analytics.retention-days:180}")
    private int retentionDays;

    @PostConstruct
    void ensureTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_analytics_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(96) NULL, user_id BIGINT NULL, event_name VARCHAR(64) NOT NULL, page VARCHAR(128), entity_type VARCHAR(64), entity_id VARCHAR(128), properties_json JSON, created_at DATETIME NOT NULL, INDEX idx_analytics_event_name_time (event_name, created_at), INDEX idx_analytics_event_user_time (user_id, created_at))");
            try { jdbcTemplate.execute("ALTER TABLE t_analytics_event ADD COLUMN event_id VARCHAR(96) NULL AFTER id"); } catch (Exception ignored) { }
            try { jdbcTemplate.execute("ALTER TABLE t_analytics_event ADD UNIQUE KEY uk_analytics_event_id (event_id)"); } catch (Exception ignored) { }
        } catch (Exception ignored) { }
    }

    public void track(String eventId, String eventName, String page, String entityType, String entityId, Map<String, Object> properties) {
        if (!enabled || eventName == null || eventName.isBlank()) return;
        try {
            String json = properties == null ? "{}" : objectMapper.writeValueAsString(properties);
            // Do not cut a JSON string in the middle (that would make the
            // MySQL JSON column reject the whole event). Keep a valid marker
            // when a client sends an oversized payload.
            if (json.length() > 4096) json = "{\"_truncated\":true}";
            jdbcTemplate.update("INSERT INTO t_analytics_event (event_id,user_id,event_name,page,entity_type,entity_id,properties_json,created_at) VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                    eventId, UserContext.getUserId(), eventName.trim().substring(0, Math.min(64, eventName.trim().length())), page,
                    entityType, entityId, json, LocalDateTime.now(BUSINESS_ZONE));
        } catch (Exception ignored) {
            // 埋点表未迁移/数据库暂不可用时静默降级，不能阻塞页面操作。
        }
    }

    /** Keep telemetry bounded; retention is best-effort and never blocks business requests. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    void purgeExpiredEvents() {
        if (!enabled) return;
        int days = Math.max(30, Math.min(retentionDays, 730));
        try {
            jdbcTemplate.update("DELETE FROM t_analytics_event WHERE created_at < ?",
                    LocalDateTime.now(BUSINESS_ZONE).minusDays(days));
        } catch (Exception ignored) { }
    }

    public Map<String, Object> summary(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        LocalDateTime cutoff = LocalDateTime.now(BUSINESS_ZONE).minusDays(safeDays);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_analytics_event WHERE created_at >= ?", Long.class, cutoff);
            Long users = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT user_id) FROM t_analytics_event WHERE user_id IS NOT NULL AND created_at >= ?", Long.class, cutoff);
            List<Map<String, Object>> events = jdbcTemplate.queryForList("SELECT event_name, COUNT(*) AS total FROM t_analytics_event WHERE created_at >= ? GROUP BY event_name ORDER BY total DESC LIMIT 100", cutoff);
            List<Map<String, Object>> userEvents = jdbcTemplate.queryForList("SELECT event_name, COUNT(DISTINCT user_id) AS users FROM t_analytics_event WHERE user_id IS NOT NULL AND created_at >= ? GROUP BY event_name", cutoff);
            Map<String, Long> eventUsers = new LinkedHashMap<>();
            userEvents.forEach(row -> eventUsers.put(String.valueOf(row.get("event_name")), number(row.get("users"))));
            Map<String, Object> funnel = new LinkedHashMap<>();
            funnel.put("matchOpenedUsers", eventUsers.getOrDefault("match_opened", 0L));
            funnel.put("predictionViewedUsers", eventUsers.getOrDefault("prediction_viewed", 0L));
            funnel.put("agentUsers", eventUsers.getOrDefault("agent_message_sent", 0L));
            funnel.put("predictionToAgentRate", ratio(eventUsers.getOrDefault("prediction_viewed", 0L), eventUsers.getOrDefault("agent_message_sent", 0L)));
            funnel.put("matchToPredictionRate", ratio(eventUsers.getOrDefault("match_opened", 0L), eventUsers.getOrDefault("prediction_viewed", 0L)));
            result.put("days", safeDays);
            result.put("totalEvents", total == null ? 0L : total);
            result.put("uniqueUsers", users == null ? 0L : users);
            result.put("eventCounts", events);
            result.put("funnel", funnel);
            result.put("status", "AVAILABLE");
        } catch (Exception e) {
            result.put("days", safeDays);
            result.put("totalEvents", 0L);
            result.put("uniqueUsers", 0L);
            result.put("eventCounts", List.of());
            result.put("funnel", Map.of());
            result.put("status", "UNAVAILABLE");
            result.put("message", "埋点表尚未初始化或暂时不可用");
        }
        return result;
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return 0L; }
    }

    private double ratio(long denominator, long numerator) {
        return denominator <= 0 ? 0d : Math.round(numerator * 10000d / denominator) / 100d;
    }
}
