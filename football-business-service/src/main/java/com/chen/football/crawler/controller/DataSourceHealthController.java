package com.chen.football.crawler.controller;

import com.chen.football.common.dto.FetchResult;
import com.chen.football.crawler.source.DataSourceManager;
import com.chen.football.crawler.source.MatchSourceProvider;
import com.chen.football.common.util.AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源测试接口 — 可以直接验证采集是否成功
 */
@RestController
@RequestMapping("/api/crawler/data-sources")
@RequiredArgsConstructor
public class DataSourceHealthController {

    private final DataSourceManager dataSourceManager;

    /**
     * 查看所有数据源健康状态
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "success", true,
                "message", "获取成功",
                "data", dataSourceManager.publicSnapshot()
        );
    }

    /**
     * 测试所有数据源采集指定日期的比赛
     */
    @GetMapping("/test")
    public Map<String, Object> testAll(@RequestParam(defaultValue = "") String date) {
        AdminGuard.requirePermission("CRAWLER");
        String testDate = date == null || date.isBlank() ? LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString() : date;
        Map<String, Object> results = new LinkedHashMap<>();
        int totalMatches = 0;
        for (MatchSourceProvider provider : dataSourceManager.orderedProviders()) {
            Map<String, Object> r = new LinkedHashMap<>();
            boolean configured = dataSourceManager.isSourceEnabled(provider.name()) && provider.isAvailable();
            r.put("available", configured);
            if (!configured) {
                r.put("status", dataSourceManager.isSourceEnabled(provider.name()) ? "unavailable" : "disabled-primary-only");
                results.put(provider.name(), r);
                continue;
            }
            if (!dataSourceManager.isAvailable(provider)) {
                r.put("status", "circuit-open");
                r.put("dataStatus", dataSourceManager.sourceHealth(provider.name()).get("status"));
                r.put("dataStatusText", dataSourceManager.sourceHealth(provider.name()).get("statusText"));
                results.put(provider.name(), r);
                continue;
            }
            long start = System.currentTimeMillis();
            try {
                FetchResult result = provider.fetchMatches(testDate);
                long elapsed = System.currentTimeMillis() - start;
                r.put("success", result != null && result.success());
                r.put("matches", result == null ? 0 : result.matches().size());
                r.put("latencyMs", elapsed);
                r.put("error", result == null ? "null result" : result.error());
                r.put("source", result == null ? provider.name() : result.source());
                dataSourceManager.recordResult(provider, result);
                if (result != null && result.success()) {
                    totalMatches += result.matches().size();
                }
            } catch (Exception e) {
                dataSourceManager.recordFailure(provider.name(), e.getMessage());
                r.put("success", false);
                r.put("matches", 0);
                r.put("latencyMs", System.currentTimeMillis() - start);
                r.put("error", e.getMessage());
            }
            Map<String, Object> health = dataSourceManager.sourceHealth(provider.name());
            r.put("dataStatus", health.get("status"));
            r.put("dataStatusText", health.get("statusText"));
            results.put(provider.name(), r);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("testDate", testDate);
        summary.put("totalMatches", totalMatches);
        summary.put("sources", results);
        return Map.of(
                "success", true,
                "message", "测试完成",
                "data", summary
        );
    }

    /**
     * 测试单个数据源
     */
    @GetMapping("/test/{sourceName}")
    public Map<String, Object> testSingle(@PathVariable String sourceName, @RequestParam(defaultValue = "") String date) {
        AdminGuard.requirePermission("CRAWLER");
        String testDate = date == null || date.isBlank() ? LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString() : date;
        MatchSourceProvider target = dataSourceManager.orderedProviders().stream()
                .filter(p -> p.name().equals(sourceName))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return Map.of("success", false, "message", "数据源不存在: " + sourceName);
        }
        long start = System.currentTimeMillis();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", target.name());
        r.put("priority", target.priority());
        boolean sourceEnabled = dataSourceManager.isSourceEnabled(target.name());
        r.put("available", sourceEnabled && target.isAvailable());
        if (!sourceEnabled) {
            r.put("success", false);
            r.put("status", "disabled-primary-only");
            r.put("error", "当前主数据源模式已停用该来源");
            return Map.of("success", true, "message", "数据源已停用", "data", r);
        }
        if (!target.isAvailable()) {
            r.put("success", false);
            r.put("error", "source unavailable");
            return Map.of("success", true, "message", "数据源不可用", "data", r);
        }
        if (!dataSourceManager.isAvailable(target)) {
            Map<String, Object> health = dataSourceManager.sourceHealth(target.name());
            r.put("success", false);
            r.put("status", "circuit-open");
            r.put("error", "source circuit is open");
            r.put("dataStatus", health.get("status"));
            r.put("dataStatusText", health.get("statusText"));
            return Map.of("success", true, "message", "数据源暂时跳过", "data", r);
        }
        try {
            FetchResult result = target.fetchMatches(testDate);
            long elapsed = System.currentTimeMillis() - start;
            r.put("success", result != null && result.success());
            r.put("matches", result == null ? 0 : result.matches().size());
            r.put("latencyMs", elapsed);
            r.put("error", result == null ? "null result" : result.error());
            dataSourceManager.recordResult(target, result);
            if (result != null && result.success() && !result.matches().isEmpty()) {
                List<Map<String, Object>> samples = result.matches().stream()
                        .limit(5)
                        .map(m -> {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("externalMatchId", m.externalMatchId());
                            s.put("league", m.leagueName());
                            s.put("home", m.homeTeamName());
                            s.put("away", m.awayTeamName());
                            s.put("homeScore", m.homeScore());
                            s.put("awayScore", m.awayScore());
                            s.put("status", m.status());
                            s.put("matchTime", m.matchTime() == null ? null : m.matchTime().toString());
                            return s;
                        })
                        .toList();
                r.put("samples", samples);
            }
        } catch (Exception e) {
            dataSourceManager.recordFailure(target.name(), e.getMessage());
            r.put("success", false);
            r.put("error", e.getMessage());
            r.put("latencyMs", System.currentTimeMillis() - start);
        }
        Map<String, Object> health = dataSourceManager.sourceHealth(target.name());
        r.put("dataStatus", health.get("status"));
        r.put("dataStatusText", health.get("statusText"));
        return Map.of("success", true, "message", "测试完成", "data", r);
    }
}
