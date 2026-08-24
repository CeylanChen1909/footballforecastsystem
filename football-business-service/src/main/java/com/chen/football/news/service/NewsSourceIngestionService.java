package com.chen.football.news.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.service.DistributedLockService;
import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.entity.NewsSourceArticleMap;
import com.chen.football.news.entity.NewsSourceTask;
import com.chen.football.news.mapper.NewsArticleMapper;
import com.chen.football.news.mapper.NewsSourceArticleMapMapper;
import com.chen.football.news.mapper.NewsSourceTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 受控的 RSS/Atom 资讯采集器。只读取管理员配置的来源，不抓取同类站点页面，
 * 并把外部文章保留为来源链接和摘要，避免版权内容被无授权复制。
 */
@Slf4j
@Service
public class NewsSourceIngestionService {

    static {
        // 本地开发环境可能通过系统代理访问海外资讯源；无代理时保持直连。
        System.setProperty("java.net.useSystemProxies", "true");
    }

    private final NewsSourceTaskMapper taskMapper;
    private final NewsSourceArticleMapMapper sourceMapMapper;
    private final NewsArticleMapper articleMapper;
    private final JdbcTemplate jdbcTemplate;
    private final DistributedLockService lockService;

    public NewsSourceIngestionService(NewsSourceTaskMapper taskMapper,
                                      NewsSourceArticleMapMapper sourceMapMapper,
                                      NewsArticleMapper articleMapper,
                                      JdbcTemplate jdbcTemplate,
                                      DistributedLockService lockService) {
        this.taskMapper = taskMapper;
        this.sourceMapMapper = sourceMapMapper;
        this.articleMapper = articleMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.lockService = lockService;
    }

    public Map<String, Object> syncAll() {
        String token = lockService.tryLock("content:news-sync", Duration.ofMinutes(10));
        if (token == null) return Map.of("ok", false, "message", "已有资讯同步任务正在运行", "fetched", 0, "inserted", 0);
        try {
            int deduplicated = deduplicatePublishedArticles();
            int quarantined = quarantineExistingLowQualityArticles();
            int fetched = 0;
            int inserted = 0;
            int lowQuality = 0;
            List<Map<String, Object>> results = new ArrayList<>();
            for (NewsSourceTask task : taskMapper.selectList(Wrappers.<NewsSourceTask>query().orderByAsc("id"))) {
                if ("DISABLED".equalsIgnoreCase(task.getLastStatus())) continue;
                Map<String, Object> result = syncTaskUnlocked(task);
                fetched += number(result.get("fetched"));
                inserted += number(result.get("inserted"));
                lowQuality += number(result.get("lowQuality"));
                results.add(result);
            }
            return Map.of("ok", true, "fetched", fetched, "inserted", inserted, "deduplicated", deduplicated,
                    "quarantined", quarantined, "lowQuality", lowQuality, "sources", results);
        } finally {
            lockService.unlock("content:news-sync", token);
        }
    }

    public Map<String, Object> syncTask(Long id) {
        String token = lockService.tryLock("content:news-sync", Duration.ofMinutes(10));
        if (token == null) return Map.of("ok", false, "message", "已有资讯同步任务正在运行", "fetched", 0, "inserted", 0);
        try {
            NewsSourceTask task = taskMapper.selectById(id);
            if (task == null) return Map.of("ok", false, "message", "来源任务不存在", "fetched", 0, "inserted", 0);
            return syncTaskUnlocked(task);
        } finally {
            lockService.unlock("content:news-sync", token);
        }
    }

    private int deduplicatePublishedArticles() {
        try {
            return jdbcTemplate.update("""
                    UPDATE t_news_article duplicate
                    JOIN t_news_article original
                      ON duplicate.title = original.title
                     AND duplicate.id > original.id
                     AND duplicate.status = 'PUBLISHED'
                     AND original.status = 'PUBLISHED'
                    SET duplicate.status = 'HIDDEN', duplicate.updated_at = NOW()
                    """);
        } catch (Exception e) {
            log.debug("[ContentIngestion] existing article dedupe skipped: {}", e.getMessage());
            return 0;
        }
    }

