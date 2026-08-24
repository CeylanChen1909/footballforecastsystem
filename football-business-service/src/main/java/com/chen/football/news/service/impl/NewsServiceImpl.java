package com.chen.football.news.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chen.football.common.context.UserContext;
import com.chen.football.news.dto.NewsArticleDetailDTO;
import com.chen.football.news.dto.NewsArticleSummaryDTO;
import com.chen.football.news.dto.NewsCommentDTO;
import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.entity.NewsArticleComment;
import com.chen.football.news.entity.NewsArticleFavorite;
import com.chen.football.news.entity.NewsArticleLike;
import com.chen.football.news.entity.NewsArticleCommentLike;
import com.chen.football.news.entity.NewsArticleTagRel;
import com.chen.football.news.entity.NewsTag;
import com.chen.football.news.mapper.NewsArticleCommentMapper;
import com.chen.football.news.mapper.NewsArticleCommentLikeMapper;
import com.chen.football.news.mapper.NewsArticleFavoriteMapper;
import com.chen.football.news.mapper.NewsArticleLikeMapper;
import com.chen.football.news.mapper.NewsArticleMapper;
import com.chen.football.news.mapper.NewsArticleTagRelMapper;
import com.chen.football.news.mapper.NewsSpotlightMapper;
import com.chen.football.news.mapper.NewsTagMapper;
import com.chen.football.news.service.NewsFeedPage;
import com.chen.football.news.service.NewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewsServiceImpl implements NewsService {
    private final NewsArticleMapper articleMapper;
    private final NewsArticleCommentMapper commentMapper;
    private final NewsArticleCommentLikeMapper commentLikeMapper;
    private final NewsArticleLikeMapper likeMapper;
    private final NewsArticleFavoriteMapper favoriteMapper;
    private final NewsSpotlightMapper spotlightMapper;
    private final NewsTagMapper tagMapper;
    private final NewsArticleTagRelMapper tagRelMapper;
    private final JdbcTemplate jdbcTemplate;

    /** user-service 地址（跨服务获取用户名，避免直连用户表） */
    @Value("${user-service.base-url:http://127.0.0.1:9001}")
    private String userServiceBaseUrl;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public List<NewsArticleSummaryDTO> getLatestArticles() {
        return getFeed(1, 10, null, null, "latest");
    }

    @Override
    public NewsFeedPage getFeedPage(Integer page, Integer size, String category, String keyword, String sortBy) {
        int safePage = Math.max(1, page == null ? 1 : page);
        // Public controllers cap requests at 100.  Internal mixed-feed merges
        // may need a larger bounded window to produce a correct global page.
        int safeSize = Math.max(1, Math.min(size == null ? 10 : size, 1000));
        LambdaQueryWrapper<NewsArticle> q = basePublishedQuery(category, keyword);
        applySort(q, sortBy);
        long total = articleMapper.selectCount(q);
        q.last("LIMIT " + ((safePage - 1) * safeSize) + "," + safeSize);
        List<NewsArticle> articles = articleMapper.selectList(q);
        List<Long> articleIds = articles.stream().map(NewsArticle::getId).filter(java.util.Objects::nonNull).toList();
        Map<Long, Long> likeCounts = batchCount(articleIds, "t_news_article_like", "article_id");
        Map<Long, Long> favoriteCounts = batchCount(articleIds, "t_news_article_favorite", "article_id");
        Map<Long, Long> commentCounts = batchCount(articleIds, "t_news_article_comment", "article_id", "status = 'PUBLISHED'");
        Map<Long, List<String>> tagsByArticle = batchTags(articleIds);
        List<NewsArticleSummaryDTO> items = articles.stream().map(a -> toSummaryBatch(a, likeCounts, favoriteCounts, commentCounts, tagsByArticle)).toList();
        return new NewsFeedPage(items, total, safePage, safeSize);
    }

    @Override
    public List<NewsArticleSummaryDTO> getFeed(Integer page, Integer size, String category, String keyword, String sortBy) {
        return getFeedPage(page, size, category, keyword, sortBy).items();
    }

    @Override
    public List<NewsArticleSummaryDTO> getFeedByMatchId(Long matchId, Integer limit) {
        if (matchId == null) return Collections.emptyList();
        int safeLimit = Math.max(1, Math.min(limit == null ? 8 : limit, 50));
        LambdaQueryWrapper<NewsArticle> q = basePublishedQuery(null, null)
                .eq(NewsArticle::getMatchId, matchId)
                .orderByDesc(NewsArticle::getIsTop)
                .orderByDesc(NewsArticle::getIsFeatured)
                .orderByDesc(NewsArticle::getPublishTime)
                .last("LIMIT " + safeLimit);
        return articleMapper.selectList(q).stream().map(this::toSummarySingle).toList();
    }

    @Override
    public long countArticles(String category, String keyword) {
        return articleMapper.selectCount(basePublishedQuery(category, keyword));
    }

    @Override
    public NewsArticleDetailDTO getArticleDetail(Long id, Long userId) {
        NewsArticle article = articleMapper.selectById(id);
        if (article == null || !"PUBLISHED".equalsIgnoreCase(article.getStatus())) return null;

        NewsArticleDetailDTO dto = toDetail(article);
        dto.setTags(getArticleTags(id));
        dto.setLikeCount(countLikes(id));
        dto.setFavoriteCount(countFavorites(id));
        dto.setCommentCount(countComments(id));
        dto.setLiked(userId != null && isLiked(id, userId));
        dto.setFavorited(userId != null && isFavorited(id, userId));
        return dto;
    }

    @Override
    public List<NewsArticleSummaryDTO> getRecommendations(Long articleId, Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 8 : limit, 20));
        NewsArticle current = articleId == null ? null : articleMapper.selectById(articleId);
        String category = current == null ? null : current.getCategory();

        LambdaQueryWrapper<NewsArticle> q = Wrappers.lambdaQuery();
        q.eq(NewsArticle::getStatus, "PUBLISHED");
        if (category != null && !category.isBlank()) q.eq(NewsArticle::getCategory, category);
        if (articleId != null) q.ne(NewsArticle::getId, articleId);
        q.orderByDesc(NewsArticle::getIsTop).orderByDesc(NewsArticle::getIsFeatured).orderByDesc(NewsArticle::getPublishTime).last("LIMIT " + safeLimit);
        return articleMapper.selectList(q).stream().map(this::toSummarySingle).toList();
    }

    @Override
    public List<NewsCommentDTO> getComments(Long articleId) {
        List<NewsArticleComment> comments = commentMapper.selectList(
                Wrappers.<NewsArticleComment>lambdaQuery()
                        .eq(NewsArticleComment::getArticleId, articleId)
                        .eq(NewsArticleComment::getStatus, "PUBLISHED")
                        .orderByAsc(NewsArticleComment::getParentId)
                        .orderByAsc(NewsArticleComment::getCreatedAt)
        );
        if (comments.isEmpty()) return Collections.emptyList();

        List<Long> userIds = comments.stream().map(NewsArticleComment::getUserId).distinct().toList();
        Map<Long, String> usernames = loadUsernames(userIds);

        // 批量查询当前登录用户对这批评论的点赞状态
        Long currentUserId = UserContext.getUserId();
        java.util.Set<Long> likedCommentIds = new java.util.HashSet<>();
        if (currentUserId != null) {
            List<NewsArticleCommentLike> likes = commentLikeMapper.selectList(
                    Wrappers.<NewsArticleCommentLike>lambdaQuery()
                            .eq(NewsArticleCommentLike::getUserId, currentUserId)
                            .in(NewsArticleCommentLike::getCommentId, comments.stream().map(NewsArticleComment::getId).toList()));
            likes.forEach(l -> likedCommentIds.add(l.getCommentId()));
        }

        return comments.stream().map(c -> {
            NewsCommentDTO dto = new NewsCommentDTO();
            dto.setId(c.getId());
            dto.setArticleId(c.getArticleId());
            dto.setUserId(c.getUserId());
            dto.setParentId(c.getParentId());
            dto.setUsername(usernames.getOrDefault(c.getUserId(), "用户" + c.getUserId()));
            dto.setContent(c.getContent());
            dto.setLikeCount(c.getLikeCount());
            dto.setStatus(c.getStatus());
            dto.setLiked(currentUserId != null && likedCommentIds.contains(c.getId()));
            dto.setCreatedAt(c.getCreatedAt());
            return dto;
        }).toList();
    }

    @Override
    @Transactional
    public NewsArticleComment addComment(Long articleId, Long userId, String content, Long parentId) {
        if (articleId == null || userId == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("参数不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() < 2 || normalized.length() > 500) {
            throw new IllegalArgumentException("评论长度需在 2-500 个字符之间");
        }
        long recent = commentMapper.selectCount(Wrappers.<NewsArticleComment>lambdaQuery()
                .eq(NewsArticleComment::getUserId, userId)
                .ge(NewsArticleComment::getCreatedAt, LocalDateTime.now().minusMinutes(1)));
        if (recent >= 5) throw new IllegalArgumentException("评论过于频繁，请稍后再试");
        String lower = normalized.toLowerCase();
        if (List.of("博彩", "刷单", "加微信", "http://", "https://").stream().anyMatch(lower::contains)) {
            throw new IllegalArgumentException("评论包含暂不允许发布的内容");
        }
        NewsArticle article = articleMapper.selectById(articleId);
        if (article == null || !"PUBLISHED".equalsIgnoreCase(article.getStatus())) {
            throw new IllegalArgumentException("资讯不存在或不可评论");
        }
        if (parentId != null && commentMapper.selectById(parentId) == null) {
            throw new IllegalArgumentException("父评论不存在");
        }
        NewsArticleComment comment = new NewsArticleComment();
        comment.setArticleId(articleId);
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setContent(normalized);
        comment.setLikeCount(0);
        // 新评论进入审核队列，审核通过后才对其他用户可见。
        comment.setStatus("PENDING");
        comment.setCreatedAt(LocalDateTime.now());
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.insert(comment);
        return comment;
    }

    @Override
    @Transactional
    public void reportComment(Long commentId, Long userId, String reason) {
        if (commentId == null || userId == null) throw new IllegalArgumentException("参数不能为空");
        NewsArticleComment comment = commentMapper.selectById(commentId);
        if (comment == null) throw new IllegalArgumentException("评论不存在");
        if ("PUBLISHED".equalsIgnoreCase(comment.getStatus())) {
            comment.setStatus("REPORTED");
            comment.setUpdatedAt(LocalDateTime.now());
            commentMapper.updateById(comment);
        }
    }

    @Override
    @Transactional
    public boolean toggleLike(Long articleId, Long userId) {
        if (articleId == null || userId == null) throw new IllegalArgumentException("参数不能为空");
        LambdaQueryWrapper<NewsArticleLike> q = Wrappers.<NewsArticleLike>lambdaQuery()
                .eq(NewsArticleLike::getArticleId, articleId)
                .eq(NewsArticleLike::getUserId, userId);
        NewsArticleLike existed = likeMapper.selectOne(q);
        if (existed != null) {
            likeMapper.deleteById(existed.getId());
            return false;
        }
        NewsArticleLike like = new NewsArticleLike();
        like.setArticleId(articleId);
        like.setUserId(userId);
        like.setCreatedAt(LocalDateTime.now());
        likeMapper.insert(like);
        return true;
    }

    @Override
    @Transactional
    public boolean toggleFavorite(Long articleId, Long userId) {
        if (articleId == null || userId == null) throw new IllegalArgumentException("参数不能为空");
        LambdaQueryWrapper<NewsArticleFavorite> q = Wrappers.<NewsArticleFavorite>lambdaQuery()
                .eq(NewsArticleFavorite::getArticleId, articleId)
                .eq(NewsArticleFavorite::getUserId, userId);
        NewsArticleFavorite existed = favoriteMapper.selectOne(q);
        if (existed != null) {
            favoriteMapper.deleteById(existed.getId());
            return false;
        }
        NewsArticleFavorite fav = new NewsArticleFavorite();
        fav.setArticleId(articleId);
        fav.setUserId(userId);
        fav.setCreatedAt(LocalDateTime.now());
        favoriteMapper.insert(fav);
        return true;
    }

    @Override
    public boolean toggleCommentLike(Long commentId, Long userId) {
        if (commentId == null || userId == null) throw new IllegalArgumentException("参数不能为空");
        NewsArticleComment comment = commentMapper.selectById(commentId);
        if (comment == null) throw new IllegalArgumentException("评论不存在");
        NewsArticleCommentLike existed = commentLikeMapper.selectOne(
                Wrappers.<NewsArticleCommentLike>lambdaQuery()
                        .eq(NewsArticleCommentLike::getCommentId, commentId)
                        .eq(NewsArticleCommentLike::getUserId, userId));
        int delta;
        if (existed != null) {
            commentLikeMapper.deleteById(existed.getId());
            delta = -1;
        } else {
            NewsArticleCommentLike like = new NewsArticleCommentLike();
            like.setCommentId(commentId);
            like.setUserId(userId);
            like.setCreatedAt(LocalDateTime.now());
            commentLikeMapper.insert(like);
            delta = 1;
        }
        int current = comment.getLikeCount() == null ? 0 : comment.getLikeCount();
        comment.setLikeCount(Math.max(0, current + delta));
        commentMapper.updateById(comment);
        return existed == null;
    }

    @Override
    public List<Map<String, Object>> getSpotlights() {
        return spotlightMapper.selectList(
                Wrappers.<com.chen.football.news.entity.NewsSpotlight>lambdaQuery()
                        .orderByAsc(com.chen.football.news.entity.NewsSpotlight::getDisplayOrder)
                        .last("LIMIT 20")
        ).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("subtitle", s.getSubtitle());
            m.put("coverImage", s.getCoverImage());
            m.put("summary", s.getSummary());
            m.put("displayOrder", s.getDisplayOrder());
            m.put("position", s.getPosition());
            return m;
        }).toList();
    }

    @Override
    public List<String> getAllCategories() {
        return articleMapper.selectList(Wrappers.<NewsArticle>lambdaQuery().select(NewsArticle::getCategory).eq(NewsArticle::getStatus, "PUBLISHED"))
                .stream().map(NewsArticle::getCategory).filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    @Override
    public List<Map<String, Object>> getTopTags(Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 50));
        List<NewsTag> tags = tagMapper.selectList(Wrappers.<NewsTag>lambdaQuery().last("LIMIT " + safeLimit));
        if (tags.isEmpty()) return Collections.emptyList();
        return tags.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("slug", t.getSlug());
            return m;
        }).toList();
    }

    @Override
    public NewsArticle getById(Long id) { return articleMapper.selectById(id); }

    private LambdaQueryWrapper<NewsArticle> basePublishedQuery(String category, String keyword) {
        LambdaQueryWrapper<NewsArticle> q = Wrappers.lambdaQuery();
        q.eq(NewsArticle::getStatus, "PUBLISHED");
        if (category != null && !category.isBlank()) q.eq(NewsArticle::getCategory, category);
        if (keyword != null && !keyword.isBlank()) q.and(x -> x.like(NewsArticle::getTitle, keyword).or().like(NewsArticle::getSummary, keyword).or().like(NewsArticle::getContent, keyword));
        return q;
    }

    private void applySort(LambdaQueryWrapper<NewsArticle> q, String sortBy) {
        if ("hot".equalsIgnoreCase(sortBy)) {
            q.orderByDesc(NewsArticle::getIsTop).orderByDesc(NewsArticle::getIsHot).orderByDesc(NewsArticle::getPublishTime).orderByDesc(NewsArticle::getUpdatedAt);
        } else if ("comments".equalsIgnoreCase(sortBy)) {
            q.orderByDesc(NewsArticle::getIsTop).orderByDesc(NewsArticle::getIsFeatured).orderByDesc(NewsArticle::getPublishTime).orderByDesc(NewsArticle::getUpdatedAt);
        } else {
            q.orderByDesc(NewsArticle::getIsTop).orderByDesc(NewsArticle::getPublishTime).orderByDesc(NewsArticle::getUpdatedAt);
        }
    }

    private NewsArticleSummaryDTO toSummaryBatch(NewsArticle a,
                                                 Map<Long, Long> likeCounts,
                                                 Map<Long, Long> favoriteCounts,
                                                 Map<Long, Long> commentCounts,
                                                 Map<Long, List<String>> tagsByArticle) {
        NewsArticleSummaryDTO dto = new NewsArticleSummaryDTO();
        dto.setId(a.getId());
        dto.setTitle(a.getTitle());
        dto.setSubtitle(a.getSubtitle());
        dto.setSummary(a.getSummary());
        dto.setCoverImage(a.getCoverImage());
        dto.setSourceName(a.getSourceName());
        dto.setSourceUrl(a.getSourceUrl());
        dto.setAuthor(a.getAuthor());
        dto.setCategory(a.getCategory());
        dto.setLeagueName(a.getLeagueName());
        dto.setLeagueId(a.getLeagueId());
        dto.setMatchId(a.getMatchId());
        dto.setTeamId(a.getTeamId());
        dto.setIsHot(a.getIsHot());
        dto.setIsFeatured(a.getIsFeatured());
        dto.setIsTop(a.getIsTop());
        dto.setPublishTime(a.getPublishTime());
        dto.setLikeCount(likeCounts.getOrDefault(a.getId(), 0L));
        dto.setFavoriteCount(favoriteCounts.getOrDefault(a.getId(), 0L));
        dto.setCommentCount(commentCounts.getOrDefault(a.getId(), 0L));
        dto.setTags(tagsByArticle.getOrDefault(a.getId(), List.of()));
        return dto;
    }

    private NewsArticleSummaryDTO toSummarySingle(NewsArticle a) {
        return toSummaryBatch(a, Map.of(), Map.of(), Map.of(), Map.of());
    }

    private Map<Long, Long> batchCount(List<Long> articleIds, String table, String column) {
        return batchCount(articleIds, table, column, null);
    }

    private Map<Long, Long> batchCount(List<Long> articleIds, String table, String column, String extraWhere) {
        if (articleIds == null || articleIds.isEmpty()) return Map.of();
        String inClause = articleIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        StringBuilder sql = new StringBuilder("SELECT ").append(column).append(", COUNT(1) AS cnt FROM ").append(table).append(" WHERE ").append(column).append(" IN (").append(inClause).append(")");
        if (extraWhere != null && !extraWhere.isBlank()) {
            sql.append(" AND ").append(extraWhere);
        }
        sql.append(" GROUP BY ").append(column);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), articleIds.toArray());
        Map<Long, Long> result = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get(column);
            Object cnt = row.get("cnt");
            if (id != null && cnt != null) {
                result.put(Long.valueOf(String.valueOf(id)), Long.valueOf(String.valueOf(cnt)));
            }
        }
        return result;
    }

    private Map<Long, List<String>> batchTags(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) return Map.of();
        String inClause = articleIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT rel.article_id, tag.name FROM t_news_article_tag_rel rel JOIN t_news_article_tag tag ON rel.tag_id = tag.id WHERE rel.article_id IN (" + inClause + ")",
                articleIds.toArray()
        );
        Map<Long, List<String>> result = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            Object articleId = row.get("article_id");
            Object name = row.get("name");
            if (articleId != null && name != null) {
                Long id = Long.valueOf(String.valueOf(articleId));
                result.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(String.valueOf(name));
            }
        }
        return result;
    }

    private NewsArticleDetailDTO toDetail(NewsArticle a) {
        NewsArticleDetailDTO dto = new NewsArticleDetailDTO();
        dto.setId(a.getId());
        dto.setTitle(a.getTitle());
        dto.setSubtitle(a.getSubtitle());
        dto.setSummary(a.getSummary());
        dto.setContent(a.getContent());
        dto.setContentHtml(a.getContentHtml());
        dto.setCoverImage(a.getCoverImage());
        dto.setSourceName(a.getSourceName());
        dto.setSourceUrl(a.getSourceUrl());
        dto.setAuthor(a.getAuthor());
        dto.setCategory(a.getCategory());
        dto.setLeagueName(a.getLeagueName());
        dto.setLeagueId(a.getLeagueId());
        dto.setMatchId(a.getMatchId());
        dto.setTeamId(a.getTeamId());
        dto.setIsHot(a.getIsHot());
        dto.setIsFeatured(a.getIsFeatured());
        dto.setIsTop(a.getIsTop());
        dto.setStatus(a.getStatus());
        dto.setPublishTime(a.getPublishTime());
        dto.setCreatedAt(a.getCreatedAt());
        dto.setTags(getArticleTags(a.getId()));
        return dto;
    }

    private List<String> getArticleTags(Long articleId) {
        if (articleId == null) return List.of();
        List<NewsArticleTagRel> rels = tagRelMapper.selectList(Wrappers.<NewsArticleTagRel>lambdaQuery().eq(NewsArticleTagRel::getArticleId, articleId));
        if (rels.isEmpty()) return List.of();
        List<Long> tagIds = rels.stream().map(NewsArticleTagRel::getTagId).filter(Objects::nonNull).toList();
        if (tagIds.isEmpty()) return List.of();
        return tagMapper.selectList(Wrappers.<NewsTag>lambdaQuery().in(NewsTag::getId, tagIds)).stream().map(NewsTag::getName).filter(s -> s != null && !s.isBlank()).toList();
    }

    private long countLikes(Long articleId) {
        return likeMapper.selectCount(Wrappers.<NewsArticleLike>lambdaQuery().eq(NewsArticleLike::getArticleId, articleId));
    }

    private long countFavorites(Long articleId) {
        return favoriteMapper.selectCount(Wrappers.<NewsArticleFavorite>lambdaQuery().eq(NewsArticleFavorite::getArticleId, articleId));
    }

    private long countComments(Long articleId) {
        return commentMapper.selectCount(Wrappers.<NewsArticleComment>lambdaQuery().eq(NewsArticleComment::getArticleId, articleId).eq(NewsArticleComment::getStatus, "PUBLISHED"));
    }

    private boolean isLiked(Long articleId, Long userId) {
        return likeMapper.selectCount(Wrappers.<NewsArticleLike>lambdaQuery().eq(NewsArticleLike::getArticleId, articleId).eq(NewsArticleLike::getUserId, userId)) > 0;
    }

    private boolean isFavorited(Long articleId, Long userId) {
        return favoriteMapper.selectCount(Wrappers.<NewsArticleFavorite>lambdaQuery().eq(NewsArticleFavorite::getArticleId, articleId).eq(NewsArticleFavorite::getUserId, userId)) > 0;
    }

    private Map<Long, String> loadUsernames(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        Map<Long, String> map = new HashMap<>();
        try {
            String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(userServiceBaseUrl + "/api/users/batch?ids=" + ids))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = OBJECT_MAPPER.readTree(response.body());
                JsonNode data = root.path("data");
                if (data.isObject()) {
                    data.fields().forEachRemaining(entry -> {
                        try {
                            map.put(Long.valueOf(entry.getKey()), entry.getValue().asText());
                        } catch (NumberFormatException ignored) {
                            // 忽略非法 ID
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("调用 user-service 获取用户名失败，使用兜底用户名: {}", e.getMessage());
        }
        for (Long id : userIds) {
            map.putIfAbsent(id, "用户" + id);
        }
        return map;
    }
}
