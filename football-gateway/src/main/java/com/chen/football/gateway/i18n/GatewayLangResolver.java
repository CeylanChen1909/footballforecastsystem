package com.chen.football.gateway.i18n;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Minimal en|zh resolution for the reactive gateway edge. */
public final class GatewayLangResolver {

    private GatewayLangResolver() {
    }

    /**
     * Reads only the {@code lang} value from a raw query string.  The gateway
     * must not ask Reactor Netty to decode every parameter just to discover the
     * language: an unrelated malformed escape in the legacy proxy query must
     * still reach the downstream 410 handler instead of failing at the edge.
     */
    public static String queryLang(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            if (!"lang".equals(decode(rawName))) continue;
            return decode(equals < 0 ? "" : pair.substring(equals + 1));
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    public static String resolve(String queryLang, String headerLang, String acceptLanguage) {
        String explicit = explicit(queryLang);
        if (explicit != null) return explicit;
        explicit = explicit(headerLang);
        if (explicit != null) return explicit;
        String accepted = fromAcceptLanguage(acceptLanguage);
        return accepted == null ? "en" : accepted;
    }

    private static String explicit(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.contains(",")) return fromAcceptLanguage(normalized);
        if (normalized.equals("zh") || normalized.startsWith("zh-")) return "zh";
        if (normalized.equals("en") || normalized.startsWith("en-")) return "en";
        return null;
    }

    private static String fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return null;
        List<Weighted> tags = new ArrayList<>();
        String[] parts = header.split(",");
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index].trim();
            if (part.isEmpty()) continue;
            String tag = part;
            double quality = 1.0;
            int semicolon = part.indexOf(';');
            if (semicolon >= 0) {
                tag = part.substring(0, semicolon).trim();
                String parameter = part.substring(semicolon + 1).trim();
                if (parameter.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(parameter.substring(2).trim());
                    } catch (NumberFormatException ignored) {
                        quality = 1.0;
                    }
                }
            }
            if (quality <= 0) continue;
            String normalized = tag.toLowerCase(Locale.ROOT).replace('_', '-');
            String lang = null;
            if (normalized.equals("zh") || normalized.startsWith("zh-")) lang = "zh";
            else if (normalized.equals("en") || normalized.startsWith("en-")) lang = "en";
            if (lang != null) tags.add(new Weighted(lang, quality, index));
        }
        return tags.stream()
                .sorted(Comparator.comparingDouble(Weighted::quality).reversed()
                        .thenComparingInt(Weighted::order))
                .map(Weighted::lang)
                .findFirst()
                .orElse(null);
    }

    private record Weighted(String lang, double quality, int order) {
    }
}
