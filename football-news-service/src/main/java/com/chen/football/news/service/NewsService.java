package com.chen.football.news.service;

import com.chen.football.news.dto.NewsArticleDetailDTO;
import com.chen.football.news.dto.NewsArticleSummaryDTO;
import com.chen.football.news.dto.NewsCommentDTO;
import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.entity.NewsArticleComment;

import java.util.List;
import java.util.Map;

public interface NewsService {
    List<NewsArticleSummaryDTO> getLatestArticles();
    NewsFeedPage getFeedPage(Integer page, Integer size, String category, String keyword, String sortBy);
    List<NewsArticleSummaryDTO> getFeed(Integer page, Integer size, String category, String keyword, String sortBy);
    long countArticles(String category, String keyword);
    NewsArticleDetailDTO getArticleDetail(Long id, Long userId);
    List<NewsArticleSummaryDTO> getRecommendations(Long articleId, Integer limit);
    List<NewsCommentDTO> getComments(Long articleId);
    NewsArticleComment addComment(Long articleId, Long userId, String content, Long parentId);
    boolean toggleLike(Long articleId, Long userId);
    boolean toggleFavorite(Long articleId, Long userId);
    List<Map<String, Object>> getSpotlights();
    List<String> getAllCategories();
    List<Map<String, Object>> getTopTags(Integer limit);
    NewsArticle getById(Long id);
}
