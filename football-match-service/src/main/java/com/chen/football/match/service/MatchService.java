package com.chen.football.match.service;

import com.chen.football.common.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
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

    public Mono<Map<String, Object>> getTodayFixtures() {
        String today = LocalDate.now().toString();
        String key = "fixtures:today:" + today;
        Map<String, Object> cached = cacheService.get(key, Map.class);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.justOrEmpty(getCrawlerData("/api/crawler/matches/db/today"))
                .switchIfEmpty(Mono.just(Map.of("response", Collections.emptyList(), "results", 0)))
                .map(raw -> {
                    cacheService.set(key, raw, cacheTtlSeconds);
                    return raw;
                });
    }

    public Mono<Map<String, Object>> getFixturesByDate(String date) {
        String key = "fixtures:date:" + date;
        Map<String, Object> cached = cacheService.get(key, Map.class);
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.justOrEmpty(getCrawlerData("/api/crawler/matches/date/" + date))
                .switchIfEmpty(Mono.just(Map.of("response", Collections.emptyList(), "results", 0)))
                .map(raw -> {
                    cacheService.set(key, raw, cacheTtlSeconds);
                    return raw;
                });
    }

    public Mono<Map<String, Object>> getLeagues() {
        List<Map<String, Object>> leagues = List.of(
                Map.of("id", 39, "name", "英超"),
                Map.of("id", 140, "name", "西甲"),
                Map.of("id", 135, "name", "意甲"),
                Map.of("id", 78, "name", "德甲"),
                Map.of("id", 61, "name", "法甲"),
                Map.of("id", 2, "name", "欧冠"),
                Map.of("id", 100, "name", "中超")
        );
        return Mono.just(Map.of("response", leagues, "results", leagues.size()));
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
