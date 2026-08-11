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

    @Value("${sync.season:2025}")
    private int season;

    @Value("${sync.leagues:39,140,135,78,61,2}")
    private String leagues;

    @Value("${sync.cron:0 30 */6 * * ?}")
    private String cron;

    public DataSyncAdminController(DataSyncService dataSyncService) {
        this.dataSyncService = dataSyncService;
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> runSyncNow() {
        LocalDateTime start = LocalDateTime.now();
        dataSyncService.syncAllLeagues();
        LocalDateTime end = LocalDateTime.now();

        Map<String, Object> result = new HashMap<>();
        result.put("action", "sync");
        result.put("startAt", start);
        result.put("endAt", end);
        result.put("season", season);
        result.put("leagues", leagues);
        return ApiResponse.ok(result);
    }

    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> runVerifyNow() {
        LocalDateTime start = LocalDateTime.now();
        dataSyncService.verifyPredictions();
        LocalDateTime end = LocalDateTime.now();

        Map<String, Object> result = new HashMap<>();
        result.put("action", "verify");
        result.put("startAt", start);
        result.put("endAt", end);
        return ApiResponse.ok(result);
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("service", "football-data-sync-service");
        result.put("serverTime", LocalDateTime.now());
        result.put("season", season);
        result.put("leagues", leagues);
        result.put("cron", cron);
        return ApiResponse.ok(result);
    }
}
