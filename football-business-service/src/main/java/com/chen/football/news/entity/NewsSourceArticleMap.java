package com.chen.football.news.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_news_source_article_map")
public class NewsSourceArticleMap {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceName;
    private String sourceArticleId;
    private Long articleId;
    private String sourceUrl;
    private LocalDateTime createdAt;
}
