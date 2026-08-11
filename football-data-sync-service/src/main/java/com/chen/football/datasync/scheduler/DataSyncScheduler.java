package com.chen.football.datasync.scheduler;

import com.chen.football.datasync.service.DataSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DataSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataSyncScheduler.class);

    private final DataSyncService dataSyncService;

    public DataSyncScheduler(DataSyncService dataSyncService) {
        this.dataSyncService = dataSyncService;
    }

    /**
     * 每6小时同步一次比赛和球队数据
     */
    @Scheduled(cron = "${sync.cron:0 30 */6 * * ?}")
    public void scheduledSync() {
        log.info("[Scheduler] Starting scheduled data sync");
        try {
            dataSyncService.syncAllLeagues();
            log.info("[Scheduler] Scheduled data sync completed");
        } catch (Exception e) {
            log.error("[Scheduler] Scheduled sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 每天凌晨2点验证预测准确性
     */
    @Scheduled(cron = "0 0 2 * * ?")
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