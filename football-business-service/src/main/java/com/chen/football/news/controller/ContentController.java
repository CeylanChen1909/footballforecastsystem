package com.chen.football.news.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.news.dto.ContentItemDTO;
import com.chen.football.news.dto.NewsArticleSummaryDTO;
import com.chen.football.news.entity.VideoHubItem;
import com.chen.football.news.service.NewsFeedPage;
import com.chen.football.news.service.NewsService;
import com.chen.football.news.service.VideoHubService;
import com.chen.football.news.service.VideoHubPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 统一赛事内容读接口。写入仍由文章/视频后台分别负责，避免一次迁移同时改动运营链路。
 */
@RestController
@RequestMapping("/api/content")
public class ContentController {

    private final NewsService newsService;
    private final VideoHubService videoHubService;

    public ContentController(NewsService newsService, VideoHubService videoHubService) {
        this.newsService = newsService;
        this.videoHubService = videoHubService;
    }

    @GetMapping("/feed")
    public ApiResponse<Map<String, Object>> feed(
            @RequestParam(name = "type", defaultValue = "all") String type,
            @RequestParam(name = "page", defaultValue = "1") Integer page,
            @RequestParam(name = "size", defaultValue = "24") Integer size,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "sortBy", defaultValue = "latest") String sortBy) {
        int safePage = Math.max(1, page == null ? 1 : page);
        int safeSize = Math.max(1, Math.min(size == null ? 24 : size, 100));
        String safeType = type == null ? "all" : type.toLowerCase(Locale.ROOT);
        if (!List.of("all", "article", "video").contains(safeType)) safeType = "all";

        if ("video".equals(safeType)) {
            VideoHubPage videoPage = videoHubService.listPublicVideosPage(keyword, category, null, null, safePage, safeSize);
            List<ContentItemDTO> items = videoPage.items().stream().map(this::fromVideo).toList();
            return ApiResponse.ok(Map.of("items", items, "total", videoPage.total(), "page", safePage,
                    "size", safeSize, "type", safeType));
        }

        if ("article".equals(safeType)) {
            NewsFeedPage articlePage = newsService.getFeedPage(safePage, safeSize, category, keyword, sortBy);
            List<ContentItemDTO> items = articlePage.items().stream().map(this::fromArticle).toList();
            return ApiResponse.ok(Map.of("items", items, "total", newsService.countArticles(category, keyword),
                    "page", safePage, "size", safeSize, "type", safeType));
        }

        List<ContentItemDTO> all = new ArrayList<>();
        // A mixed feed only needs the first page*size rows from each source to
        // produce a correct merged page.  The old implementation hard-capped
        // the source list at 100, which made later content permanently vanish.
        int fetchLimit = Math.min(1000, Math.max(safeSize, safePage * safeSize));
        NewsFeedPage articlePage = newsService.getFeedPage(1, fetchLimit, category, keyword, sortBy);
        articlePage.items().forEach(article -> all.add(fromArticle(article)));
        videoHubService.listPublicVideos(keyword, null, null, null, fetchLimit).stream()
                .filter(video -> category == null || category.isBlank()
                        || category.equalsIgnoreCase(video.getLeagueName())
                        || category.equalsIgnoreCase(video.getVideoType()))
                .forEach(video -> all.add(fromVideo(video)));

        sort(all, sortBy);
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", all.subList(from, to));
        result.put("total", newsService.countArticles(category, keyword)
                + videoHubService.countPublicVideos(keyword, null, null, null));
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("type", safeType);
        return ApiResponse.ok(result);
    }

    @GetMapping("/types")
    public ApiResponse<List<Map<String, String>>> types() {
        return ApiResponse.ok(List.of(
                Map.of("value", "all", "label", "全部内容"),
                Map.of("value", "article", "label", "资讯"),
                Map.of("value", "video", "label", "视频")
        ));
    }

    @GetMapping("/match/{matchId}")
    public ApiResponse<Map<String, Object>> matchContent(
            @PathVariable Long matchId,
            @RequestParam(name = "homeTeamName", required = false) String homeTeamName,
            @RequestParam(name = "awayTeamName", required = false) String awayTeamName,
            @RequestParam(name = "matchTime", required = false) String matchTime,
            @RequestParam(name = "limit", defaultValue = "8") Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 8 : limit, 20));
        List<ContentItemDTO> items = new ArrayList<>();
        newsService.getFeedByMatchId(matchId, safeLimit).forEach(article -> items.add(fromArticle(article)));
        String home = normalizeTeamName(homeTeamName);
        String away = normalizeTeamName(awayTeamName);
        LocalDateTime kickoff = parseDate(matchTime);
        if (!home.isBlank() || !away.isBlank()) {
            videoHubService.listPublicVideos(null, null, null, null, 100).stream()
                    .filter(video -> teamMatches(video, home, away))
                    .filter(video -> kickoff == null || video.getMatchTime() == null
                            || Math.abs(Duration.between(kickoff, video.getMatchTime()).toHours()) <= 72)
                    .limit(safeLimit)
                    .forEach(video -> items.add(fromVideo(video)));
        }
        sort(items, "latest");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        result.put("matchId", matchId);
        return ApiResponse.ok(result);
    }

    private boolean teamMatches(VideoHubItem video, String home, String away) {
        String videoHome = normalizeTeamName(video.getHomeTeamName());
        String videoAway = normalizeTeamName(video.getAwayTeamName());
        if (videoHome.isBlank() && videoAway.isBlank()) return false;
        boolean direct = !home.isBlank() && !away.isBlank()
                && videoHome.contains(home) && videoAway.contains(away);
        boolean reversed = !home.isBlank() && !away.isBlank()
                && videoHome.contains(away) && videoAway.contains(home);
        if (!home.isBlank() && !away.isBlank()) return direct || reversed;
        return (!home.isBlank() && (videoHome.equals(home) || videoHome.contains(home)))
                || (!away.isBlank() && (videoAway.equals(away) || videoAway.contains(away)));
    }

    private String normalizeTeamName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·•]", "");
    }

    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toLocalDateTime(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(value.replace("T", " ").substring(0, Math.min(19, value.length()))); } catch (Exception ignored) {}
        return null;
    }

    private ContentItemDTO fromArticle(NewsArticleSummaryDTO article) {
        ContentItemDTO item = new ContentItemDTO();
        item.setKind("ARTICLE");
        item.setId(article.getId());
        item.setTitle(article.getTitle());
        item.setSummary(article.getSummary());
        item.setCoverImage(article.getCoverImage());
        item.setSourceName(article.getSourceName());
        item.setSourceUrl(article.getSourceUrl());
        item.setCategory(article.getCategory());
        item.setLeagueName(article.getLeagueName());
        item.setMatchId(article.getMatchId());
        item.setStatus("PUBLISHED");
        item.setPublishedAt(article.getPublishTime());
        item.setMetrics(Map.of(
                "likes", safe(article.getLikeCount()),
                "favorites", safe(article.getFavoriteCount()),
                "comments", safe(article.getCommentCount()),
                "featured", Integer.valueOf(1).equals(article.getIsFeatured()),
                "hot", Integer.valueOf(1).equals(article.getIsHot())
        ));
        return item;
    }

    private ContentItemDTO fromVideo(VideoHubItem video) {
        ContentItemDTO item = new ContentItemDTO();
        item.setKind("VIDEO");
        item.setId(video.getId());
        item.setTitle(video.getTitle());
        item.setSummary(video.getDescription());
        item.setCoverImage(video.getCoverImage());
        item.setCategory(video.getVideoType());
        item.setLeagueName(video.getLeagueName());
        item.setPlatform(video.getPlatform());
        item.setVideoType(video.getVideoType());
        item.setSourceUrl(video.getVideoUrl());
        item.setStatus(video.getStatus());
        item.setPublishedAt(video.getUpdatedAt() != null ? video.getUpdatedAt() : video.getCreatedAt());
        item.setMetrics(Map.of(
                "featured", Integer.valueOf(1).equals(video.getIsFeatured()),
                "hot", Integer.valueOf(1).equals(video.getIsHot())
        ));
        return item;
    }

    private void sort(List<ContentItemDTO> items, String sortBy) {
        Comparator<ContentItemDTO> latest = Comparator.comparing(
                ContentItemDTO::getPublishedAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
        if ("hot".equalsIgnoreCase(sortBy)) {
            items.sort(Comparator.comparingInt(this::hotScore).reversed().thenComparing(latest));
        } else {
            items.sort(latest);
        }
    }

    private int hotScore(ContentItemDTO item) {
        if (item.getMetrics() == null) return 0;
        int score = 0;
        if (Boolean.TRUE.equals(item.getMetrics().get("featured"))) score += 3;
        if (Boolean.TRUE.equals(item.getMetrics().get("hot"))) score += 2;
        score += number(item.getMetrics().get("likes"));
        score += number(item.getMetrics().get("comments"));
        return score;
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }
}
