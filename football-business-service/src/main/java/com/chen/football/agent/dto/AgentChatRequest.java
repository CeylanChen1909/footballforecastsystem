package com.chen.football.agent.dto;

import java.util.List;
import java.util.Map;

public record AgentChatRequest(
        String message,
        List<AgentMessage> history,
        String sessionId,
        String model,
        String provider,
        Double temperature,
        Integer maxTokens,
        Boolean thinking,
        Map<String, Object> context
) {
    public AgentChatRequest(String message) {
        this(message, List.of(), null, null, null, null, null, null, Map.of());
    }
}
