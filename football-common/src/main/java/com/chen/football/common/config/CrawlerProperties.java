package com.chen.football.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    private long requestIntervalMs = 2000;
    private boolean enabled = true;
    /**
     * 当前比赛数据只允许由主源写入。保留其他 provider 便于后续恢复，
     * 但 primary-only=true 时它们不会被自动或手动采集调用。
     */
    private boolean primaryOnly = true;
    private String primarySource = "bbc-scores";
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private Proxy proxy = new Proxy();

    private WorldFootball worldFootball = new WorldFootball();
    private Flashscore flashscore = new Flashscore();
    private Bbc bbc = new Bbc();
    private Espn espn = new Espn();
    private Transfermarkt transfermarkt = new Transfermarkt();

    @Data
    public static class WorldFootball {
        /**
         * WorldFootball 页面目前会返回 403，默认关闭旧的网页数据源，
         * 避免健康检查和定时任务为它反复等待。
         */
        private boolean enabled = false;
        private String baseUrl = "https://www.worldfootball.net";
        private String standingsUrl = "https://www.worldfootball.net/competition/co91/england-premier-league/results-and-standings/";
        private String fixturesUrl = "https://www.worldfootball.net/competition/co91/england-premier-league/fixtures/";
    }

    @Data
    public static class Flashscore {
        private String baseUrl = "https://www.flashscore.com";
        private String footballUrl = "https://www.flashscore.com/football";
    }

    @Data
    public static class Bbc {
        private boolean enabled = true;
        private String baseUrl = "https://www.bbc.com";
        private String scoresPath = "/sport/football/scores-fixtures";
        private long timeoutMs = 10000;
        private String userAgent = "ChenFootball/1.0 (+https://www.bbc.com/sport/football/scores-fixtures)";
    }

    @Data
    public static class Espn {
        private boolean enabled = true;
        private String baseUrl = "https://www.espn.com";
        private long timeoutMs = 15000;
        private String userAgent = "ChenFootball/1.0 (public team roster crawler)";
    }

    @Data
    public static class Transfermarkt {
        private boolean enabled = true;
        private String baseUrl = "https://www.transfermarkt.com";
        private long timeoutMs = 15000;
        private String userAgent = "ChenFootball/1.0 (public team roster crawler)";
    }

    @Data
    public static class Proxy {
        private boolean enabled = true;
        private String host = "";
        private int port = 0;
    }
}
