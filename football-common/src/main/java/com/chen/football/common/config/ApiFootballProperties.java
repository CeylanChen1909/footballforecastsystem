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
    /**
     * P0-2 budget split of {@link #maxDailyRequests}: at most this many requests
     * per UTC day may be spent by scheduled enrichment (PrematchDetailsRefreshTask,
     * DataSyncService, provider sync). The remainder is reserved for on-demand
     * detail requests (match page / refresh). Env: API_FOOTBALL_SCHEDULED_DAILY_LIMIT.
     * scheduled + on-demand must not exceed maxDailyRequests; the client clamps
     * them at construction time and always protects the on-demand reserve.
     */
    private int scheduledDailyLimit = 60;
    /** On-demand reserve, see {@link #scheduledDailyLimit}. Env: API_FOOTBALL_ON_DEMAND_DAILY_LIMIT. */
    private int onDemandDailyLimit = 30;
    /**
     * P0-2 pacing: minimum milliseconds between two provider requests.
     * The 282 REQUEST_FAILED snapshot rows on production (2026-11-23) are ALL
     * provider "429 Too Many Requests" — bursts of up to ~25 requests in one
     * minute trip the provider's per-minute throttle. 2000 ms caps the client
     * at ~30 requests/minute. Env: API_FOOTBALL_MIN_INTERVAL_MS. 0 disables.
     */
    private long minRequestIntervalMs = 2000;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public int getMaxDailyRequests() { return maxDailyRequests; }
    public void setMaxDailyRequests(int maxDailyRequests) { this.maxDailyRequests = maxDailyRequests; }
    public int getScheduledDailyLimit() { return scheduledDailyLimit; }
    public void setScheduledDailyLimit(int scheduledDailyLimit) { this.scheduledDailyLimit = scheduledDailyLimit; }
    public int getOnDemandDailyLimit() { return onDemandDailyLimit; }
    public void setOnDemandDailyLimit(int onDemandDailyLimit) { this.onDemandDailyLimit = onDemandDailyLimit; }
    public long getMinRequestIntervalMs() { return minRequestIntervalMs; }
    public void setMinRequestIntervalMs(long minRequestIntervalMs) { this.minRequestIntervalMs = minRequestIntervalMs; }
}
