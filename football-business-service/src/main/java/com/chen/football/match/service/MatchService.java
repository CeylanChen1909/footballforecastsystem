package com.chen.football.match.service;

import com.chen.football.common.dto.FixtureDetailResponse;
import com.chen.football.common.dto.FixtureListResponse;
import com.chen.football.common.dto.LeagueItem;
import com.chen.football.common.dto.LeagueListResponse;
import com.chen.football.common.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MatchService {

    private final RedisCacheService cacheService;
    private final WebClient crawlerWebClient;
    private final int cacheTtlSeconds;

    public MatchService(RedisCacheService cacheService,
                        WebClient.Builder webClientBuilder,
                        @Value("${crawler.base-url:http://127.0.0.1:9009}") String crawlerBaseUrl,
                        @Value("${crawler.cache-ttl-seconds:300}") int cacheTtlSeconds) {
        this.cacheService = cacheService;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.crawlerWebClient = webClientBuilder.baseUrl(crawlerBaseUrl).build();
    }

    @SuppressWarnings("unchecked")
    public Mono<FixtureListResponse> getTodayFixtures() {
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String key = "fixtures:today:" + today;
        FixtureListResponse cached = cacheService.get(key, FixtureListResponse.class);
        if (cached != null) {
            return Mono.just(cached);
        }
        Map<String, Object> raw = getCrawlerData("/api/crawler/matches/db/today");
        if (raw == null) {
            return Mono.just(FixtureListResponse.empty(null));
        }
        cacheService.set(key, raw, cacheTtlSeconds);
        return Mono.just(toFixtureList(raw));
    }

    @SuppressWarnings("unchecked")
    public Mono<FixtureListResponse> getFixturesByDate(String date) {
        String key = "fixtures:date:" + date;
        FixtureListResponse cached = cacheService.get(key, FixtureListResponse.class);
        if (cached != null) {
            return Mono.just(cached);
        }
        Map<String, Object> raw = getCrawlerData("/api/crawler/matches/date/" + date);
        if (raw == null) {
            return Mono.just(FixtureListResponse.empty(null));
        }
        cacheService.set(key, raw, cacheTtlSeconds);
        return Mono.just(toFixtureList(raw));
    }

    public Mono<LeagueListResponse> getLeagues() {
        List<LeagueItem> leagues = List.of(
                new LeagueItem(39, "英超"),
                new LeagueItem(140, "西甲"),
                new LeagueItem(135, "意甲"),
                new LeagueItem(78, "德甲"),
                new LeagueItem(61, "法甲"),
                new LeagueItem(88, "荷甲"),
                new LeagueItem(94, "葡超"),
                new LeagueItem(40, "英冠")
        );
        return Mono.just(new LeagueListResponse(leagues, leagues.size()));
    }

    @SuppressWarnings("unchecked")
    public Mono<FixtureDetailResponse> getFixtureDetail(Long fixtureId) {
        String key = "fixture:detail:" + fixtureId;
        FixtureDetailResponse cached = cacheService.get(key, FixtureDetailResponse.class);
        if (cached != null) {
            return Mono.just(cached);
        }
        Map<String, Object> raw = getCrawlerData("/api/crawler/matches/detail/" + fixtureId);
        if (raw == null) {
            return Mono.just(FixtureDetailResponse.empty(fixtureId, null));
        }
        Object resp = raw.get("response");
        Map<String, Object> detailMap = resp instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        int results = raw.get("results") instanceof Number n ? n.intValue() : 0;
        String error = raw.get("error") == null ? null : String.valueOf(raw.get("error"));
        FixtureDetailResponse converted = new FixtureDetailResponse(fixtureId, detailMap, results, error);
        // Cache the public DTO, not the crawler envelope.  The two shapes are
        // intentionally different; caching the envelope makes every cache hit
        // fail deserialization and turns the detail endpoint into a proxy call.
        cacheService.set(key, converted, cacheTtlSeconds);
        return Mono.just(converted);
    }

    @SuppressWarnings("unchecked")
    private FixtureListResponse toFixtureList(Map<String, Object> raw) {
        Object resp = raw.get("response");
        List<Map<String, Object>> list = resp instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        int results = raw.get("results") instanceof Number n ? n.intValue() : list.size();
        String error = raw.get("error") == null ? null : String.valueOf(raw.get("error"));
        return new FixtureListResponse(list, results, error);
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
        } catch (Exception e) {
            log.warn("Crawler request failed {}: {}", endpoint, e.getMessage());
        }
        return null;
    }
}
