package com.chen.football.agent.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight post-generation grounding checks.  It deliberately reports
 * warnings instead of trying to rewrite every natural-language answer; only
 * roster answers get a deterministic fallback because a missing player name
 * is an unambiguous correctness failure.
 */
@Component
public class AgentAnswerValidator {

    public Validation validate(String answer, AgentToolRun run) {
        List<String> warnings = new ArrayList<>();
        String safeAnswer = answer == null ? "" : answer;
        if (run != null && "team-roster".equals(run.intent())) {
            Map<String, Object> squad = run.toolOutputs().get("squad_context") instanceof Map<?, ?> raw
                    ? cast(raw) : Map.of();
            String status = String.valueOf(squad.getOrDefault("status", ""));
            List<Map<String, Object>> players = players(squad.get("players"));
            if ("AVAILABLE".equalsIgnoreCase(status) && !players.isEmpty()) {
                boolean containsKnownPlayer = players.stream().map(item -> item.get("name"))
                        .filter(value -> value != null)
                        .map(String::valueOf)
                        .anyMatch(safeAnswer::contains);
                if (!containsKnownPlayer) {
                    warnings.add("roster-answer-missing-source-player");
                    safeAnswer = deterministicRosterAnswer(squad, players);
                }
            }
        }
        boolean hasScheduleFacts = run != null && run.toolOutputs().get("crawler_summary") instanceof Map<?, ?>;
        if (run != null && ("schedule".equals(run.intent()) || hasScheduleFacts) && asksForPredictionStatus(run.context())) {
            Map<String, Object> schedule = run.toolOutputs().get("crawler_summary") instanceof Map<?, ?> raw
                    ? cast(raw) : Map.of();
            String correction = schedulePredictionCorrection(schedule, safeAnswer);
            if (!correction.isBlank()) {
                safeAnswer = safeAnswer.trim();
                safeAnswer = safeAnswer.isBlank() ? correction : safeAnswer + "\n\n" + correction;
                warnings.add("schedule-prediction-status-grounding");
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("grounded", warnings.isEmpty());
        metadata.put("warnings", warnings);
        return new Validation(safeAnswer, metadata);
    }

    private boolean asksForPredictionStatus(Map<String, Object> context) {
        if (context == null) return false;
        String message = String.valueOf(context.getOrDefault("message", ""));
        return message.matches("(?s).*(预测|生成|快照|状态|prediction|generated|snapshot|status).*");
    }

    private String schedulePredictionCorrection(Map<String, Object> schedule, String answer) {
        if (schedule == null || schedule.isEmpty()) return "";
        Object summaryValue = schedule.get("predictionSummary");
        if (!(summaryValue instanceof Map<?, ?> rawSummary)) return "";
        Map<String, Object> summary = cast(rawSummary);
        int ready = count(summary, "READY");
        int unavailable = count(summary, "UNAVAILABLE");
        int pending = count(summary, "PENDING");
        int failed = count(summary, "FAILED");
        int notGenerated = count(summary, "NOT_GENERATED");
        int notRead = count(summary, "NOT_READ");
        boolean broadFalseClaim = answer != null && java.util.Arrays.stream(answer.split("[\\n。！？.!?]"))
                .map(String::trim)
                .filter(sentence -> !sentence.isBlank())
                .anyMatch(sentence -> sentence.matches("(?s).*(所有|全部|均|都).*预测.*(未生成|没有|无).*")
                        || sentence.matches("(?s).*(所有|全部|均|都).*(未生成|没有|无).*预测.*"));
        boolean hasStatusLine = answer != null && answer.matches("(?s).*(已生成|预测不可用|生成中|尚未生成|状态查询失败).*");
        if (!broadFalseClaim && hasStatusLine) return "";
        List<String> parts = new ArrayList<>();
        if (ready > 0) parts.add("已生成 " + ready + " 场");
        if (unavailable > 0) parts.add("预测不可用 " + unavailable + " 场");
        if (pending > 0) parts.add("生成中 " + pending + " 场");
        if (failed > 0) parts.add("生成失败 " + failed + " 场");
        if (notGenerated > 0) parts.add("尚未生成 " + notGenerated + " 场");
        if (notRead > 0) parts.add("状态查询失败 " + notRead + " 场");
        if (parts.isEmpty()) return "";
        StringBuilder correction = new StringBuilder("服务端预测状态核验：").append(String.join("；", parts)).append("。");
        if (broadFalseClaim) correction.append("不能将这批比赛统一标记为‘预测未生成’，请以每场记录的 predictionStatus 为准。");
        if ("NEXT_24_HOURS".equals(String.valueOf(schedule.get("windowType")))) {
            String zone = String.valueOf(schedule.getOrDefault("timeZone", "Asia/Shanghai"));
            correction.append("本次‘接下来24小时’窗口按 ").append(zone).append(" 计算。");
        }
        return correction.toString();
    }

    private int count(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> players(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item instanceof Map<?, ?>).map(item -> cast((Map<?, ?>) item)).toList();
    }

    private String deterministicRosterAnswer(Map<String, Object> squad, List<Map<String, Object>> players) {
        String team = String.valueOf(squad.getOrDefault("teamName", "该球队"));
        StringBuilder answer = new StringBuilder("根据公开球队阵容源，").append(team)
                .append("当前可核验到 ").append(squad.getOrDefault("playerCount", players.size())).append(" 名球员：\n");
        for (Map<String, Object> player : players) {
            answer.append("- ").append(player.getOrDefault("name", "未知球员"));
            if (player.get("position") != null) answer.append("（").append(player.get("position")).append("）");
            if (player.get("number") != null && !String.valueOf(player.get("number")).isBlank()) answer.append(" #").append(player.get("number"));
            answer.append('\n');
        }
        if (Boolean.TRUE.equals(squad.get("truncated"))) answer.append("\n以上为返回的部分名单，不能视为完整注册名单。");
        return answer.toString().trim();
    }

    public record Validation(String answer, Map<String, Object> metadata) { }
}
