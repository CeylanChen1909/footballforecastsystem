package com.chen.football.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "juhe-football")
public class JuheFootballProperties {
    private String apiKey = "b509116906ee880c8482797e5e0e7345";
    private String baseUrl = "http://apis.juhe.cn/fapig/football/query";
    private String rankUrl = "http://apis.juhe.cn/fapig/football/rank";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getRankUrl() { return rankUrl; }
    public void setRankUrl(String rankUrl) { this.rankUrl = rankUrl; }
}
