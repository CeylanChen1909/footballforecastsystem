package com.chen.football.news.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class NewsArticleDetailDTO {
    private Long id;
    private String title;
    private String subtitle;
    private String summary;
    private String content;
    private String contentHtml;
    private String coverImage;
    private String sourceName;
    private String sourceUrl;
    private String author;
    private String category;
    private String leagueName;
    private String leagueId;
    private Long matchId;
    private Long teamId;
    private Integer isHot;
    private Integer isFeatured;
    private Integer isTop;
    private String status;
    private LocalDateTime publishTime;
    private LocalDateTime createdAt;
    private List<String> tags;
    private Long likeCount;
    private Long favoriteCount;
    private Long commentCount;
    private Boolean liked;
    private Boolean favorited;
}
