package com.chen.football.news.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class NewsArticleSummaryDTO {
    private Long id;
    private String title;
    private String subtitle;
    private String summary;
    private String coverImage;
    private String author;
    private String category;
    private String leagueName;
    private String leagueId;
    private Integer isHot;
    private Integer isFeatured;
    private Integer isTop;
    private LocalDateTime publishTime;
    private Long likeCount;
    private Long favoriteCount;
    private Long commentCount;
    private List<String> tags;
}
