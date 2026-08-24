package com.chen.football.agent.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes legacy Map based tool responses without forcing every existing
 * tool to change its public shape at once.  The model, evidence builder and
 * SSE layer can therefore rely on the same status/source contract.
 */
public final class AgentToolResult {
    private static final Map<String, String> SOURCES = Map.of(
            "match_context", "本地赛程数据库",
            "prematch_data_context", "API-Football 赛前详情缓存 + Understat 历史 xG",
            "prediction", "赛前预测服务",
            "team_context", "球队资料数据库",
            "squad_context", "公开球队阵容源",
            "news_context", "赛事资讯数据库",
            "crawler_summary", "主爬虫赛程源",
            "agent_summary", "Agent 运行状态"
    );

    private AgentToolResult() { }

    public static Map<String, Object> normalize(String toolName, Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw != null) result.putAll(raw);
        result.putIfAbsent("tool", toolName);
        result.putIfAbsent("source", SOURCES.getOrDefault(toolName, toolName));
        result.putIfAbsent("observedAt", Instant.now().toString());
        result.putIfAbsent("status", inferStatus(result));
        return result;
    }

    private static String inferStatus(Map<String, Object> result) {
        if (Boolean.TRUE.equals(result.get("skipped"))) return "SKIPPED";
        if (result.get("error") != null) return "REQUEST_FAILED";
        Object status = result.get("status");
        if (status != null && !String.valueOf(status).isBlank()) return String.valueOf(status).toUpperCase();
        return "AVAILABLE";
    }
}
