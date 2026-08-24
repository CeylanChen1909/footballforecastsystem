package com.chen.football.crawler.http;

import com.chen.football.common.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import reactor.util.retry.Retry;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

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
        WebClient.Builder configuredBuilder = webClientBuilder.clone();
        CrawlerProperties.Proxy proxy = properties.getProxy();
        HttpClient crawlerHttpClient = HttpClient.create()
                // BBC 会返回 gzip/br 压缩内容；开启 Netty 解压，避免 Jsoup
                // 将压缩字节当作乱码，最终解析出 0 场比赛。
                .compress(true)
                .followRedirect(true)
                .responseTimeout(Duration.ofSeconds(30));
        if (proxy != null && proxy.isEnabled() && proxy.getHost() != null
                && !proxy.getHost().isBlank() && proxy.getPort() > 0) {
            crawlerHttpClient = crawlerHttpClient.proxy(spec -> spec
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(proxy.getHost().trim())
                    .port(proxy.getPort()));
            log.info("爬虫 HTTP 客户端启用代理: {}:{}", proxy.getHost(), proxy.getPort());
        }
        configuredBuilder.clientConnector(new ReactorClientHttpConnector(crawlerHttpClient));
        configuredBuilder.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024));
        this.webClient = configuredBuilder
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
     * 面向新网页源的单次请求。
     *
     * 旧的 getHtml 为 WorldFootball 设计，包含多个 WorldFootball referer
     * 和较长的重试链路。BBC 页面不需要这些 referer；如果继续复用，目标站
     * 失败时一次日期采集会被放大成数十秒甚至更久。因此新源使用明确的
     * 超时、单次请求和调用方 User-Agent。
     *
     * @throws IllegalStateException 当目标返回非 2xx 或请求超时时抛出，
     *                               由数据源 provider 转换为 REQUEST_FAILED
     */
    public String getHtmlDirect(String url, String userAgent, Duration timeout) {
        if (!properties.isEnabled()) {
            log.debug("爬虫已禁用，跳过请求: {}", url);
            return null;
        }
        String resolvedUrl = resolveUrl(url);
        waitForInterval(resolvedUrl);
        String effectiveUserAgent = userAgent == null || userAgent.isBlank()
                ? properties.getUserAgent()
                : userAgent;
        Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(10)
                : timeout;
        try {
            String html = webClient.get()
                    .uri(resolvedUrl)
                    .headers(headers -> {
                        headers.set("User-Agent", effectiveUserAgent);
                        headers.set("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
                        headers.set("Accept-Language", "en-GB,en;q=0.9,zh-CN;q=0.7");
                        headers.set("Accept-Encoding", "gzip, deflate");
                        headers.set("Cache-Control", "no-cache");
                        headers.set("Pragma", "no-cache");
                    })
                    .exchangeToMono(response -> {
                        if (response.statusCode().value() >= 400) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(new IllegalStateException(
                                            "HTTP " + response.statusCode().value() + " from " + getDomain(resolvedUrl)
                                                    + (body.isBlank() ? "" : ": " + compact(body)))));
                        }
                        return response.bodyToMono(String.class);
                    })
                    // 网页源偶发 502/503/429 或连接抖动时允许极少量退避重试。
                    // 不对 401/403/404 等确定性错误重试，避免反爬和配额问题被放大。
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofSeconds(5))
                            .jitter(0.2)
                            .filter(this::isDirectRetryable)
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .timeout(effectiveTimeout)
                    .block();
            if (html == null || html.isBlank()) {
                throw new IllegalStateException("empty HTML response from " + getDomain(resolvedUrl));
            }
            lastRequestTime.put(getDomain(resolvedUrl), System.currentTimeMillis());
            log.info("direct HTML 请求成功: url={}, bytes={}", resolvedUrl, html.length());
            return html;
        } catch (RuntimeException e) {
            log.warn("direct HTML 请求失败: url={}, error={}", resolvedUrl, e.getMessage());
            if (e instanceof IllegalStateException && e.getMessage() != null
                    && e.getMessage().startsWith("HTTP ")) {
                throw e;
            }
            throw new IllegalStateException("direct HTML request failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
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

    private String compact(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 180 ? normalized.substring(0, 180) : normalized;
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

    private boolean isDirectRetryable(Throwable throwable) {
        String msg = throwable == null ? "" : String.valueOf(throwable.getMessage());
        return msg.contains("Connection refused")
                || msg.contains("Read timeout")
                || msg.contains("TimeoutException")
                || msg.contains("HTTP 429")
                || msg.contains("HTTP 500")
                || msg.contains("HTTP 502")
                || msg.contains("HTTP 503")
                || msg.contains("HTTP 504");
    }
}
