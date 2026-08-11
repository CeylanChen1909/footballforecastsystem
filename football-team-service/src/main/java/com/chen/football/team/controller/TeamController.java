package com.chen.football.team.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.team.service.TeamService;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 球队控制器
 * 提供球队详情、联赛球队列表、球队收藏等功能
 */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    /**
     * 获取球队详情
     */
    @GetMapping("/{teamId}")
    @SentinelResource(value = "team/detail")
    public ApiResponse<Map<String, Object>> team(@PathVariable Long teamId) {
        return ApiResponse.ok(teamService.getTeam(teamId).block());
    }

    /**
     * 按联赛获取球队列表
     */
    @GetMapping("/league/{leagueId}")
    @SentinelResource(value = "team/by-league")
    public ApiResponse<Map<String, Object>> byLeague(
            @PathVariable int leagueId,
            @RequestParam(defaultValue = "2024") int season) {
        return ApiResponse.ok(teamService.getTeamsByLeague(leagueId, season).block());
    }

    /**
     * 获取球队统计信息
     */
    @GetMapping("/{teamId}/statistics")
    @SentinelResource(value = "team/statistics")
    public ApiResponse<Map<String, Object>> statistics(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "39") int leagueId,
            @RequestParam(defaultValue = "2024") int season) {
        return ApiResponse.ok(teamService.getTeamStatistics(teamId, leagueId, season).block());
    }

    /**
     * 获取球队最近比赛
     */
    @GetMapping("/{teamId}/recent")
    @SentinelResource(value = "team/recent")
    public ApiResponse<Map<String, Object>> recentMatches(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(teamService.getRecentMatches(teamId, limit).block());
    }

    /**
     * 搜索球队
     */
    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestParam String name) {
        return ApiResponse.ok(teamService.searchTeam(name).block());
    }
}
