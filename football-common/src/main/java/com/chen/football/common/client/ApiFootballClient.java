package com.chen.football.common.client;

import com.chen.football.common.config.ApiFootballProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * API-Football API 客户端
 * 文档: https://www.api-football.com/documentation-v3
 */
@Component
public class ApiFootballClient {

    private final WebClient webClient;

    public ApiFootballClient(ApiFootballProperties props) {
        this.webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("x-apisports-key", props.getApiKey())
                .build();
    }

    private <T> Mono<T> get(String path, Map<String, Object> params, Class<T> clazz) {
        MultiValueMap<String, String> queryParams = toMultiValueMap(params);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(queryParams).build())
                .retrieve()
                .bodyToMono(clazz)
                .timeout(Duration.ofSeconds(15));
    }

    private <T> Mono<T> get(String path, Map<String, Object> params, ParameterizedTypeReference<T> typeRef) {
        MultiValueMap<String, String> queryParams = toMultiValueMap(params);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(queryParams).build())
                .retrieve()
                .bodyToMono(typeRef)
                .timeout(Duration.ofSeconds(15));
    }

    private MultiValueMap<String, String> toMultiValueMap(Map<String, Object> params) {
        MultiValueMap<String, String> multi = new LinkedMultiValueMap<>();
        if (params != null) {
            params.forEach((k, v) -> multi.add(k, String.valueOf(v)));
        }
        return multi;
    }

    // ==================== 联赛接口 ====================

    /**
     * 获取当前赛季联赛列表
     */
    public Mono<Map<String, Object>> getLeagues() {
        return get("/leagues", Map.of("current", "true"),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 获取特定联赛信息
     */
    public Mono<Map<String, Object>> getLeague(int leagueId) {
        return get("/leagues", Map.of("id", leagueId),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ==================== 球队接口 ====================

    /**
     * 按联赛获取球队列表
     */
    public Mono<Map<String, Object>> getTeams(int league, int season) {
        return get("/teams", Map.of("league", league, "season", season),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 获取单个球队信息
     */
    public Mono<Map<String, Object>> getTeam(long teamId) {
        return get("/teams", Map.of("id", teamId),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 按联赛获取球队列表(别名)
     */
    public Mono<Map<String, Object>> getTeamsByLeague(int league, int season) {
        return getTeams(league, season);
    }

    // ==================== 比赛接口 ====================

    /**
     * 按日期获取比赛
     */
    public Mono<Map<String, Object>> getFixturesByDate(String date) {
        return get("/fixtures", Map.of("date", date),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 按日期范围获取比赛
     */
    public Mono<Map<String, Object>> getFixturesByDateRange(String from, String to) {
        Map<String, Object> params = new HashMap<>();
        params.put("from", from);
        params.put("to", to);
        return get("/fixtures", params, new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 获取单个比赛详情
     */
    public Mono<Map<String, Object>> getFixture(long fixtureId) {
        return get("/fixtures", Map.of("id", fixtureId),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 按联赛和赛季获取比赛
     */
    public Mono<Map<String, Object>> getFixturesByLeague(int league, int season) {
        return get("/fixtures", Map.of("league", league, "season", season),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 按日期和联赛获取比赛
     */
    public Mono<Map<String, Object>> getFixtures(int league, int season, String date) {
        Map<String, Object> params = new HashMap<>();
        params.put("league", league);
        params.put("season", season);
        if (date != null) {
            params.put("date", date);
        }
        return get("/fixtures", params, new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ==================== 积分榜接口 ====================

    /**
     * 获取联赛积分榜
     * @param league 联赛ID
     * @param season 赛季年份
     */
    public Mono<Map<String, Object>> getStandings(int league, int season) {
        return get("/standings", Map.of("league", league, "season", season),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ==================== 历史交锋接口 ====================

    /**
     * 获取两队历史交锋记录
     * @param homeTeamId 主队ID
     * @param awayTeamId 客队ID
     * @param limit 限制返回条数
     */
    public Mono<Map<String, Object>> getHeadToHead(long homeTeamId, long awayTeamId, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("h2h", homeTeamId + "-" + awayTeamId);
        params.put("last", limit);
        return get("/fixtures", params, new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ==================== 球队统计接口 ====================

    /**
     * 获取球队赛季统计
     * @param team 球队ID
     * @param league 联赛ID
     * @param season 赛季
     */
    public Mono<Map<String, Object>> getTeamStatistics(long team, int league, int season) {
        Map<String, Object> params = new HashMap<>();
        params.put("team", team);
        params.put("league", league);
        params.put("season", season);
        return get("/teams/statistics", params, new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ==================== 球员接口 ====================

    /**
     * 获取球队球员列表
     * @param team 球队ID
     * @param season 赛季
     */
    public Mono<Map<String, Object>> getTeamPlayers(long team, int season) {
        return get("/players", Map.of("team", team, "season", season),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 获取球队Top球员(进球榜)
     * @param league 联赛ID
     * @param season 赛季
     */
    public Mono<Map<String, Object>> getTopScorers(int league, int season) {
        return get("/players/topscorers", Map.of("league", league, "season", season),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ==================== 赛程接口 ====================

    /**
     * 获取联赛赛程/赛果
     * @param league 联赛ID
     * @param season 赛季
     */
    public Mono<Map<String, Object>> getRounds(int league, int season) {
        return get("/fixtures/rounds", Map.of("league", league, "season", season, "current", "true"),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /**
     * 获取特定轮次比赛
     * @param league 联赛ID
     * @param season 赛季
     * @param round 轮次
     */
    public Mono<Map<String, Object>> getFixturesByRound(int league, int season, String round) {
        return get("/fixtures", Map.of("league", league, "season", season, "round", round),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
