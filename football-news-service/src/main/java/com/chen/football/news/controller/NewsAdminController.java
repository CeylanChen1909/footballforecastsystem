package com.chen.football.news.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.service.NewsAdminPage;
import com.chen.football.news.service.NewsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
        return ApiResponse.ok(adminService.listArticles(keyword, status));
    }

    @GetMapping("/page")
    public ApiResponse<Map<String, Object>> page(@RequestParam(name = "keyword", required = false) String keyword,
                                                 @RequestParam(name = "status", required = false) String status,
                                                 @RequestParam(name = "page", defaultValue = "1") Integer page,
                                                 @RequestParam(name = "size", defaultValue = "20") Integer size) {
        NewsAdminPage result = adminService.listArticlesPage(keyword, status, page, size);
        return ApiResponse.ok(Map.of(
                "items", result.items(),
                "total", result.total(),
                "page", result.page(),
                "size", result.size()
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<NewsArticle> detail(@PathVariable(name = "id") Long id) {
        return ApiResponse.ok(adminService.getById(id));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody NewsArticle article) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", article.getTitle());
        payload.put("subtitle", article.getSubtitle());
        payload.put("summary", article.getSummary());
        payload.put("content", article.getContent());
        payload.put("contentHtml", article.getContentHtml());
        payload.put("coverImage", article.getCoverImage());payload.put("sourceName", article.getSourceName());
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
        return ApiResponse.ok(adminService.createArticle(payload, UserContext.getUserId(), UserContext.getUsername()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable(name = "id") Long id) {
        return ApiResponse.ok(adminService.deleteArticle(id, UserContext.getUserId(), UserContext.getUsername()));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable(name = "id") Long id,
                                                   @RequestParam(name = "status") String status) {
        return ApiResponse.ok(adminService.toggleStatus(id, status, UserContext.getUserId(), UserContext.getUsername()));
    }
}
