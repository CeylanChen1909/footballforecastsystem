package com.chen.football.crawler.task;

import com.chen.football.common.config.CrawlerTaskConfig;
import com.chen.football.common.service.DistributedLockService;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.service.CrawlerTaskStatusService;
import com.chen.football.crawler.service.MatchCrawlerService;
import com.chen.football.crawler.service.StandingCrawlerService;
import com.chen.football.prediction.service.MatchPredictionPrecomputeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;


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
    private final MatchPredictionPrecomputeService predictionPrecomputeService;
    private final DistributedLockService distributedLockService;

    public CrawlerTask(MatchCrawlerService matchCrawlerService,
                       StandingCrawlerService standingCrawlerService,
                       CrawlerTaskStatusService taskStatusService,
                       CrawlerTaskConfig taskConfig,
                       MatchPredictionPrecomputeService predictionPrecomputeService,
                       DistributedLockService distributedLockService) {
        this.matchCrawlerService = matchCrawlerService;
        this.standingCrawlerService = standingCrawlerService;
        this.taskStatusService = taskStatusService;
        this.taskConfig = taskConfig;
        this.predictionPrecomputeService = predictionPrecomputeService;
        this.distributedLockService = distributedLockService;
    }

    /**
     * 服务启动时主动补采一次时间窗口，避免错过凌晨 3 点的定时任务。
     * 异步执行，避免第三方接口较慢时阻塞应用就绪事件。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupMatchWindow() {
        if (!taskConfig.isEnabled() || !taskConfig.isStartupWarmupEnabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            String lockToken = distributedLockService.tryLock("crawler:startup-warmup", Duration.ofMinutes(30));
            if (lockToken == null) {
                log.info("定时任务: 启动补采已有实例执行，跳过本次触发");
                return;
            }
            log.info("定时任务: 服务启动，开始补采今日及未来 7 天比赛");
            try {
                List<CrawlerMatch> matches = matchCrawlerService.crawlUpcomingMatches();
                predictionPrecomputeService.schedule(matches);
                // 积分榜不再依赖凌晨定时任务；新实例启动后也要尽快建立当前赛季快照。
                runStandingsTask("startupStandings");
            } finally {
                distributedLockService.unlock("crawler:startup-warmup", lockToken);
            }
        }).exceptionally(error -> {
            log.error("定时任务: 服务启动补采失败", error);
            return null;
        });
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
        String lockToken = distributedLockService.tryLock("crawler:" + taskName, Duration.ofMinutes(15));
        if (lockToken == null) return;
        if (!taskStatusService.tryStart(taskName)) {
            log.warn("定时任务: {} 正在执行中，跳过本次触发", taskName);
            distributedLockService.unlock("crawler:" + taskName, lockToken);
            return;
        }
        try {
            log.info("定时任务: 开始爬取今日比赛");
            List<CrawlerMatch> matches = matchCrawlerService.crawlTodayMatches();
            predictionPrecomputeService.schedule(matches);
            int count = matches.size();
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.success(taskName, elapsed, count);
            log.info("定时任务: 今日比赛爬取完成，耗时 {} ms，处理 {} 条", elapsed, count);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.failure(taskName, elapsed, e);
            log.error("定时任务: 爬取今日比赛失败", e);
        } finally {
            distributedLockService.unlock("crawler:" + taskName, lockToken);
        }
    }

    /**
     * 每 5 分钟刷新一次主爬虫比分；同时覆盖昨日收尾赛事。
     */
    @Scheduled(fixedRateString = "${crawler.task.score-update-fixed-rate-ms:300000}")
    public void updateMatchScores() {
        runScoreTask("updateMatchScores", matchCrawlerService::updateMatchScores);
    }

    /**
     * 低频回补最近几天的赛果修正，避免把 3 天窗口放进每 5 分钟的实时任务，
     * 既减少 BBC 请求，也能覆盖赛后延迟修正。
     */
    @Scheduled(fixedDelayString = "${crawler.task.score-correction-fixed-delay-ms:1800000}",
            initialDelayString = "${crawler.task.score-correction-initial-delay-ms:180000}")
    public void backfillScoreCorrections() {
        runScoreTask("backfillScoreCorrections", matchCrawlerService::backfillRecentScoreCorrections);
    }

    private void runScoreTask(String taskName, Supplier<Integer> action) {
        if (!taskConfig.isEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        String lockToken = distributedLockService.tryLock("crawler:" + taskName, Duration.ofMinutes(30));
        if (lockToken == null) return;
        if (!taskStatusService.tryStart(taskName)) {
            log.warn("定时任务: {} 正在执行中，跳过本次触发", taskName);
            distributedLockService.unlock("crawler:" + taskName, lockToken);
            return;
        }
        try {
            log.info("定时任务: 开始更新比赛比分，task={}", taskName);
            int updated = action.get();
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.success(taskName, elapsed, updated);
            log.info("定时任务: 比赛比分更新完成，task={}，耗时 {} ms，更新 {} 场", taskName, elapsed, updated);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.failure(taskName, elapsed, e);
            log.error("定时任务: 更新比赛比分失败，task={}", taskName, e);
        } finally {
            distributedLockService.unlock("crawler:" + taskName, lockToken);
        }
    }

    /**
     * 每天凌晨2点爬取所有联赛积分榜
     */
    @Scheduled(cron = "${crawler.task.standings-cron:0 0 2 * * ?}",
            zone = "${crawler.task.time-zone:Asia/Shanghai}")
    public void crawlStandings() {
        runStandingsTask("crawlStandings");
    }

    /**
     * 比赛结束后积分榜应在当天内更新，而不是一直等到次日凌晨。
     * 与凌晨任务共用分布式锁，避免两个实例同时请求 BBC。
     */
    @Scheduled(fixedRateString = "${crawler.task.standings-fixed-rate-ms:1800000}",
            initialDelayString = "${crawler.task.standings-startup-delay-ms:1800000}")
    public void refreshStandingsPeriodically() {
        runStandingsTask("refreshStandingsPeriodically");
    }

    private void runStandingsTask(String taskName) {
        if (!taskConfig.isEnabled()) return;
        long start = System.currentTimeMillis();
        String lockToken = distributedLockService.tryLock("crawler:standings", Duration.ofMinutes(30));
        if (lockToken == null) return;
        if (!taskStatusService.tryStart(taskName)) {
            log.warn("定时任务: {} 正在执行中，跳过本次触发", taskName);
            distributedLockService.unlock("crawler:standings", lockToken);
            return;
        }
        try {
            log.info("定时任务: 开始爬取积分榜，task={}", taskName);
            int count = standingCrawlerService.crawlAllStandings().size();
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.success(taskName, elapsed, count);
            log.info("定时任务: 积分榜爬取完成，task={}，耗时 {} ms，处理 {} 条", taskName, elapsed, count);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.failure(taskName, elapsed, e);
            log.error("定时任务: 爬取积分榜失败，task={}", taskName, e);
        } finally {
            distributedLockService.unlock("crawler:standings", lockToken);
        }
    }

    /**
     * 每天凌晨3点爬取今日及未来7天比赛
     */
    @Scheduled(cron = "${crawler.task.upcoming-cron:0 0 3 * * ?}",
            zone = "${crawler.task.time-zone:Asia/Shanghai}")
    public void crawlUpcomingMatches() {
        runUpcomingTask("crawlUpcomingMatches");
    }

    /**
     * 赛程在比赛日之外也会临时公布或调整。每天一次会造成最长 24 小时的空窗，
     * 因此增加日内补采；与凌晨任务共用同一把锁，不会重复并发抓取。
     */
    @Scheduled(fixedDelayString = "${crawler.task.upcoming-fixed-delay-ms:21600000}",
            initialDelayString = "${crawler.task.upcoming-startup-delay-ms:3600000}")
    public void refreshUpcomingMatchesPeriodically() {
        runUpcomingTask("refreshUpcomingMatchesPeriodically");
    }

    private void runUpcomingTask(String taskName) {
        if (!taskConfig.isEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        String lockToken = distributedLockService.tryLock("crawler:upcoming-window", Duration.ofHours(2));
        if (lockToken == null) return;
        if (!taskStatusService.tryStart(taskName)) {
            log.warn("定时任务: {} 正在执行中，跳过本次触发", taskName);
            distributedLockService.unlock("crawler:upcoming-window", lockToken);
            return;
        }
        try {
            log.info("定时任务: 开始爬取近期比赛");
            List<CrawlerMatch> matches = matchCrawlerService.crawlUpcomingMatches();
            predictionPrecomputeService.schedule(matches);
            int count = matches.size();
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.success(taskName, elapsed, count);
            log.info("定时任务: 近期比赛爬取完成，耗时 {} ms，处理 {} 条", elapsed, count);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskStatusService.failure(taskName, elapsed, e);
            log.error("定时任务: 爬取近期比赛失败", e);
        } finally {
            distributedLockService.unlock("crawler:upcoming-window", lockToken);
        }
    }
}
