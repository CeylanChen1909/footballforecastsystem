package com.chen.football.common.i18n;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bilingual label catalog for competitions, clubs, player positions and
 * table-zone labels.
 *
 * <p>Canonical league values deliberately remain Chinese internally because
 * the production database and request filters already use them.  English
 * request values are normalized back to Chinese before querying, and English
 * display values are produced only at the response boundary.</p>
 */
public final class LabelCatalog {

    private static final Map<String, String> LEAGUE_ALIAS_TO_ZH = new LinkedHashMap<>();
    private static final Map<String, String> LEAGUE_ZH_TO_EN = new LinkedHashMap<>();
    private static final Map<String, String> TEAM_ALIAS_TO_EN = new LinkedHashMap<>();
    private static final Map<String, String> TEAM_ALIAS_TO_ZH = new LinkedHashMap<>();
    /** Canonical English key -> every known English/Chinese spelling for that team. */
    private static final Map<String, LinkedHashSet<String>> TEAM_VARIANTS = new LinkedHashMap<>();
    private static final Map<String, String> POSITION_ALIAS_TO_EN = new LinkedHashMap<>();
    private static final Map<String, String> POSITION_ALIAS_TO_ZH = new LinkedHashMap<>();
    private static final Map<String, String> ZONE_EN = new LinkedHashMap<>();
    private static final Map<String, String> ZONE_ZH = new LinkedHashMap<>();

    static {
        // Football-common / business-service competition scope.
        league("英超", "Premier League", "EPL", "PL", "England Premier League", "English Premier League",
                "premier league", "bbc-premier-league", "39");
        league("英冠", "Championship", "EFL Championship", "ELC", "English Championship",
                "bbc-championship", "40");
        league("英甲", "League One", "EFL League One", "41");
        league("英乙", "League Two", "EFL League Two", "42");
        league("西甲", "La Liga", "LaLiga", "Primera Division", "Primera División", "PD",
                "Spanish La Liga", "bbc-spanish-la-liga", "140");
        league("西乙", "Segunda División", "Segunda Division", "LaLiga 2", "141");
        league("意甲", "Serie A", "SA", "Italian Serie A", "bbc-italian-serie-a", "135");
        league("意乙", "Serie B", "136");
        league("德甲", "Bundesliga", "BL1", "German Bundesliga", "bbc-german-bundesliga", "78");
        league("德乙", "2. Bundesliga", "Bundesliga 2", "79");
        league("法甲", "Ligue 1", "FL1", "French Ligue 1", "bbc-french-ligue-one", "61");
        league("法乙", "Ligue 2", "62");
        league("葡超", "Primeira Liga", "PPL", "Liga Portugal", "Portuguese Primeira Liga",
                "bbc-portuguese-primeira-liga", "94");
        league("葡甲", "Liga Portugal 2", "Portuguese Second Division", "95");
        league("荷甲", "Eredivisie", "DED", "Dutch Eredivisie", "bbc-dutch-eredivisie", "88");
        league("比甲", "Belgian Pro League", "Jupiler Pro League", "Belgium", "144");
        league("苏超", "Scottish Premiership", "179");
        league("土超", "Süper Lig", "Super Lig", "Turkey", "203");
        league("捷克甲", "Czech First League", "Czech Liga", "Czechia");
        league("乌超", "Ukrainian Premier League", "Ukraine");
        league("欧冠", "UEFA Champions League", "Champions League", "UCL", "European Cup",
                "bbc-champions-league", "2");
        league("欧联", "UEFA Europa League", "Europa League", "UEL", "bbc-europa-league", "3");
        league("欧协联", "UEFA Conference League", "Conference League", "UECL", "848");
        league("欧国联", "UEFA Nations League", "Nations League", "UNL", "bbc-nations-league");
        league("世界杯", "FIFA World Cup", "World Cup", "WC", "1");
        league("欧洲杯", "UEFA European Championship", "European Championship", "Euro", "4");
        league("世俱杯", "FIFA Club World Cup", "Club World Cup", "15");
        league("巴西甲", "Campeonato Brasileiro Série A", "Brasileirao Serie A", "71");
        league("巴西乙", "Campeonato Brasileiro Série B", "Brasileirao Serie B", "72");
        league("美职联", "Major League Soccer", "MLS", "253");
        league("墨超", "Liga MX", "262");
        league("阿甲", "Argentine Primera División", "Liga Profesional Argentina", "128");
        league("解放者杯", "Copa Libertadores", "13");
        league("南美杯", "Copa Sudamericana", "11");
        league("中超", "Chinese Super League", "CSL", "169");
        league("日职联", "J1 League", "J.League", "98");
        league("韩K联", "K League 1", "292");
        league("意大利杯", "Coppa Italia", "137");
        league("英锦赛", "EFL Trophy", "46");
        league("足总杯", "FA Cup", "45");
        league("英联杯", "EFL Cup", "League Cup", "Carabao Cup", "48");
        league("国际友谊赛", "International Friendly", "FRIENDLY");

        // Club / national-team directory generated from the frontend TEAM_DIRECTORY.
        for (String[] row : TeamCatalogData.TEAMS) {
            if (row.length < 2) continue;
            String en = row[0];
            String zh = row[1];
            LinkedHashSet<String> variants = TEAM_VARIANTS.computeIfAbsent(normalizeKey(en), ignored -> new LinkedHashSet<>());
            variants.add(en);
            variants.add(zh);
            TEAM_ALIAS_TO_EN.put(normalizeKey(en), en);
            TEAM_ALIAS_TO_EN.put(normalizeKey(zh), en);
            TEAM_ALIAS_TO_ZH.put(normalizeKey(en), zh);
            TEAM_ALIAS_TO_ZH.put(normalizeKey(zh), zh);
            for (int index = 2; index < row.length; index++) {
                String alias = row[index];
                if (alias == null || alias.isBlank()) continue;
                variants.add(alias);
                TEAM_ALIAS_TO_EN.putIfAbsent(normalizeKey(alias), en);
                TEAM_ALIAS_TO_ZH.putIfAbsent(normalizeKey(alias), zh);
            }
        }

        position("门将", "Goalkeeper", "GK", "Keeper");
        position("后卫", "Defender", "DEF");
        position("中场", "Midfielder", "MF");
        position("前锋", "Forward", "Striker", "FW");
        position("边前卫", "Wide Midfielder", "Wide Midfield");
        position("边锋", "Winger");
        position("前腰", "Attacking Midfielder", "CAM");
        position("后腰", "Defensive Midfielder", "CDM");
        position("中锋", "Centre-Forward", "Center-Forward", "Centre Forward", "Striker", "CF", "ST");
        position("中后卫", "Centre-Back", "Center-Back", "Centre Back", "CB");
        position("边后卫", "Full-Back", "Full Back", "FB");
        position("左后卫", "Left-Back", "Left Back", "LB");
        position("右后卫", "Right-Back", "Right Back", "RB");
        position("左中场", "Left Midfielder", "LM");
        position("右中场", "Right Midfielder", "RM");
        position("左边锋", "Left Winger", "LW");
        position("右边锋", "Right Winger", "RW");
        position("左翼卫", "Left Wing-Back", "Left Wing Back", "LWB");
        position("右翼卫", "Right Wing-Back", "Right Wing Back", "RWB");
        position("左边前卫", "Left Wide Midfielder", "LWM");
        position("右边前卫", "Right Wide Midfielder", "RWM");
        position("全能", "Versatile", "All-rounder", "All-Rounder", "Utility");

        zone("欧冠正赛", "UEFA Champions League");
        zone("欧冠资格赛", "UEFA Champions League qualifying");
        zone("欧联正赛", "UEFA Europa League");
        zone("欧联资格赛", "UEFA Europa League qualifying");
        zone("欧协联正赛", "UEFA Conference League");
        zone("欧协联资格赛", "UEFA Conference League qualifying");
        zone("欧协联附加赛", "UEFA Conference League play-off");
        zone("直接升级", "Automatic promotion");
        zone("升级附加赛", "Promotion play-off");
        zone("降级附加赛", "Relegation play-off");
        zone("降级", "Relegation");
    }

