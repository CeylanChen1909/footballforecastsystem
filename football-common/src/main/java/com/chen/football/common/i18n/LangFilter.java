package com.chen.football.common.i18n;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Sets {@link LangContext} for every servlet request.  Registered as a
 * component so both football-business-service and football-user-service pick
 * it up automatically through their component scan of {@code com.chen.football.common}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LangFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String lang = LangResolver.resolve(
                queryLanguage(request),
                request.getHeader("X-Lang"),
                request.getHeader("Accept-Language"));
        LangContext.set(lang);
        try {
            filterChain.doFilter(request, response);
        } finally {
            LangContext.clear();
        }
    }

    /**
     * Read only the {@code lang} query parameter from the raw query string.
     * Calling {@code request.getParameter("lang")} forced the container to
     * decode every other parameter before the controller ran; a malformed
     * percent escape therefore failed the request (or reached a blocking legacy
     * handler) before the endpoint could answer.  Decoding just this one value
     * keeps language resolution independent of all other parameters.
     */
    private String queryLanguage(HttpServletRequest request) {
        String rawQuery = request.getQueryString();
        if (rawQuery != null && !rawQuery.isBlank()) {
            for (String pair : rawQuery.split("&")) {
                int equals = pair.indexOf('=');
                String rawName = equals < 0 ? pair : pair.substring(0, equals);
                if (!"lang".equals(decode(rawName))) continue;
                String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
                return decode(rawValue);
            }
            return null;
        }
        // Mock requests and some programmatic forwards expose parameters
        // without a query string.  In that case there is nothing unsafe to
        // decode, so honour the container's parameter map.
        try {
            return request.getParameter("lang");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }
}
