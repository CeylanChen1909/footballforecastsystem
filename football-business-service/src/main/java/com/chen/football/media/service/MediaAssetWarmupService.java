package com.chen.football.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chen.football.media.config.MediaProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Warms the persistent media cache after crawler data is available.  It is
 * intentionally best-effort: a missing cache table or a temporarily blocked
 * source must never make the crawler or the public API fail.
 */
@Component
public class MediaAssetWarmupService {

    private static final Logger log = LoggerFactory.getLogger(MediaAssetWarmupService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MediaAssetCacheService mediaAssetCacheService;
    private final MediaProxyProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MediaAssetWarmupService(JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   MediaAssetCacheService mediaAssetCacheService,
                                   MediaProxyProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.mediaAssetCacheService = mediaAssetCacheService;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${media.proxy.warmup-initial-delay-ms:60000}",
            fixedDelayString = "${media.proxy.warmup-fixed-delay-ms:21600000}")
    public void scheduleWarmup() {
        if (!properties.isEnabled() || !properties.isWarmupEnabled() || !running.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(this::warmupSafely)
                .whenComplete((ignored, error) -> running.set(false));
    }

    private void warmupSafely() {
        try {
            Set<String> urls = collectUrls();
            if (urls.isEmpty()) return;
            List<String> bounded = new ArrayList<>(urls).subList(0,
                    Math.min(urls.size(), Math.max(1, properties.getWarmupMaxPerRun())));
            int loaded = mediaAssetCacheService.warmup(bounded);
            log.info("[MediaWarmup] candidates={}, loaded={}", bounded.size(), loaded);
        } catch (RuntimeException ex) {
            log.warn("[MediaWarmup] skipped: {}", ex.getMessage());
        }
    }

    private Set<String> collectUrls() {
        Set<String> urls = new LinkedHashSet<>();
        addColumn(urls, "SELECT home_team_logo FROM crawler_matches WHERE home_team_logo IS NOT NULL AND TRIM(home_team_logo) <> '' AND match_time >= DATE_SUB(NOW(), INTERVAL 60 DAY) AND match_time <= DATE_ADD(NOW(), INTERVAL 30 DAY) ORDER BY match_time DESC LIMIT 400");
        addColumn(urls, "SELECT away_team_logo FROM crawler_matches WHERE away_team_logo IS NOT NULL AND TRIM(away_team_logo) <> '' AND match_time >= DATE_SUB(NOW(), INTERVAL 60 DAY) AND match_time <= DATE_ADD(NOW(), INTERVAL 30 DAY) ORDER BY match_time DESC LIMIT 400");
        addColumn(urls, "SELECT logo FROM crawler_teams WHERE logo IS NOT NULL AND TRIM(logo) <> '' ORDER BY updated_at DESC LIMIT 300");
        addColumn(urls, "SELECT team_logo FROM crawler_standings WHERE team_logo IS NOT NULL AND TRIM(team_logo) <> '' ORDER BY updated_at DESC LIMIT 300");

        // t_team_squad_cache is introduced by a migration in production, but
        // older installations may not have it yet; addColumnJson handles that
        // case without affecting the normal logo warmup.
        addSquadPhotos(urls);
        return urls;
    }

    private void addColumn(Set<String> urls, String sql) {
        try {
            jdbcTemplate.queryForList(sql, String.class).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(urls::add);
        } catch (RuntimeException ex) {
            log.debug("[MediaWarmup] query skipped: {}", ex.getMessage());
        }
    }

    private void addSquadPhotos(Set<String> urls) {
        try {
            jdbcTemplate.queryForList("SELECT squad_json FROM t_team_squad_cache WHERE squad_json IS NOT NULL AND squad_json <> '[]' ORDER BY updated_at DESC LIMIT 200", String.class)
                    .forEach(json -> collectPhotoNodes(urls, json));
        } catch (RuntimeException ex) {
            log.debug("[MediaWarmup] squad cache query skipped: {}", ex.getMessage());
        }
    }

    private void collectPhotoNodes(Set<String> urls, String json) {
        try {
            collectPhotoNodes(urls, objectMapper.readTree(json));
        } catch (Exception ignored) {
            // One malformed historical snapshot must not stop other photos.
        }
    }

    private void collectPhotoNodes(Set<String> urls, JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ("photo".equalsIgnoreCase(entry.getKey()) && entry.getValue().isTextual()) {
                    String value = entry.getValue().asText().trim();
                    if (!value.isBlank()) urls.add(value);
                }
                collectPhotoNodes(urls, entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectPhotoNodes(urls, child));
        }
    }
}
