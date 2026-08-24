package com.chen.football.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the compact, user-facing fact/unknown layer from tool evidence. */
public final class AgentEvidenceComposer {
    private AgentEvidenceComposer() { }

    public static List<Map<String, Object>> facts(AgentToolRun run) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (run == null) return result;
        for (Map<String, Object> evidence : run.evidence()) {
            String status = String.valueOf(evidence.getOrDefault("status", "ok"));
            if (!List.of("ok", "partial", "available").contains(status.toLowerCase())) continue;
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("tool", evidence.get("tool"));
            fact.put("label", evidence.get("label"));
            fact.put("status", status);
            fact.put("recordCount", evidence.getOrDefault("recordCount", 0));
            if (evidence.get("sourceUpdatedAt") != null) fact.put("sourceUpdatedAt", evidence.get("sourceUpdatedAt"));
            if (evidence.get("observedAt") != null) fact.put("observedAt", evidence.get("observedAt"));
            result.add(fact);
        }
        return result;
    }

    public static List<Map<String, Object>> unknowns(AgentToolRun run) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (run == null) return result;
        for (Map<String, Object> evidence : run.evidence()) {
            String status = String.valueOf(evidence.getOrDefault("status", "ok"));
            if (List.of("ok", "available").contains(status.toLowerCase())) continue;
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("tool", evidence.get("tool"));
            unknown.put("label", evidence.get("label"));
            unknown.put("status", status);
            unknown.put("message", statusMessage(status));
            result.add(unknown);
        }
        return result;
    }

    public static Map<String, Object> quality(AgentToolRun run) {
        List<Map<String, Object>> facts = facts(run);
        List<Map<String, Object>> unknowns = unknowns(run);
        String level;
        if (facts.isEmpty()) level = "low";
        else if (unknowns.isEmpty()) level = "high";
        else level = "medium";
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("level", level);
        quality.put("availableSources", facts.size());
        quality.put("unknownSources", unknowns.size());
        quality.put("reason", unknowns.isEmpty() ? "关键工具均返回可用数据" : "仍有数据源未能提供完整事实");
        return quality;
    }

    private static String statusMessage(String status) {
        return switch (status.toLowerCase()) {
            case "empty" -> "数据源已响应，但当前没有可核验记录";
            case "missing-input" -> "缺少查询条件，无法读取该数据";
            case "stale" -> "只有过期数据，不能视为当前状态";
            case "not-configured" -> "当前联赛或数据源尚未配置";
            case "quota-limited" -> "数据源额度受限，暂时无法刷新";
            case "error", "request-failed" -> "数据源请求失败";
            case "skipped" -> "工具因熔断或资源限制未执行";
            default -> "该数据暂时无法核验";
        };
    }
}
