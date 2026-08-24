package com.chen.football.datasync.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.datasync.service.DataSyncService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class DataSyncAdminController {

    private final DataSyncService dataSyncService;

    @Value("${sync.season:0}")
    private int configuredSeason;

    @Value("${sync.leagues:39,140,135,78,61,2}")
    private String leagues;

    @Value("${sync.cron:0 30 */6 * * ?}")
    private String cron;

    public DataSyncAdminController(DataSyncService dataSyncService, com.chen.football.common.service.AdminAuditService auditService) {
        this.dataSyncService = dataSyncService;
        this.auditService = auditService;
    }

    private final com.chen.football.common.service.AdminAuditService auditService;

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> runSyncNow() {
        com.chen.football.common.util.AdminGuard.requireSuperAdmin();
        LocalDateTime start = LocalDateTime.now();
        dataSyncService.syncAllLeagues();
        LocalDateTime end = LocalDateTime.now();

        Map<String, Object> result = new HashMap<>();
        result.put("action", "sync");
        result.put("startAt", start);
        result.put("endAt", end);
        result.put("season", resolveSeason());
        result.put("leagues", leagues);
        auditService.record("SYNC", "RUN", "sync", "all-leagues", null, "SUCCESS");
        return ApiResponse.ok(result);
    }

    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> runVerifyNow() {
        com.chen.football.common.util.AdminGuard.requireSuperAdmin();
        LocalDateTime start = LocalDateTime.now();
        dataSyncService.verifyPredictions();
        LocalDateTime end = LocalDateTime.now();

        Map<String, Object> result = new HashMap<>();
        result.put("action", "verify");
        result.put("startAt", start);
        result.put("endAt", end);
        auditService.record("SYNC", "VERIFY", "prediction", "all", null, "SUCCESS");
        return ApiResponse.ok(result);
    }

    /** 仅从当前 crawler_matches 回填预测所需的球队近期战绩/ELO，不依赖外部 API 配额。 */
    @PostMapping("/features")
    public ApiResponse<Map<String, Object>> runFeatureSyncNow() {
        com.chen.football.common.util.AdminGuard.requirePermission("DATASYNC");
        LocalDateTime start = LocalDateTime.now();
        dataSyncService.syncCrawlerFeatures();
        LocalDateTime end = LocalDateTime.now();
        Map<String, Object> result = new HashMap<>();
        result.put("action", "feature-sync");
        result.put("startAt", start);
        result.put("endAt", end);
        auditService.record("SYNC", "FEATURES", "prediction-feature", "crawler_matches", null, "SUCCESS");
        return ApiResponse.ok(result);
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("service", "football-data-sync-service");
        result.put("serverTime", LocalDateTime.now());
        result.put("season", resolveSeason());
        result.put("leagues", leagues);
        result.put("cron", cron);
        return ApiResponse.ok(result);
    }

    private int resolveSeason() {
        return configuredSeason > 0 ? configuredSeason : java.time.Year.now().getValue();
    }
}
