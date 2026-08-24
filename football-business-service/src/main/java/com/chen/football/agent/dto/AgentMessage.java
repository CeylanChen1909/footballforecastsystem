package com.chen.football.agent.dto;

import java.util.Map;
import java.util.LinkedHashMap;

public record AgentMessage(
        String role,
        String content,
        Map<String, Object> metadata
) {
    public AgentMessage(String role, String content) {
        this(role, content, Map.of());
    }

    public AgentMessage {
        role = role == null ? "user" : role.trim();
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content);
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage("assistant", content);
    }

    public static AgentMessage system(String content) {
        return new AgentMessage("system", content);
    }

    public static AgentMessage assistant(String content, Map<String, Object> metadata) {
        return new AgentMessage("assistant", content, metadata);
    }
}
