package com.chen.football.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/** Small operational view over the Agent events already written by analytics. */
@Service
@RequiredArgsConstructor
public class AgentMetricsService {
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> summary(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(safeDays);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("days", safeDays);
            result.put("sent", count("agent_message_sent", cutoff));
            result.put("completed", count("agent_message_completed", cutoff));
            result.put("failed", count("agent_message_failed", cutoff));
            result.put("cancelled", count("agent_message_cancelled", cutoff));
            result.put("exports", count("agent_export", cutoff));
            result.put("avgLatencyMs", averageLatency(cutoff));
            result.put("p95LatencyMs", percentileLatency(cutoff, 0.95));
            result.put("tokens", tokenUsage(cutoff));
            result.put("feedback", feedback(cutoff));
            result.put("grounding", grounding(cutoff));
            result.put("providers", providers(cutoff));
            result.put("status", "ok");
        } catch (Exception ex) {
            result.put("status", "unavailable");
            result.put("message", "埋点表尚未初始化或暂时不可用");
        }
        return result;
    }

    private int count(String event, LocalDateTime cutoff) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_analytics_event WHERE event_name=? AND created_at >= ?", Integer.class, event, cutoff);
        return value == null ? 0 : value;
    }

    private double averageLatency(LocalDateTime cutoff) {
        Double value = jdbcTemplate.queryForObject("SELECT AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.latencyMs')) AS DECIMAL(14,2))) FROM t_analytics_event WHERE event_name='agent_message_completed' AND created_at >= ?", Double.class, cutoff);
        return value == null ? 0.0 : Math.round(value * 10.0) / 10.0;
    }

    private double percentileLatency(LocalDateTime cutoff, double percentile) {
        try {
            List<Double> values = jdbcTemplate.query(
                    "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.latencyMs')) AS DECIMAL(14,2)) FROM t_analytics_event WHERE event_name='agent_message_completed' AND created_at >= ? AND JSON_EXTRACT(properties_json, '$.latencyMs') IS NOT NULL ORDER BY 1",
                    (rs, rowNum) -> rs.getDouble(1), cutoff);
            if (values.isEmpty()) return 0.0;
            int index = Math.min(values.size() - 1, Math.max(0, (int) Math.ceil(values.size() * percentile) - 1));
            return values.get(index);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private Map<String, Object> feedback(LocalDateTime cutoff) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("helpful", feedbackCount("helpful", cutoff));
        result.put("notHelpful", feedbackCount("not-helpful", cutoff));
        return result;
    }

    private int feedbackCount(String value, LocalDateTime cutoff) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_analytics_event WHERE event_name='agent_feedback' AND JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.value'))=? AND created_at >= ?", Integer.class, value, cutoff);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> providers(LocalDateTime cutoff) {
        return jdbcTemplate.query("SELECT JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.provider')) AS provider, COUNT(*) AS total FROM t_analytics_event WHERE event_name='agent_message_completed' AND created_at >= ? GROUP BY provider ORDER BY total DESC",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("provider", rs.getString("provider"));
                    item.put("total", rs.getInt("total"));
                    return item;
                }, cutoff);
    }

    private Map<String, Object> grounding(LocalDateTime cutoff) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("grounded", countJsonBoolean("grounded", true, cutoff));
            result.put("warnings", countJsonBoolean("grounded", false, cutoff));
        } catch (Exception ignored) {
            result.put("grounded", 0);
            result.put("warnings", 0);
            result.put("status", "unavailable");
        }
        return result;
    }

    private int countJsonBoolean(String key, boolean value, LocalDateTime cutoff) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_analytics_event WHERE event_name='agent_message_completed' AND created_at >= ? AND JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$." + key + "'))=?",
                Integer.class, cutoff, String.valueOf(value));
        return count == null ? 0 : count;
    }

    private Map<String, Object> tokenUsage(LocalDateTime cutoff) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long prompt = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.usage.prompt_tokens')) AS DECIMAL(18,0))),0) FROM t_analytics_event WHERE event_name='agent_message_completed' AND created_at >= ?",
                    Long.class, cutoff);
            Long completion = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(properties_json, '$.usage.completion_tokens')) AS DECIMAL(18,0))),0) FROM t_analytics_event WHERE event_name='agent_message_completed' AND created_at >= ?",
                    Long.class, cutoff);
            result.put("prompt", prompt == null ? 0L : prompt);
            result.put("completion", completion == null ? 0L : completion);
            result.put("total", (prompt == null ? 0L : prompt) + (completion == null ? 0L : completion));
        } catch (Exception ignored) {
            result.put("prompt", 0L);
            result.put("completion", 0L);
            result.put("total", 0L);
            result.put("status", "unavailable");
        }
        return result;
    }
}
