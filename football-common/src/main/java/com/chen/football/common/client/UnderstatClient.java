package com.chen.football.common.client;

import com.chen.football.common.config.UnderstatProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/** Minimal, bounded client for Understat's public league snapshots. */
@Component
public class UnderstatClient {
    private final WebClient webClient;
    private final UnderstatProperties properties;
    private final ObjectMapper objectMapper;

    public UnderstatClient(WebClient.Builder builder, UnderstatProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.clone()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().compress(true).followRedirect(true)))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                        .build())
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .defaultHeader("Accept", "application/json")
                // Keep the response readable for the explicit String decoder;
                // some Understat edge nodes otherwise return a gzip stream
                // without the client transparently expanding it.
                .defaultHeader("Accept-Encoding", "identity")
                .defaultHeader("X-Requested-With", "XMLHttpRequest")
                .defaultHeader("Referer", properties.getBaseUrl() + "/")
                .build();
    }

    public Mono<Map<String, Object>> getLeagueData(String league, int season) {
        if (league == null || league.isBlank() || season <= 0) {
            return Mono.error(new IllegalArgumentException("Understat league/season is required"));
        }
        return webClient.get()
                // Understat currently requires the trailing slash; without it
                // the same public endpoint returns 404.
                .uri(uri -> uri.path("/getLeagueData/{league}/{season}/")
                        .build(league, season))
                .header("Referer", properties.getBaseUrl() + "/league/" + league + "/" + season)
                .retrieve()
                // Understat may label this JSON response as text/plain depending
                // on the edge node; decode the body explicitly instead of
                // relying on the content-type based Jackson decoder.
                .bodyToMono(String.class)
                .map(body -> {
                    try {
                        return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
                    } catch (Exception ex) {
                        throw new IllegalStateException("Understat JSON decode failed: " + ex.getMessage(), ex);
                    }
                })
                .timeout(Duration.ofMillis(Math.max(1000, properties.getTimeoutMs())));
    }
}
