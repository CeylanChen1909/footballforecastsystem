package com.chen.football.news.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_news_article")
public class NewsArticle {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String subtitle;
    private String summary;
    private String content;
    private String contentHtml;
    private String coverImage;
    private String sourceName;
    private String sourceUrl;
    private String sourceType;
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
    private LocalDateTime updatedAt;
}
