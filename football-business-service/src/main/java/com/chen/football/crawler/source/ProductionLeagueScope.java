package com.chen.football.crawler.source;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerStanding;
import com.chen.football.crawler.entity.CrawlerTeam;

import java.util.Locale;
import java.util.Set;

/** Shared read-time visibility policy for the production eight-league set. */
public final class ProductionLeagueScope {
    private static final Set<String> IDS = Set.of(
            "39", "140", "135", "78", "61", "88", "94", "40", "2",
            "pl", "pd", "sa", "bl1", "fl1", "ded", "ppl", "elc",
            // Hyphenated provider ids (exact) and de-hyphenated forms because
            // normalize() strips hyphens before IDS.contains().
            "bbc-premier-league", "bbcpremierleague",
            "bbc-spanish-la-liga", "bbcspanishlaliga",
            "bbc-italian-serie-a", "bbcitalianseriea",
            "bbc-german-bundesliga", "bbcgermanbundesliga",
            "bbc-french-ligue-one", "bbcfrenchligueone",
            "bbc-dutch-eredivisie", "bbcdutcheredivisie",
            "bbc-portuguese-primeira-liga", "bbcportugueseprimeiraliga",
            "bbc-championship", "bbcchampionship",
            "bbc-champions-league", "bbcchampionsleague",
            "bbc-nations-league", "bbcnationsleague");
    private static final Set<String> NAMES = Set.of(
            "英超", "西甲", "意甲", "德甲", "法甲", "荷甲", "葡超", "英冠", "欧冠", "欧国联",
            "premierleague", "laliga", "primeradivision", "seriea", "bundesliga",
            "ligue1", "eredivisie", "primeiraliga", "portugueseprimeiraliga", "championship",
            "uefachampionsleague", "championsleague", "uefanationsleague", "nationsleague");

    private ProductionLeagueScope() {}

    public static boolean isSupported(String leagueId, String leagueName) {
        String id = normalize(leagueId);
        if (!id.isBlank() && IDS.contains(id)) return true;
        String name = normalize(leagueName);
        return !name.isBlank() && NAMES.contains(name);
    }

    public static boolean isVisible(CrawlerMatch match, CrawlerProperties properties) {
        if (match == null || !isSupported(match.getLeagueId(), match.getLeagueName())) return false;
        return isPrimarySource(match.getSource(), properties);
    }

    public static boolean isVisible(CrawlerTeam team, CrawlerProperties properties) {
        return team != null
                && isSupported(null, team.getLeagueName())
                && isPrimarySource(team.getSource(), properties);
    }

    public static boolean isVisible(CrawlerStanding standing, CrawlerProperties properties) {
        if (standing == null || !isSupported(standing.getLeagueId(), standing.getLeagueName())) return false;
        return isPrimarySource(standing.getSource(), properties);
    }

    private static boolean isPrimarySource(String source, CrawlerProperties properties) {
        if (properties == null || !properties.isPrimaryOnly()) return true;
        String primary = properties.getPrimarySource();
        if (primary == null || primary.isBlank() || source == null || source.isBlank()) return false;
        if (primary.equalsIgnoreCase(source)) return true;
        // BBC scores is the primary match feed, while standings are persisted
        // from the same provider under a dedicated source label.  Treat that
        // label as the same trusted source family so Agent/team context does
        // not report an empty database when the club directory is available.
        return "bbc-scores".equalsIgnoreCase(primary)
                && "bbc-standings".equalsIgnoreCase(source);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
    }
}
