package com.chen.football.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Public Understat enrichment settings.  Understat is deliberately an
 * enrichment provider only: it never owns fixture identity or scores.
 */
@Configuration
@ConfigurationProperties(prefix = "understat")
public class UnderstatProperties {
    private boolean enabled = true;
    private String baseUrl = "https://understat.com";
    private int timeoutMs = 15000;
    private int cacheTtlHours = 720;
    private int maxRequestsPerRun = 15;
    private int maxRowsPerSeason = 1200;
    private long fixedDelayMs = 21600000L;
    private long initialDelayMs = 120000L;
    /**
     * Keep a six-season local cache by default.  The refresher remains request
     * budget/TTL bounded, so this expands the historical window over several
     * scheduled runs instead of issuing a burst of requests at startup.
     */
    private List<Integer> seasons = new ArrayList<>(List.of(2020, 2021, 2022, 2023, 2024, 2025));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getCacheTtlHours() { return cacheTtlHours; }
    public void setCacheTtlHours(int cacheTtlHours) { this.cacheTtlHours = cacheTtlHours; }
    public int getMaxRequestsPerRun() { return maxRequestsPerRun; }
    public void setMaxRequestsPerRun(int maxRequestsPerRun) { this.maxRequestsPerRun = maxRequestsPerRun; }
    public int getMaxRowsPerSeason() { return maxRowsPerSeason; }
    public void setMaxRowsPerSeason(int maxRowsPerSeason) { this.maxRowsPerSeason = maxRowsPerSeason; }
    public long getFixedDelayMs() { return fixedDelayMs; }
    public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
    public List<Integer> getSeasons() { return seasons; }
    public void setSeasons(List<Integer> seasons) { this.seasons = seasons == null ? new ArrayList<>() : new ArrayList<>(seasons); }
}
