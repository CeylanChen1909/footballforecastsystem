package com.chen.football.crawler.service;

import com.chen.football.common.i18n.I18nText;
import com.chen.football.common.i18n.LabelCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * League-specific table zones.  A league table cannot use a universal
 * "top four = Champions League" rule: qualifying rounds, promotion play-offs,
 * domestic cups and UEFA performance places differ by association and season.
 *
 * This class intentionally describes the league-position baseline only. Cup
 * winners and continental title holders can move a ticket down the table, so
 * the API exposes the rule note and season used alongside every zone.
 */
public final class StandingZoneRules {
    private static final String CURRENT_VARIANT = "2026/2027";

    /** English renderings for the user-facing rule notes. */
    private static final Map<String, String> NOTE_EN = Map.ofEntries(
            Map.entry("按 2026/27 基础名次：英超前五进入欧冠正赛；杯赛和欧战冠军名额可能顺延。",
                    "2026/27 baseline: the top five Premier League clubs enter the Champions League league phase; cup winners and UEFA title holders may shift places down."),
            Map.entry("按 2026/27 基础名次：西甲前五进入欧冠正赛；杯赛和欧战冠军名额可能顺延。",
                    "2026/27 baseline: the top five La Liga clubs enter the Champions League league phase; cup winners and UEFA title holders may shift places down."),
            Map.entry("按当前法甲名额分配：前三欧冠正赛，第 4 名欧冠资格赛，第 5 名欧联正赛，第 6 名欧协联资格赛；杯赛和欧战冠军名额可能顺延。",
                    "Current Ligue 1 allocation: top three enter the Champions League league phase, 4th enters Champions League qualifying, 5th enters the Europa League league phase, 6th enters Conference League qualifying; cup winners and UEFA title holders may shift places down."),
            Map.entry("按 2026/27 access list：前两名进入欧冠正赛，第 3 名欧联资格赛，第 4–7 名争夺欧协联资格。",
                    "2026/27 access list: top two enter the Champions League league phase, 3rd enters Europa League qualifying, and 4th–7th contest Conference League qualification."),
            Map.entry("按 2026/27 最终 access list：前两名进入欧冠正赛；第 2 个席位来自欧战冠军重平衡，非永久联赛固定名额。",
                    "Final 2026/27 access list: the top two enter the Champions League league phase; the second place comes from UEFA title-holder rebalancing and is not a permanent fixed league slot."),
            Map.entry("按当前已配置的法甲名额分配：前三欧冠正赛，第 4 名欧冠资格赛，第 5 名欧联正赛，第 6 名欧协联资格赛；杯赛和欧战冠军名额可能顺延。",
                    "Configured Ligue 1 allocation: top three enter the Champions League league phase, 4th enters Champions League qualifying, 5th enters the Europa League league phase, 6th enters Conference League qualifying; cup winners and UEFA title holders may shift places down."),
            Map.entry("按联赛基础名次：冠军欧冠正赛，亚军欧冠资格赛，第 3 名欧联资格赛，第 4–7 名争夺欧协联资格。",
                    "League baseline: the champion enters the Champions League league phase, runner-up enters Champions League qualifying, 3rd enters Europa League qualifying, and 4th–7th contest Conference League qualification."),
            Map.entry("按联赛基础名次：冠军欧冠正赛，亚军欧冠资格赛，第 3 名欧联资格赛，第 4 名欧协联资格赛。",
                    "League baseline: the champion enters the Champions League league phase, runner-up enters Champions League qualifying, 3rd enters Europa League qualifying, and 4th enters Conference League qualifying."),
            Map.entry("英冠不参加欧战：前两名直接升级，第 3–6 名参加升级附加赛。",
                    "The Championship does not enter UEFA competitions: the top two are promoted automatically and 3rd–6th enter the promotion play-offs."),
            Map.entry("按联赛基础名次：前四欧冠正赛，第 5 名欧联正赛，第 6 名欧协联资格赛。",
                    "League baseline: the top four enter the Champions League league phase, 5th enters the Europa League league phase, and 6th enters Conference League qualifying."),
            Map.entry("欧冠联赛阶段积分榜：不按国内联赛欧战名次区着色；排名仅反映联赛阶段成绩。",
                    "Champions League league-phase table: domestic qualification zones are not applied; the ranking reflects league-phase results only."),
            Map.entry("欧国联为多组赛制：按组展示组内排名，不套用国内联赛欧冠区规则。",
                    "The Nations League uses multiple groups: rankings are shown within each group and domestic Champions League zone rules do not apply."),
            Map.entry("该联赛尚未配置欧战名次规则；不会用默认前四规则伪造欧冠区域。",
                    "No UEFA qualification rules are configured for this league; a default top-four rule is never used to invent Champions League zones."),
            Map.entry("按 2026/27 access list：联赛冠军进入欧冠正赛；其余欧战名额与资格赛轮次受协会杯赛和 UEFA 重平衡影响。",
                    "2026/27 access list: the league champion enters the Champions League league phase; other UEFA places and qualifying rounds depend on association cups and UEFA rebalancing."),
            Map.entry("未指定赛季", "Season not specified")
    );

