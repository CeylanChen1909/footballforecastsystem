package com.chen.football.agent.service;

import java.util.List;
import java.util.Map;

public record AgentStructuredResult(
        String summary,
        double confidence,
        List<String> keyPoints,
        List<String> risks,
        String recommendation,
        List<String> followUpQuestions,
        boolean parsed,
        String rawContent
) {
    public static AgentStructuredResult fallback(String summary, String rawContent) {
        return fallback(summary, rawContent, AgentText.resolve(null));
    }

    public static AgentStructuredResult fallback(String summary, String rawContent, String lang) {
        return new AgentStructuredResult(
                summary,
                0.3,
                List.of(),
                List.of(),
                AgentText.of(lang, "建议补充更多信息后再做决策。", "Add more information before making a decision."),
                List.of(),
                false,
                rawContent
        );
    }
}
