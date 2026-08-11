package com.chen.football.match.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.match.service.MatchService;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final MatchService matchService;

    public MatchController(MatchService matchService) {
        this.matchService = matchService;
    }

    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> today() {
        try {
            return ApiResponse.ok(matchService.getTodayFixtures().block());
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("response", Collections.emptyList(), "results", 0, "error", e.getMessage()));
        }
    }

    @GetMapping("/date/{date}")
    public ApiResponse<Map<String, Object>> byDate(@PathVariable("date") String date) {
        try {
            return ApiResponse.ok(matchService.getFixturesByDate(date).block());
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("response", Collections.emptyList(), "results", 0, "error", e.getMessage()));
        }
    }

    @GetMapping("/{fixtureId}")
    public ApiResponse<Map<String, Object>> fixture(@PathVariable("fixtureId") Long fixtureId) {
        return ApiResponse.ok(Map.of(
                "fixtureId", fixtureId,
                "message", "比赛详情功能暂不可用"
        ));
    }

    @GetMapping("/leagues")
    public ApiResponse<Map<String, Object>> leagues() {
        try {
            return ApiResponse.ok(matchService.getLeagues().block());
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("response", Collections.emptyList(), "error", e.getMessage()));
        }
    }
}
