package com.chen.football.agent.service;

import com.chen.football.agent.dto.AgentAnalysisResponse;
import com.chen.football.agent.dto.AgentChatResponse;
import com.chen.football.common.service.RedisCacheService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Service
public class AgentResponseCacheService {

    private static final long ANALYSIS_TTL_SECONDS = Duration.ofHours(6).toSeconds();
    private static final long CHAT_TTL_SECONDS = Duration.ofHours(24).toSeconds();

    private final RedisCacheService cacheService;

    public AgentResponseCacheService(RedisCacheService cacheService) {
        this.cacheService = cacheService;
    }

    public AgentAnalysisResponse getAnalysis(String cacheKey) {
        return cacheService.get(cacheKey(cacheKey), AgentAnalysisResponse.class);
    }

    public void putAnalysis(String cacheKey, AgentAnalysisResponse response) {
        if (cacheKey == null || cacheKey.isBlank() || response == null) {
            return;
        }
        cacheService.set(cacheKey(cacheKey), response, ANALYSIS_TTL_SECONDS);
    }

    public AgentChatResponse getChat(String cacheKey) {
        return cacheService.get(chatKey(cacheKey), AgentChatResponse.class);
    }

    public void putChat(String cacheKey, AgentChatResponse response) {
        if (cacheKey == null || cacheKey.isBlank() || response == null) {
            return;
        }
        cacheService.set(chatKey(cacheKey), response, CHAT_TTL_SECONDS);
    }

    public Map<String, Object> snapshot(String key) {
        return Map.of(
                "analysis", cacheService.get(cacheKey(key), Map.class),
                "chat", cacheService.get(chatKey(key), Map.class)
        );
    }

    private String cacheKey(String key) {
        return "agent:analysis:" + key;
    }

    private String chatKey(String key) {
        return "agent:chat:" + key;
    }
}
