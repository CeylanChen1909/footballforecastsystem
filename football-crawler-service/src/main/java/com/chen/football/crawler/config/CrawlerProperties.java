package com.chen.football.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    private long requestIntervalMs = 2000;
    private boolean enabled = true;
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private WorldFootball worldFootball = new WorldFootball();
    private Flashscore flashscore = new Flashscore();

    @Data
    public static class WorldFootball {
        private String baseUrl = "https://www.worldfootball.net";
        private String standingsUrl = "https://www.worldfootball.net/competition/co91/england-premier-league/results-and-standings/";
        private String fixturesUrl = "https://www.worldfootball.net/competition/co91/england-premier-league/fixtures/";
    }

    @Data
    public static class Flashscore {
        private String baseUrl = "https://www.flashscore.com";
        private String footballUrl = "https://www.flashscore.com/football";
    }
}