    private static final Map<String, String> LEAGUE_ALIASES = Map.ofEntries(
            Map.entry("英超", "英超"), Map.entry("premierleague", "英超"), Map.entry("epl", "英超"), Map.entry("pl", "英超"),
            Map.entry("西甲", "西甲"), Map.entry("laliga", "西甲"), Map.entry("primeradivision", "西甲"), Map.entry("pd", "西甲"),
            Map.entry("意甲", "意甲"), Map.entry("seriea", "意甲"), Map.entry("sa", "意甲"),
            Map.entry("德甲", "德甲"), Map.entry("bundesliga", "德甲"), Map.entry("bl1", "德甲"),
            Map.entry("法甲", "法甲"), Map.entry("ligue1", "法甲"), Map.entry("fl1", "法甲"),
            Map.entry("荷甲", "荷甲"), Map.entry("eredivisie", "荷甲"), Map.entry("ded", "荷甲"),
            Map.entry("葡超", "葡超"), Map.entry("primeiraliga", "葡超"), Map.entry("ppl", "葡超"),
            Map.entry("英冠", "英冠"), Map.entry("championship", "英冠"), Map.entry("elc", "英冠"),
            Map.entry("比甲", "比甲"), Map.entry("jupilerproleague", "比甲"), Map.entry("belgium", "比甲"),
            Map.entry("捷克甲", "捷克甲"), Map.entry("czechfirstleague", "捷克甲"), Map.entry("czechia", "捷克甲"),
            Map.entry("土超", "土超"), Map.entry("superlig", "土超"), Map.entry("turkiye", "土超"),
            Map.entry("乌超", "乌超"), Map.entry("premierleagueukraine", "乌超"), Map.entry("ukraine", "乌超"),
            // 赛事自身表格（不是国内联赛“欧冠区”）
            Map.entry("欧冠", "欧冠"), Map.entry("championsleague", "欧冠"), Map.entry("uefachampionsleague", "欧冠"),
            Map.entry("bbcchampionsleague", "欧冠"),
            Map.entry("欧国联", "欧国联"), Map.entry("nationsleague", "欧国联"), Map.entry("uefanationsleague", "欧国联"),
            Map.entry("bbcnationsleague", "欧国联")
    );

    private StandingZoneRules() { }

    public record Zone(String code, String label) {
        static Zone none() { return new Zone("", ""); }
    }

    private record Band(String code, String label, int start, int end) { }

    private record Rule(String league, String season, String note, List<Band> bands,
                        int relegationCount, int relegationPlayoffFromBottom) { }

    public static Zone resolve(String leagueName, String season, Integer rank, int total) {
        if (rank == null || rank <= 0 || total <= 0) return Zone.none();
        Rule rule = ruleFor(leagueName, season);
        for (Band band : rule.bands()) {
            if (rank >= band.start() && rank <= band.end()) return zone(band.code(), band.label());
        }
        if (rule.relegationPlayoffFromBottom() > 0
                && rank == total - rule.relegationPlayoffFromBottom()) {
            return zone("RELEGATION_PLAYOFF", "降级附加赛");
        }
        if (rule.relegationCount() > 0 && rank > total - rule.relegationCount()) {
            return zone("RELEGATION", "降级");
        }
        return Zone.none();
    }

