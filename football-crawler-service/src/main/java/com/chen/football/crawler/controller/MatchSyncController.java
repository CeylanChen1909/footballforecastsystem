package com.chen.football.crawler.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.crawler.service.MatchSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/crawler/sync")
@RequiredArgsConstructor
public class MatchSyncController {

    private final MatchSyncService matchSyncService;

    @PostMapping("/today")
    public ApiResponse<Map<String, Object>> syncToday() { return ApiResponse.ok(toResponse(matchSyncService.syncToday())); }

    @PostMapping("/upcoming")
    public ApiResponse<Map<String, Object>> syncUpcoming() { return ApiResponse.ok(toResponse(matchSyncService.syncUpcoming())); }

    @PostMapping("/league/{leagueName}")
    public ApiResponse<Map<String, Object>> syncLeague(@PathVariable String leagueName,
                                                       @RequestParam(name = "date", required = false) String date) {
        Date targetDate = null;
        try {
            if (date != null && !date.isBlank()) targetDate = new java.text.SimpleDateFormat("yyyy-MM-dd").parse(date);
        } catch (Exception ignored) { targetDate = new Date(); }
        if (targetDate == null) targetDate = new Date();
        return ApiResponse.ok(toResponse(matchSyncService.syncLeague(leagueName, targetDate)));
    }

    @PostMapping("/football-data")
    public ApiResponse<Map<String, Object>> syncFootballData() { return ApiResponse.ok(toResponse(matchSyncService.syncFootballData())); }

    @PostMapping("/juhe")
    public ApiResponse<Map<String, Object>> syncJuhe() { return ApiResponse.ok(toResponse(matchSyncService.syncJuhe())); }

    @PostMapping("/crawler")
    public ApiResponse<Map<String, Object>> syncCrawler() { return ApiResponse.ok(toResponse(matchSyncService.syncCrawler())); }

    @PostMapping("/all")
    public ApiResponse<Map<String, Object>> syncAll() { return ApiResponse.ok(toResponse(matchSyncService.syncAll())); }

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