    private LabelCatalog() {
    }

    private static void league(String zh, String en, String... aliases) {
        LEAGUE_ZH_TO_EN.put(zh, en);
        LEAGUE_ALIAS_TO_ZH.put(normalizeKey(zh), zh);
        LEAGUE_ALIAS_TO_ZH.put(normalizeKey(en), zh);
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) LEAGUE_ALIAS_TO_ZH.putIfAbsent(normalizeKey(alias), zh);
        }
    }

    private static void position(String zh, String en, String... aliases) {
        POSITION_ALIAS_TO_EN.put(normalizeKey(zh), en);
        POSITION_ALIAS_TO_EN.put(normalizeKey(en), en);
        POSITION_ALIAS_TO_ZH.put(normalizeKey(en), zh);
        POSITION_ALIAS_TO_ZH.put(normalizeKey(zh), zh);
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) continue;
            POSITION_ALIAS_TO_EN.putIfAbsent(normalizeKey(alias), en);
            POSITION_ALIAS_TO_ZH.putIfAbsent(normalizeKey(alias), zh);
        }
    }

    private static void zone(String zh, String en) {
        ZONE_EN.put(zh, en);
        ZONE_ZH.put(en, zh);
    }

    /**
     * Normalizes a competition name, provider slug or short code to the
     * internal Chinese canonical value.  Unknown values are returned trimmed
     * so old callers keep their existing behaviour.
     */
    public static String canonicalLeague(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        String canonical = LEAGUE_ALIAS_TO_ZH.get(normalizeKey(trimmed));
        return canonical == null ? trimmed : canonical;
    }

    /**
     * Display name for a competition.  In English mode known values are
     * rendered with their official English name; in Chinese mode the internal
     * canonical Chinese value is returned.
     */
    public static String leagueDisplay(String value) {
        if (value == null) return "";
        String source = value.trim();
        if (source.isEmpty()) return source;
        String canonical = LEAGUE_ALIAS_TO_ZH.getOrDefault(normalizeKey(source), source);
        if (LangContext.isChinese()) return canonical;
        return LEAGUE_ZH_TO_EN.getOrDefault(canonical, source);
    }

    /** Canonical English name regardless of the current request language. */
    public static String leagueEnglish(String value) {
        String canonical = canonicalLeague(value);
        return LEAGUE_ZH_TO_EN.getOrDefault(canonical, value == null ? "" : value.trim());
    }

    /** Canonical Chinese name regardless of the current request language. */
    public static String leagueChinese(String value) {
        return canonicalLeague(value);
    }

    /**
     * Display name for a club or national team.  In English mode the original
     * English canonical name is preferred; Chinese input is mapped back to it.
     */
    public static String teamDisplay(String value) {
        if (value == null) return "";
        String source = value.trim();
        if (source.isEmpty()) return source;
        String key = normalizeKey(source);
        if (LangContext.isChinese()) return TEAM_ALIAS_TO_ZH.getOrDefault(key, source);
        return TEAM_ALIAS_TO_EN.getOrDefault(key, source);
    }

    /** Canonical English club/national-team name regardless of request language. */
    public static String teamEnglish(String value) {
        if (value == null) return "";
        String source = value.trim();
        return TEAM_ALIAS_TO_EN.getOrDefault(normalizeKey(source), source);
    }

    /** Canonical Chinese club/national-team name regardless of request language. */
    public static String teamChinese(String value) {
        if (value == null) return "";
        String source = value.trim();
        return TEAM_ALIAS_TO_ZH.getOrDefault(normalizeKey(source), source);
    }

    /**
     * All known spellings for a team, ordered from the supplied value to the
     * canonical English and Chinese names, then aliases.  The production match
     * feed stores English names while legacy/seed rows may store Chinese, so
     * lookups should use every equivalent spelling instead of only one
     * direction.  Both languages therefore resolve to the same candidate set
     * and return the same rows.
     */
    public static List<String> teamNameVariants(String value) {
        if (value == null) return List.of();
        String source = value.trim();
        if (source.isEmpty()) return List.of();
        String canonicalEnglish = TEAM_ALIAS_TO_EN.get(normalizeKey(source));
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (canonicalEnglish == null) {
            // Unknown team: preserve the caller's spelling instead of inventing
            // an English canonical value.
            variants.add(source);
        } else {
            variants.add(canonicalEnglish);
            String canonicalChinese = TEAM_ALIAS_TO_ZH.get(normalizeKey(canonicalEnglish));
            if (canonicalChinese != null && !canonicalChinese.isBlank()) variants.add(canonicalChinese);
            LinkedHashSet<String> known = TEAM_VARIANTS.get(normalizeKey(canonicalEnglish));
            if (known != null) variants.addAll(known);
        }
        return List.copyOf(variants);
    }

    /** Display name for a player position or squad slot. */
    public static String positionDisplay(String value) {
        if (value == null) return "";
        String source = value.trim();
        if (source.isEmpty()) return source;
        String key = normalizeKey(source);
        if (LangContext.isChinese()) return POSITION_ALIAS_TO_ZH.getOrDefault(key, source);
        return POSITION_ALIAS_TO_EN.getOrDefault(key, source);
    }

    /** Canonical English position name regardless of request language. */
    public static String positionEnglish(String value) {
        if (value == null) return "";
        String source = value.trim();
        return POSITION_ALIAS_TO_EN.getOrDefault(normalizeKey(source), source);
    }

    /** Canonical Chinese position name regardless of request language. */
    public static String positionChinese(String value) {
        if (value == null) return "";
        String source = value.trim();
        return POSITION_ALIAS_TO_ZH.getOrDefault(normalizeKey(source), source);
    }

    /** Display label for a standing-zone code label such as 欧冠正赛. */
    public static String zoneDisplay(String value) {
        if (value == null) return "";
        String source = value.trim();
        if (source.isEmpty()) return source;
        return LangContext.isChinese() ? ZONE_ZH.getOrDefault(source, source) : ZONE_EN.getOrDefault(source, source);
    }

    public static boolean isKnownLeague(String value) {
        return value != null && LEAGUE_ALIAS_TO_ZH.containsKey(normalizeKey(value));
    }

    public static boolean isKnownPosition(String value) {
        return value != null && POSITION_ALIAS_TO_EN.containsKey(normalizeKey(value));
    }

    /** Exposed for tests and for provider-specific alias lists. */
    public static String normalizeKey(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "");
    }
}
