package com.chen.football.news.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.service.NewsAdminPage;
import com.chen.football.news.service.NewsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/news")
@RequiredArgsConstructor
public class NewsAdminController {

    private final NewsAdminService adminService;

    @GetMapping
    public ApiResponse<List<NewsArticle>> list(@RequestParam(name = "keyword", required = false) String keyword,
                                               @RequestParam(name = "status", required = false) String status) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(adminService.listArticles(normalize(keyword), normalize(status)));
    }

    @GetMapping("/page")
    public ApiResponse<Map<String, Object>> page(@RequestParam(name = "keyword", required = false) String keyword,
                                                 @RequestParam(name = "status", required = false) String status,
                                                 @RequestParam(name = "page", defaultValue = "1") Integer page,
                                                 @RequestParam(name = "size", defaultValue = "20") Integer size) {
        AdminGuard.requireAdmin();
        int safePage = Math.max(page == null ? 1 : page, 1);
        int safeSize = Math.min(Math.max(size == null ? 20 : size, 1), 100);
        NewsAdminPage result = adminService.listArticlesPage(normalize(keyword), normalize(status), safePage, safeSize);
        return ApiResponse.ok(Map.of(
                "items", result.items(),
                "total", result.total(),
                "page", result.page(),
                "size", result.size()
        ));
    }

    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> metrics() {
        return ApiResponse.ok(adminService.metrics());
    }

    @GetMapping("/{id}")
    public ApiResponse<NewsArticle> detail(@PathVariable(name = "id") Long id) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(adminService.getById(id));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody NewsArticle article) {
        AdminGuard.requireAdmin();
        if (article == null || !StringUtils.hasText(article.getTitle())) {
            throw new com.chen.football.common.exception.BusinessException("title 不能为空");
        }
        return ApiResponse.ok(adminService.createArticle(toPayload(article), UserContext.getUserId(), UserContext.getUsername()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable(name = "id") Long id, @RequestBody NewsArticle article) {
        AdminGuard.requireAdmin();
        if (article == null || !StringUtils.hasText(article.getTitle())) {
            throw new com.chen.football.common.exception.BusinessException("title 不能为空");
        }
        return ApiResponse.ok(adminService.updateArticle(id, toPayload(article), UserContext.getUserId(), UserContext.getUsername()));
    }

    private Map<String, Object> toPayload(NewsArticle article) {
        article.setTitle(article.getTitle().trim());
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("title", article.getTitle());
        payload.put("subtitle", article.getSubtitle());
        payload.put("summary", article.getSummary());
        payload.put("content", article.getContent());
        payload.put("contentHtml", article.getContentHtml());
        payload.put("coverImage", article.getCoverImage());
        payload.put("sourceName", article.getSourceName());
        payload.put("sourceUrl", article.getSourceUrl());
        payload.put("sourceType", article.getSourceType());
        payload.put("author", article.getAuthor());
        payload.put("category", article.getCategory());
        payload.put("leagueName", article.getLeagueName());
        payload.put("leagueId", article.getLeagueId());
        payload.put("matchId", article.getMatchId());
        payload.put("teamId", article.getTeamId());
        payload.put("status", article.getStatus());
        payload.put("isHot", article.getIsHot());
        payload.put("isFeatured", article.getIsFeatured());
        payload.put("isTop", article.getIsTop());
        return payload;
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable(name = "id") Long id) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(adminService.deleteArticle(id, UserContext.getUserId(), UserContext.getUsername()));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable(name = "id") Long id,
                                                   @RequestParam(name = "status") String status) {
        AdminGuard.requireAdmin();
        String safeStatus = normalize(status);
        if (!List.of("DRAFT", "PUBLISHED", "HIDDEN", "ARCHIVED").contains(safeStatus.toUpperCase())) {
            return ApiResponse.ok(Map.of("ok", false, "message", "status 不合法"));
        }
        return ApiResponse.ok(adminService.toggleStatus(id, safeStatus, UserContext.getUserId(), UserContext.getUsername()));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
