package com.chen.football.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "crawler.task")
public class CrawlerTaskConfig {

    private boolean enabled = true;
    private long todayFixedRateMs = 5 * 60 * 1000;
    /** BBC score page is cheap and should be refreshed while matches are live. */
    private long scoreUpdateFixedRateMs = 5 * 60 * 1000;
    /** 未来窗口补采间隔；固定延迟避免长时间抓取时重叠触发。 */
    private long upcomingFixedDelayMs = 6 * 60 * 60 * 1000;
    private long upcomingStartupDelayMs = 60 * 60 * 1000;
    /** 比分修正回溯天数（不含今天）。 */
    private int scoreLookbackDays = 3;
    /** 实时任务只检查昨日和今日；更早修正由低频补偿任务覆盖。 */
    private int scoreLiveLookbackDays = 1;
    private long scoreCorrectionFixedDelayMs = 30 * 60 * 1000;
    private long scoreCorrectionInitialDelayMs = 3 * 60 * 1000;
    private String upcomingCron = "0 0 3 * * ?";
    private String standingsCron = "0 0 2 * * ?";
    private String timeZone = "Asia/Shanghai";
    /** 比赛日内定期刷新积分榜，避免凌晨快照覆盖整天。 */
    private long standingsFixedRateMs = 30 * 60 * 1000;
    /** 启动补采完成后给积分榜一个独立的首轮同步机会。 */
    private long standingsStartupDelayMs = 30 * 60 * 1000;
    /** 服务启动后是否立即补采今日及未来 7 天赛程。 */
    private boolean startupWarmupEnabled = true;
}
