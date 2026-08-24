package com.chen.football.agent.service;

import com.chen.football.agent.dto.AgentMessage;
import com.chen.football.common.service.RedisCacheService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentSessionMemoryService {

    private static final int DEFAULT_MAX_MESSAGES = 20;
    private static final long DEFAULT_TTL_SECONDS = Duration.ofDays(7).toSeconds();

    private final RedisCacheService cacheService;

    public AgentSessionMemoryService(RedisCacheService cacheService) {
        this.cacheService = cacheService;
    }

    public List<AgentMessage> loadMessages(String sessionId, int maxMessages) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        SessionMemory memory = cacheService.get(memoryKey(sessionId), SessionMemory.class);
        if (memory == null || memory.messages == null || memory.messages.isEmpty()) {
            return List.of();
        }
        int safeMax = Math.max(1, Math.min(maxMessages <= 0 ? DEFAULT_MAX_MESSAGES : maxMessages, 50));
        int fromIndex = Math.max(0, memory.messages.size() - safeMax);
        return new ArrayList<>(memory.messages.subList(fromIndex, memory.messages.size()));
    }

    public void append(String sessionId, List<AgentMessage> messages, Map<String, Object> metadata) {
        if (sessionId == null || sessionId.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        SessionMemory memory = cacheService.get(memoryKey(sessionId), SessionMemory.class);
        if (memory == null) {
            memory = new SessionMemory();
            memory.sessionId = sessionId;
            memory.messages = new ArrayList<>();
        }
        memory.messages.addAll(messages);
        if (memory.messages.size() > 50) {
            memory.messages = new ArrayList<>(memory.messages.subList(memory.messages.size() - 50, memory.messages.size()));
        }
        memory.lastUpdatedAt = Instant.now().toString();
        memory.metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        cacheService.set(memoryKey(sessionId), memory, DEFAULT_TTL_SECONDS);
    }

    public Map<String, Object> snapshot(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("sessionId", null, "messages", List.of(), "metadata", Map.of());
        }
        SessionMemory memory = cacheService.get(memoryKey(sessionId), SessionMemory.class);
        if (memory == null) {
            return Map.of("sessionId", sessionId, "messages", List.of(), "metadata", Map.of());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", memory.sessionId);
        result.put("messages", memory.messages == null ? List.of() : memory.messages);
        result.put("metadata", memory.metadata == null ? Map.of() : memory.metadata);
        result.put("lastUpdatedAt", memory.lastUpdatedAt);
        return result;
    }

    private String memoryKey(String sessionId) {
        return "agent:session:" + sessionId;
    }

    public static class SessionMemory {
        public String sessionId;
        public List<AgentMessage> messages = new ArrayList<>();
        public Map<String, Object> metadata = new LinkedHashMap<>();
        public String lastUpdatedAt;
    }
}
