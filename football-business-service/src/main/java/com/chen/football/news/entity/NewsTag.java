package com.chen.football.news.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_news_article_tag")
public class NewsTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String slug;
    private LocalDateTime createdAt;
}
