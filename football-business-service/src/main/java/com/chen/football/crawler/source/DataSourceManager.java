package com.chen.football.crawler.source;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.dto.FetchResult;
import com.chen.football.common.dto.NormalizedMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DataSourceManager {

    private final List<MatchSourceProvider> providers;
    private final DataSourceHealthTracker healthTracker;
    private final CrawlerProperties properties;

    public DataSourceManager(List<MatchSourceProvider> providers,
                             DataSourceHealthTracker healthTracker,
                             CrawlerProperties properties) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(MatchSourceProvider::priority))
                .toList();
        this.healthTracker = healthTracker;
        this.properties = properties;
    }

    public FetchResult fetchMatches(String date) {
        return fetchPrimary(date, null, null);
    }

    public FetchResult fetchMatchesByLeague(int leagueId, int season) {
        return fetchPrimary(null, leagueId, season);
    }

    public FetchResult fetchMatchesWithFallback(String date) {
        return fetchAll(date, null, null);
    }

    public FetchResult fetchMatchesByLeagueWithFallback(int leagueId, int season) {
        return fetchAll(null, leagueId, season);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("primaryOnly", properties.isPrimaryOnly());
        result.put("primarySource", properties.getPrimarySource());
        result.put("providers", providers.stream().map(p -> {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("priority", p.priority());
            pm.put("enabled", isSourceEnabled(p.name()));
            if (!isSourceEnabled(p.name())) {
                pm.put("disabledReason", "当前处于主数据源模式");
            }
            pm.put("available", p.isAvailable());
            pm.putAll(healthTracker.sourceSnapshot(p.name()));
            String status = String.valueOf(pm.getOrDefault("status", DataSourceHealthTracker.UNKNOWN));
            // Keep transport/circuit health separate from data health.  An
            // empty response is a successful request, but it is not evidence
            // that the requested date has complete coverage.
            pm.put("transportHealthy", healthTracker.isAvailable(p.name()));
            pm.put("dataAvailable", DataSourceHealthTracker.NORMAL.equals(status));
            pm.put("healthy", DataSourceHealthTracker.NORMAL.equals(status));
            return pm;
        }).toList());
        result.put("health", healthTracker.snapshot());
        result.put("coverageMatrix", providers.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> health = healthTracker.sourceSnapshot(p.name());
            row.put("source", p.name());
            row.put("priority", p.priority());
            row.put("enabled", isSourceEnabled(p.name()));
            row.put("status", health.getOrDefault("status", DataSourceHealthTracker.UNKNOWN));
            row.put("coverageStatus", health.getOrDefault("coverageStatus", "NOT_CHECKED"));
            row.put("lastSuccess", health.get("lastSuccess"));
            row.put("dataAgeSeconds", health.get("dataAgeSeconds"));
            row.put("lastMatchCount", health.get("lastMatchCount"));
            return row;
        }).toList());
        List<Map<String, Object>> alerts = new ArrayList<>();
        providers.forEach(p -> {
            if (!isSourceEnabled(p.name())) return;
            Map<String, Object> health = healthTracker.sourceSnapshot(p.name());
            String status = String.valueOf(health.getOrDefault("status", DataSourceHealthTracker.UNKNOWN));
            if (!DataSourceHealthTracker.NORMAL.equals(status)) {
                alerts.add(Map.of("source", p.name(), "severity", DataSourceHealthTracker.QUOTA_LIMITED.equals(status) ? "HIGH" : "MEDIUM",
                        "status", status, "message", health.getOrDefault("statusText", "数据源需要关注")));
            }
        });
        result.put("alerts", alerts);
        return result;
    }

    /**
     * Public, privacy-safe health view for authenticated product surfaces.
     * Detailed provider errors, quota messages and circuit internals remain
     * available only through the admin test endpoints.
     */
    public Map<String, Object> publicSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("primaryOnly", properties.isPrimaryOnly());
        result.put("primarySource", properties.getPrimarySource());
        result.put("providers", providers.stream().map(p -> {
            Map<String, Object> health = healthTracker.sourceSnapshot(p.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.name());
            row.put("priority", p.priority());
            row.put("enabled", isSourceEnabled(p.name()));
            row.put("available", p.isAvailable());
            row.put("status", health.getOrDefault("status", DataSourceHealthTracker.UNKNOWN));
            row.put("statusText", health.getOrDefault("statusText", "待检测"));
            row.put("lastSuccess", health.get("lastSuccess"));
            row.put("dataAgeSeconds", health.get("dataAgeSeconds"));
            row.put("lastMatchCount", health.get("lastMatchCount"));
            row.put("coverageStatus", health.getOrDefault("coverageStatus", "NOT_CHECKED"));
            return row;
        }).toList());
        result.put("checkedAt", java.time.Instant.now().toString());
        return result;
    }

    public List<MatchSourceProvider> orderedProviders() {
        return providers;
    }

    public boolean isAvailable(MatchSourceProvider provider) {
        return provider != null && isSourceEnabled(provider.name())
                && provider.isAvailable() && healthTracker.isAvailable(provider.name());
    }

    /** 当前数据源是否允许参与比赛采集/写入。 */
    public boolean isSourceEnabled(String source) {
        if (source == null || source.isBlank()) return false;
        if (!properties.isPrimaryOnly()) return true;
        return properties.getPrimarySource() != null
                && properties.getPrimarySource().equalsIgnoreCase(source.trim());
    }

    public String primarySource() {
        return properties.getPrimarySource();
    }

    /**
     * Whether reads as well as writes should be isolated to the configured
     * primary source.  This is exposed so legacy database-backed endpoints
     * cannot surface rows written by providers that are now disabled.
     */
    public boolean isPrimaryOnly() {
        return properties.isPrimaryOnly();
    }

    public void recordResult(MatchSourceProvider provider, FetchResult result) {
        if (provider == null) return;
        if (result != null && result.success()) {
            healthTracker.recordSuccess(provider.name(), result.matches() == null ? 0 : result.matches().size());
        } else {
            healthTracker.recordFailure(provider.name(), result == null ? "null result" : result.error());
        }
    }

    public void recordFailure(String source, String error) {
        healthTracker.recordFailure(source, error);
    }

    public Map<String, Object> sourceHealth(String source) {
        return healthTracker.sourceSnapshot(source);
    }

    private FetchResult fetchPrimary(String date, Integer leagueId, Integer season) {
        List<String> errors = new ArrayList<>();
        boolean successfulEmpty = false;
        long start = System.currentTimeMillis();
        for (MatchSourceProvider provider : providers) {
            if (!isAvailable(provider)) continue;
            FetchResult result;
            try {
                result = leagueId == null
                        ? provider.fetchMatches(date)
                        : provider.fetchMatchesByLeague(leagueId, season == null ? Year.now().getValue() : season);
            } catch (Exception e) {
                healthTracker.recordFailure(provider.name(), e.getMessage());
                errors.add(provider.name() + ":" + e.getMessage());
                continue;
            }
            if (result != null && result.success()) {
                recordResult(provider, result);
                if (result.matches() != null && !result.matches().isEmpty()) {
                    return result;
                }
                // 空数据只代表该源覆盖不到这个日期，不能阻断后续源（例如
                // football-data 的免费层为空时仍应继续尝试 BBC）。
                successfulEmpty = true;
                continue;
            }
            String error = result == null ? "null result" : result.error();
            recordFailure(provider.name(), error);
            errors.add(provider.name() + ":" + error);
        }
        if (successfulEmpty) {
            return FetchResult.success("all-sources-empty", List.of(), System.currentTimeMillis() - start);
        }
        return FetchResult.failure("all-sources", String.join(" | ", errors), 0);
    }

    private FetchResult fetchAll(String date, Integer leagueId, Integer season) {
        List<NormalizedMatch> merged = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (MatchSourceProvider provider : providers) {
            if (!isAvailable(provider)) continue;
            try {
                FetchResult result = leagueId == null
                        ? provider.fetchMatches(date)
                        : provider.fetchMatchesByLeague(leagueId, season == null ? Year.now().getValue() : season);
                if (result != null && result.success()) {
                    recordResult(provider, result);
                    mergeMatches(merged, result.matches());
                } else {
                    String error = result == null ? "null result" : result.error();
                    recordFailure(provider.name(), error);
                    errors.add(provider.name() + ":" + error);
                }
            } catch (Exception e) {
                healthTracker.recordFailure(provider.name(), e.getMessage());
                errors.add(provider.name() + ":" + e.getMessage());
            }
        }
        if (merged.isEmpty()) {
            if (errors.isEmpty()) {
                return FetchResult.success("merged", List.of(), 0);
            }
            return FetchResult.failure("all-sources", String.join(" | ", errors), 0);
        }
        return FetchResult.success("merged", merged, 0);
    }

    private void mergeMatches(List<NormalizedMatch> target, List<NormalizedMatch> source) {
        if (source == null || source.isEmpty()) return;
        Map<String, NormalizedMatch> dedup = new LinkedHashMap<>();
        for (NormalizedMatch match : target) {
            dedup.put(matchKey(match), match);
        }
        for (NormalizedMatch match : source) {
            dedup.putIfAbsent(matchKey(match), match);
        }
        target.clear();
        target.addAll(dedup.values());
    }

    private String matchKey(NormalizedMatch match) {
        String homeId = normalize(match.homeTeamId());
        String awayId = normalize(match.awayTeamId());
        String home = normalize(match.homeTeamName());
        String away = normalize(match.awayTeamName());
        String league = normalize(match.leagueId());
        if (league.isBlank()) league = normalize(match.leagueName());
        String date = match.matchTime() == null ? "" : match.matchTime().toLocalDate().toString();
        // Date-only matching silently merges double-headers or a postponed
        // fixture with its replacement. Prefer team IDs and a minute-level
        // kickoff slot; fall back to names only when the source has no IDs.
        String teams = !homeId.isBlank() && !awayId.isBlank()
                ? homeId + "#" + awayId
                : home + "#" + away;
        if (match.matchTime() == null) {
            // 缺少时间时不能把同一天同两队的不同事件折叠成一场；
            // 使用“来源+外部事件 ID”作为保守身份，待后续补齐时间再归并。
            String external = normalize(match.externalMatchId());
            return normalize(match.source()) + "|" + league + "|" + teams + "|" + date + "|" + (external.isBlank() ? "unknown" : external);
        }
        String kickoff = match.matchTime().withSecond(0).withNano(0).toLocalTime().toString();
        return league + "|" + teams + "|" + date + "|" + kickoff;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }
}
