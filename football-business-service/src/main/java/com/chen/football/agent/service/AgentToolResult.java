package com.chen.football.agent.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes legacy Map based tool responses without forcing every existing
 * tool to change its public shape at once.  The model, evidence builder and
 * SSE layer can therefore rely on the same status/source contract.
 */
public final class AgentToolResult {
    private static final List<String> LOCALIZED_KEYS = List.of(
            "message", "source", "note", "fallbackReason", "predictionStatusMessage"
    );
    private static final List<String> TEAM_KEYS = List.of(
            "teamName", "homeTeamName", "awayTeamName", "comparisonTeamName",
            "teamDisplayName", "homeTeamDisplayName", "awayTeamDisplayName"
    );
    private static final List<String> LEAGUE_KEYS = List.of(
            "leagueName", "leagueDisplayName", "displayLeagueName"
    );

    private AgentToolResult() { }

    public static Map<String, Object> normalize(String toolName, Map<String, Object> raw) {
        return normalize(toolName, raw, AgentText.resolve(null));
    }

    public static Map<String, Object> normalize(String toolName, Map<String, Object> raw, String lang) {
        String language = AgentText.resolve(lang);
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw != null) result.putAll(raw);
        result.putIfAbsent("tool", toolName);
        if (result.get("source") == null || String.valueOf(result.get("source")).isBlank()) {
            result.put("source", AgentText.sourceLabel(language, toolName));
        }
        result.putIfAbsent("observedAt", Instant.now().toString());
        result.putIfAbsent("status", inferStatus(result));
        // Tool implementations keep returning canonical Chinese business
        // messages; localize the fixed copy where every result passes through.
        localizeKnownFields(result, language, 0);
        return result;
    }

    private static void localizeKnownFields(Object value, String lang, int depth) {
        if (depth > 12) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object nested = entry.getValue();
                if (nested instanceof String text) {
                    String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                    if (!text.isBlank()) {
                        Object localized = text;
                        if (LOCALIZED_KEYS.contains(key)) localized = AgentText.translate(lang, text);
                        else if (TEAM_KEYS.contains(key)) localized = AgentText.teamName(lang, text);
                        else if (LEAGUE_KEYS.contains(key)) localized = AgentText.leagueName(lang, text);
                        if (!localized.equals(text)) {
                            @SuppressWarnings("unchecked")
                            Map<Object, Object> mutable = (Map<Object, Object>) map;
                            mutable.put(entry.getKey(), localized);
                        }
                    }
                } else {
                    localizeKnownFields(nested, lang, depth + 1);
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) localizeKnownFields(nested, lang, depth + 1);
        }
    }

    private static String inferStatus(Map<String, Object> result) {
        if (Boolean.TRUE.equals(result.get("skipped"))) return "SKIPPED";
        if (result.get("error") != null) return "REQUEST_FAILED";
        Object status = result.get("status");
        if (status != null && !String.valueOf(status).isBlank()) return String.valueOf(status).toUpperCase();
        return "AVAILABLE";
    }
}
