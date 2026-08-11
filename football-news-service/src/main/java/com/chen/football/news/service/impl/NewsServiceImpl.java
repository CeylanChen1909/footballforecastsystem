package com.chen.football.news.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.news.dto.NewsArticleDetailDTO;
import com.chen.football.news.dto.NewsArticleSummaryDTO;
import com.chen.football.news.dto.NewsCommentDTO;
import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.entity.NewsArticleComment;
import com.chen.football.news.entity.NewsArticleFavorite;
import com.chen.football.news.entity.NewsArticleLike;
import com.chen.football.news.entity.NewsArticleTagRel;
import com.chen.football.news.entity.NewsTag;
import com.chen.football.news.mapper.NewsArticleCommentMapper;
import com.chen.football.news.mapper.NewsArticleFavoriteMapper;
import com.chen.football.news.mapper.NewsArticleLikeMapper;
import com.chen.football.news.mapper.NewsArticleMapper;
import com.chen.football.news.mapper.NewsArticleTagRelMapper;
import com.chen.football.news.mapper.NewsSpotlightMapper;
import com.chen.football.news.mapper.NewsTagMapper;
import com.chen.football.news.service.NewsFeedPage;
import com.chen.football.news.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
public class NewsServiceImpl implements NewsService {
    private final NewsArticleMapper articleMapper;
    private final NewsArticleCommentMapper commentMapper;
    private final NewsArticleLikeMapper likeMapper;
    private final NewsArticleFavoriteMapper favoriteMapper;
    private final NewsSpotlightMapper spotlightMapper;
    private final NewsTagMapper tagMapper;
    private final NewsArticleTagRelMapper tagRelMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<NewsArticleSummaryDTO> getLatestArticles() {
        return getFeed(1, 10, null, null, "latest");
    }

    @Override
    public NewsFeedPage getFeedPage(Integer page, Integer size, String category, String keyword, String sortBy) {
        int safePage = Math.max(1, page == null ? 1 : page);
        int safeSize = Math.max(1, Math.min(size == null ? 10 : size, 100));
        LambdaQueryWrapper<NewsArticle> q = basePublishedQuery(category, keyword);
        applySort(q, sortBy);
        long total = articleMapper.selectCount(q);
        q.last("LIMIT " + ((safePage - 1) * safeSize) + "," + safeSize);
        List<NewsArticleSummaryDTO> items = articleMapper.selectList(q).stream().map(this::toSummary).toList();
        return new NewsFeedPage(items, total, safePage, safeSize);
    }

    @Override
    public List<NewsArticleSummaryDTO> getFeed(Integer page, Integer size, String category, String keyword, String sortBy) {
        return getFeedPage(page, size, category, keyword, sortBy).items();
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
        return articleMapper.selectList(q).stream().map(this::toSummary).toList();
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
            dto.setCreatedAt(c.getCreatedAt());
            return dto;
        }).toList();
    }

    @Override
    public NewsArticleComment addComment(Long articleId, Long userId, String content, Long parentId) {
        if (articleId == null || userId == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("参数不能为空");
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
        comment.setContent(content.trim());
        comment.setLikeCount(0);
        comment.setStatus("PUBLISHED");
        comment.setCreatedAt(LocalDateTime.now());
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.insert(comment);
        return comment;
    }

    @Override
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

    private NewsArticleSummaryDTO toSummary(NewsArticle a) {
        NewsArticleSummaryDTO dto = new NewsArticleSummaryDTO();
        dto.setId(a.getId());
        dto.setTitle(a.getTitle());
        dto.setSubtitle(a.getSubtitle());
        dto.setSummary(a.getSummary());
        dto.setCoverImage(a.getCoverImage());
        dto.setAuthor(a.getAuthor());
        dto.setCategory(a.getCategory());
        dto.setLeagueName(a.getLeagueName());
        dto.setLeagueId(a.getLeagueId());
        dto.setIsHot(a.getIsHot());
        dto.setIsFeatured(a.getIsFeatured());
        dto.setIsTop(a.getIsTop());
        dto.setPublishTime(a.getPublishTime());
        dto.setLikeCount(countLikes(a.getId()));
        dto.setFavoriteCount(countFavorites(a.getId()));
        dto.setCommentCount(countComments(a.getId()));
        dto.setTags(getArticleTags(a.getId()));
        return dto;
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
        try {
            String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(","));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, username FROM t_user WHERE id IN (" + placeholders + ")",
                    userIds.toArray()
            );
            Map<Long, String> map = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Object idObj = row.get("id");
                Object usernameObj = row.get("username");
                if (idObj != null && usernameObj != null) {
                    map.put(Long.valueOf(String.valueOf(idObj)), String.valueOf(usernameObj));
                }
            }
            for (Long id : userIds) {
                map.putIfAbsent(id, "用户" + id);
            }
            return map;
        } catch (Exception e) {
            return userIds.stream().collect(Collectors.toMap(id -> id, id -> "用户" + id, (a, b) -> a, HashMap::new));
        }
    }
}
