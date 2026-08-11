package com.chen.football.crawler.service;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchSyncService {

    private final MatchCrawlerService matchCrawlerService;
    private final CrawlerMatchMapper crawlerMatchMapper;

    public SyncReport syncToday() {
        return syncSingle("today", matchCrawlerService.crawlTodayMatches());
    }

    public SyncReport syncUpcoming() {
        return syncSingle("upcoming", matchCrawlerService.crawlUpcomingMatches());
    }

    public SyncReport syncLeague(String leagueName, java.util.Date date) {
        return syncSingle("league:" + leagueName, matchCrawlerService.crawlMatchesByLeagueAndDate(leagueName, date));
    }

    public SyncReport syncAll() {
        List<SourceReport> sources = new ArrayList<>();
        SyncReport footballData = syncFootballData();
        SyncReport juhe = syncJuhe();
        SyncReport crawler = syncCrawler();
        sources.addAll(footballData.sources());
        sources.addAll(juhe.sources());
        sources.addAll(crawler.sources());

        int total = sources.stream().mapToInt(SourceReport::fetched).sum();
        int inserted = sources.stream().mapToInt(SourceReport::inserted).sum();
        int updated = sources.stream().mapToInt(SourceReport::updated).sum();
        SyncReport report = new SyncReport("all", total, inserted, updated, LocalDateTime.now(), sources);
        log.info("[SYNC][ALL] {}", report.toSummaryLine());
        sources.forEach(s -> log.info("[SYNC][{}] {}", s.source(), s.toSummaryLine()));
        return report;
    }

    public SyncReport syncFootballData() {
        return syncSingle("football-data", matchCrawlerService.crawlFootballDataRecentMatches());
    }

    public SyncReport syncJuhe() {
        return syncSingle("juhe", matchCrawlerService.crawlJuheTodayMatches());
    }

    public SyncReport syncCrawler() {
        return syncSingle("crawler", matchCrawlerService.crawlWebFallbackTodayMatches());
    }

    private SyncReport syncSingle(String sourceName, List<CrawlerMatch> matches) {
        SourceReport sourceReport = syncSource(sourceName, matches, 10);
        SyncReport report = new SyncReport(sourceName, sourceReport.fetched(), sourceReport.inserted(), sourceReport.updated(), LocalDateTime.now(), List.of(sourceReport));
        log.info("[SYNC][{}] {}", sourceName, sourceReport.toSummaryLine());
        return report;
    }

    private SourceReport syncSource(String sourceName, List<CrawlerMatch> matches, int previewLimit) {
        int fetched = matches == null ? 0 : matches.size();
        int inserted = 0;
        int updated = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        if (matches != null) {
            for (CrawlerMatch match : matches) {
                boolean isInserted = upsert(match);
                if (isInserted) inserted++; else updated++;
                if (samples.size() < previewLimit && match != null) samples.add(snapshot(match));
            }
        }
        return new SourceReport(sourceName, fetched, inserted, updated, samples);
    }

    private boolean upsert(CrawlerMatch match) {
        if (match == null || match.getExternalMatchId() == null || match.getExternalMatchId().isBlank()) return false;
        CrawlerMatch existing = crawlerMatchMapper.findByExternalId(match.getExternalMatchId(), match.getSource());
        if (existing == null) {
            match.setCreatedAt(LocalDateTime.now());
            match.setUpdatedAt(LocalDateTime.now());
            crawlerMatchMapper.insert(match);
            return true;
        }
        existing.setLeagueName(match.getLeagueName());
        existing.setLeagueId(match.getLeagueId());
        existing.setHomeTeamName(match.getHomeTeamName());
        existing.setHomeTeamId(match.getHomeTeamId());
        existing.setHomeTeamLogo(match.getHomeTeamLogo());
        existing.setAwayTeamName(match.getAwayTeamName());
        existing.setAwayTeamId(match.getAwayTeamId());
        existing.setAwayTeamLogo(match.getAwayTeamLogo());
        existing.setHomeScore(match.getHomeScore());
        existing.setAwayScore(match.getAwayScore());
        existing.setStatus(match.getStatus());
        existing.setMatchTime(match.getMatchTime());
        existing.setVenue(match.getVenue());
        existing.setRound(match.getRound());
        existing.setSource(match.getSource());
        existing.setUpdatedAt(LocalDateTime.now());
        crawlerMatchMapper.updateById(existing);
        return false;
    }

    private Map<String, Object> snapshot(CrawlerMatch match) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", match.getSource());
        data.put("fixtureId", match.getFixtureId());
        data.put("leagueName", match.getLeagueName());
        data.put("homeTeamName", match.getHomeTeamName());
        data.put("awayTeamName", match.getAwayTeamName());
        data.put("homeScore", match.getHomeScore());
        data.put("awayScore", match.getAwayScore());
        data.put("status", match.getStatus());
        data.put("matchTime", match.getMatchTime());
        return data;
    }

    public record SyncReport(String source, int total, int inserted, int updated, LocalDateTime syncedAt, List<SourceReport> sources) {
        public String toSummaryLine() { return "total=" + total + ", inserted=" + inserted + ", updated=" + updated + ", syncedAt=" + syncedAt; }
    }

    public record SourceReport(String source, int fetched, int inserted, int updated, List<Map<String, Object>> samples) {
        public String toSummaryLine() { return "fetched=" + fetched + ", inserted=" + inserted + ", updated=" + updated + ", samples=" + samples; }
    }
}
