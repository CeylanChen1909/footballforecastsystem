package com.chen.football.agent;

import com.chen.football.agent.service.AgentIntentClassifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentIntentClassifierTest {

    private final AgentIntentClassifier classifier = new AgentIntentClassifier();

    @Test
    void crossLeagueComparisonIsTeamAnalysis() {
        assertEquals("team-analysis", classifier.classify("不同联赛比较阿森纳和巴萨").get("intent"));
    }

    @Test
    void randomScheduleQuestionIsSchedule() {
        assertEquals("schedule", classifier.classify("随机找两支有赛程安排的球队分析").get("intent"));
    }

    @Test
    void nextHoursScheduleQuestionIsNotMatchAnalysis() {
        assertEquals("schedule", classifier.classify("请按开赛时间列出接下来24小时的比赛并标记预测状态").get("intent"));
    }

    @Test
    void genericTeamQuestionDoesNotBecomeMatchAnalysis() {
        assertEquals("team-analysis", classifier.classify("比较两支球队近期状态").get("intent"));
    }

    @Test
    void rosterQuestionUsesSquadIntent() {
        assertEquals("team-roster", classifier.classify("巴萨俱乐部队员都有谁").get("intent"));
    }
}
