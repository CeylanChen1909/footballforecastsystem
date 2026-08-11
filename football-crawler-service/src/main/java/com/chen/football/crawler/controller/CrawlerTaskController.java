package com.chen.football.crawler.controller;

import com.chen.football.crawler.service.CrawlerTaskStatusService;
import com.chen.football.crawler.service.MatchCrawlerService;
import com.chen.football.crawler.service.StandingCrawlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 爬虫任务控制接口
 */
@RestController
@RequestMapping("/api/crawler/task")
@RequiredArgsConstructor
public class CrawlerTaskController {

    private final MatchCrawlerService matchCrawlerService;
    private final StandingCrawlerService standingCrawlerService;
    private final CrawlerTaskStatusService taskStatusService;

    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam("type") String type,
                                   @RequestParam(name = "league", required = false) String league) {
        Object result;
        switch (type) {
            case "today":
                result = matchCrawlerService.crawlTodayMatches().size();
                break;
            case "upcoming":
                result = matchCrawlerService.crawlUpcomingMatches().size();
                break;
            case "standings":
                result = standingCrawlerService.crawlAllStandings().size();
                break;
            case "league":
                result = league == null ? 0 : matchCrawlerService.crawlMatchesByLeagueAndDate(league, new java.util.Date()).size();
                break;
            default:
                return Map.of("success", false, "message", "不支持的任务类型: " + type);
        }
        return Map.of("success", true, "message", "获取成功", "data", Map.of("result", result));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("success", true, "message", "获取成功", "data", taskStatusService.snapshot());
    }
}
