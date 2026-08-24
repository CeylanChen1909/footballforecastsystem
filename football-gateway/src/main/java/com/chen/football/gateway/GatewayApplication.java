package com.chen.football.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * 网关应用主类
 * 配置路由、限流、熔断等网关功能
 */
@SpringBootApplication
public class GatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(GatewayApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * 自定义全局过滤器 - 请求日志
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public org.springframework.cloud.gateway.filter.GlobalFilter loggingFilter() {
        return (exchange, chain) -> {
            long startTime = System.currentTimeMillis();
            String path = exchange.getRequest().getURI().getPath();
            String method = exchange.getRequest().getMethod().name();

            log.info("[Gateway] {} {} - 开始处理", method, path);

            return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                long duration = System.currentTimeMillis() - startTime;
                int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
                log.info("[Gateway] {} {} - 状态:{} 耗时:{}ms", method, path, status, duration);
            }));
        };
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public org.springframework.cloud.gateway.filter.GlobalFilter securityHeadersFilter() {
        return (exchange, chain) -> {
            var headers = exchange.getResponse().getHeaders();
            headers.addIfAbsent("X-Content-Type-Options", "nosniff");
            headers.addIfAbsent("X-Frame-Options", "DENY");
            headers.addIfAbsent("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.addIfAbsent("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            return chain.filter(exchange);
        };
    }

    /**
     * Keep Agent SSE responses genuinely streamable through the gateway.  A
     * browser can otherwise receive all progress events in one buffered block
     * when an intermediary applies a default cache/buffering policy.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public org.springframework.cloud.gateway.filter.GlobalFilter agentStreamHeadersFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getURI().getPath();
            if (path != null && path.endsWith("/api/agent/chat/stream")) {
                var headers = exchange.getResponse().getHeaders();
                headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
                headers.set("Pragma", "no-cache");
                headers.set("X-Accel-Buffering", "no");
                headers.set("Connection", "keep-alive");
            }
            return chain.filter(exchange);
        };
    }

    /**
     * Agent 路由使用显式 Java 配置兜底，避免运行环境中的外部配置覆盖 YAML
     * 后导致 /api/agent/** 无法匹配。普通业务路由仍由 application.yml 管理。
     */
    @Bean
    public RouteLocator agentRoute(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("agent-service-explicit", route -> route
                        .order(-10)
                        .path("/api/agent", "/api/agent/**")
                        .uri("lb://football-business-service"))
                .build();
    }

    /**
     * 网关级限流使用客户端地址作为默认键，避免所有匿名请求共享一个桶。
     * 只有明确部署在可信反向代理后时才启用 GATEWAY_TRUST_PROXY_HEADERS，
     * 否则不信任用户可伪造的 X-Forwarded-For。
     */
    @Bean
    public KeyResolver clientAddressKeyResolver() {
        boolean trustProxyHeaders = Boolean.parseBoolean(
                System.getenv().getOrDefault("GATEWAY_TRUST_PROXY_HEADERS", "false"));
        return exchange -> {
            if (trustProxyHeaders) {
                String forwarded = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
                if (forwarded == null || forwarded.isBlank()) {
                    forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
                    if (forwarded != null && forwarded.contains(",")) forwarded = forwarded.split(",", 2)[0];
                }
                if (forwarded != null && !forwarded.isBlank()) return Mono.just(forwarded.trim());
            }
            var remote = exchange.getRequest().getRemoteAddress();
            String address = remote == null || remote.getAddress() == null
                    ? "unknown"
                    : remote.getAddress().getHostAddress();
            return Mono.just(address);
        };
    }

    /**
     * CORS 跨域配置
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        String configuredOrigins = System.getenv().get("GATEWAY_CORS_ALLOWED_ORIGINS");
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            configuredOrigins = String.join(",",
                    System.getenv().getOrDefault("GATEWAY_CORS_ORIGIN_1", "http://localhost:5173"),
                    System.getenv().getOrDefault("GATEWAY_CORS_ORIGIN_2", "http://127.0.0.1:5173"));
        }
        config.setAllowedOrigins(Stream.of(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList());
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        
        return new CorsWebFilter(source);
    }
}
