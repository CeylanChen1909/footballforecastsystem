package com.chen.football.crawler.controller;

import com.chen.football.common.dto.ApiResponse;
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
        return 1L;
    }

    @GetMapping
    public ApiResponse<?> listTeams() {
        return ApiResponse.ok(favoriteService.listTeams(currentUserId()));
    }

    @PostMapping
    public ApiResponse<?> addTeam(@RequestBody Map<String, Object> body) {
        String teamId = String.valueOf(body.get("teamId"));
        String teamName = body.get("teamName") == null ? "" : String.valueOf(body.get("teamName"));
        favoriteService.addTeam(currentUserId(), teamId, teamName);
        return ApiResponse.ok(Map.of("teamId", teamId, "teamName", teamName));
    }

    @DeleteMapping("/{teamId}")
    public ApiResponse<?> removeTeam(@PathVariable String teamId) {
        favoriteService.removeTeam(currentUserId(), teamId);
        return ApiResponse.ok(Map.of("teamId", teamId));
    }

    @GetMapping("/matches")
    public ApiResponse<?> listMatches() {
        return ApiResponse.ok(favoriteService.listMatches(currentUserId()));
    }

    @PostMapping("/matches")
    public ApiResponse<?> addMatch(@RequestBody Map<String, Object> body) {
        String fixtureId = String.valueOf(body.get("fixtureId"));
        String label = body.get("matchLabel") == null ? "" : String.valueOf(body.get("matchLabel"));
        favoriteService.addMatch(currentUserId(), fixtureId, label);
        return ApiResponse.ok(Map.of("fixtureId", fixtureId, "matchLabel", label));
    }

    @DeleteMapping("/matches/{fixtureId}")
    public ApiResponse<?> removeMatch(@PathVariable String fixtureId) {
        favoriteService.removeMatch(currentUserId(), fixtureId);
        return ApiResponse.ok(Map.of("fixtureId", fixtureId));
    }
}
