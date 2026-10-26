package com.chen.football.common.i18n;

import com.chen.football.common.dto.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Last-mile localization for map-based API payloads.
 *
 * <p>Most controllers build {@code Map<String,Object>} responses directly.
 * This advice translates API-owned message fields ({@code message},
 * {@code statusText}, {@code note}, {@code warning}, {@code fallbackReason},
 * {@code confidenceLabel}, ...) without touching arbitrary string values such
 * as news titles, changelog entries or persona descriptions.</p>
 */
@RestControllerAdvice
public class I18nResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final int MAX_DEPTH = 20;
    // Keys are compared after lower-casing, so keep every entry lower-case.
    private static final List<String> MESSAGE_KEYS = List.of(
            "message", "error", "statustext", "datastatustext", "note", "warning", "fallbackreason",
            "gatereason", "confidencelabel", "explanation", "reasontext", "reasontexts",
            "emptyreasontext", "detail"
    );

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null) return null;
        String path = request == null || request.getURI() == null ? "" : request.getURI().getPath();
        // Assistant/agent payloads are owned by a separate localization task.
        if (path != null && path.startsWith("/api/agent")) return body;
        return localize(body, null, path, 0);
    }

    private Object localize(Object value, String key, String path, int depth) {
        if (value == null || depth > MAX_DEPTH) return value;
        if (value instanceof String text) return localizeString(text, key, path);
        if (value instanceof ApiResponse<?> apiResponse) {
            return new ApiResponse<>(apiResponse.success(),
                    I18nText.translate(apiResponse.message()),
                    localize(apiResponse.data(), null, path, depth + 1));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> localized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String entryKey = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                localized.put(entryKey, localize(entry.getValue(), entryKey, path, depth + 1));
            }
            return localized;
        }
        if (value instanceof List<?> list) {
            List<Object> localized = new ArrayList<>(list.size());
            for (Object item : list) localized.add(localize(item, key, path, depth + 1));
            return localized;
        }
        // POJOs (entities, DTOs) are left untouched; entities expose explicit
        // display getters and DTO-level messages are localized where created.
        return value;
    }

    private String localizeString(String text, String key, String path) {
        if (text == null || text.isEmpty() || key == null) return text;
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        if (MESSAGE_KEYS.contains(normalizedKey)) return I18nText.translate(text);
        if ("zonelabel".equals(normalizedKey)) return LabelCatalog.zoneDisplay(I18nText.translate(text));
        if ("leaguedisplayname".equals(normalizedKey) || "displayleaguename".equals(normalizedKey)) {
            return LabelCatalog.leagueDisplay(text);
        }
        // Canonical En/Zh fields are language-independent. They must not be
        // run through leagueDisplay(), otherwise leagueNameEn becomes Chinese
        // in a zh response (and vice versa).
        if ("leaguenameen".equals(normalizedKey)) return LabelCatalog.leagueEnglish(text);
        if ("leaguenamezh".equals(normalizedKey)) return LabelCatalog.leagueChinese(text);
        if ("teamdisplayname".equals(normalizedKey) || "displayteamname".equals(normalizedKey)
                || "hometeamdisplayname".equals(normalizedKey) || "awayteamdisplayname".equals(normalizedKey)) {
            return LabelCatalog.teamDisplay(text);
        }
        if ("hometeamnameen".equals(normalizedKey) || "awayteamnameen".equals(normalizedKey)) {
            return LabelCatalog.teamEnglish(text);
        }
        if ("hometeamnamezh".equals(normalizedKey) || "awayteamnamezh".equals(normalizedKey)) {
            return LabelCatalog.teamChinese(text);
        }
        if ("position".equals(normalizedKey) && isMatchDomainPath(path)) {
            return LabelCatalog.positionDisplay(text);
        }
        return text;
    }

    private boolean isMatchDomainPath(String path) {
        if (path == null) return false;
        return path.startsWith("/api/crawler/") || path.startsWith("/api/teams/")
                || path.startsWith("/api/matches/") || path.startsWith("/api/search");
    }
}
