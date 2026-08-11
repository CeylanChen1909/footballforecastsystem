package com.chen.football.crawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 爬虫任务配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "crawler.task")
public class CrawlerTaskConfig {

    /**
     * 是否启用定时任务
     */
    private boolean enabled = true;

    /**
     * 今日比赛爬取频率，毫秒
     */
    private long todayFixedRateMs = 5 * 60 * 1000;

    /**
     * 比分更新频率，毫秒
     */
    private long scoreUpdateFixedRateMs = 60 * 60 * 1000;

    /**
     * 近期比赛任务 cron
     */
    private String upcomingCron = "0 0 3 * * ?";

    /**
     * 积分榜任务 cron
     */
    private String standingsCron = "0 0 2 * * ?";
}
