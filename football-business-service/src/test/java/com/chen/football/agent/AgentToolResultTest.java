package com.chen.football.agent;

import com.chen.football.agent.service.AgentToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentToolResultTest {
    @Test
    void normalizesLegacyErrorAndAddsSourceMetadata() {
        Map<String, Object> result = AgentToolResult.normalize("squad_context", Map.of("error", "timeout"));
        assertEquals("REQUEST_FAILED", result.get("status"));
        assertEquals("公开球队阵容源", result.get("source"));
        assertEquals("squad_context", result.get("tool"));
    }

    @Test
    void preservesExplicitDataStatus() {
        Map<String, Object> result = AgentToolResult.normalize("crawler_summary", Map.of("status", "EMPTY"));
        assertEquals("EMPTY", result.get("status"));
    }
}
