package com.chen.football.news.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 赛事内容中心统一读模型。文章和视频保留各自的写模型，前台只依赖这个内容卡片协议。
 */
@Data
public class ContentItemDTO {
    private String kind;
    private Long id;
    private String title;
    private String summary;
    private String coverImage;
    private String category;
    private String leagueName;
    private Long matchId;
    private String sourceName;
    private String sourceUrl;
    private String platform;
    private String videoType;
    private String status;
    private LocalDateTime publishedAt;
    private Map<String, Object> metrics;
}
