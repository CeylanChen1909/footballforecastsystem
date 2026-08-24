package com.chen.football.crawler.source;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.http.CrawlerHttpClient;
import com.chen.football.crawler.parser.WorldFootballParser;
import com.chen.football.crawler.parser.Zq123Parser;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.dto.FetchResult;
import com.chen.football.common.dto.MatchStatus;
import com.chen.football.common.dto.NormalizedMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class WebCrawlerSourceProvider implements MatchSourceProvider {

    private static final Map<String, String> LEAGUE_URLS = Map.of(
            "英超", "/competition/co91/england-premier-league/results-and-standings/",
            "西甲", "/competition/co64/spain-la-liga/results-and-standings/",
            "意甲", "/competition/co33/italy-serie-a/results-and-standings/",
            "德甲", "/competition/co25/germany-bundesliga/results-and-standings/",
            "法甲", "/competition/co34/france-ligue-1/results-and-standings/",
            "中超", "/competition/co355/china-super-league/results-and-standings/",
            "欧冠", "/competition/co19/uefa-champions-league/results-and-standings/",
            "荷甲", "/competition/co3/netherlands-eredivisie/results-and-standings/",
            "葡超", "/competition/co32/portugal-primeira-liga/results-and-standings/",
            "英冠", "/competition/co14/england-championship/results-and-standings/"
    );

    private final CrawlerHttpClient httpClient;
    private final WorldFootballParser worldFootballParser;
    private final Zq123Parser zq123Parser;
    private final CrawlerProperties properties;

    public WebCrawlerSourceProvider(CrawlerHttpClient httpClient,
                                    WorldFootballParser worldFootballParser,
                                    Zq123Parser zq123Parser,
                                    CrawlerProperties properties) {
        this.httpClient = httpClient;
        this.worldFootballParser = worldFootballParser;
        this.zq123Parser = zq123Parser;
        this.properties = properties;
    }

    @Override
    public String name() { return "web-crawler"; }

    @Override
    public int priority() { return 6; }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled()
                && properties.getWorldFootball() != null
                && properties.getWorldFootball().isEnabled();
    }

    @Override
    public FetchResult fetchMatches(String date) {
        long start = System.currentTimeMillis();
        try {
            List<NormalizedMatch> matches = new ArrayList<>();
            for (var entry : LEAGUE_URLS.entrySet()) {
                String html = httpClient.getHtml(entry.getValue() + date);
                matches.addAll(parseHtml(html, entry.getKey()));
            }
            return FetchResult.success(name(), matches, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[WebCrawler] fetchMatches failed: {}", e.getMessage());
            return FetchResult.failure(name(), e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public FetchResult fetchMatchesByLeague(int leagueId, int season) {
        long start = System.currentTimeMillis();
        try {
            String leagueName = switch (leagueId) {
                case 39 -> "英超";
                case 140 -> "西甲";
                case 135 -> "意甲";
                case 78 -> "德甲";
                case 61 -> "法甲";
                case 2 -> "欧冠";
                case 88 -> "荷甲";
                case 94 -> "葡超";
                case 40 -> "英冠";
                default -> "英超";
            };
            String html = httpClient.getHtml(LEAGUE_URLS.getOrDefault(leagueName, LEAGUE_URLS.get("英超")));
            return FetchResult.success(name(), parseHtml(html, leagueName), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[WebCrawler] fetchMatchesByLeague failed: {}", e.getMessage());
            return FetchResult.failure(name(), e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private List<NormalizedMatch> parseHtml(String html, String leagueName) {
        List<NormalizedMatch> result = new ArrayList<>();
        if (html == null || html.isBlank()) return result;
        List<CrawlerMatch> matches = worldFootballParser.parseMatchList(html, leagueName);
        if (matches.isEmpty()) {
            matches = zq123Parser.parseMatchList(html);
        }
        for (CrawlerMatch m : matches) {
            String status = MatchStatus.normalize(m.getStatus());
            result.add(new NormalizedMatch(
                    name(),
                    m.getExternalMatchId() == null ? (m.getHomeTeamName() + "_" + m.getAwayTeamName()) : m.getExternalMatchId(),
                    m.getFixtureId(),
                    m.getLeagueId(),
                    m.getLeagueName(),
                    m.getHomeTeamId(),
                    m.getHomeTeamName(),
                    m.getHomeTeamLogo(),
                    m.getAwayTeamId(),
                    m.getAwayTeamName(),
                    m.getAwayTeamLogo(),
                    MatchStatus.hasScore(status) ? m.getHomeScore() : null,
                    MatchStatus.hasScore(status) ? m.getAwayScore() : null,
                    status,
                    m.getMatchTime() == null ? null : m.getMatchTime(),
                    m.getVenue(),
                    m.getRound()
            ));
        }
        return result;
    }
}
