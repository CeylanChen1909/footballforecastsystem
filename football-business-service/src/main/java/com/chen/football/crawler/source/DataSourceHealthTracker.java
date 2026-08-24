package com.chen.football.crawler.source;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service("crawlerDataSourceHealthTracker")
public class DataSourceHealthTracker {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long RECOVERY_SECONDS = 120;
    private static final long QUOTA_RECOVERY_SECONDS = 3600;
    private static final String REDIS_PREFIX = "crawler:data-source:health:";

    public static final String NORMAL = "NORMAL";
    public static final String EMPTY = "EMPTY";
    public static final String QUOTA_LIMITED = "QUOTA_LIMITED";
    public static final String REQUEST_FAILED = "REQUEST_FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    private record HealthState(int successCount, int failureCount, Instant lastAttempt, Instant lastSuccess,
                               Instant circuitTrippedAt, String lastError,
                               String status, int lastMatchCount) {}

    private final Map<String, HealthState> states = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;

    public DataSourceHealthTracker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void recordSuccess(String source, int matchCount) {
        states.compute(source, (k, existing) -> new HealthState(
                existing == null ? 1 : existing.successCount() + 1,
                0,
                Instant.now(),
                Instant.now(),
                null,
                null,
                matchCount > 0 ? NORMAL : EMPTY,
                Math.max(0, matchCount)
        ));
        persist(source);
        if (matchCount > 0) {
            log.debug("[HealthTracker] {} success, matches={}", source, matchCount);
        }
    }

    public void recordFailure(String source, String error) {
        states.compute(source, (k, existing) -> {
            int failure = existing == null ? 1 : existing.failureCount() + 1;
            String status = classifyFailure(error);
            Instant tripped = failure >= FAILURE_THRESHOLD || QUOTA_LIMITED.equals(status) ? Instant.now() : null;
            return new HealthState(existing == null ? 0 : existing.successCount(), failure,
                    Instant.now(),
                    existing == null ? null : existing.lastSuccess(), tripped, error, status, 0);
        });
        persist(source);
        log.warn("[HealthTracker] {} failure, error={}", source, error);
    }

    public boolean isAvailable(String source) {
        HealthState state = load(source).orElse(states.get(source));
        if (state == null || state.circuitTrippedAt() == null) {
            return state == null || state.failureCount() < FAILURE_THRESHOLD;
        }
        if (Duration.between(state.circuitTrippedAt(), Instant.now()).toSeconds() > recoverySeconds(state)) {
            states.remove(source);
            try { redisTemplate.delete(REDIS_PREFIX + source); } catch (RuntimeException ex) {
                log.debug("[HealthTracker] Redis unavailable while clearing circuit for {}: {}", source, ex.getMessage());
            }
            return true;
        }
        return false;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : states.entrySet()) {
            result.put(entry.getKey(), toMap(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public Map<String, Object> sourceSnapshot(String source) {
        HealthState state = load(source).orElse(states.get(source));
        if (state == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("successCount", 0);
            empty.put("failureCount", 0);
            empty.put("lastAttempt", null);
            empty.put("lastSuccess", null);
            empty.put("dataAgeSeconds", null);
            empty.put("lastError", null);
            empty.put("status", UNKNOWN);
            empty.put("statusText", statusText(UNKNOWN));
            empty.put("lastMatchCount", 0);
            empty.put("circuitOpen", false);
            empty.put("successRate", 0.0);
            return empty;
        }
        return toMap(source, state);
    }

    private Map<String, Object> toMap(String source, HealthState state) {
        Map<String, Object> s = new LinkedHashMap<>();
        int total = state.successCount() + state.failureCount();
        double successRate = total == 0 ? 0.0 : (state.successCount() * 100.0 / total);
        s.put("source", source);
        s.put("successCount", state.successCount());
        s.put("failureCount", state.failureCount());
        s.put("lastAttempt", state.lastAttempt() == null ? null : state.lastAttempt().toString());
        s.put("lastSuccess", state.lastSuccess() == null ? null : state.lastSuccess().toString());
        s.put("dataAgeSeconds", state.lastSuccess() == null ? null : Math.max(0, Duration.between(state.lastSuccess(), Instant.now()).toSeconds()));
        s.put("coverageStatus", coverageStatus(state));
        s.put("lastError", state.lastError());
        s.put("status", state.status());
        s.put("statusText", statusText(state.status()));
        s.put("lastMatchCount", state.lastMatchCount());
        s.put("circuitOpen", !isHealthyNow(state));
        s.put("successRate", Math.round(successRate * 100.0) / 100.0);
        return s;
    }

    private boolean isHealthyNow(HealthState state) {
        if (state == null || state.circuitTrippedAt() == null) {
            return state == null || state.failureCount() < FAILURE_THRESHOLD;
        }
        return Duration.between(state.circuitTrippedAt(), Instant.now()).toSeconds() > recoverySeconds(state);
    }

    private long recoverySeconds(HealthState state) {
        return QUOTA_LIMITED.equals(state.status()) ? QUOTA_RECOVERY_SECONDS : RECOVERY_SECONDS;
    }

    public static boolean isQuotaOrPlanError(String error) {
        if (error == null || error.isBlank()) return false;
        String normalized = error.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("request limit")
                || normalized.contains("rate limit")
                || normalized.contains("too many requests")
                || normalized.contains("quota")
                || normalized.contains("每日可允许请求次数")
                || normalized.contains("free plans do not have access")
                || normalized.contains("plan does not have access")
                || normalized.contains("subscription")
                || normalized.contains("access to this date");
    }

    private String classifyFailure(String error) {
        return isQuotaOrPlanError(error) ? QUOTA_LIMITED : REQUEST_FAILED;
    }

    private String statusText(String status) {
        return switch (status) {
            case NORMAL -> "正常";
            case EMPTY -> "空数据";
            case QUOTA_LIMITED -> "额度受限";
            case REQUEST_FAILED -> "请求失败";
            default -> "未检测";
        };
    }

    private void persist(String source) {
        HealthState state = states.get(source);
        if (state == null) return;
        String payload = String.join("|",
                String.valueOf(state.successCount()),
                String.valueOf(state.failureCount()),
                String.valueOf(Optional.ofNullable(state.lastAttempt()).map(Instant::toString).orElse("")),
                String.valueOf(Optional.ofNullable(state.lastSuccess()).map(Instant::toString).orElse("")),
                String.valueOf(Optional.ofNullable(state.circuitTrippedAt()).map(Instant::toString).orElse("")),
                safeError(state.lastError()),
                Optional.ofNullable(state.status()).orElse(UNKNOWN),
                String.valueOf(state.lastMatchCount())
        );
        try {
            redisTemplate.opsForValue().set(REDIS_PREFIX + source, payload, Duration.ofHours(24));
        } catch (RuntimeException ex) {
            // Health tracking is telemetry; never turn a successful source
            // response into a failed crawl because Redis is unavailable.
            log.debug("[HealthTracker] Redis unavailable, keeping local state for {}: {}", source, ex.getMessage());
        }
    }

    private String safeError(String error) {
        return Optional.ofNullable(error).orElse("").replace('|', ' ');
    }

    private Optional<HealthState> load(String source) {
        String payload;
        try {
            payload = redisTemplate.opsForValue().get(REDIS_PREFIX + source);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (payload == null || payload.isBlank()) return Optional.empty();
        String[] parts = payload.split("\\|", -1);
        try {
            int successCount = Integer.parseInt(parts[0]);
            int failureCount = Integer.parseInt(parts[1]);
            // 兼容旧版 Redis payload（旧格式没有 lastAttempt）。
            boolean newFormat = parts.length >= 8;
            int attemptIndex = newFormat ? 2 : -1;
            int successIndex = newFormat ? 3 : 2;
            int circuitIndex = newFormat ? 4 : 3;
            int errorIndex = newFormat ? 5 : 4;
            int statusIndex = newFormat ? 6 : 5;
            int countIndex = newFormat ? 7 : 6;
            Instant lastSuccess = parts.length > successIndex && !parts[successIndex].isBlank() ? Instant.parse(parts[successIndex]) : null;
            Instant lastAttempt = newFormat && parts.length > attemptIndex && !parts[attemptIndex].isBlank()
                    ? Instant.parse(parts[attemptIndex]) : lastSuccess;
            Instant circuitTrippedAt = parts.length > circuitIndex && !parts[circuitIndex].isBlank() ? Instant.parse(parts[circuitIndex]) : null;
            String lastError = parts.length > errorIndex ? parts[errorIndex] : null;
            String status = parts.length > statusIndex && !parts[statusIndex].isBlank() ? parts[statusIndex] : UNKNOWN;
            int lastMatchCount = parts.length > countIndex ? Integer.parseInt(parts[countIndex]) : 0;
            return Optional.of(new HealthState(successCount, failureCount, lastAttempt, lastSuccess, circuitTrippedAt,
                    lastError, status, lastMatchCount));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String coverageStatus(HealthState state) {
        if (state == null || state.lastAttempt() == null) return "NOT_CHECKED";
        if (QUOTA_LIMITED.equals(state.status())) return "LIMITED";
        if (REQUEST_FAILED.equals(state.status())) return "FAILED";
        if (EMPTY.equals(state.status())) return "NO_DATA";
        if (state.lastSuccess() == null) return "NO_DATA";
        return "AVAILABLE";
    }
}
