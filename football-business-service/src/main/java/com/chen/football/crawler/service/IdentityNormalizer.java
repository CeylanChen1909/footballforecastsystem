package com.chen.football.crawler.service;

import java.text.Normalizer;
import java.util.Locale;

/** Stable, source-independent identity keys for teams and leagues. */
public final class IdentityNormalizer {
    private IdentityNormalizer() { }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        // NFKD decomposes accented Latin characters before the mark-removal
        // pass. NFKC leaves characters such as “é” composed, which made
        // BBC's “Alaves” miss football-data's “Alavés” history entirely.
        String text = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\p{M}", "")
                // Preserve word boundaries long enough to remove provider suffixes
                // such as “FC”, “AFC” and “SC”; they are not part of a club's identity.
                .replaceAll("[\\p{Punct}\\p{Z}\\p{C}]+", " ")
                .trim();
        if (text.isBlank()) return "";
        String[] tokens = text.split("\\s+");
        int start = 0;
        int end = tokens.length;
        while (start < end && isClubAffix(tokens[start])) start++;
        while (end > start && isClubAffix(tokens[end - 1])) end--;
        StringBuilder normalized = new StringBuilder();
        for (int i = start; i < end; i++) normalized.append(tokens[i]);
        String compact = normalized.toString();
        // Cross-provider names must resolve to the same identity.  BBC tends
        // to use short names while football-data/API-Football often returns an
        // official name; these aliases prevent false "0/3 history" gates and
        // duplicate rolling samples without changing the displayed name.
        return switch (compact) {
            case "deportivoalaves" -> "alaves";
            case "sbvexcelsior" -> "excelsior";
            case "sportingcp", "sportinglisbon" -> "sportingportugal";
            case "vitoriaguimaraes", "vitoria" -> "vitoriaguimaraes";
            case "olympiquelyonnais" -> "lyon";
            case "brightonhovealbion", "brightonhove" -> "brightonhovealbion";
            case "atleticomadrid", "atleti" -> "atleticomadrid";
            case "racingclubdelens", "rclens" -> "lens";
            case "angerssco" -> "angers";
            case "lilleosc" -> "lille";
            case "celtavigo" -> "celta";
            case "acmonza" -> "monza";
            case "intermilan", "internazionalemilano", "fcinternazionalemilano" -> "inter";
            case "udinesecalcio" -> "udinese";
            case "como1907" -> "como";
            case "estorilpraia" -> "estoril";
            case "cdnacional" -> "nacional";
            case "fortunasittard", "sittard" -> "fortunasittard";
            case "goahead", "goaheadeagles" -> "goaheadeagles";
            case "peczwolle", "zwolle" -> "peczwolle";
            case "spartarotterdam" -> "spartarotterdam";
            default -> compact;
        };
    }

    /**
     * 判断两个来源的球队名称是否足以视为同一身份。
     *
     * BBC 通常使用短名（例如 Nottingham、Brighton），而历史数据源会
     * 使用官方全名（Nottingham Forest、Brighton & Hove Albion）。仅做
     * equals 会把这些球队错误地视为 0 场历史；这里只对长度足够且有
     * 明确词边界的前后缀/单词重合放宽，避免把 "United" 这类短公共词
     * 误合并。
     */
    public static boolean compatible(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        if (left.isBlank() || right.isBlank()) return false;
        if (left.equals(right)) return true;
        if (left.length() < 6 || right.length() < 6) return false;
        if (left.startsWith(right) || right.startsWith(left)) return true;
        // normalize() returns a compact key, so use a conservative compact
        // prefix check as the primary rule. The explicit alias table above
        // handles the common non-prefix cases (Inter, Sporting, etc.).
        return left.contains(right) || right.contains(left);
    }

    private static boolean isClubAffix(String token) {
        return "fc".equals(token) || "afc".equals(token) || "sc".equals(token)
                || "cf".equals(token) || "club".equals(token);
    }

    public static String key(String entityType, String id, String name) {
        String normalized = normalize(name);
        if (!normalized.isBlank()) return entityType + ":name:" + normalized;
        if (id != null && !id.isBlank()) return entityType + ":id:" + id.trim().toLowerCase(Locale.ROOT);
        return entityType + ":unknown";
    }

    public static String matchKey(String league, String home, String away, java.time.LocalDateTime time) {
        String day = time == null ? "" : time.toLocalDate().toString();
        // 日期 alone is not an identity: postponed fixtures, double-headers
        // and youth/reserve games can share the same teams on one day. Keep a
        // minute-level kickoff slot when available and use an explicit
        // unknown marker when the source did not provide a time.
        String slot = time == null ? "unknown" : time.withSecond(0).withNano(0).toLocalTime().toString();
        return normalize(league) + "|" + normalize(home) + "|" + normalize(away) + "|" + day + "|" + slot;
    }
}
