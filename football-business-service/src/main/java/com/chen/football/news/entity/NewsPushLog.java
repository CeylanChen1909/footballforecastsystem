package com.chen.football.news.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_news_push_log")
public class NewsPushLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long fixtureId;
    private String leagueId;
    private String leagueName;
    private String pushType;
    private String title;
    private String content;
    private String targetScope;
    private String targetValue;
    private String channel;
    private String status;
    private LocalDateTime sentAt;
    private String failureReason;
    private LocalDateTime createdAt;
}
