package com.chen.football.datasync.scheduler;

import com.chen.football.datasync.service.DataSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataSyncScheduler.class);

    private final DataSyncService dataSyncService;

    public DataSyncScheduler(DataSyncService dataSyncService) {
        this.dataSyncService = dataSyncService;
    }

    /** 服务启动后先用当前爬虫赛果回填特征，不等待外部 API 的六小时任务。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupFeatureSync() {
        try {
            dataSyncService.syncCrawlerFeatures();
        } catch (Exception e) {
            log.warn("[Scheduler] Startup feature sync failed: {}", e.getMessage());
        }
    }

    /**
     * 每6小时同步一次比赛和球队数据
     */
    @Scheduled(cron = "${sync.cron:0 30 */6 * * ?}", zone = "Asia/Shanghai")
    public void scheduledSync() {
        log.info("[Scheduler] Starting scheduled data sync");
        try {
            dataSyncService.syncAllLeagues();
            log.info("[Scheduler] Scheduled data sync completed");
        } catch (Exception e) {
            log.error("[Scheduler] Scheduled sync failed: {}", e.getMessage(), e);
        }
    }

    /** 爬虫每 5 分钟刷新赛程，预测特征也同步刷新，避免只等六小时全量任务。 */
    @Scheduled(fixedDelayString = "${sync.feature-fixed-delay-ms:300000}")
    public void scheduledFeatureSync() {
        try {
            dataSyncService.syncCrawlerFeatures();
        } catch (Exception e) {
            log.warn("[Scheduler] Periodic feature sync failed: {}", e.getMessage());
        }
    }

    /**
     * 比赛结束后尽快验证预测准确性。只在凌晨跑一次会让用户在赛后很久仍看到“待验证”，
     * 因此改为固定间隔任务，并由 DataSyncService 内部互斥，避免和同步任务并发写入。
     */
    @Scheduled(
            fixedDelayString = "${sync.verify-fixed-delay-ms:900000}",
            initialDelayString = "${sync.verify-initial-delay-ms:120000}"
    )
    public void scheduledVerify() {
        log.info("[Scheduler] Starting scheduled prediction verification");
        try {
            dataSyncService.verifyPredictions();
            log.info("[Scheduler] Prediction verification completed");
        } catch (Exception e) {
            log.error("[Scheduler] Prediction verification failed: {}", e.getMessage(), e);
        }
    }
}
