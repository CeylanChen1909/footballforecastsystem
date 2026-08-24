package com.chen.football.news.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.exception.BusinessException;
import com.chen.football.common.exception.UnauthorizedException;
import com.chen.football.news.dto.NewsArticleDetailDTO;
import com.chen.football.news.dto.NewsArticleSummaryDTO;
import com.chen.football.news.dto.NewsCommentDTO;
import com.chen.football.news.entity.NewsArticleComment;
import com.chen.football.news.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsService newsService;

    @GetMapping("/latest")
    public ApiResponse<List<NewsArticleSummaryDTO>> latest() {
        return ApiResponse.ok(newsService.getLatestArticles());
    }

    @GetMapping("/feed")
    public ApiResponse<Map<String, Object>> feed(@RequestParam(name = "page", defaultValue = "1") Integer page,
                                                 @RequestParam(name = "size", defaultValue = "10") Integer size,
                                                 @RequestParam(name = "category", required = false) String category,
                                                 @RequestParam(name = "keyword", required = false) String keyword,
                                                 @RequestParam(name = "sortBy", defaultValue = "latest") String sortBy) {
        var result = newsService.getFeedPage(page, size, category, keyword, sortBy);
        return ApiResponse.ok(Map.of(
                "items", result.items(),
                "total", result.total(),
                "page", result.page(),
                "size", result.size()
        ));
    }

    @GetMapping("/articles/{id}")
    public ApiResponse<NewsArticleDetailDTO> detail(@PathVariable(name = "id") Long id) {
        // userId 从登录态获取，不再信任前端传参（防越权）
        NewsArticleDetailDTO dto = newsService.getArticleDetail(id, UserContext.getUserId());
        return dto == null ? new ApiResponse<>(false, "资讯不存在", null) : ApiResponse.ok(dto);
    }

    @GetMapping("/articles/{id}/related")
    public ApiResponse<List<NewsArticleSummaryDTO>> related(@PathVariable(name = "id") Long id,
                                                            @RequestParam(name = "limit", defaultValue = "6") Integer limit) {
        return ApiResponse.ok(newsService.getRecommendations(id, limit));
    }

    @GetMapping("/articles/{id}/comments")
    public ApiResponse<List<NewsCommentDTO>> comments(@PathVariable(name = "id") Long id) {
        return ApiResponse.ok(newsService.getComments(id));
    }

    @PostMapping("/articles/{id}/comments")
    public ApiResponse<NewsArticleComment> addComment(@PathVariable(name = "id") Long id,
                                                      @RequestParam(name = "content") String content,
                                                      @RequestParam(name = "parentId", required = false) Long parentId) {
        return ApiResponse.ok(newsService.addComment(id, currentUserId(), content, parentId));
    }

    @PostMapping("/articles/comments/{commentId}/report")
    public ApiResponse<Map<String, Object>> reportComment(@PathVariable(name = "commentId") Long commentId,
                                                           @RequestParam(name = "reason", required = false) String reason) {
        newsService.reportComment(commentId, currentUserId(), reason);
        return ApiResponse.ok(Map.of("reported", true));
    }

    @PostMapping("/articles/{id}/like")
    public ApiResponse<Map<String, Object>> toggleLike(@PathVariable(name = "id") Long id) {
        boolean liked = newsService.toggleLike(id, currentUserId());
        return ApiResponse.ok(Map.of("liked", liked));
    }

    @PostMapping("/articles/{id}/favorite")
    public ApiResponse<Map<String, Object>> toggleFavorite(@PathVariable(name = "id") Long id) {
        boolean favorited = newsService.toggleFavorite(id, currentUserId());
        return ApiResponse.ok(Map.of("favorited", favorited));
    }

    /**
     * 评论点赞（基于 t_news_article_comment_like 记录表去重，返回切换后的点赞状态）
     */
    @PostMapping("/articles/comments/{commentId}/like")
    public ApiResponse<Map<String, Object>> toggleCommentLike(@PathVariable(name = "commentId") Long commentId) {
        return ApiResponse.ok(Map.of("liked", newsService.toggleCommentLike(commentId, currentUserId())));
    }

    @GetMapping("/spotlights")
    public ApiResponse<List<Map<String, Object>>> spotlights() {
        return ApiResponse.ok(newsService.getSpotlights());
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.ok(newsService.getAllCategories());
    }

    @GetMapping("/tags")
    public ApiResponse<List<Map<String, Object>>> tags(@RequestParam(name = "limit", defaultValue = "20") Integer limit) {
        return ApiResponse.ok(newsService.getTopTags(limit));
    }

    @GetMapping("/recommendations")
    public ApiResponse<List<NewsArticleSummaryDTO>> recommendations(@RequestParam(name = "articleId", required = false) Long articleId,
                                                                     @RequestParam(name = "limit", defaultValue = "8") Integer limit) {
        return ApiResponse.ok(newsService.getRecommendations(articleId, limit));
    }

    private Long currentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        return userId;
    }
}
