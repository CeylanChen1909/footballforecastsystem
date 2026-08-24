package com.chen.football.crawler.service;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.http.CrawlerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches the ESPN roster with real player portraits from Transfermarkt's
 * public club page. ESPN's current roster JSON no longer exposes headshot
 * URLs, so blindly constructing the old ESPN CDN path produces 404s.
 */
@Slf4j
@Service
public class TransfermarktPhotoService {

    private static final Pattern CLUB_PATH = Pattern.compile("/([^/]+)/startseite/verein/(\\d+)");
    private final CrawlerHttpClient httpClient;
    private final CrawlerProperties properties;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public TransfermarktPhotoService(CrawlerHttpClient httpClient, CrawlerProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public List<Map<String, Object>> enrich(String teamName, List<Map<String, Object>> players) {
        if (players == null || players.isEmpty() || properties.getTransfermarkt() == null || !properties.getTransfermarkt().isEnabled()) {
            return players == null ? List.of() : players;
        }
        try {
            String key = normalize(teamName);
            Map<String, String> portraits = getPortraits(teamName, key);
            if (portraits.isEmpty()) return players;
            for (Map<String, Object> player : players) {
                String name = normalize(String.valueOf(player.getOrDefault("name", "")));
                String photo = portraits.get(name);
                if (photo == null) {
                    photo = portraits.entrySet().stream()
                            .filter(entry -> entry.getKey().startsWith(name + " ") || name.startsWith(entry.getKey() + " "))
                            .map(Map.Entry::getValue)
                            .findFirst().orElse(null);
                }
                if (photo != null && !photo.isBlank()) {
                    player.put("photo", photo);
                    player.put("photoSource", "transfermarkt");
                }
            }
        } catch (Exception ex) {
            log.debug("[Transfermarkt] 球员头像补充失败, team={}, error={}", teamName, ex.getMessage());
        }
        return players;
    }

    private Map<String, String> getPortraits(String teamName, String cacheKey) {
        CacheEntry current = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (current != null && now - current.loadedAt() < 24 * 60 * 60 * 1000L) return current.portraits();

        CrawlerProperties.Transfermarkt config = properties.getTransfermarkt();
        String base = config.getBaseUrl() == null ? "https://www.transfermarkt.com" : config.getBaseUrl().replaceAll("/$", "");
        String searchUrl = base + "/schnellsuche/ergebnis/schnellsuche?query=" + URLEncoder.encode(teamName == null ? "" : teamName, StandardCharsets.UTF_8);
        String searchHtml = httpClient.getHtmlDirect(searchUrl, config.getUserAgent(), Duration.ofMillis(Math.max(1000, config.getTimeoutMs())));
        ClubRef club = findClub(searchHtml, teamName);
        if (club == null) return Map.of();

        String clubUrl = base + "/" + club.slug() + "/startseite/verein/" + club.id();
        String clubHtml = httpClient.getHtmlDirect(clubUrl, config.getUserAgent(), Duration.ofMillis(Math.max(1000, config.getTimeoutMs())));
        Map<String, String> portraits = new LinkedHashMap<>();
        for (Element image : Jsoup.parse(clubHtml).select("img.bilderrahmen-fixed")) {
            String name = image.hasAttr("title") ? image.attr("title") : image.attr("alt");
            String url = image.hasAttr("data-src") ? image.attr("data-src") : image.attr("src");
            if (name == null || name.isBlank() || url == null || url.isBlank() || url.startsWith("data:")) continue;
            portraits.put(normalize(name), url);
        }
        cache.put(cacheKey, new CacheEntry(portraits, now));
        log.info("[Transfermarkt] 补充球队头像: team={}, portraits={}", teamName, portraits.size());
        return portraits;
    }

    private ClubRef findClub(String html, String requestedName) {
        if (html == null || html.isBlank()) return null;
        String requested = normalize(requestedName);
        List<ClubRef> candidates = new ArrayList<>();
        for (Element link : Jsoup.parse(html).select("a[href*='/startseite/verein/']")) {
            Matcher matcher = CLUB_PATH.matcher(link.attr("href"));
            if (!matcher.find()) continue;
            String name = clean(link.text());
            if (name.isBlank()) name = clean(link.attr("title"));
            candidates.add(new ClubRef(matcher.group(1), matcher.group(2), name));
        }
        return candidates.stream().filter(item -> normalize(item.name()).equals(requested)).findFirst()
                .orElseGet(() -> candidates.stream().filter(item -> {
                    String value = normalize(item.name());
                    return value.startsWith(requested + " ") || requested.startsWith(value + " ");
                }).findFirst().orElse(null));
    }

    private String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace('&', ' ')
                .replaceAll("\\b(fc|cf|afc|sc|ac)\\b", " ")
                .replaceAll("[^a-z0-9]+", " ").replace(" and ", " ").trim();
    }

    private String clean(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }

    private record ClubRef(String slug, String id, String name) {}
    private record CacheEntry(Map<String, String> portraits, long loadedAt) {}
}
