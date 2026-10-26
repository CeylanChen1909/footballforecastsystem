package com.chen.football.common.i18n;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the effective request language from, in priority order:
 * query parameter {@code lang}, request header {@code X-Lang}, then
 * {@code Accept-Language}.  Only {@code en} and {@code zh} are supported and
 * the default is English.
 */
public final class LangResolver {

    private LangResolver() {
    }

    public static String resolve(String queryLang, String headerLang, String acceptLanguage) {
        String explicit = explicit(queryLang);
        if (explicit != null) return explicit;
        explicit = explicit(headerLang);
        if (explicit != null) return explicit;
        String accepted = fromAcceptLanguage(acceptLanguage);
        return accepted == null ? LangContext.DEFAULT : accepted;
    }

    /** Accepts only a direct en/zh tag (including en-US / zh-CN); otherwise null. */
    private static String explicit(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        // Explicit values are expected to be a single tag.  Still tolerate a
        // full Accept-Language style value in X-Lang for interoperability.
        if (normalized.contains(",")) return fromAcceptLanguage(normalized);
        if (normalized.equals("zh") || normalized.startsWith("zh-")) return LangContext.ZH;
        if (normalized.equals("en") || normalized.startsWith("en-")) return LangContext.EN;
        return null;
    }

    private static String fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return null;
        List<WeightedTag> tags = new ArrayList<>();
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
            String lang;
            if (normalized.equals("zh") || normalized.startsWith("zh-")) lang = LangContext.ZH;
            else if (normalized.equals("en") || normalized.startsWith("en-")) lang = LangContext.EN;
            else continue;
            tags.add(new WeightedTag(lang, quality, index));
        }
        return tags.stream()
                .sorted(Comparator.comparingDouble(WeightedTag::quality).reversed()
                        .thenComparingInt(WeightedTag::order))
                .map(WeightedTag::lang)
                .findFirst()
                .orElse(null);
    }

    private record WeightedTag(String lang, double quality, int order) {
    }
}
