package com.chen.football.agent.dto;

import java.util.List;
import java.util.Map;

public record AgentAnalysisResponse(
        String agent,
        String requestId,
        String status,
        String summary,
        List<String> steps,
        Map<String, Object> data
) {
}
