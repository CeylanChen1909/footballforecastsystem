package com.chen.football.news.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NewsCommentDTO {
    private Long id;
    private Long articleId;
    private Long userId;
    private Long parentId;
    private String username;
    private String content;
    private Integer likeCount;
    private String status;
    private LocalDateTime createdAt;
}
