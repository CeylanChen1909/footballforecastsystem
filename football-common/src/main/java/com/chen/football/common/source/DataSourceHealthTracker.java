package com.chen.football.common.source;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component("legacyDataSourceHealthTracker")
public class DataSourceHealthTracker {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long RECOVERY_SECONDS = 120;

    private record HealthState(int successCount, int failureCount, Instant lastSuccess, Instant circuitTrippedAt) {
    }

    private final Map<String, HealthState> states = new ConcurrentHashMap<>();

    public void recordSuccess(String source, int matchCount) {
        states.compute(source, (k, existing) -> {
            int success = existing == null ? 1 : existing.successCount() + 1;
            return new HealthState(success, 0, Instant.now(), null);
        });
        if (matchCount > 0) {
            log.debug("[HealthTracker] {} success, matches={}", source, matchCount);
        }
    }

    public void recordFailure(String source, String error) {
        states.compute(source, (k, existing) -> {
            int failure = existing == null ? 1 : existing.failureCount() + 1;
            Instant tripped = failure >= FAILURE_THRESHOLD ? Instant.now() : null;
            return new HealthState(
                    existing == null ? 0 : existing.successCount(),
                    failure,
                    existing == null ? null : existing.lastSuccess(),
                    tripped
            );
        });
        log.warn("[HealthTracker] {} failure, error={}", source, error);
    }

    public boolean isAvailable(String source) {
        HealthState state = states.get(source);
        if (state == null || state.failureCount() < FAILURE_THRESHOLD) {
            return true;
        }
        if (state.circuitTrippedAt() != null) {
            if (Duration.between(state.circuitTrippedAt(), Instant.now()).toSeconds() > RECOVERY_SECONDS) {
                states.remove(source);
                log.info("[HealthTracker] {} circuit breaker reset after {}s", source, RECOVERY_SECONDS);
                return true;
            }
            return false;
        }
        return true;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : states.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("successCount", entry.getValue().successCount());
            s.put("failureCount", entry.getValue().failureCount());
            s.put("lastSuccess", entry.getValue().lastSuccess() == null ? null : entry.getValue().lastSuccess().toString());
            s.put("circuitOpen", entry.getValue().failureCount() >= FAILURE_THRESHOLD && !isAvailable(entry.getKey()));
            result.put(entry.getKey(), s);
        }
        return result;
    }
}