    public static Map<String, Object> describe(String leagueName, String season, int total) {
        Rule rule = ruleFor(leagueName, season);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("league", LabelCatalog.leagueDisplay(rule.league()));
        result.put("season", rule.season());
        result.put("note", localizedNote(rule.note()));
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Band band : rule.bands()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", band.code());
            item.put("label", LabelCatalog.zoneDisplay(band.label()));
            item.put("from", band.start());
            item.put("to", band.end());
            zones.add(item);
        }
        if (rule.relegationPlayoffFromBottom() > 0 && total > 0) {
            int rank = total - rule.relegationPlayoffFromBottom();
            zones.add(zoneDescription("RELEGATION_PLAYOFF", "降级附加赛", rank, rank));
        }
        if (rule.relegationCount() > 0 && total > 0) {
            zones.add(zoneDescription("RELEGATION", "降级", total - rule.relegationCount() + 1, total));
        }
        result.put("zones", zones);
        return result;
    }

    private static Map<String, Object> zoneDescription(String code, String label, int from, int to) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("label", LabelCatalog.zoneDisplay(label));
        item.put("from", from);
        item.put("to", to);
        return item;
    }

    private static String localizedNote(String note) {
        return I18nText.of(note, NOTE_EN.getOrDefault(note, note));
    }

    private static Zone zone(String code, String label) { return new Zone(code, LabelCatalog.zoneDisplay(label)); }

    private static Rule ruleFor(String leagueName, String season) {
        String league = canonicalLeague(leagueName);
        String normalizedSeason = season == null || season.isBlank() ? "" : season.trim();
        boolean currentVariant = isCurrentVariant(normalizedSeason);

        // The current 2026/27 access list includes EPS and the final
        // rebalancing effects that change the raw association baseline.
        if (currentVariant) {
            return switch (league) {
                case "英超" -> rule(league, normalizedSeason, "按 2026/27 基础名次：英超前五进入欧冠正赛；杯赛和欧战冠军名额可能顺延。",
                        bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 5), band("EUROPA_LEAGUE", "欧联正赛", 6, 7), band("CONFERENCE_LEAGUE_QUALIFYING", "欧协联资格赛", 8, 8)), 3, 0);
                case "西甲" -> rule(league, normalizedSeason, "按 2026/27 基础名次：西甲前五进入欧冠正赛；杯赛和欧战冠军名额可能顺延。",
                        bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 5), band("EUROPA_LEAGUE", "欧联正赛", 6, 6), band("CONFERENCE_LEAGUE", "欧协联正赛", 7, 7)), 3, 0);
                case "法甲" -> rule(league, normalizedSeason, "按当前法甲名额分配：前三欧冠正赛，第 4 名欧冠资格赛，第 5 名欧联正赛，第 6 名欧协联资格赛；杯赛和欧战冠军名额可能顺延。",
                        bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 3), band("CHAMPIONS_LEAGUE_QUALIFYING", "欧冠资格赛", 4, 4), band("EUROPA_LEAGUE", "欧联正赛", 5, 5), band("CONFERENCE_LEAGUE_QUALIFYING", "欧协联资格赛", 6, 6)), 2, 2);
                case "荷甲" -> rule(league, normalizedSeason, "按 2026/27 access list：前两名进入欧冠正赛，第 3 名欧联资格赛，第 4–7 名争夺欧协联资格。",
                        bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 2), band("EUROPA_LEAGUE_QUALIFYING", "欧联资格赛", 3, 3), band("CONFERENCE_PLAYOFF", "欧协联附加赛", 4, 7)), 2, 2);
                case "葡超" -> rule(league, normalizedSeason, "按 2026/27 最终 access list：前两名进入欧冠正赛；第 2 个席位来自欧战冠军重平衡，非永久联赛固定名额。",
                        bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 2), band("EUROPA_LEAGUE_QUALIFYING", "欧联资格赛", 3, 3), band("CONFERENCE_LEAGUE_QUALIFYING", "欧协联资格赛", 4, 4)), 2, 2);
                case "比甲", "捷克甲", "土超", "乌超" -> singleChampionsLeagueRule(league, normalizedSeason);
                default -> baseRule(league, normalizedSeason);
            };
        }
        return baseRule(league, normalizedSeason);
    }

    private static Rule baseRule(String league, String season) {
        return switch (league) {
            case "法甲" -> rule(league, season, "按当前已配置的法甲名额分配：前三欧冠正赛，第 4 名欧冠资格赛，第 5 名欧联正赛，第 6 名欧协联资格赛；杯赛和欧战冠军名额可能顺延。",
                    bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 3), band("CHAMPIONS_LEAGUE_QUALIFYING", "欧冠资格赛", 4, 4), band("EUROPA_LEAGUE", "欧联正赛", 5, 5), band("CONFERENCE_LEAGUE_QUALIFYING", "欧协联资格赛", 6, 6)), 2, 2);
            case "荷甲" -> rule(league, season, "按联赛基础名次：冠军欧冠正赛，亚军欧冠资格赛，第 3 名欧联资格赛，第 4–7 名争夺欧协联资格。",
                    bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 1), band("CHAMPIONS_LEAGUE_QUALIFYING", "欧冠资格赛", 2, 2), band("EUROPA_LEAGUE_QUALIFYING", "欧联资格赛", 3, 3), band("CONFERENCE_PLAYOFF", "欧协联附加赛", 4, 7)), 2, 2);
            case "葡超" -> rule(league, season, "按联赛基础名次：冠军欧冠正赛，亚军欧冠资格赛，第 3 名欧联资格赛，第 4 名欧协联资格赛。",
                    bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 1), band("CHAMPIONS_LEAGUE_QUALIFYING", "欧冠资格赛", 2, 2), band("EUROPA_LEAGUE_QUALIFYING", "欧联资格赛", 3, 3), band("CONFERENCE_LEAGUE_QUALIFYING", "欧协联资格赛", 4, 4)), 2, 2);
            case "英冠" -> rule(league, season, "英冠不参加欧战：前两名直接升级，第 3–6 名参加升级附加赛。",
                    bands(band("PROMOTION", "直接升级", 1, 2), band("PROMOTION_PLAYOFF", "升级附加赛", 3, 6)), 3, 0);
            case "德甲" -> rule(league, season, "按联赛基础名次：前四欧冠正赛，第 5 名欧联正赛，第 6 名欧协联资格赛。",
                    bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 4), band("EUROPA_LEAGUE", "欧联正赛", 5, 5), band("CONFERENCE_LEAGUE_QUALIFYING", "欧协联资格赛", 6, 6)), 2, 2);
            case "比甲", "捷克甲", "土超", "乌超" -> singleChampionsLeagueRule(league, season);
            case "欧冠" -> rule(league, season, "欧冠联赛阶段积分榜：不按国内联赛欧战名次区着色；排名仅反映联赛阶段成绩。",
                    List.of(), 0, 0);
            case "欧国联" -> rule(league, season, "欧国联为多组赛制：按组展示组内排名，不套用国内联赛欧冠区规则。",
                    List.of(), 0, 0);
            default -> rule(league, season, "该联赛尚未配置欧战名次规则；不会用默认前四规则伪造欧冠区域。",
                    List.of(), 0, 0);
        };
    }

    private static Rule singleChampionsLeagueRule(String league, String season) {
        return rule(league, season, "按 2026/27 access list：联赛冠军进入欧冠正赛；其余欧战名额与资格赛轮次受协会杯赛和 UEFA 重平衡影响。",
                bands(band("CHAMPIONS_LEAGUE", "欧冠正赛", 1, 1)), 2, 2);
    }

    private static Rule rule(String league, String season, String note, List<Band> bands, int relegationCount, int relegationPlayoffFromBottom) {
        return new Rule(league, season == null || season.isBlank() ? "未指定赛季" : season, note, bands, relegationCount, relegationPlayoffFromBottom);
    }

    private static List<Band> bands(Band... bands) { return List.of(bands); }

    private static Band band(String code, String label, int start, int end) { return new Band(code, label, start, end); }

    private static boolean isCurrentVariant(String season) {
        String value = season == null ? "" : season.replace('-', '/').trim();
        return CURRENT_VARIANT.equals(value) || value.startsWith("2026/") || "2026".equals(value);
    }

    private static String canonicalLeague(String value) {
        String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
        return LEAGUE_ALIASES.getOrDefault(key, value == null || value.isBlank() ? "未知联赛" : value.trim());
    }
}
