package com.chen.football.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "api-football")
public class ApiFootballProperties {
    private String baseUrl = "https://v3.football.api-sports.io";
    private String apiKey = System.getenv().getOrDefault("API_FOOTBALL_KEY", "");
    private int cacheTtlSeconds = 300;
    /** Keep a safety margin below the provider's daily quota. 0 means unlimited. */
    private int maxDailyRequests = 90;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public int getMaxDailyRequests() { return maxDailyRequests; }
    public void setMaxDailyRequests(int maxDailyRequests) { this.maxDailyRequests = maxDailyRequests; }
}
