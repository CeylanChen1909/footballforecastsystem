package com.chen.football.crawler.controller;

import com.chen.football.crawler.config.CrawlerProperties;
import com.chen.football.crawler.service.CrawlerTaskStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 爬虫健康与状态接口
 */
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
public class CrawlerHealthController {

    private final CrawlerProperties crawlerProperties;
    private final CrawlerTaskStatusService taskStatusService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", crawlerProperties.isEnabled());
        data.put("requestIntervalMs", crawlerProperties.getRequestIntervalMs());
        data.put("baseUrl", crawlerProperties.getFlashscore() == null ? null : crawlerProperties.getFlashscore().getBaseUrl());
        data.put("footballUrl", crawlerProperties.getFlashscore() == null ? null : crawlerProperties.getFlashscore().getFootballUrl());
        data.put("taskSummary", taskStatusService.snapshot().get("summary"));
        data.put("tasks", taskStatusService.snapshot().get("tasks"));
        return Map.of(
                "success", true,
                "message", "健康检查成功",
                "data", data
        );
    }

    @GetMapping("/health/{taskName}")
    public Map<String, Object> taskHealth(@org.springframework.web.bind.annotation.PathVariable String taskName) {
        return Map.of(
                "success", true,
                "message", "获取成功",
                "data", taskStatusService.getTask(taskName)
        );
    }
}
