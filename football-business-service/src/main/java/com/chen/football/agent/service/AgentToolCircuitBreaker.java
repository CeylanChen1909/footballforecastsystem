package com.chen.football.agent.service;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentToolCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long RECOVERY_SECONDS = 60;

    private record BreakerState(int failures, Instant trippedAt, AtomicInteger inFlight) {
    }

    private final Map<String, BreakerState> states = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;

    public AgentToolCircuitBreaker() {
        this.redisTemplate = null;
    }

    @Autowired
    public AgentToolCircuitBreaker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean allow(String toolName) {
        if (redisTemplate != null) {
            try {
                String value = redisTemplate.opsForValue().get(redisKey(toolName));
                if (value != null && Integer.parseInt(value) >= FAILURE_THRESHOLD) return false;
            } catch (Exception ignored) {
                // Redis is an optimization for multi-instance coordination;
                // the local state below remains the safety net.
            }
        }
        BreakerState state = states.get(toolName);
        if (state == null || state.failures() < FAILURE_THRESHOLD) {
            return true;
        }
        if (Duration.between(state.trippedAt(), Instant.now()).toSeconds() > RECOVERY_SECONDS) {
            states.remove(toolName);
            return true;
        }
        return false;
    }

    public void recordSuccess(String toolName) {
        states.remove(toolName);
        if (redisTemplate != null) {
            try { redisTemplate.delete(redisKey(toolName)); } catch (Exception ignored) { }
        }
    }

    public void recordFailure(String toolName) {
        states.compute(toolName, (k, existing) -> {
            int failures = existing == null ? 1 : existing.failures() + 1;
            return new BreakerState(failures, Instant.now(), existing == null ? new AtomicInteger(0) : existing.inFlight());
        });
        if (redisTemplate != null) {
            try {
                Long failures = redisTemplate.opsForValue().increment(redisKey(toolName));
                if (failures != null && failures == 1L) {
                    redisTemplate.expire(redisKey(toolName), Duration.ofSeconds(RECOVERY_SECONDS));
                }
            } catch (Exception ignored) { }
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : states.entrySet()) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("failures", entry.getValue().failures());
            state.put("trippedAt", entry.getValue().trippedAt().toString());
            state.put("isOpen", entry.getValue().failures() >= FAILURE_THRESHOLD);
            result.put(entry.getKey(), state);
        }
        return result;
    }

    private String redisKey(String toolName) {
        String safe = toolName == null ? "unknown" : toolName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return "agent:circuit:" + safe;
    }
}
