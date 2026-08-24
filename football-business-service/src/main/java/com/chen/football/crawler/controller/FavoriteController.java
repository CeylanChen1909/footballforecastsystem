package com.chen.football.crawler.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.exception.BusinessException;
import com.chen.football.crawler.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    private Long currentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException("未登录或登录已过期");
        }
        return userId;
    }

    @GetMapping
    public ApiResponse<?> listTeams() {
        return ApiResponse.ok(favoriteService.listTeams(currentUserId()));
    }

    @PostMapping
    public ApiResponse<?> addTeam(@RequestBody Map<String, Object> body) {
        String teamId = body.get("teamId") == null ? "" : String.valueOf(body.get("teamId")).trim();
        String teamName = body.get("teamName") == null ? "" : String.valueOf(body.get("teamName")).trim();
        if (teamId.isBlank()) {
            throw new BusinessException("teamId 不能为空");
        }
        favoriteService.addTeam(currentUserId(), teamId, teamName);
        return ApiResponse.ok(Map.of("teamId", teamId, "teamName", teamName));
    }

    @DeleteMapping("/{teamId}")
    public ApiResponse<?> removeTeam(@PathVariable String teamId) {
        if (teamId == null || teamId.isBlank()) {
            throw new BusinessException("teamId 不能为空");
        }
        favoriteService.removeTeam(currentUserId(), teamId);
        return ApiResponse.ok(Map.of("teamId", teamId));
    }

    @GetMapping("/matches")
    public ApiResponse<?> listMatches() {
        return ApiResponse.ok(favoriteService.listMatches(currentUserId()));
    }

    @PostMapping("/matches")
    public ApiResponse<?> addMatch(@RequestBody Map<String, Object> body) {
        String fixtureId = body.get("fixtureId") == null ? "" : String.valueOf(body.get("fixtureId")).trim();
        String label = body.get("matchLabel") == null ? "" : String.valueOf(body.get("matchLabel")).trim();
        String leagueName = body.get("leagueName") == null ? "" : String.valueOf(body.get("leagueName")).trim();
        String matchTime = body.get("matchTime") == null ? "" : String.valueOf(body.get("matchTime")).trim();
        if (fixtureId.isBlank()) {
            throw new BusinessException("fixtureId 不能为空");
        }
        favoriteService.addMatch(currentUserId(), fixtureId, label, leagueName, matchTime);
        return ApiResponse.ok(Map.of("fixtureId", fixtureId, "matchLabel", label));
    }

    @DeleteMapping("/matches/{fixtureId}")
    public ApiResponse<?> removeMatch(@PathVariable String fixtureId) {
        if (fixtureId == null || fixtureId.isBlank()) {
            throw new BusinessException("fixtureId 不能为空");
        }
        favoriteService.removeMatch(currentUserId(), fixtureId);
        return ApiResponse.ok(Map.of("fixtureId", fixtureId));
    }
}
