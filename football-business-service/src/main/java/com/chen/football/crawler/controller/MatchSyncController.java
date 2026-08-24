package com.chen.football.crawler.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.crawler.service.MatchSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

@RestController
@RequestMapping("/api/crawler/sync")
@RequiredArgsConstructor
public class MatchSyncController {

    private final MatchSyncService matchSyncService;

    @PostMapping("/today")
    public ApiResponse<Map<String, Object>> syncToday() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(toResponse(matchSyncService.syncToday()));
    }

    @PostMapping("/upcoming")
    public ApiResponse<Map<String, Object>> syncUpcoming() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(toResponse(matchSyncService.syncUpcoming()));
    }

    @PostMapping("/league/{leagueName}")
    public ApiResponse<Map<String, Object>> syncLeague(@PathVariable String leagueName,
                                                       @RequestParam(name = "date", required = false) String date) {
        AdminGuard.requireAdmin();
        Date targetDate = null;
        try {
            if (date != null && !date.isBlank()) {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd");
                format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                targetDate = format.parse(date);
            }
        } catch (Exception e) { targetDate = new Date(); }
        if (targetDate == null) targetDate = new Date();
        return ApiResponse.ok(toResponse(matchSyncService.syncLeague(leagueName, targetDate)));
    }

    @PostMapping("/football-data")
    public ApiResponse<Map<String, Object>> syncFootballData() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(toResponse(matchSyncService.syncFootballData()));
    }

    @PostMapping("/juhe")
    public ApiResponse<Map<String, Object>> syncJuhe() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(toResponse(matchSyncService.syncJuhe()));
    }

    @PostMapping("/crawler")
    public ApiResponse<Map<String, Object>> syncCrawler() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(toResponse(matchSyncService.syncCrawler()));
    }

    @PostMapping("/all")
    public ApiResponse<Map<String, Object>> syncAll() {
        AdminGuard.requireSuperAdmin();
        return ApiResponse.ok(toResponse(matchSyncService.syncAll()));
    }

    private Map<String, Object> toResponse(MatchSyncService.SyncReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", report.source());
        data.put("total", report.total());
        data.put("inserted", report.inserted());
        data.put("updated", report.updated());
        data.put("syncedAt", report.syncedAt());
        data.put("sources", report.sources());
        data.put("summary", report.toSummaryLine());
        return data;
    }
}
