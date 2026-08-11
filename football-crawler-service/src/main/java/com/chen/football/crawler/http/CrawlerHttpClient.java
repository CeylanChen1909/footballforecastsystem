package com.chen.football.crawler.http;

import com.chen.football.crawler.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 客户端封装，支持：
 * - 自动重试
 * - 请求间隔控制
 * - 随机 User-Agent
 */
@Slf4j
@Component
public class CrawlerHttpClient {

    private final WebClient webClient;
    private final CrawlerProperties properties;

    // 每个域名的最后请求时间
    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();

    // User-Agent 列表
    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15"
    };

    public CrawlerHttpClient(WebClient.Builder webClientBuilder, CrawlerProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .defaultHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .defaultHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .defaultHeader("Accept-Encoding", "gzip, deflate, br")
                .build();
    }

    /**
     * GET 请求获取 HTML 内容
     */
    public String getHtml(String url) {
        if (!properties.isEnabled()) {
            log.debug("爬虫已禁用，跳过请求: {}", url);
            return null;
        }

        String resolvedUrl = resolveUrl(url);
        waitForInterval(resolvedUrl);
        try {
            String html = executeRequest(resolvedUrl, null);
            if (html == null) {
                html = executeRequest(resolvedUrl, "https://www.worldfootball.net/");
            }
            if (html == null) {
                html = executeRequest(resolvedUrl, resolvedUrl);
            }

            if (html != null) {
                lastRequestTime.put(getDomain(resolvedUrl), System.currentTimeMillis());
                logHtmlSample(resolvedUrl, html);
            }
            return html;
        } catch (Exception e) {
            log.error("请求失败: {}, 错误: {}", resolvedUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 异步 GET 请求获取 HTML
     */
    public Mono<String> getHtmlAsync(String url) {
        return Mono.fromCallable(() -> getHtml(url));
    }

    /**
     * 将相对路径解析为完整 URL
     */    private String resolveUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        String baseUrl = properties.getWorldFootball() != null ? properties.getWorldFootball().getBaseUrl() : null;
        if (baseUrl == null || baseUrl.isBlank()) {
            return url;
        }
        if (url.startsWith("/")) {
            return baseUrl + url;
        }
        return baseUrl + "/" + url;
    }

    /**
     * 执行一次带浏览器头的请求
     */
    private String executeRequest(String resolvedUrl, String referer) {
        try {
            return webClient.get()
                    .uri(resolvedUrl)
                    .headers(headers -> {
                        headers.set("User-Agent", getRandomUserAgent());
                        headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
                        headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                        headers.set("Cache-Control", "no-cache");
                        headers.set("Pragma", "no-cache");
                        headers.set("Sec-Fetch-Dest", "document");
                        headers.set("Sec-Fetch-Mode", "navigate");
                        headers.set("Sec-Fetch-Site", "none");
                        headers.set("Sec-Fetch-User", "?1");
                        headers.set("Upgrade-Insecure-Requests", "1");
                        headers.set("Connection", "keep-alive");
                        if (referer != null && !referer.isBlank()) {
                            headers.set("Referer", referer);
                        }
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .filter(this::isRetryableException)
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure()))
                    .timeout(Duration.ofSeconds(20))
                    .block();
        } catch (Exception e) {
            log.debug("请求失败: {}, 错误: {}", resolvedUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 输出 HTML 样本，帮助诊断反爬/结构变化
     */
    private void logHtmlSample(String url, String html) {
        if (html == null || html.isBlank()) {
            log.info("worldfootball 返回空 HTML: {}", url);
            return;
        }
        String sample = html.replaceAll("\\s+", " ");
        if (sample.length() > 500) {
            sample = sample.substring(0, 500);
        }
        log.info("worldfootball HTML 样本[{}]: {}", url, sample);
    }

    /**
     * 获取随机 User-Agent
     */
    private String getRandomUserAgent() {
        return USER_AGENTS[(int) (Math.random() * USER_AGENTS.length)];
    }

    /**
     * 等待请求间隔
     */
    private void waitForInterval(String url) {
        String domain = getDomain(url);
        long lastTime = lastRequestTime.getOrDefault(domain, 0L);
        long elapsed = System.currentTimeMillis() - lastTime;
        if (elapsed < properties.getRequestIntervalMs()) {
            try {
                Thread.sleep(properties.getRequestIntervalMs() - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 提取域名
     */
    private String getDomain(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 判断是否可重试
     */
    private boolean isRetryableException(Throwable throwable) {
        String msg = throwable.getMessage();
        return msg != null && (
                msg.contains("Connection refused") ||
                msg.contains("Read timeout") ||
                msg.contains("Service unavailable") ||
                msg.contains("429") ||
                msg.contains("502") ||
                msg.contains("503")
        );
    }
}
