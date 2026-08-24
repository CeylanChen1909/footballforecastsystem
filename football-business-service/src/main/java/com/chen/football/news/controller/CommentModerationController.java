package com.chen.football.news.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.news.entity.NewsArticleComment;
import com.chen.football.news.mapper.NewsArticleCommentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 评论审核队列：PENDING/REPORTED 评论不会直接暴露给普通用户。 */
@RestController
@RequestMapping("/api/admin/news/comments")
@RequiredArgsConstructor
public class CommentModerationController {
    private final NewsArticleCommentMapper commentMapper;

    @GetMapping
    public ApiResponse<List<NewsArticleComment>> list(@RequestParam(name = "status", defaultValue = "PENDING") String status) {
        AdminGuard.requireAdmin();
        String safe = status == null ? "PENDING" : status.trim().toUpperCase();
        return ApiResponse.ok(commentMapper.selectList(Wrappers.<NewsArticleComment>lambdaQuery()
                .eq(NewsArticleComment::getStatus, safe)
                .orderByAsc(NewsArticleComment::getCreatedAt)
                .last("LIMIT 200")));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> moderate(@PathVariable Long id, @RequestParam String status) {
        AdminGuard.requireAdmin();
        String safe = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("PUBLISHED", "HIDDEN", "DELETED", "PENDING").contains(safe)) {
            return ApiResponse.ok(Map.of("ok", false, "message", "status 不合法"));
        }
        NewsArticleComment comment = commentMapper.selectById(id);
        if (comment == null) return ApiResponse.ok(Map.of("ok", false, "message", "评论不存在"));
        comment.setStatus(safe);
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.updateById(comment);
        return ApiResponse.ok(Map.of("ok", true, "id", id, "status", safe));
    }
}
