package com.chen.football.common.client;

import com.chen.football.common.config.FootballDataProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * football-data.org API 客户端
 */
@Slf4j
@Component
public class FootballDataClient {

    private final FootballDataProperties props;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public FootballDataClient(FootballDataProperties props) {
        this.props = props;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
    }

    public Mono<Map<String, Object>> getMatches(String dateFrom, String dateTo, String competitions, String status) {
        return Mono.fromCallable(() -> {
            StringBuilder url = new StringBuilder(props.getBaseUrl()).append("/matches");
            boolean hasQuery = false;
            if (competitions != null && !competitions.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("competitions=").append(competitions);
                hasQuery = true;
            }
            if (dateFrom != null && !dateFrom.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("dateFrom=").append(dateFrom);
                hasQuery = true;
            }
            if (dateTo != null && !dateTo.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("dateTo=").append(dateTo);
                hasQuery = true;
            }
            if (status != null && !status.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("status=").append(status);
            }
            String finalUrl = url.toString();
            log.info("football-data 请求开始: {}", finalUrl);
            return request(finalUrl);
        }).timeout(Duration.ofSeconds(20));
    }

    public Mono<Map<String, Object>> getCompetitionMatches(String competitionCode, String dateFrom, String dateTo, String status, Integer matchday) {
        return Mono.fromCallable(() -> {
            StringBuilder url = new StringBuilder(props.getBaseUrl())
                    .append("/competitions/").append(competitionCode)
                    .append("/matches");
            boolean hasQuery = false;
            if (dateFrom != null && !dateFrom.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("dateFrom=").append(dateFrom);
                hasQuery = true;
            }
            if (dateTo != null && !dateTo.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("dateTo=").append(dateTo);
                hasQuery = true;
            }
            if (status != null && !status.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("status=").append(status);
                hasQuery = true;
            }
            if (matchday != null) {
                url.append(hasQuery ? "&" : "?").append("matchday=").append(matchday);
            }
            String finalUrl = url.toString();
            log.info("football-data 请求开始: {}", finalUrl);
            return request(finalUrl);
        }).timeout(Duration.ofSeconds(20));
    }

    public Mono<Map<String, Object>> getCompetitionStandings(String competitionCode, String season, String date) {
        return Mono.fromCallable(() -> {
            StringBuilder url = new StringBuilder(props.getBaseUrl())
                    .append("/competitions/").append(competitionCode)
                    .append("/standings");
            boolean hasQuery = false;
            if (season != null && !season.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("season=").append(season);
                hasQuery = true;
            }
            if (date != null && !date.isBlank()) {
                url.append(hasQuery ? "&" : "?").append("date=").append(date);
            }
            String finalUrl = url.toString();
            log.info("football-data 请求开始: {}", finalUrl);
            return request(finalUrl);
        }).timeout(Duration.ofSeconds(20));
    }

    public Mono<Map<String, Object>> getTodaysMatches() {
        return getMatches(null, null, null, null);
    }

    private Map<String, Object> request(String url) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Auth-Token", props.getToken());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            log.debug("football-data 请求发送: {}", url);
            byte[] bytes = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class).getBody();
            if (bytes == null) {
                log.warn("football-data 响应为空: {}", url);
                return emptyResult();
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            log.info("football-data 响应长度: {}, 前300字符: {}", json.length(), snippet(json));
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> converted = convertMatches(data);
            log.info("football-data 转换完成: results={}", converted.get("results"));
            return converted;
        } catch (Exception e) {
            log.error("football-data 请求失败: {}, 错误: {}", url, e.getMessage());
            return emptyResult();
        }
    }

    private Map<String, Object> convertMatches(Map<String, Object> data) {
        Object matchesObj = data.get("matches");
        if (!(matchesObj instanceof List<?> list)) {
            return emptyResult();
        }
        List<Map<String, Object>> response = new java.util.ArrayList<>();
        for (Object item : list) {
            Map<String, Object> raw = asMap(item);
            if (raw == null) {
                continue;
            }
            Map<String, Object> match = new HashMap<>();
            Map<String, Object> competition = asMap(raw.get("competition"));
            Map<String, Object> home = asMap(raw.get("homeTeam"));
            Map<String, Object> away = asMap(raw.get("awayTeam"));
            Map<String, Object> score = asMap(raw.get("score"));
            Map<String, Object> fullTime = score == null ? null : asMap(score.get("fullTime"));
            match.put("fixture", Map.of(
                    "id", raw.get("id"),
                    "date", raw.get("utcDate"),
                    "status", Map.of("short", raw.get("status"))
            ));
            match.put("league", Map.of(
                    "id", competition == null ? "" : String.valueOf(competition.getOrDefault("code", competition.getOrDefault("id", ""))),
                    "name", competition == null ? "" : String.valueOf(competition.getOrDefault("name", "")),
                    "logo", competition == null ? "" : String.valueOf(competition.getOrDefault("emblem", ""))
            ));
            match.put("teams", Map.of(
                    "home", Map.of("id", home == null ? "" : home.get("id"), "name", home == null ? "" : home.get("name"), "logo", home == null ? "" : home.getOrDefault("crest", "")),
                    "away", Map.of("id", away == null ? "" : away.get("id"), "name", away == null ? "" : away.get("name"), "logo", away == null ? "" : away.getOrDefault("crest", ""))
            ));
            match.put("goals", Map.of(
                    "home", fullTime == null ? null : fullTime.get("home"),
                    "away", fullTime == null ? null : fullTime.get("away")
            ));
            response.add(match);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("response", response);
        result.put("results", response.size());
        return result;
    }

    private Map<String, Object> emptyResult() {
        Map<String, Object> result = new HashMap<>();
        result.put("response", Collections.emptyList());
        result.put("results", 0);
        return result;
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ");
        return normalized.length() > 300 ? normalized.substring(0, 300) : normalized;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        return obj instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
