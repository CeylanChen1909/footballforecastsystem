package com.chen.football.crawler.task;

import com.chen.football.crawler.config.CrawlerTaskConfig;
import com.chen.football.crawler.service.CrawlerTaskStatusService;
import com.chen.football.crawler.service.MatchCrawlerService;
import com.chen.football.crawler.service.StandingCrawlerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 定时爬取任务
 */
@Slf4j
@Component
public class CrawlerTask {

    private final MatchCrawlerService matchCrawlerService;
    private final StandingCrawlerService standingCrawlerService;
    private final CrawlerTaskStatusService taskStatusService;
    private final CrawlerTaskConfig taskConfig;

    public CrawlerTask(MatchCrawlerService matchCrawlerService,
                       StandingCrawlerService standingCrawlerService,
                       CrawlerTaskStatusService taskStatusService,
                       CrawlerTaskConfig taskConfig) {
        this.matchCrawlerService = matchCrawlerService;
        this.standingCrawlerService = standingCrawlerService;
        this.taskStatusService = taskStatusService;
        this.taskConfig = taskConfig;
    }

    /**
     * 每5分钟爬取一次今日比赛
     */
    @Scheduled(fixedRateString = "${crawler.task.today-fixed-rate-ms:300000}")
    public void crawlTodayMatches() {
        if (!taskConfig.isEnabled()) {
            return;
        }
        String taskName = "crawlTodayMatches";
        long start = System.currentTimeMillis();
        if (!taskStatusService.tryStart(taskName)) {
            log.warn("定时任务: {} 正在执行中，跳过本次触发", taskName);
            return;
        }
        try {
            log.info("定时任务: 开始爬取今日比赛");
            int count = matchCrawlerService.crawlTodayMatches().size();
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.success(taskName, elapsed, count);
            log.info("定时任务: 今日比赛爬取完成，耗时 {} ms，处理 {} 条", elapsed, count);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.failure(taskName, elapsed, e);
            log.error("定时任务: 爬取今日比赛失败", e);
        }
    }

    /**
     * 每小时更新一次比赛比分
     */
    @Scheduled(fixedRateString = "${crawler.task.score-update-fixed-rate-ms:3600000}")
    public void updateMatchScores() {
        if (!taskConfig.isEnabled()) {
            return;
        }
        String taskName = "updateMatchScores";
        long start = System.currentTimeMillis();
        if (!taskStatusService.tryStart(taskName)) {
            log.warn("定时任务: {} 正在执行中，跳过本次触发", taskName);
            return;
        }
        try {
            log.info("定时任务: 开始更新比赛比分");
            matchCrawlerService.updateMatchScores();
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.success(taskName, elapsed, 0);
            log.info("定时任务: 比赛比分更新完成，耗时 {} ms", elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.failure(taskName, elapsed, e);
            log.error("定时任务: 更新比赛比分失败", e);
        }
    }

    /**
     * 每天凌晨2点爬取所有联赛积分榜
     */
    @Scheduled(cron = "${crawler.task.standings-cron:0 0 2 * * ?}")
    public void crawlStandings() {
        if (!taskConfig.isEnabled()) {
            return;
        }
        String taskName = "crawlStandings";
        long start = System.currentTimeMillis();
        if (!taskStatusService.tryStart(taskName)) {
            log.warn("定时任务: {} 正在执行中，跳过本次触发", taskName);
            return;
        }
        try {
            log.info("定时任务: 开始爬取积分榜");
            int count = standingCrawlerService.crawlAllStandings().size();
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.success(taskName, elapsed, count);
            log.info("定时任务: 积分榜爬取完成，耗时 {} ms，处理 {} 条", elapsed, count);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.failure(taskName, elapsed, e);
            log.error("定时任务: 爬取积分榜失败", e);
        }
    }

    /**
     * 每天凌晨3点爬取近期7天比赛
     */
    @Scheduled(cron = "${crawler.task.upcoming-cron:0 0 3 * * ?}")
    public void crawlUpcomingMatches() {
        if (!taskConfig.isEnabled()) {
            return;
        }
        String taskName = "crawlUpcomingMatches";
        long start = System.currentTimeMillis();
        if (!taskStatusService.tryStart(taskName)) {
            log.warn("定时任务: {} 正在执行中，跳过本次触发", taskName);
            return;
        }
        try {
            log.info("定时任务: 开始爬取近期比赛");
            int count = matchCrawlerService.crawlUpcomingMatches().size();
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.success(taskName, elapsed, count);
            log.info("定时任务: 近期比赛爬取完成，耗时 {} ms，处理 {} 条", elapsed, count);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.failure(taskName, elapsed, e);
            log.error("定时任务: 爬取近期比赛失败", e);
        }
    }
}
