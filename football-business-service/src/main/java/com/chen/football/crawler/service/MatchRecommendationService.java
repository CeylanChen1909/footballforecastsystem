package com.chen.football.crawler.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.dto.MatchStatus;
import com.chen.football.common.service.RedisCacheService;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.source.DataSourceManager;
import com.chen.football.crawler.source.ProductionLeagueScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.Objects;

/**
 * Provides an explainable, source-consistent match-focus ranking.
 *
 * <p>This deliberately calls the result "focus" internally. Until real
 * engagement signals are available, the service must not claim that a score
 * represents public popularity.</p>
 */
@Slf4j
@Service
public class MatchRecommendationService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ALGORITHM_VERSION = "focus-v2";
    private static final long CACHE_TTL_SECONDS = 45L;
    private static final Set<String> TERMINAL_OR_INVALID = Set.of(
            "CANC", "CANCELED", "CANCELLED", "PST", "POSTPONED", "PPD", "ABD", "AWD", "WO",
            "SOURCEREMOVED");

    private final CrawlerMatchMapper crawlerMatchMapper;
    private final DataSourceManager dataSourceManager;
    private final RedisCacheService redisCacheService;

    public MatchRecommendationService(CrawlerMatchMapper crawlerMatchMapper,
                                      DataSourceManager dataSourceManager,
                                      RedisCacheService redisCacheService) {
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.dataSourceManager = dataSourceManager;
        this.redisCacheService = redisCacheService;
    }

    public RecommendationResult recommend(LocalDate requestedDate, String requestedMode, int requestedLimit) {
        LocalDate date = requestedDate == null ? LocalDate.now(BUSINESS_ZONE) : requestedDate;
        String mode = requestedMode == null || requestedMode.isBlank() ? "focus" : requestedMode.trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? 6 : requestedLimit, 10));
        String cacheKey = "match-recommendations:" + ALGORITHM_VERSION + ":" + date + ":" + mode + ":" + limit;

        RecommendationResult cached = redisCacheService.get(cacheKey, RecommendationResult.class);
        if (cached != null && cached.items != null) {
            Map<String, Object> meta = new LinkedHashMap<>(cached.meta == null ? Map.of() : cached.meta);
            meta.put("cacheHit", true);
            return new RecommendationResult(cached.items, meta);
        }

        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        // Include the selected date and a small surrounding window so a user
        // can see a match that is just before/after midnight without exposing
        // a noisy two-week list.
        LocalDateTime windowStart = date.minusDays(1).atStartOfDay();
        LocalDateTime windowEnd = date.plusDays(2).atStartOfDay();
        List<CrawlerMatch> candidates = crawlerMatchMapper.selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .ge(CrawlerMatch::getMatchTime, windowStart)
                .lt(CrawlerMatch::getMatchTime, windowEnd)
                .orderByAsc(CrawlerMatch::getMatchTime)
                .orderByAsc(CrawlerMatch::getId));

        int candidateCount = candidates.size();
        List<CrawlerMatch> visible = candidates.stream()
                .filter(Objects::nonNull)
                .filter(match -> dataSourceManager.isSourceEnabled(match.getSource()))
                .filter(match -> ProductionLeagueScope.isSupported(match.getLeagueId(), match.getLeagueName()))
                .filter(match -> !TERMINAL_OR_INVALID.contains(normalizeStatus(match.getStatus())))
                .toList();
        List<CrawlerMatch> unique = deduplicate(visible);
        List<ScoredMatch> scored = unique.stream()
                .map(match -> score(match, now, date))
                .sorted(Comparator.comparingInt(ScoredMatch::score).reversed()
                        .thenComparing(item -> kickoff(item.match), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.match.getId(), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<ScoredMatch> selected = diversify(scored, limit);
        List<RecommendationItem> items = selected.stream().map(item -> new RecommendationItem(
                item.match, item.score, item.tier, item.reasonCodes, item.reasonTexts)).toList();

        java.time.Instant generatedAt = java.time.Instant.now();
        InstantPair times = new InstantPair(generatedAt, generatedAt.plusSeconds(CACHE_TTL_SECONDS));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("date", date.toString());
        meta.put("mode", mode);
        meta.put("candidateCount", candidateCount);
        meta.put("visibleCandidateCount", visible.size());
        meta.put("uniqueCandidateCount", unique.size());
        meta.put("returnedCount", items.size());
        meta.put("generatedAt", times.generatedAt.toString());
        meta.put("expiresAt", times.expiresAt.toString());
        meta.put("algorithmVersion", ALGORITHM_VERSION);
        meta.put("source", dataSourceManager.primarySource());
        meta.put("windowStart", windowStart.toString());
        meta.put("windowEnd", windowEnd.toString());
        meta.put("dataQuality", unique.isEmpty() ? "EMPTY" : "AVAILABLE");
        meta.put("fallbackUsed", false);
        meta.put("cacheHit", false);
        if (items.isEmpty()) {
            meta.put("emptyReason", candidateCount == 0 ? "NO_MATCHES_IN_WINDOW" : "NO_VISIBLE_FOCUS_MATCHES");
        }

        RecommendationResult result = new RecommendationResult(items, meta);
        redisCacheService.set(cacheKey, result, CACHE_TTL_SECONDS);
        return result;
    }

    /** Score used by legacy match payloads as a stable display hint. */
    public int displayScore(CrawlerMatch match) {
        return score(match, LocalDateTime.now(BUSINESS_ZONE), LocalDate.now(BUSINESS_ZONE)).score;
    }

    private List<CrawlerMatch> deduplicate(List<CrawlerMatch> matches) {
        Map<String, CrawlerMatch> byIdentity = new LinkedHashMap<>();
        for (CrawlerMatch match : matches) {
            String key = identity(match);
            if (key.isBlank()) continue;
            CrawlerMatch previous = byIdentity.get(key);
            if (previous == null || updatedAt(match).isAfter(updatedAt(previous))) byIdentity.put(key, match);
        }
        return new ArrayList<>(byIdentity.values());
    }

    private ScoredMatch score(CrawlerMatch match, LocalDateTime now, LocalDate selectedDate) {
        String status = normalizeStatus(match.getStatus());
        int score = 0;
        List<String> codes = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        String tier;

        if (isLive(status)) {
            score += 40;
            tier = "LIVE";
            codes.add("LIVE_NOW");
            texts.add("正在进行");
        } else if (isUpcoming(status, match, now)) {
            score += 28;
            tier = "UPCOMING";
            codes.add("UPCOMING");
            texts.add("即将开始");
        } else {
            score += 8;
            tier = "RECENT";
            codes.add("RECENT_RESULT");
            texts.add("近期赛果");
        }

        if (match.getMatchTime() != null) {
            long minutes = ChronoUnit.MINUTES.between(now, match.getMatchTime());
            long absoluteMinutes = Math.abs(minutes);
            if (minutes >= 0 && minutes <= 120) {
                score += 20;
                codes.add("KICKOFF_SOON");
                texts.add("2小时内开赛");
            } else if (minutes > 0 && minutes <= 720) {
                score += 12;
                codes.add("KICKOFF_TODAY");
                texts.add("今天开赛");
            } else if (absoluteMinutes <= 720) {
                score += 6;
            }
            if (selectedDate.equals(match.getMatchTime().toLocalDate())) score += 6;
        }

        int leagueScore = leagueWeight(match.getLeagueId(), match.getLeagueName());
        score += leagueScore;
        if (leagueScore >= 12) {
            codes.add("TOP_LEAGUE");
            texts.add("重点联赛");
        }
        if (match.getRound() != null && !match.getRound().isBlank()) {
            String round = match.getRound().toLowerCase(Locale.ROOT);
            if (round.contains("final") || round.contains("决赛") || round.contains("semi") || round.contains("半决赛")) {
                score += 8;
                codes.add("KEY_ROUND");
                texts.add("关键轮次");
            }
        }
        if (match.getHomeScore() != null || match.getAwayScore() != null) score += 2;
        if (hasText(match.getHomeTeamLogo()) && hasText(match.getAwayTeamLogo())) score += 2;
        return new ScoredMatch(match, Math.min(100, score), tier, codes, texts);
    }

    private List<ScoredMatch> diversify(List<ScoredMatch> sorted, int limit) {
        List<ScoredMatch> result = new ArrayList<>();
        Map<String, Integer> leagueCounts = new HashMap<>();
        for (ScoredMatch item : sorted) {
            String league = normalize(item.match.getLeagueName());
            int count = leagueCounts.getOrDefault(league, 0);
            if (count >= 2 && sorted.size() > limit) continue;
            result.add(item);
            leagueCounts.put(league, count + 1);
            if (result.size() >= limit) break;
        }
        if (result.size() < limit) {
            for (ScoredMatch item : sorted) {
                if (!result.contains(item)) result.add(item);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    private int leagueWeight(String id, String name) {
        String key = normalize(id) + "|" + normalize(name);
        if (key.contains("bbc-premierleague") || key.contains("premierleague") || key.contains("英超")) return 15;
        if (key.contains("bbc-spanishlaliga") || key.contains("laliga") || key.contains("西甲")) return 14;
        if (key.contains("bbc-italianseriea") || key.contains("seriea") || key.contains("意甲")) return 13;
        if (key.contains("bbc-germanbundesliga") || key.contains("bundesliga") || key.contains("德甲")) return 13;
        if (key.contains("bbc-frenchligueone") || key.contains("ligue1") || key.contains("法甲")) return 12;
        if (key.contains("bbc-dutcheredivisie") || key.contains("eredivisie") || key.contains("荷甲")) return 11;
        if (key.contains("bbc-portugueseprimeiraliga") || key.contains("primeiraliga") || key.contains("葡超")) return 11;
        if (key.contains("bbc-championship") || key.contains("championship") || key.contains("英冠")) return 10;
        return 5;
    }

    private boolean isUpcoming(String status, CrawlerMatch match, LocalDateTime now) {
        if (Set.of("NS", "TBD", "TIMED", "SCHEDULED").contains(status)) return true;
        return match.getMatchTime() != null && match.getMatchTime().isAfter(now);
    }

    private boolean isLive(String status) {
        return Set.of("LIVE", "INPLAY", "1H", "2H", "HT", "ET", "BT", "P").contains(status);
    }

    private String identity(CrawlerMatch match) {
        if (match.getExternalMatchId() != null && !match.getExternalMatchId().isBlank()) {
            return "external|" + normalize(match.getSource()) + "|" + normalize(match.getExternalMatchId());
        }
        if (match.getFixtureId() != null) return "fixture|" + normalize(match.getSource()) + "|" + match.getFixtureId();
        String homeKey = hasText(match.getHomeTeamId()) ? match.getHomeTeamId() : match.getHomeTeamName();
        String awayKey = hasText(match.getAwayTeamId()) ? match.getAwayTeamId() : match.getAwayTeamName();
        return String.join("|", normalize(match.getLeagueId()), normalize(match.getLeagueName()),
                normalize(homeKey), normalize(awayKey),
                match.getMatchTime() == null ? "" : match.getMatchTime().toString());
    }

    private String normalizeStatus(String value) { return normalize(value).toUpperCase(Locale.ROOT); }
    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", ""); }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private LocalDateTime kickoff(CrawlerMatch match) { return match == null ? null : match.getMatchTime(); }
    private LocalDateTime updatedAt(CrawlerMatch match) { return match.getUpdatedAt() == null ? LocalDateTime.MIN : match.getUpdatedAt(); }

    public static class RecommendationResult {
        public List<RecommendationItem> items;
        public Map<String, Object> meta;
        public RecommendationResult() {}
        public RecommendationResult(List<RecommendationItem> items, Map<String, Object> meta) { this.items = items; this.meta = meta; }
    }

    public static class RecommendationItem {
        public CrawlerMatch match;
        public int score;
        public String tier;
        public List<String> reasonCodes;
        public List<String> reasonTexts;
        public RecommendationItem() {}
        public RecommendationItem(CrawlerMatch match, int score, String tier, List<String> reasonCodes, List<String> reasonTexts) {
            this.match = match;
            this.score = score;
            this.tier = tier;
            this.reasonCodes = reasonCodes;
            this.reasonTexts = reasonTexts;
        }
    }

    private record ScoredMatch(CrawlerMatch match, int score, String tier, List<String> reasonCodes, List<String> reasonTexts) {}
    private record InstantPair(java.time.Instant generatedAt, java.time.Instant expiresAt) {}
}
