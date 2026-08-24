package com.chen.football.agent.dto;

import java.util.List;
import java.util.Map;

public record AgentChatResponse(
        String agent,
        String requestId,
        String status,
        String answer,
        List<AgentMessage> messages,
        Map<String, Object> metadata
) {
}
