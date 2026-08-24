package com.chen.football.crawler.service;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchSyncService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final MatchCrawlerService matchCrawlerService;
    private final CrawlerMatchMapper crawlerMatchMapper;
    private final IdentityMappingService identityMappingService;

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
        SyncReport report = new SyncReport("all", total, inserted, updated, LocalDateTime.now(BUSINESS_ZONE), sources);
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
        SyncReport report = new SyncReport(sourceName, sourceReport.fetched(), sourceReport.inserted(), sourceReport.updated(), LocalDateTime.now(BUSINESS_ZONE), List.of(sourceReport));
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
        if (match == null || match.getExternalMatchId() == null || match.getExternalMatchId().isBlank()
                || !matchCrawlerService.isProductionLeague(match)) return false;
        identityMappingService.ensureMatch(match);
        CrawlerMatch existing = crawlerMatchMapper.findByExternalId(match.getExternalMatchId(), match.getSource());
        if (existing == null) {
            existing = crawlerMatchMapper.findByFixtureIdAndSource(match.getFixtureId(), match.getSource());
        }
        if (existing == null) {
            existing = crawlerMatchMapper.findBySourceAndTeamsOnDate(
                    match.getSource(), match.getHomeTeamId(), match.getAwayTeamId(),
                    match.getHomeTeamName(), match.getAwayTeamName(), match.getMatchTime());
        }
        if (existing == null) {
            match.setCreatedAt(LocalDateTime.now(BUSINESS_ZONE));
            match.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
            crawlerMatchMapper.insert(match);
            return true;
        }
        // 身份字段不能只在首次插入时写入。上游可能先返回字符串事件 ID，
        // 后续才补充数字 fixture ID；保留旧值会让预测和详情链路长期无法关联。
        if (match.getExternalMatchId() != null && !match.getExternalMatchId().isBlank()) {
            existing.setExternalMatchId(match.getExternalMatchId());
        }
        if (match.getFixtureId() != null && match.getFixtureId() > 0) {
            existing.setFixtureId(match.getFixtureId());
        }
        mergeNonBlank(existing, match);
        existing.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
        crawlerMatchMapper.updateById(existing);
        return false;
    }

    private void mergeNonBlank(CrawlerMatch existing, CrawlerMatch incoming) {
        if (incoming.getLeagueName() != null && !incoming.getLeagueName().isBlank()) existing.setLeagueName(incoming.getLeagueName());
        if (incoming.getLeagueId() != null && !incoming.getLeagueId().isBlank()) existing.setLeagueId(incoming.getLeagueId());
        if (incoming.getHomeTeamName() != null && !incoming.getHomeTeamName().isBlank()) existing.setHomeTeamName(incoming.getHomeTeamName());
        if (incoming.getHomeTeamId() != null && !incoming.getHomeTeamId().isBlank()) existing.setHomeTeamId(incoming.getHomeTeamId());
        if (incoming.getHomeTeamLogo() != null && !incoming.getHomeTeamLogo().isBlank()) existing.setHomeTeamLogo(incoming.getHomeTeamLogo());
        if (incoming.getAwayTeamName() != null && !incoming.getAwayTeamName().isBlank()) existing.setAwayTeamName(incoming.getAwayTeamName());
        if (incoming.getAwayTeamId() != null && !incoming.getAwayTeamId().isBlank()) existing.setAwayTeamId(incoming.getAwayTeamId());
        if (incoming.getAwayTeamLogo() != null && !incoming.getAwayTeamLogo().isBlank()) existing.setAwayTeamLogo(incoming.getAwayTeamLogo());
        if (incoming.getHomeScore() != null) existing.setHomeScore(incoming.getHomeScore());
        if (incoming.getAwayScore() != null) existing.setAwayScore(incoming.getAwayScore());
        if (incoming.getStatus() != null && !incoming.getStatus().isBlank()) existing.setStatus(incoming.getStatus());
        if (incoming.getMatchTime() != null) existing.setMatchTime(incoming.getMatchTime());
        if (incoming.getVenue() != null && !incoming.getVenue().isBlank()) existing.setVenue(incoming.getVenue());
        if (incoming.getRound() != null && !incoming.getRound().isBlank()) existing.setRound(incoming.getRound());
        if (incoming.getNote() != null && !incoming.getNote().isBlank()) existing.setNote(incoming.getNote());
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
