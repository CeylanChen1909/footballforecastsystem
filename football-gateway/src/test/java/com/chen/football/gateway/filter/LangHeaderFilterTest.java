package com.chen.football.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangHeaderFilterTest {

    @Test
    void completesTheChainAndForwardsEnglishForASpacedLeagueQuery() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://localhost/api/proxy/prediction?leagueName=Premier%20League&homeTeam=Liverpool")
                .build());
        AtomicBoolean called = new AtomicBoolean();
        GatewayFilterChain chain = filtered -> {
            called.set(true);
            assertEquals("en", filtered.getRequest().getHeaders().getFirst("X-Lang"));
            assertEquals("/api/proxy/prediction", filtered.getRequest().getURI().getPath());
            return Mono.empty();
        };

        assertDoesNotThrow(() -> new LangHeaderFilter().filter(exchange, chain).block());

        assertTrue(called.get());
        assertEquals("en", exchange.getRequest().getHeaders().getFirst("X-Lang"));
        assertEquals("Accept-Language, X-Lang", exchange.getResponse().getHeaders().getFirst("Vary"));
    }

    @Test
    void forwardsExplicitChineseAndKeepsTheChainReactive() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://localhost/api/crawler/matches/h2h?lang=zh&homeTeam=%E5%88%A9%E7%89%A9%E6%B5%A6")
                .build());
        AtomicBoolean called = new AtomicBoolean();
        GatewayFilterChain chain = filtered -> {
            called.set(true);
            assertEquals("zh", filtered.getRequest().getHeaders().getFirst("X-Lang"));
            return Mono.empty();
        };

        assertDoesNotThrow(() -> new LangHeaderFilter().filter(exchange, chain).block());

        assertTrue(called.get());
    }

    @Test
    void ignoresUnusableLanguageButStillCompletesTheChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://localhost/api/proxy/prediction?lang=%25ZZ&leagueName=Premier%20League")
                .build());
        AtomicBoolean called = new AtomicBoolean();
        GatewayFilterChain chain = filtered -> {
            called.set(true);
            assertEquals("en", filtered.getRequest().getHeaders().getFirst("X-Lang"));
            return Mono.empty();
        };

        assertDoesNotThrow(() -> new LangHeaderFilter().filter(exchange, chain).block());

        assertTrue(called.get());
    }
}