    private Map<String, Object> syncTaskUnlocked(NewsSourceTask task) {
        LocalDateTime now = LocalDateTime.now();
        task.setLastFetchTime(now);
        task.setLastStatus("RUNNING");
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        String url = StringUtils.hasText(task.getFetchUrl()) ? task.getFetchUrl() : task.getSourceBaseUrl();
        int fetched = 0;
        int inserted = 0;
        int lowQuality = 0;
        try {
            if (!StringUtils.hasText(url)) throw new IllegalArgumentException("fetchUrl 不能为空");
            Document document = feedConnection(url)
                    .userAgent("ChenFootball/1.0 (+content-ingestion)")
                    .timeout(12_000)
                    .ignoreContentType(true)
                    .execute()
                    .parse();
            Elements entries = new Elements();
            entries.addAll(document.select("item"));
            entries.addAll(document.select("entry"));
            for (Element entry : entries) {
                FeedItem item = readItem(entry);
                if (item == null || !StringUtils.hasText(item.title())) continue;
                fetched++;
                if (isLowQuality(item)) {
                    lowQuality++;
                    quarantineSourceArticle(task, item);
                    continue;
                }
                NewsSourceArticleMap existingMap = findExisting(task.getSourceName(), item.sourceId());
                if (existingMap != null) {
                    // 来源已经同步过时仍补齐 RSS 后来提供的摘要/封面，不覆盖管理员手工编辑的正文。
                    enrichExistingArticle(existingMap.getArticleId(), item);
                    continue;
                }
                // 不同 RSS 源经常转载同一篇文章。来源映射只能避免单源重复，
                // 这里再按规范化原文链接/标题做全局去重，避免用户在资讯流中反复看到同一条新闻。
                NewsArticle duplicate = findGlobalDuplicate(item);
                if (duplicate != null) {
                    NewsSourceArticleMap map = new NewsSourceArticleMap();
                    map.setSourceName(task.getSourceName());
                    map.setSourceArticleId(item.sourceId());
                    map.setArticleId(duplicate.getId());
                    map.setSourceUrl(item.link());
                    map.setCreatedAt(LocalDateTime.now());
                    sourceMapMapper.insert(map);
                    enrichExistingArticle(duplicate.getId(), item);
                    continue;
                }
                NewsArticle article = toArticle(task, item);
                articleMapper.insert(article);
                NewsSourceArticleMap sourceMap = new NewsSourceArticleMap();
                sourceMap.setSourceName(task.getSourceName());
                sourceMap.setSourceArticleId(item.sourceId());
                sourceMap.setArticleId(article.getId());
                sourceMap.setSourceUrl(item.link());
                sourceMap.setCreatedAt(LocalDateTime.now());
                sourceMapMapper.insert(sourceMap);
                inserted++;
            }
            task.setLastStatus("SUCCESS");
            task.setLastSuccessTime(LocalDateTime.now());
            // MyBatis 默认不会更新 null 字段，使用空字符串确保清除上一次失败信息。
            task.setLastError(lowQuality > 0 ? "检测到 " + lowQuality + " 条低质量摘要，已进入待审核" : "");
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            return Map.of("ok", true, "sourceName", task.getSourceName(), "fetched", fetched, "inserted", inserted,
                    "lowQuality", lowQuality);
        } catch (Exception e) {
            task.setLastStatus("FAILED");
            task.setLastError(limit(e.getMessage(), 1000));
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            log.warn("[ContentIngestion] source={} failed: {}", task.getSourceName(), e.getMessage());
            return Map.of("ok", false, "sourceName", task.getSourceName(), "fetched", fetched, "inserted", inserted,
                    "lowQuality", lowQuality,
                    "message", e.getMessage() == null ? "读取来源失败" : e.getMessage());
        }
    }

