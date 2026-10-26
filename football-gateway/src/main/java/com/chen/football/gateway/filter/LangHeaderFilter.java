package com.chen.football.gateway.filter;

import com.chen.football.gateway.i18n.GatewayLangResolver;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Normalizes the effective language once at the edge and forwards it as
 * {@code X-Lang}.  The original {@code lang} query parameter is preserved, so
 * downstream servlet services can apply their own query > header >
 * Accept-Language precedence without the gateway stripping anything.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class LangHeaderFilter implements GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String queryLang = GatewayLangResolver.queryLang(request.getURI().getRawQuery());
        String headerLang = request.getHeaders().getFirst("X-Lang");
        String acceptLanguage = request.getHeaders().getFirst("Accept-Language");
        String lang = GatewayLangResolver.resolve(queryLang, headerLang, acceptLanguage);

        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> headers.set("X-Lang", lang))
                .build();
        exchange.getResponse().getHeaders().add("Vary", "Accept-Language, X-Lang");
        return chain.filter(exchange.mutate().request(mutated).build());
    }
}
