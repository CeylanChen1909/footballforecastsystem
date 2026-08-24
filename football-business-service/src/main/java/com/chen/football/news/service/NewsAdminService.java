package com.chen.football.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.entity.NewsArticleAuditLog;
import com.chen.football.news.mapper.NewsArticleAuditLogMapper;
import com.chen.football.news.mapper.NewsArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NewsAdminService {
    private final NewsArticleMapper articleMapper;
    private final NewsArticleAuditLogMapper auditLogMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public Map<String, Object> metrics() {
        AdminGuard.requireAdmin();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_news_article", Long.class));
        result.put("published", countStatus("PUBLISHED"));
        result.put("draft", countStatus("DRAFT"));
        result.put("hidden", countStatus("HIDDEN"));
        result.put("archived", countStatus("ARCHIVED"));
        result.put("deleted", countStatus("DELETED"));
        result.put("pendingReview", countStatus("DRAFT"));
        result.put("todayPublished", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_news_article WHERE publish_time >= ? AND publish_time < ? AND status = 'PUBLISHED'",
                Long.class, start, end));
        result.put("timezone", "Asia/Shanghai");
        result.put("generatedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        List<String> trendDays = new java.util.ArrayList<>();
        List<Long> trendNews = new java.util.ArrayList<>();
        List<Long> trendLogs = new java.util.ArrayList<>();
        for (int offset = 6; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            LocalDateTime dayStart = day.atStartOfDay();
            LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
            trendDays.add(day.toString());
            trendNews.add(safeCount("SELECT COUNT(*) FROM t_news_article WHERE publish_time >= ? AND publish_time < ?", dayStart, dayEnd));
            trendLogs.add(safeCount("SELECT COUNT(*) FROM t_admin_audit_log WHERE created_at >= ? AND created_at < ?", dayStart, dayEnd));
        }
        result.put("trend", Map.of("days", trendDays, "news", trendNews, "logs", trendLogs));
        return result;
    }

    private long countStatus(String status) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_news_article WHERE status = ?", Long.class, status);
        return count == null ? 0L : count;
    }

    private long safeCount(String sql, Object... args) {
        try {
            Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
            return count == null ? 0L : count;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public List<NewsArticle> listArticles(String keyword, String status) {
        AdminGuard.requireAdmin();
        LambdaQueryWrapper<NewsArticle> q = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) q.and(x -> x.like(NewsArticle::getTitle, keyword).or().like(NewsArticle::getSummary, keyword));
        if (StringUtils.hasText(status)) q.eq(NewsArticle::getStatus, status.trim());
        q.orderByDesc(NewsArticle::getPublishTime).orderByDesc(NewsArticle::getUpdatedAt);
        return articleMapper.selectList(q);
    }

    public NewsAdminPage listArticlesPage(String keyword, String status, Integer page, Integer size) {
        AdminGuard.requireAdmin();
        int safePage = Math.max(1, page == null ? 1 : page);
        int safeSize = Math.max(1, Math.min(size == null ? 20 : size, 100));
        LambdaQueryWrapper<NewsArticle> q = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) q.and(x -> x.like(NewsArticle::getTitle, keyword).or().like(NewsArticle::getSummary, keyword));
        if (StringUtils.hasText(status)) q.eq(NewsArticle::getStatus, status.trim());
        q.orderByDesc(NewsArticle::getPublishTime).orderByDesc(NewsArticle::getUpdatedAt);
        long total = articleMapper.selectCount(q);
        q.last("LIMIT " + ((safePage - 1) * safeSize) + "," + safeSize);
        return new NewsAdminPage(articleMapper.selectList(q), total, safePage, safeSize);
    }

    public NewsArticle getById(Long id) { return articleMapper.selectById(id); }

    @Transactional
    public Map<String, Object> createArticle(Map<String, Object> payload, Long operatorId, String operatorName) {
        NewsArticle article = new NewsArticle();
        applyPayload(article, payload);
        if (!StringUtils.hasText(article.getTitle())) {
            return Map.of("ok", false, "message", "title 不能为空");
        }
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        if (article.getPublishTime() == null && "PUBLISHED".equals(article.getStatus())) article.setPublishTime(LocalDateTime.now());
        articleMapper.insert(article);
        audit(operatorId, operatorName, "NEWS", "CREATE", String.valueOf(article.getId()), article.getTitle(), "SUCCESS");
        return Map.of("ok", true, "id", article.getId());
    }

    @Transactional
    public Map<String, Object> updateArticle(Long id, Map<String, Object> payload, Long operatorId, String operatorName) {
        NewsArticle article = articleMapper.selectById(id);
        if (article == null) return Map.of("ok", false, "message", "资讯不存在");
        applyPayload(article, payload);
        article.setUpdatedAt(LocalDateTime.now());
        if (article.getPublishTime() == null && "PUBLISHED".equals(article.getStatus())) article.setPublishTime(LocalDateTime.now());
        articleMapper.updateById(article);
        audit(operatorId, operatorName, "NEWS", "UPDATE", String.valueOf(id), article.getTitle(), "SUCCESS");
        return Map.of("ok", true, "id", id);
    }

    @Transactional
    public Map<String, Object> deleteArticle(Long id, Long operatorId, String operatorName) {
        NewsArticle article = articleMapper.selectById(id);
        boolean ok = article != null && articleMapper.deleteById(id) > 0;
        audit(operatorId, operatorName, "NEWS", "DELETE", String.valueOf(id), article != null ? article.getTitle() : null, ok ? "SUCCESS" : "FAILED");
        return Map.of("ok", ok);
    }

    @Transactional
    public Map<String, Object> toggleStatus(Long id, String status, Long operatorId, String operatorName) {
        NewsArticle article = articleMapper.selectById(id);
        if (article == null) return Map.of("ok", false, "message", "资讯不存在");
        if (!List.of("DRAFT", "PUBLISHED", "ARCHIVED", "HIDDEN").contains(status)) {
            return Map.of("ok", false, "message", "status 不合法");
        }
        article.setStatus(status);
        if ("PUBLISHED".equals(status) && article.getPublishTime() == null) article.setPublishTime(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        boolean ok = articleMapper.updateById(article) > 0;
        audit(operatorId, operatorName, "NEWS", "STATUS", String.valueOf(id), article.getTitle() + " -> " + status, ok ? "SUCCESS" : "FAILED");
        return Map.of("ok", ok);
    }

    private void applyPayload(NewsArticle article, Map<String, Object> payload) {
        if (payload == null) return;
        if (payload.get("title") != null) article.setTitle(String.valueOf(payload.get("title")).trim());
        if (payload.get("subtitle") != null) article.setSubtitle(String.valueOf(payload.get("subtitle")));
        if (payload.get("summary") != null) article.setSummary(String.valueOf(payload.get("summary")));
        if (payload.get("content") != null) article.setContent(String.valueOf(payload.get("content")));
        if (payload.get("contentHtml") != null) article.setContentHtml(String.valueOf(payload.get("contentHtml")));
        if (payload.get("coverImage") != null) article.setCoverImage(String.valueOf(payload.get("coverImage")));
        if (payload.get("sourceName") != null) article.setSourceName(String.valueOf(payload.get("sourceName")));
        if (payload.get("sourceUrl") != null) article.setSourceUrl(String.valueOf(payload.get("sourceUrl")));
        if (payload.get("sourceType") != null) article.setSourceType(String.valueOf(payload.get("sourceType")));
        if (payload.get("author") != null) article.setAuthor(String.valueOf(payload.get("author")));
        if (payload.get("category") != null) article.setCategory(String.valueOf(payload.get("category")));
        if (payload.get("leagueName") != null) article.setLeagueName(String.valueOf(payload.get("leagueName")));
        if (payload.get("leagueId") != null) article.setLeagueId(String.valueOf(payload.get("leagueId")));
        article.setMatchId(parseLong(payload.get("matchId"), article.getMatchId()));
        article.setTeamId(parseLong(payload.get("teamId"), article.getTeamId()));
        if (payload.get("status") != null) article.setStatus(String.valueOf(payload.get("status")).trim());
        article.setIsHot(parseInt(payload.get("isHot"), article.getIsHot()));
        article.setIsFeatured(parseInt(payload.get("isFeatured"), article.getIsFeatured()));
        article.setIsTop(parseInt(payload.get("isTop"), article.getIsTop()));
    }

    private Long parseLong(Object value, Long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private Integer parseInt(Object value, Integer fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void audit(Long operatorId, String operatorName, String module, String action, String targetId, String content, String result) {
        NewsArticleAuditLog audit = new NewsArticleAuditLog();
        audit.setOperatorId(operatorId);
        audit.setOperatorName(operatorName);
        audit.setModule(module);
        audit.setAction(action);
        audit.setTargetType("t_news_article");
        audit.setTargetId(targetId);
        audit.setContent(content);
        audit.setResult(result);
        audit.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(audit);
    }
}