    /**
     * RSS 条目如果只有标题拼接出来的短描述且没有封面，就不应直接进入公开资讯流。
     * 这类内容通常来自聚合站的搜索结果页，先进入待审核状态，避免把“标题卡片”误当成完整文章。
     */
    private boolean isLowQuality(FeedItem item) {
        if (item == null || StringUtils.hasText(item.imageUrl())) return false;
        String title = normalize(item.title());
        String summary = normalize(item.summary());
        if (!StringUtils.hasText(summary)) return true;
        // 聚合搜索结果常把“标题 + 来源名”塞进 description，长度通常很短，
        // 即便与标题不完全相等，也不具备可读的正文摘要。
        return summary.length() < 180
                || summary.equals(title)
                || summary.startsWith(title) && summary.length() <= title.length() + 40;
    }

    private int quarantineExistingLowQualityArticles() {
        try {
            return jdbcTemplate.update("""
                    UPDATE t_news_article
                    SET status = 'DRAFT', updated_at = NOW()
                    WHERE status = 'PUBLISHED'
                      AND source_type = 'rss'
                      AND (cover_image IS NULL OR TRIM(cover_image) = '')
                      AND (summary IS NULL OR CHAR_LENGTH(TRIM(summary)) < 180
                           OR content IS NULL OR CHAR_LENGTH(TRIM(content)) < 180)
                    """);
        } catch (Exception e) {
            log.debug("[ContentIngestion] low-quality quarantine skipped: {}", e.getMessage());
            return 0;
        }
    }

    private void quarantineSourceArticle(NewsSourceTask task, FeedItem item) {
        NewsSourceArticleMap existing = findExisting(task.getSourceName(), item.sourceId());
        if (existing == null || existing.getArticleId() == null) return;
        NewsArticle article = articleMapper.selectById(existing.getArticleId());
        if (article == null || !"PUBLISHED".equalsIgnoreCase(article.getStatus())) return;
        if (StringUtils.hasText(article.getCoverImage())) return;
        article.setStatus("DRAFT");
        article.setUpdatedAt(LocalDateTime.now());
        articleMapper.updateById(article);
    }

