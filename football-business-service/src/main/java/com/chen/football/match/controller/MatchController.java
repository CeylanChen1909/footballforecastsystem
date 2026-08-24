package com.chen.football.match.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.dto.FixtureDetailResponse;
import com.chen.football.common.dto.FixtureListResponse;
import com.chen.football.common.dto.LeagueListResponse;
import com.chen.football.match.service.MatchService;
import com.chen.football.match.service.MatchDetailsService;
import com.chen.football.prediction.service.PrematchFeatureService;
import com.chen.football.common.util.AdminGuard;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final MatchService matchService;
    private final MatchDetailsService matchDetailsService;
    private final PrematchFeatureService prematchFeatureService;

    public MatchController(MatchService matchService, MatchDetailsService matchDetailsService,
                           PrematchFeatureService prematchFeatureService) {
        this.matchService = matchService;
        this.matchDetailsService = matchDetailsService;
        this.prematchFeatureService = prematchFeatureService;
    }

    @GetMapping("/today")
    @SentinelResource(value = "match/today")
    public ApiResponse<FixtureListResponse> today() {
        try {
            return ApiResponse.ok(matchService.getTodayFixtures().block());
        } catch (Exception e) {
            return ApiResponse.ok(FixtureListResponse.empty(e.getMessage()));
        }
    }

    @GetMapping("/date/{date}")
    @SentinelResource(value = "match/by-date")
    public ApiResponse<FixtureListResponse> byDate(@PathVariable("date") String date) {
        try {
            return ApiResponse.ok(matchService.getFixturesByDate(date).block());
        } catch (Exception e) {
            return ApiResponse.ok(FixtureListResponse.empty(e.getMessage()));
        }
    }

    @GetMapping("/{fixtureId}")
    @SentinelResource(value = "match/detail")
    public ApiResponse<FixtureDetailResponse> fixture(@PathVariable("fixtureId") Long fixtureId) {
        try {
            return ApiResponse.ok(matchService.getFixtureDetail(fixtureId).block());
        } catch (Exception e) {
            return ApiResponse.ok(FixtureDetailResponse.empty(fixtureId, e.getMessage()));
        }
    }

    /** 统一赛事详情：事件、阵容、技术统计和球员表现。 */
    @GetMapping("/{fixtureId}/details")
    @SentinelResource(value = "match/details")
    public ApiResponse<Map<String, Object>> details(@PathVariable("fixtureId") Long fixtureId,
                                                    @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        if (refresh) AdminGuard.requireLogin();
        return ApiResponse.ok(refresh ? matchDetailsService.refresh(fixtureId)
                : matchDetailsService.getDetails(fixtureId, false));
    }

    /** 允许用户在比赛详情页主动刷新外部赛事内容。 */
    @PostMapping("/{fixtureId}/details/refresh")
    @SentinelResource(value = "match/details-refresh")
    public ApiResponse<Map<String, Object>> refreshDetails(@PathVariable("fixtureId") Long fixtureId) {
        AdminGuard.requireLogin();
        return ApiResponse.ok(matchDetailsService.refresh(fixtureId));
    }

    /**
     * 统一的赛前增强视图：详情缓存中的伤停/首发，加上不含目标赛后数据的
     * 历史 xG 特征快照。默认只读缓存，不在页面请求中消耗外部 API 额度。
     */
    @GetMapping("/{fixtureId}/prematch-data")
    @SentinelResource(value = "match/prematch-data")
    public ApiResponse<Map<String, Object>> prematchData(@PathVariable("fixtureId") Long fixtureId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        Map<String, Object> details = matchDetailsService.getDetails(fixtureId, false);
        result.put("fixtureId", fixtureId);
        result.put("details", details);
        result.put("featureSnapshot", prematchFeatureService.getSnapshot(fixtureId));
        result.put("source", "API-Football 赛前详情缓存 + Understat 历史 xG");
        result.put("readOnlyCache", true);
        return ApiResponse.ok(result);
    }

    @GetMapping("/leagues")
    public ApiResponse<LeagueListResponse> leagues() {
        try {
            return ApiResponse.ok(matchService.getLeagues().block());
        } catch (Exception e) {
            return ApiResponse.ok(new LeagueListResponse(java.util.List.of(), 0));
        }
    }
}
