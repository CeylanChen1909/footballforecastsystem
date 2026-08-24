package com.chen.football.media.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Persistent media proxy configuration.  The cache is intentionally kept on
 * the service volume rather than in MySQL so image bytes do not inflate the
 * business database.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "media.proxy")
public class MediaProxyProperties {

    private boolean enabled = true;
    private String cacheDir = "./data/media-cache";
    private long diskTtlHours = 720;
    private long maxDiskBytes = 1024L * 1024L * 1024L;
    private int maxMemoryEntries = 256;
    private int maxImageBytes = 2 * 1024 * 1024;
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 8;
    private boolean warmupEnabled = true;
    private int warmupMaxPerRun = 100;
    private long warmupInitialDelayMs = 60_000L;
    private long warmupFixedDelayMs = 60 * 60 * 1000L;
}
