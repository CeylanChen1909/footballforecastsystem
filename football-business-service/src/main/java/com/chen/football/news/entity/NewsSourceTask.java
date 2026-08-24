package com.chen.football.news.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_news_source_task")
public class NewsSourceTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceName;
    private String sourceBaseUrl;
    private String taskType;
    private String fetchUrl;
    private LocalDateTime lastFetchTime;
    private LocalDateTime lastSuccessTime;
    private String lastStatus;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
