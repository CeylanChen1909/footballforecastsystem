package com.chen.football.team.service;

import com.chen.football.common.service.RedisCacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 球队服务（纯爬虫模式）
 */
@Service
public class TeamService {

    private final RedisCacheService cacheService;
    private final WebClient crawlerWebClient;

    public TeamService(RedisCacheService cacheService,
                       WebClient.Builder webClientBuilder,
                       @Value("${crawler.base-url:http://127.0.0.1:9009}") String crawlerBaseUrl) {
        this.cacheService = cacheService;
        this.crawlerWebClient = webClientBuilder.baseUrl(crawlerBaseUrl).build();
    }

    public Mono<Map<String, Object>> getTeam(long teamId) {
        String key = "team:" + teamId;
        Map<String, Object> cached = cacheService.get(key, Map.class);
        if (cached != null) {
            return Mono.just(cached);
        }

        return searchAllByName("")
                .map(raw -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> teams = (List<Map<String, Object>>) raw.getOrDefault("response", List.of());
                    Map<String, Object> hit = teams.stream()
                            .filter(t -> String.valueOf(t.get("id")).equals(String.valueOf(teamId)))
                            .findFirst()
                            .orElse(Map.of());
                    cacheService.set(key, hit, 600);
                    return hit;
                });
    }

    public Mono<Map<String, Object>> getTeamsByLeague(int league, int season) {
        String leagueName = toLeagueName(league);
        String key = "teams:league:" + league + ":" + season;
        Map<String, Object> cached = cacheService.get(key, Map.class);
        if (cached != null) {
            return Mono.just(cached);
        }

        String endpoint = "/api/crawler/teams/league/" + leagueName;
        return Mono.justOrEmpty(getCrawlerData(endpoint))
                .switchIfEmpty(Mono.just(Map.of("response", Collections.emptyList(), "results", 0)))
                .map(raw -> {
                    cacheService.set(key, raw, 600);
                    return raw;
                });
    }

    public Mono<Map<String, Object>> getTeamStatistics(long teamId, int league, int season) {
        // 纯爬虫模式下先返回结构化占位数据，避免前端报错
        Map<String, Object> data = new HashMap<>();
        data.put("teamId", teamId);
        data.put("leagueId", league);
        data.put("season", season);
        data.put("source", "crawler");
        data.put("note", "统计数据由爬虫持续补充中");
        data.put("response", Map.of());
        return Mono.just(data);
    }

    public Mono<Map<String, Object>> getRecentMatches(long teamId, int limit) {
        return Mono.justOrEmpty(getCrawlerData("/api/crawler/matches/db/today"))
                .switchIfEmpty(Mono.just(Map.of("response", Collections.emptyList(), "results", 0)))
                .map(raw -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> matches = (List<Map<String, Object>>) raw.getOrDefault("response", List.of());
                    List<Map<String, Object>> filtered = matches.stream().filter(m -> {
                        Object teamsObj = m.get("teams");
                        if (!(teamsObj instanceof Map<?, ?> teams)) return false;
                        Object homeObj = teams.get("home");
                        Object awayObj = teams.get("away");
                        String tid = String.valueOf(teamId);
                        boolean isHome = homeObj instanceof Map<?, ?> home && tid.equals(String.valueOf(home.get("id")));
                        boolean isAway = awayObj instanceof Map<?, ?> away && tid.equals(String.valueOf(away.get("id")));
                        return isHome || isAway;
                    }).limit(limit).toList();
                    return Map.of("response", filtered, "results", filtered.size());
                });
    }

    public Mono<Map<String, Object>> searchTeam(String name) {
        String key = "team:search:" + name.hashCode();
        Map<String, Object> cached = cacheService.get(key, Map.class);
        if (cached != null) {
            return Mono.just(cached);
        }

        return searchAllByName(name)
                .map(raw -> {
                    cacheService.set(key, raw, 600);
                    return raw;
                });
    }

    private Mono<Map<String, Object>> searchAllByName(String name) {
        String endpoint = "/api/crawler/teams/search?name=" + name;
        return Mono.justOrEmpty(getCrawlerData(endpoint))
                .switchIfEmpty(Mono.just(Map.of("response", Collections.emptyList(), "results", 0)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCrawlerData(String endpoint) {
        try {
            Map<String, Object> result = crawlerWebClient.get()
                    .uri(endpoint)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(12))
                    .block();
            if (result != null && Boolean.TRUE.equals(result.get("success")) && result.get("data") instanceof Map<?, ?> data) {
                return (Map<String, Object>) data;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String toLeagueName(int leagueId) {
        return switch (leagueId) {
            case 39 -> "英超";
            case 140 -> "西甲";
            case 135 -> "意甲";
            case 78 -> "德甲";
            case 61 -> "法甲";
            case 2 -> "欧冠";
            default -> "英超";
        };
    }
}
