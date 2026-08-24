package com.chen.football.crawler.controller;

import com.chen.football.crawler.source.DataSourceManager;
import com.chen.football.crawler.service.MatchCrawlerService;
import com.chen.football.common.util.AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据采集统计与调试接口
 */
@RestController
@RequestMapping("/api/crawler/stats")
@RequiredArgsConstructor
public class CrawlerStatsController {

    private final DataSourceManager dataSourceManager;
    private final MatchCrawlerService matchCrawlerService;

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        AdminGuard.requirePermission("CRAWLER");
        return Map.of(
                "success", true,
                "message", "获取成功",
                "data", dataSourceManager.snapshot()
        );
    }

    @GetMapping("/today-count")
    public Map<String, Object> todayCount() {
        int count = matchCrawlerService.countMatchesByDate(new java.util.Date());
        return Map.of(
                "success", true,
                "message", "获取成功",
                "data", Map.of("date", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(), "count", count)
        );
    }

    @GetMapping("/by-date")
    public Map<String, Object> byDate(@RequestParam("date") String date) {
        try {
            java.util.Date parsed = java.sql.Date.valueOf(date);
            int count = matchCrawlerService.countMatchesByDate(parsed);
            return Map.of(
                    "success", true,
                    "message", "获取成功",
                    "data", Map.of("date", date, "count", count)
            );
        } catch (Exception e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("date", date);
            data.put("error", e.getMessage());
            return Map.of("success", false, "message", "日期格式错误", "data", data);
        }
    }
}