    private Connection feedConnection(String url) {
        Connection connection = Jsoup.connect(url);
        try {
            String configuredHost = firstNonBlank(System.getenv("CONTENT_NEWS_PROXY_HOST"),
                    System.getProperty("content.news-proxy-host"));
            if (StringUtils.hasText(configuredHost)) {
                int configuredPort = parsePort(firstNonBlank(System.getenv("CONTENT_NEWS_PROXY_PORT"),
                        System.getProperty("content.news-proxy-port")), 7897);
                connection.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(configuredHost, configuredPort)));
                return connection;
            }
            for (Proxy proxy : ProxySelector.getDefault().select(URI.create(url))) {
                if (proxy == null || proxy.type() == Proxy.Type.DIRECT || !(proxy.address() instanceof InetSocketAddress address)) {
                    continue;
                }
                connection.proxy(new Proxy(proxy.type(), address));
                break;
            }
        } catch (Exception e) {
            log.debug("[ContentIngestion] system proxy unavailable: {}", e.getMessage());
        }
        return connection;
    }

    private int parsePort(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private NewsArticle toArticle(NewsSourceTask task, FeedItem item) {
        MatchCandidate candidate = resolveMatch(item.title() + " " + item.summary(), item.publishedAt());
        NewsArticle article = new NewsArticle();
        article.setTitle(item.title().trim());
        article.setSubtitle(limit(item.summary(), 512));
        article.setSummary(limit(item.summary(), 1000));
        article.setContent(limit(item.summary(), 4000));
        article.setContentHtml("<p>外部来源摘要：</p><p>" + escapeHtml(limit(item.summary(), 4000)) + "</p>");
        article.setCoverImage(item.imageUrl());
        article.setSourceName(task.getSourceName());
        article.setSourceUrl(item.link());
        article.setSourceType("rss");
        article.setAuthor(item.author());
        article.setCategory(StringUtils.hasText(task.getTaskType()) ? task.getTaskType() : "赛事资讯");
        article.setLeagueName(candidate == null ? null : candidate.leagueName());
        article.setLeagueId(candidate == null ? null : candidate.leagueId());
        article.setMatchId(candidate == null ? null : candidate.fixtureId());
        article.setStatus("PUBLISHED");
        article.setPublishTime(item.publishedAt() == null ? LocalDateTime.now() : item.publishedAt());
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        article.setIsHot(0);
        article.setIsFeatured(0);
        article.setIsTop(0);
        return article;
    }

    private NewsSourceArticleMap findExisting(String sourceName, String sourceArticleId) {
        return sourceMapMapper.selectOne(Wrappers.<NewsSourceArticleMap>lambdaQuery()
                .eq(NewsSourceArticleMap::getSourceName, sourceName)
                .eq(NewsSourceArticleMap::getSourceArticleId, sourceArticleId)
                .last("LIMIT 1"));
    }

    private NewsArticle findGlobalDuplicate(FeedItem item) {
        String link = item.link();
        if (StringUtils.hasText(link)) {
            NewsArticle byUrl = articleMapper.selectOne(Wrappers.<NewsArticle>lambdaQuery()
                    .eq(NewsArticle::getSourceUrl, link.trim()).last("LIMIT 1"));
            if (byUrl != null) return byUrl;
        }
        String normalizedTitle = normalize(item.title());
        if (!StringUtils.hasText(normalizedTitle)) return null;
        List<NewsArticle> candidates = articleMapper.selectList(Wrappers.<NewsArticle>lambdaQuery()
                .select(NewsArticle::getId, NewsArticle::getTitle, NewsArticle::getSourceUrl,
                        NewsArticle::getSummary, NewsArticle::getCoverImage)
                .eq(NewsArticle::getStatus, "PUBLISHED")
                .last("LIMIT 500"));
        return candidates.stream().filter(article -> normalizedTitle.equals(normalize(article.getTitle()))).findFirst().orElse(null);
    }

    private void enrichExistingArticle(Long articleId, FeedItem item) {
        if (articleId == null) return;
        NewsArticle article = articleMapper.selectById(articleId);
        if (article == null) return;
        boolean changed = false;
        if (!StringUtils.hasText(article.getSummary()) && StringUtils.hasText(item.summary())) {
            article.setSummary(limit(item.summary(), 1000));
            changed = true;
        }
        if (!StringUtils.hasText(article.getSubtitle()) && StringUtils.hasText(item.summary())) {
            article.setSubtitle(limit(item.summary(), 512));
            changed = true;
        }
        if (!StringUtils.hasText(article.getContent()) && StringUtils.hasText(item.summary())) {
            article.setContent(limit(item.summary(), 4000));
            article.setContentHtml("<p>外部来源摘要：</p><p>" + escapeHtml(limit(item.summary(), 4000)) + "</p>");
            changed = true;
        }
        if (!StringUtils.hasText(article.getCoverImage()) && StringUtils.hasText(item.imageUrl())) {
            article.setCoverImage(item.imageUrl());
            changed = true;
        }
        if (!StringUtils.hasText(article.getAuthor()) && StringUtils.hasText(item.author())) {
            article.setAuthor(item.author());
            changed = true;
        }
        if (changed) {
            article.setUpdatedAt(LocalDateTime.now());
            articleMapper.updateById(article);
        }
    }

    private FeedItem readItem(Element entry) {
        String title = text(entry, "title");
        String link = text(entry, "link");
        Element linkElement = entry.selectFirst("link[href]");
        if (linkElement != null && StringUtils.hasText(linkElement.attr("href"))) link = linkElement.attr("href");
        String sourceId = text(entry, "guid");
        if (!StringUtils.hasText(sourceId)) sourceId = text(entry, "id");
        if (!StringUtils.hasText(sourceId)) sourceId = StringUtils.hasText(link) ? link : title;
        String summary = firstNonBlank(text(entry, "description"), text(entry, "content\\:encoded"),
                text(entry, "summary"), text(entry, "content"));
        String author = text(entry, "author");
        if (!StringUtils.hasText(author)) author = text(entry, "creator");
        if (!StringUtils.hasText(author)) author = text(entry, "[name=creator]");
        String image = "";
        // RSS 媒体字段通常带命名空间（media:thumbnail/media:content），旧选择器无法匹配，
        // 导致 BBC RSS 的封面全部丢失。
        Element media = entry.selectFirst("media\\:content[url], media\\:thumbnail[url], content[url], thumbnail[url], enclosure[url], link[rel=enclosure][href]");
        if (media != null) image = firstNonBlank(media.attr("url"), media.attr("href"));
        if (!StringUtils.hasText(image)) {
            Element embeddedImage = Jsoup.parse(summary == null ? "" : summary).selectFirst("img[src]");
            if (embeddedImage != null) image = embeddedImage.attr("src");
        }
        String normalizedLink = link == null ? "" : link.trim();
        image = normalizeUrl(image, normalizedLink);
        LocalDateTime publishedAt = parseDate(firstNonBlank(text(entry, "pubDate"), text(entry, "published"), text(entry, "updated")));
        return new FeedItem(clean(title), clean(summary), normalizedLink, compactSourceId(sourceId.trim(), normalizedLink), clean(author), image, publishedAt);
    }

    private String compactSourceId(String sourceId, String link) {
        String value = StringUtils.hasText(sourceId) ? sourceId : link;
        if (value == null) value = "unknown";
        if (value.length() <= 120) return value;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value + "|" + link).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ignored) {
            return value.substring(0, 120);
        }
    }

    private MatchCandidate resolveMatch(String text, LocalDateTime publishedAt) {
        if (!StringUtils.hasText(text)) return null;
        LocalDateTime from = publishedAt == null ? LocalDateTime.now().minusDays(3) : publishedAt.minusDays(3);
        LocalDateTime to = publishedAt == null ? LocalDateTime.now().plusDays(3) : publishedAt.plusDays(3);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT fixture_id, league_id, league_name, home_team_name, away_team_name, match_time
                FROM crawler_matches
                WHERE match_time BETWEEN ? AND ? AND fixture_id IS NOT NULL
                ORDER BY ABS(TIMESTAMPDIFF(SECOND, match_time, ?)) ASC
                LIMIT 100
                """, from, to, publishedAt == null ? LocalDateTime.now() : publishedAt);
        String normalized = normalize(text);
        MatchCandidate best = null;
        int bestScore = 0;
        for (Map<String, Object> row : rows) {
            String home = normalize(String.valueOf(row.getOrDefault("home_team_name", "")));
            String away = normalize(String.valueOf(row.getOrDefault("away_team_name", "")));
            boolean homeHit = home.length() >= 2 && normalized.contains(home);
            boolean awayHit = away.length() >= 2 && normalized.contains(away);
            int score = homeHit && awayHit ? 100 : homeHit || awayHit ? 35 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = new MatchCandidate(longValue(row.get("fixture_id")), stringValue(row.get("league_id")),
                        stringValue(row.get("league_name")));
            }
        }
        return bestScore >= 100 ? best : null;
    }

    private String text(Element element, String selector) {
        Element child = element.selectFirst(selector);
        return child == null ? "" : child.text();
    }

    private LocalDateTime parseDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(value.replace("T", " ").replace("Z", "").substring(0, Math.min(19, value.length())), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); } catch (Exception ignored) {}
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·•]", "");
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", " ").replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private String normalizeUrl(String value, String baseUrl) {
        if (!StringUtils.hasText(value)) return "";
        String image = value.trim();
        if (image.startsWith("//")) return "https:" + image;
        try {
            return URI.create(baseUrl == null ? "" : baseUrl).resolve(image).toString();
        } catch (Exception ignored) {
            return image;
        }
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value;
        return "";
    }

    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private Long longValue(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception e) { return null; } }
    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private String limit(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }

    private record FeedItem(String title, String summary, String link, String sourceId, String author,
                            String imageUrl, LocalDateTime publishedAt) {}
    private record MatchCandidate(Long fixtureId, String leagueId, String leagueName) {}
}
