package com.chen.football.crawler.controller;

import com.chen.football.crawler.service.CrawlerTaskStatusService;
import com.chen.football.crawler.service.CrawlerIngestionAuditService;
import com.chen.football.crawler.service.HistoricalBackfillService;
import com.chen.football.crawler.service.MatchCrawlerService;
import com.chen.football.crawler.service.StandingCrawlerService;
import com.chen.football.common.util.AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.time.LocalDate;

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
    private final HistoricalBackfillService historicalBackfillService;
    private final CrawlerIngestionAuditService ingestionAuditService;

    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam("type") String type,
                                   @RequestParam(name = "league", required = false) String league) {
        // 手动触发会消耗外部数据源额度，必须由管理员执行。
        AdminGuard.requirePermission("CRAWLER");
        String taskName = "manual:" + (type == null ? "unknown" : type.trim());
        if (!taskStatusService.tryStart(taskName)) {
            return Map.of("success", false, "message", "相同采集任务正在执行，请稍后查看任务状态");
        }
        long started = System.currentTimeMillis();
        Object result;
        try {
            switch (type) {
                case "today":
                    result = matchCrawlerService.crawlTodayMatches().size();
                    break;
                case "upcoming":
                    result = matchCrawlerService.crawlUpcomingMatches().size();
                    break;
                case "score-update", "live":
                    result = matchCrawlerService.updateMatchScores();
                    break;
                case "standings":
                    result = standingCrawlerService.crawlAllStandings().size();
                    break;
                case "league":
                    result = league == null ? 0 : matchCrawlerService.crawlMatchesByLeagueAndDate(league, new java.util.Date()).size();
                    break;
                default:
                    taskStatusService.failure(taskName, System.currentTimeMillis() - started, new IllegalArgumentException("不支持的任务类型: " + type));
                    return Map.of("success", false, "message", "不支持的任务类型: " + type);
            }
            int count = result instanceof Number number ? number.intValue() : 0;
            taskStatusService.success(taskName, System.currentTimeMillis() - started, count);
        } catch (Exception error) {
            taskStatusService.failure(taskName, System.currentTimeMillis() - started, error);
            return Map.of("success", false, "message", "采集任务失败: " + (error.getMessage() == null ? "unknown" : error.getMessage()));
        }
        return Map.of("success", true, "message", "获取成功", "data", Map.of("result", result));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        AdminGuard.requirePermission("CRAWLER");
        return Map.of("success", true, "message", "获取成功", "data", taskStatusService.snapshot());
    }

    /** 管理员查看日期范围内的来源级采集质量，而不是只看最后一次任务状态。 */
    @GetMapping("/audit/quality")
    public Map<String, Object> quality(@RequestParam(defaultValue = "bbc-scores") String source,
                                      @RequestParam String from,
                                      @RequestParam String to) {
        AdminGuard.requirePermission("CRAWLER");
        try {
            return Map.of("success", true, "data", ingestionAuditService.qualitySummary(source, from, to));
        } catch (Exception ex) {
            return Map.of("success", false, "message", "采集质量参数无效: " + ex.getMessage());
        }
    }

    /**
     * 管理员触发有限日期范围的历史回填。任务异步执行，可通过 /status 查看
     * checkpoint；resume=true 会从上次失败日期继续，重复执行仍由比赛幂等键去重。
     */
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestParam("from") String from,
                                        @RequestParam("to") String to,
                                        @RequestParam(name = "maxDays", defaultValue = "30") int maxDays,
                                        @RequestParam(name = "resume", defaultValue = "true") boolean resume) {
        AdminGuard.requirePermission("CRAWLER");
        try {
            return Map.of("success", true, "data", historicalBackfillService.start(
                    LocalDate.parse(from), LocalDate.parse(to), maxDays, resume));
        } catch (Exception ex) {
            return Map.of("success", false, "message", "历史回填参数或任务错误: " + ex.getMessage());
        }
    }

    @GetMapping("/backfill/status")
    public Map<String, Object> backfillStatus() {
        AdminGuard.requirePermission("CRAWLER");
        return Map.of("success", true, "data", historicalBackfillService.status());
    }
}
