package com.chen.football.crawler.source;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.dto.FetchResult;
import com.chen.football.common.dto.MatchStatus;
import com.chen.football.common.dto.NormalizedMatch;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.http.CrawlerHttpClient;
import com.chen.football.crawler.parser.BbcScoresParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * BBC Sport 按日赛事源。
 *
 * 该源只负责读取和规范化，不直接写数据库；正式入库仍由
 * MatchCrawlerService 的统一去重/校验流程完成。
 */
@Slf4j
@Component
public class BbcScoresSourceProvider implements MatchSourceProvider {

    private final CrawlerHttpClient httpClient;
    private final BbcScoresParser parser;
    private final CrawlerProperties properties;

    public BbcScoresSourceProvider(CrawlerHttpClient httpClient,
                                   BbcScoresParser parser,
                                   CrawlerProperties properties) {
        this.httpClient = httpClient;
        this.parser = parser;
        this.properties = properties;
    }

    @Override
    public String name() {
        return BbcScoresParser.SOURCE;
    }

    @Override
    public int priority() {
        // API/football-data/聚合数据之后，旧网页源之前。
        return 4;
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled()
                && properties.getBbc() != null
                && properties.getBbc().isEnabled();
    }

    @Override
    public FetchResult fetchMatches(String date) {
        long start = System.currentTimeMillis();
        LocalDate targetDate;
        try {
            targetDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return FetchResult.failure(name(), "invalid date: " + date, elapsed(start));
        }

        try {
            String url = buildUrl(targetDate);
            CrawlerProperties.Bbc bbc = properties.getBbc();
            String html = httpClient.getHtmlDirect(
                    url,
                    bbc.getUserAgent(),
                    Duration.ofMillis(Math.max(1000, bbc.getTimeoutMs()))
            );
            if (!parser.isValidScoresPage(html)) {
                return FetchResult.failure(name(), "BBC scores page signature missing; keep last good snapshot", elapsed(start));
            }
            List<CrawlerMatch> parsed = parser.parseMatchList(html, targetDate);
            if (parsed.isEmpty() && parser.hasEventMarkers(html)) {
                return FetchResult.failure(name(), "BBC page contains event markers but no fixtures were parsed; parser drift suspected", elapsed(start));
            }
            List<NormalizedMatch> normalized = parsed.stream().map(this::normalize).toList();
            return FetchResult.success(name(), normalized, elapsed(start));
        } catch (Exception e) {
            log.warn("[BBC] fetchMatches failed, date={}, error={}", date, e.getMessage());
            return FetchResult.failure(name(), e.getMessage(), elapsed(start));
        }
    }

    @Override
    public FetchResult fetchMatchesByLeague(int leagueId, int season) {
        // BBC 的公开页面按日期聚合，不提供稳定的联赛/赛季查询。
        // 不能用“今天的数据”冒充指定联赛结果，否则会把错误赛事写入链路。
        return FetchResult.failure(name(), "该主爬虫源仅支持按日期查询，不支持按联赛/赛季查询", 0);
    }

    private String buildUrl(LocalDate date) {
        CrawlerProperties.Bbc bbc = properties.getBbc();
        String base = bbc.getBaseUrl() == null ? "https://www.bbc.com" : bbc.getBaseUrl().replaceAll("/$", "");
        String path = bbc.getScoresPath() == null ? "/sport/football/scores-fixtures" : bbc.getScoresPath();
        if (!path.startsWith("/")) path = "/" + path;
        return base + path.replaceAll("/$", "") + "/" + date;
    }

    private NormalizedMatch normalize(CrawlerMatch match) {
        String status = MatchStatus.normalize(match.getStatus());
        return new NormalizedMatch(
                name(),
                match.getExternalMatchId(),
                match.getFixtureId(),
                match.getLeagueId(),
                match.getLeagueName(),
                match.getHomeTeamId(),
                match.getHomeTeamName(),
                match.getHomeTeamLogo(),
                match.getAwayTeamId(),
                match.getAwayTeamName(),
                match.getAwayTeamLogo(),
                MatchStatus.hasScore(status) ? match.getHomeScore() : null,
                MatchStatus.hasScore(status) ? match.getAwayScore() : null,
                status,
                match.getMatchTime(),
                match.getVenue(),
                match.getRound()
        );
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
