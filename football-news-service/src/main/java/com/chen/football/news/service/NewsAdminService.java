package com.chen.football.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.entity.NewsArticleAuditLog;
import com.chen.football.news.mapper.NewsArticleAuditLogMapper;
import com.chen.football.news.mapper.NewsArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NewsAdminService {
    private final NewsArticleMapper articleMapper;
    private final NewsArticleAuditLogMapper auditLogMapper;

    public List<NewsArticle> listArticles(String keyword, String status) {
        LambdaQueryWrapper<NewsArticle> q = Wrappers.lambdaQuery();
        if (keyword != null && !keyword.isBlank()) q.and(x -> x.like(NewsArticle::getTitle, keyword).or().like(NewsArticle::getSummary, keyword));
        if (status != null && !status.isBlank()) q.eq(NewsArticle::getStatus, status);
        q.orderByDesc(NewsArticle::getPublishTime).orderByDesc(NewsArticle::getUpdatedAt);
        return articleMapper.selectList(q);
    }

    public NewsAdminPage listArticlesPage(String keyword, String status, Integer page, Integer size) {
        int safePage = Math.max(1, page == null ? 1 : page);
        int safeSize = Math.max(1, Math.min(size == null ? 20 : size, 100));
        LambdaQueryWrapper<NewsArticle> q = Wrappers.lambdaQuery();
        if (keyword != null && !keyword.isBlank()) q.and(x -> x.like(NewsArticle::getTitle, keyword).or().like(NewsArticle::getSummary, keyword));
        if (status != null && !status.isBlank()) q.eq(NewsArticle::getStatus, status);
        q.orderByDesc(NewsArticle::getPublishTime).orderByDesc(NewsArticle::getUpdatedAt);
        long total = articleMapper.selectCount(q);
        q.last("LIMIT " + ((safePage - 1) * safeSize) + "," + safeSize);
        return new NewsAdminPage(articleMapper.selectList(q), total, safePage, safeSize);
    }

    public NewsArticle getById(Long id) { return articleMapper.selectById(id); }

    public Map<String, Object> createArticle(Map<String, Object> payload, Long operatorId, String operatorName) {
        NewsArticle article = new NewsArticle();
        applyPayload(article, payload);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        if (article.getPublishTime() == null && "PUBLISHED".equals(article.getStatus())) article.setPublishTime(LocalDateTime.now());
        articleMapper.insert(article);
        audit(operatorId, operatorName, "NEWS", "CREATE", String.valueOf(article.getId()), article.getTitle(), "SUCCESS");
        return Map.of("ok", true, "id", article.getId());
    }

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

    public Map<String, Object> deleteArticle(Long id, Long operatorId, String operatorName) {
        NewsArticle article = articleMapper.selectById(id);
        boolean ok = article != null && articleMapper.deleteById(id) > 0;
        audit(operatorId, operatorName, "NEWS", "DELETE", String.valueOf(id), article != null ? article.getTitle() : null, ok ? "SUCCESS" : "FAILED");
        return Map.of("ok", ok);
    }

    public Map<String, Object> toggleStatus(Long id, String status, Long operatorId, String operatorName) {
        NewsArticle article = articleMapper.selectById(id);
        if (article == null) return Map.of("ok", false, "message", "资讯不存在");
        article.setStatus(status);
        if ("PUBLISHED".equals(status) && article.getPublishTime() == null) article.setPublishTime(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        boolean ok = articleMapper.updateById(article) > 0;
        audit(operatorId, operatorName, "NEWS", "STATUS", String.valueOf(id), article.getTitle() + " -> " + status, ok ? "SUCCESS" : "FAILED");
        return Map.of("ok", ok);
    }

    private void applyPayload(NewsArticle article, Map<String, Object> payload) {
        if (payload.get("title") != null) article.setTitle(String.valueOf(payload.get("title")));
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
        if (payload.get("matchId") != null) article.setMatchId(Long.parseLong(String.valueOf(payload.get("matchId"))));
        if (payload.get("teamId") != null) article.setTeamId(Long.parseLong(String.valueOf(payload.get("teamId"))));
        if (payload.get("status") != null) article.setStatus(String.valueOf(payload.get("status")));
        if (payload.get("isHot") != null) article.setIsHot(Integer.parseInt(String.valueOf(payload.get("isHot"))));
        if (payload.get("isFeatured") != null) article.setIsFeatured(Integer.parseInt(String.valueOf(payload.get("isFeatured"))));
        if (payload.get("isTop") != null) article.setIsTop(Integer.parseInt(String.valueOf(payload.get("isTop"))));
    }

    private void audit(Long operatorId, String operatorName, String module, String action, String targetId, String content, String result) {
        NewsArticleAuditLog log = new NewsArticleAuditLog();
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setModule(module);
        log.setAction(action);
        log.setTargetType("t_news_article");
        log.setTargetId(targetId);
        log.setContent(content);
        log.setResult(result);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
