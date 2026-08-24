package com.chen.football.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "football-data")
public class FootballDataProperties {

    private String baseUrl = "https://api.football-data.org/v4";
    private String token = System.getenv().getOrDefault("FOOTBALL_DATA_TOKEN", "");

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
