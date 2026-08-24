package com.chen.football.agent;

import com.chen.football.agent.service.AgentAnswerValidator;
import com.chen.football.agent.service.AgentToolRun;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAnswerValidatorTest {
    private final AgentAnswerValidator validator = new AgentAnswerValidator();

    @Test
    void rosterAnswerWithoutAnyKnownPlayerFallsBackToSourceFacts() {
        Map<String, Object> squad = Map.of(
                "status", "AVAILABLE",
                "teamName", "Barcelona",
                "playerCount", 1,
                "players", List.of(Map.of("name", "Pedri", "position", "Midfielder", "number", "8"))
        );
        AgentToolRun run = new AgentToolRun("team-roster", .7, Map.of(), Map.of("squad_context", squad),
                List.of("squad_context"), List.of(), Map.of(), List.of(Map.of("tool", "squad_context", "status", "ok")));
        AgentAnswerValidator.Validation result = validator.validate("目前没有可用名单", run);
        assertTrue(result.answer().contains("Pedri"));
        assertTrue(Boolean.FALSE.equals(result.metadata().get("grounded")));
    }

    @Test
    void scheduleAnswerCannotClaimAllPredictionsAreMissing() {
        Map<String, Object> context = Map.of(
                "message", "接下来24小时列出比赛并标记预测是否已生成");
        Map<String, Object> schedule = Map.of(
                "windowType", "NEXT_24_HOURS",
                "timeZone", "Asia/Shanghai",
                "predictionSummary", Map.of("READY", 7, "UNAVAILABLE", 2));
        AgentToolRun run = new AgentToolRun("schedule", .7, context,
                Map.of("crawler_summary", schedule), List.of("crawler_summary"),
                List.of(), Map.of(), List.of());
        AgentAnswerValidator.Validation result = validator.validate("以上所有比赛均未生成预测。", run);
        assertTrue(result.answer().contains("已生成 7 场"));
        assertTrue(result.answer().contains("不能将这批比赛统一标记"));
        assertTrue(Boolean.FALSE.equals(result.metadata().get("grounded")));
    }

    @Test
    void correctScheduleAnswerIsNotFlaggedByUnrelatedMissingData() {
        Map<String, Object> context = Map.of(
                "message", "接下来24小时列出比赛并标记预测是否已生成");
        Map<String, Object> schedule = Map.of(
                "windowType", "NEXT_24_HOURS",
                "timeZone", "Asia/Shanghai",
                "predictionSummary", Map.of("READY", 6));
        AgentToolRun run = new AgentToolRun("schedule", .7, context,
                Map.of("crawler_summary", schedule), List.of("crawler_summary"),
                List.of(), Map.of(), List.of());
        AgentAnswerValidator.Validation result = validator.validate(
                "全部6场已生成预测。当前没有伤停数据，无法核验首发。", run);
        assertTrue(result.metadata().get("warnings") instanceof List<?> warnings && warnings.isEmpty());
    }
}
