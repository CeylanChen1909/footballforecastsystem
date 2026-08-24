package com.chen.football.agent;

import com.chen.football.agent.service.AgentJsonTools;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentJsonToolsTest {

    @Test
    void extractsFirstBalancedObjectWithoutSwallowingTrailingText() {
        String value = "回答如下： {\"summary\":\"主队\",\"risk\":\"x}y\"} 后续说明";
        String json = AgentJsonTools.extractJson(value);
        assertEquals("{\"summary\":\"主队\",\"risk\":\"x}y\"}", json);
        assertTrue(AgentJsonTools.looksLikeJson(json));
    }

    @Test
    void extractsNestedFencedJson() {
        String value = "```json\n{\"summary\":\"主队\",\"points\":[{\"text\":\"x\"}]}\n```";
        assertEquals("{\"summary\":\"主队\",\"points\":[{\"text\":\"x\"}]}", AgentJsonTools.extractJson(value));
    }
}
