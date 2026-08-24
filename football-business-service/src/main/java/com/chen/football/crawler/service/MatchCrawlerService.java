package com.chen.football.crawler.service;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.common.client.FootballDataClient;
import com.chen.football.common.client.JuheFootballClient;
import com.chen.football.common.dto.FetchResult;
import com.chen.football.common.dto.MatchStatus;
import com.chen.football.common.dto.NormalizedMatch;
import com.chen.football.common.service.DistributedLockService;
import com.chen.football.crawler.source.DataSourceManager;
import com.chen.football.crawler.source.MatchSourceProvider;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 比赛数据爬取服务
 */
@Slf4j
@Service
public class MatchCrawlerService {

    private final CrawlerMatchMapper matchMapper;
    private final FootballDataClient footballDataClient;
    private final JuheFootballClient juheFootballClient;
    private final DataSourceManager dataSourceManager;
    private final DistributedLockService distributedLockService;
    private final IdentityMappingService identityMappingService;
    private final CrawlerIngestionAuditService ingestionAuditService;
    private final Map<String, CachedPrimarySnapshot> recentPrimarySnapshots = new ConcurrentHashMap<>();

    @Value("${crawler.task.score-lookback-days:3}")
    private int scoreLookbackDays;
    @Value("${crawler.task.score-live-lookback-days:1}")
    private int scoreLiveLookbackDays;

    private record CachedPrimarySnapshot(FetchResult result, long fetchedAtMs) {}

    private static final String MATCH_WINDOW_LOCK = "crawler:match-window";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    // The window crawl is normally completed in seconds. A 15-minute lease
    // keeps the cross-task mutex safe while ensuring a hard process restart
    // cannot block all future score/fixture refreshes for hours.
    private static final Duration MATCH_WINDOW_LOCK_TTL = Duration.ofMinutes(15);
    // 今日采集和比分刷新现在可以并行调度；等待同一窗口锁最多 60 秒，
    // 确保短任务结束后比分任务能复用快照，而不是静默返回 0 条。
    private static final int LOCK_ACQUIRE_ATTEMPTS = 120;
    private static final long LOCK_RETRY_DELAY_MS = 500L;
    /** 同一调度周期内复用主源快照，避免今日赛程和比分任务重复请求 BBC。 */
    private static final long PRIMARY_SNAPSHOT_REUSE_MS = Duration.ofMinutes(4).toMillis();
    /** Production match scope.  Provider IDs are authoritative; names are a fallback for legacy rows. */
    private static final Set<String> PRODUCTION_LEAGUE_IDS = Set.of(
            "39", "140", "135", "78", "61", "88", "94", "40",
            "PL", "PD", "SA", "BL1", "FL1", "DED", "PPL", "ELC",
            "bbc-premier-league", "bbc-spanish-la-liga", "bbc-italian-serie-a",
            "bbc-german-bundesliga", "bbc-french-ligue-one", "bbc-dutch-eredivisie",
            "bbc-portuguese-primeira-liga", "bbc-championship"
    );
    private static final Set<String> PRODUCTION_LEAGUE_NAMES = Set.of(
            "premierleague", "laliga", "primeradivision", "seriea", "bundesliga",
            "ligue1", "eredivisie", "primeiraliga", "championship", "英超", "西甲", "德甲", "法甲", "意甲", "荷甲", "葡超", "英冠"
    );

    public MatchCrawlerService(CrawlerMatchMapper matchMapper,
                                FootballDataClient footballDataClient,
                               JuheFootballClient juheFootballClient,
                               DataSourceManager dataSourceManager,
                               DistributedLockService distributedLockService,
                               IdentityMappingService identityMappingService,
                               CrawlerIngestionAuditService ingestionAuditService) {
        this.matchMapper = matchMapper;
        this.footballDataClient = footballDataClient;
        this.juheFootballClient = juheFootballClient;
        this.dataSourceManager = dataSourceManager;
        this.distributedLockService = distributedLockService;
        this.identityMappingService = identityMappingService;
        this.ingestionAuditService = ingestionAuditService;
    }

    /**
     * 爬取指定日期的所有比赛 — 统一数据源流水线
     */
    public List<CrawlerMatch> crawlMatchesByDate(Date date) {
        return withMatchWindowLock(() -> crawlMatchesByDateInternal(date));
    }

    private List<CrawlerMatch> crawlMatchesByDateInternal(Date date) {
        String dateStr = dateString(date);
        log.info("[Crawl] 开始采集 {} 的比赛数据", dateStr);

        List<CrawlerMatch> allMatches = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        boolean sourceSucceeded = false;

        for (var provider : dataSourceManager.orderedProviders()) {
            if (!dataSourceManager.isAvailable(provider)) {
                log.debug("[Crawl] {} 不可用，跳过", provider.name());
                continue;
            }
            long auditRunId = ingestionAuditService.startRun("crawlMatchesByDate", provider.name(), dateStr);
            FetchResult result;
            try {
                result = fetchProviderSnapshot(provider, dateStr);
            } catch (Exception e) {
                dataSourceManager.recordFailure(provider.name(), e.getMessage());
                ingestionAuditService.finishRun(auditRunId, "FAILED", 0, 0, 0, 0, 0, 0, 0, e.getMessage());
                log.warn("[Crawl] {} 采集异常: {}", provider.name(), e.getMessage());
                continue;
            }
            dataSourceManager.recordResult(provider, result);
            // A successful empty response is still an authoritative answer
            // for this date.  Falling back to old DB rows after a successful
            // BBC snapshot was the reason finished fixtures were shown as
            // stale “状态待更新” rows.
            if (result != null && result.success()) {
                sourceSucceeded = true;
                if ((result.matches() == null || result.matches().isEmpty())
                        && provider.name().equalsIgnoreCase(dataSourceManager.primarySource())) {
                    reconcileRemovedScheduledMatches(LocalDate.parse(dateStr), provider.name(), List.of());
                }
            }
            if (result == null || !result.success() || result.matches() == null || result.matches().isEmpty()) {
                ingestionAuditService.finishRun(auditRunId, result != null && result.success() ? "EMPTY" : "FAILED",
                        result == null || result.matches() == null ? 0 : result.matches().size(), 0, 0, 0, 0, 0,
                        result == null ? 0 : result.latencyMs(), result == null ? "null result" : result.error());
                log.debug("[Crawl] {} 无数据", provider.name());
                continue;
            }
            List<CrawlerMatch> converted = toCrawlerMatches(result.matches()).stream()
                    .filter(this::isProductionLeague)
                    .toList();
            if (provider.name().equalsIgnoreCase(dataSourceManager.primarySource())) {
                reconcileRemovedScheduledMatches(
                        LocalDate.parse(dateStr), provider.name(), converted);
            }
            int inserted = 0;
            int updated = 0;
            int rejected = Math.max(0, result.matches().size() - converted.size());
            int duplicates = 0;
            int beforeSize = allMatches.size();
            for (CrawlerMatch match : converted) {
                String key = buildMatchKey(match);
                if (seenKeys.add(key)) {
                    normalizeMatch(match, match.getLeagueName() == null ? "" : match.getLeagueName(), dateStr);
                    ingestionAuditService.observe(match);
                    SaveOutcome outcome = saveOrUpdateMatch(match);
                    if (outcome == SaveOutcome.INSERTED) inserted++;
                    if (outcome == SaveOutcome.UPDATED) updated++;
                    allMatches.add(match);
                } else duplicates++;
            }
            int added = allMatches.size() - beforeSize;
            ingestionAuditService.finishRun(auditRunId, "SUCCESS", result.matches().size(), converted.size(), rejected,
                    duplicates, inserted, updated, result.latencyMs(), null);
            log.info("[Crawl] {} 采集 {} 场，新增 {} 场", provider.name(), converted.size(), added);
        }

        // Only use the database as a last-resort cache when every enabled
        // source failed.  A successful source response (including an empty
        // coverage response) must never be padded with stale DB fixtures.
        if (!sourceSucceeded && allMatches.size() < 20) {
            long databaseAuditRun = ingestionAuditService.startRun("crawlMatchesByDate:database-fallback", "database", dateStr);
            List<CrawlerMatch> dbMatches = getMatchesFromDb(date, date);
            dbMatches = deduplicateMatches(dbMatches.stream().filter(this::isProductionLeague).toList());
            int beforeSize = allMatches.size();
            for (CrawlerMatch match : dbMatches) {
                String key = buildMatchKey(match);
                if (seenKeys.add(key)) {
                    normalizeMatch(match, Optional.ofNullable(match.getLeagueName()).orElse("数据库"), dateStr);
                    allMatches.add(match);
                }
            }
            int dbAdded = allMatches.size() - beforeSize;
            if (dbAdded > 0) {
                log.info("[Crawl] 数据库补充 {} 场", dbAdded);
            }
            ingestionAuditService.finishRun(databaseAuditRun, "DATABASE_FALLBACK", dbMatches.size(), dbAdded,
                    0, Math.max(0, dbMatches.size() - dbAdded), 0, 0, 0,
                    dbAdded == 0 ? "主数据源未达到预期数量，数据库没有可补充记录" : "主数据源不足，使用已有数据库记录补充展示");
        }

        log.info("[Crawl] {} 采集完成，共 {} 场比赛", dateStr, allMatches.size());
        return allMatches;
    }

    private FetchResult fetchProviderSnapshot(MatchSourceProvider provider, String dateStr) {
        boolean primary = provider != null && provider.name().equalsIgnoreCase(dataSourceManager.primarySource());
        if (primary) {
            CachedPrimarySnapshot cached = recentPrimarySnapshots.get(dateStr);
            if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs() <= PRIMARY_SNAPSHOT_REUSE_MS) {
                log.debug("[Crawl] 复用 {} 最近主源快照，跳过重复请求", dateStr);
                return cached.result();
            }
        }
        FetchResult result = provider.fetchMatches(dateStr);
        if (primary && result != null && result.success()) {
            recentPrimarySnapshots.put(dateStr,
                    new CachedPrimarySnapshot(result, System.currentTimeMillis()));
        }
        return result;
    }

    private List<CrawlerMatch> toCrawlerMatches(List<NormalizedMatch> matches) {
        List<CrawlerMatch> result = new ArrayList<>();
        if (matches == null) return result;
        for (NormalizedMatch m : matches) {
            CrawlerMatch match = new CrawlerMatch();
            match.setSource(m.source());
            match.setExternalMatchId(m.externalMatchId());
            match.setFixtureId(m.fixtureId());
            match.setLeagueId(m.leagueId());
            match.setLeagueName(m.leagueName());
            match.setHomeTeamId(m.homeTeamId());
            match.setHomeTeamName(m.homeTeamName());
            match.setHomeTeamLogo(m.homeTeamLogo());
            match.setAwayTeamId(m.awayTeamId());
            match.setAwayTeamName(m.awayTeamName());
            match.setAwayTeamLogo(m.awayTeamLogo());
            match.setHomeScore(m.homeScore());
            match.setAwayScore(m.awayScore());
            match.setStatus(MatchStatus.normalize(m.status()));
            match.setMatchTime(m.matchTime() == null ? null : m.matchTime());
            match.setVenue(m.venue());
            match.setRound(m.round());
            result.add(match);
        }
        return result;
    }

    private int crawlFromWebPages(String dateStr, List<CrawlerMatch> allMatches) {
        List<CrawlerMatch> matches = crawlFromConfiguredSources(dateStr, null);
        allMatches.addAll(matches);
        return matches.size();
    }

    /**
     * 统一网页采集入口。旧实现会逐个请求 WorldFootball 的联赛页面，
     * 目标站点返回 403 时会把一次同步拖到超时；现在统一走已注册数据源，
     * 由 BBC 按日期源负责网页采集，其它 API 源仍可作为补充。
     */
    private List<CrawlerMatch> crawlFromConfiguredSources(String dateStr, String leagueName) {
        FetchResult fetched = dataSourceManager.fetchMatchesWithFallback(dateStr);
        if (fetched == null || !fetched.success() || fetched.matches() == null) {
            log.info("[Crawl] 网页数据源无可用结果，date={}, error={}", dateStr,
                    fetched == null ? "null result" : fetched.error());
            return new ArrayList<>();
        }
        List<CrawlerMatch> result = new ArrayList<>();
        for (CrawlerMatch match : toCrawlerMatches(fetched.matches())) {
            if (!isProductionLeague(match)) continue;
            if (leagueName != null && !leagueName.isBlank()
                    && !leagueName.equals(match.getLeagueName())) {
                continue;
            }
            normalizeMatch(match, Optional.ofNullable(match.getLeagueName()).orElse(leagueName), dateStr);
            saveOrUpdateMatch(match);
            result.add(match);
        }
        return deduplicateMatches(result);
    }

    @SuppressWarnings("unchecked")
    private List<CrawlerMatch> tryCrawlFromFootballData(String dateStr) {
        List<CrawlerMatch> result = new ArrayList<>();
        try {
            Map<String, Object> data = footballDataClient
                    .getMatches(dateStr, dateStr, FOOTBALL_DATA_FREE_TIER_COMPETITIONS, null)
                    .block();
            if (isFootballDataResultEmpty(data)) {
                return result;
            }
            Object responseObj = data.get("response");
            if (!(responseObj instanceof List<?> responseList)) {
                return result;
            }
            for (Object item : responseList) {
                if (!(item instanceof Map<?, ?> itemMapRaw)) continue;
                Map<String, Object> itemMap = (Map<String, Object>) itemMapRaw;
                CrawlerMatch match = toCrawlerMatchFromFootballData(itemMap, dateStr);
                if (match != null && isProductionLeague(match)) {
                    saveOrUpdateMatch(match);
                    result.add(match);
                }
            }
        } catch (Exception e) {
            log.warn("football-data 兜底失败: {}", e.getMessage());
        }
        return result;
    }

    private static final String FOOTBALL_DATA_FREE_TIER_COMPETITIONS = String.join(",",
            "WC", "CL", "BL1", "DED", "BSA", "PD", "FL1", "ELC", "PPL", "EC", "SA", "PL");

    private boolean isFootballDataResultEmpty(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return true;
        Object responseObj = data.get("response");
        return !(responseObj instanceof List<?> list) || list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private CrawlerMatch toCrawlerMatchFromFootballData(Map<String, Object> item, String dateStr) {
        try {
            Map<String, Object> fixture = (Map<String, Object>) item.get("fixture");
            Map<String, Object> league = (Map<String, Object>) item.get("league");
            Map<String, Object> teams = (Map<String, Object>) item.get("teams");
            Map<String, Object> home = teams == null ? null : (Map<String, Object>) teams.get("home");
            Map<String, Object> away = teams == null ? null : (Map<String, Object>) teams.get("away");
            Map<String, Object> goals = (Map<String, Object>) item.get("goals");
            if (fixture == null || home == null || away == null) return null;

            String homeName = String.valueOf(home.getOrDefault("name", "")).trim();
            String awayName = String.valueOf(away.getOrDefault("name", "")).trim();
            if (homeName.isEmpty() || awayName.isEmpty()) return null;

            Map<String, Object> status = (Map<String, Object>) fixture.get("status");
            String fixtureId = String.valueOf(fixture.getOrDefault("id", ""));
            String matchTime = String.valueOf(fixture.getOrDefault("date", dateStr));
            String leagueCode = String.valueOf(league == null ? "" : league.getOrDefault("id", league.getOrDefault("code", "")));

            CrawlerMatch match = new CrawlerMatch();
            match.setSource("football-data");
            match.setLeagueId(leagueCode);
            match.setLeagueName(String.valueOf(league == null ? leagueCode : league.getOrDefault("name", leagueCode)));
            match.setHomeTeamId(String.valueOf(home.getOrDefault("id", "")));
            match.setHomeTeamName(homeName);
            match.setHomeTeamLogo(String.valueOf(home.getOrDefault("logo", home.getOrDefault("crest", ""))));
            match.setAwayTeamId(String.valueOf(away.getOrDefault("id", "")));
            match.setAwayTeamName(awayName);
            match.setAwayTeamLogo(String.valueOf(away.getOrDefault("logo", away.getOrDefault("crest", ""))));
            match.setStatus(MatchStatus.normalize(String.valueOf(status == null ? "NS" : status.getOrDefault("short", "NS"))));
            match.setExternalMatchId(!fixtureId.isBlank() ? fixtureId : (homeName + "_" + awayName + "_" + dateStr));
            match.setFixtureId(toLong(fixtureId));
            match.setMatchTime(parseFootballDataMatchTime(matchTime));

            if (goals != null) {
                String normStatus = MatchStatus.normalize(match.getStatus());
                if (MatchStatus.hasScore(normStatus)) {
                    match.setHomeScore(toInt(goals.get("home")));
                    match.setAwayScore(toInt(goals.get("away")));
                }
            }

            match.setCreatedAt(LocalDateTime.now(BUSINESS_ZONE));
            match.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
            return match;
        } catch (Exception e) {
            log.debug("转换 football-data 比赛数据失败: {}", e.getMessage());
            return null;
        }
    }

    private LocalDateTime parseFootballDataMatchTime(String utcDate) {
        try {
            if (utcDate == null || utcDate.isBlank()) return null;
            String text = utcDate.trim();
            if (text.endsWith("Z")) return java.time.Instant.parse(text).atZone(BUSINESS_ZONE).toLocalDateTime();
            if (text.matches(".*[+-]\\d{2}:?\\d{2}$")) return java.time.OffsetDateTime.parse(text).atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
            return LocalDateTime.parse(text.replace('T', ' '), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            // 绝不把无法解析的时间伪造成“现在”，否则会把比赛归入错误日期，
            // 并让预测在错误的截止时间上读取特征。
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<CrawlerMatch> tryCrawlFromJuhe(String dateStr) {
        List<CrawlerMatch> result = new ArrayList<>();
        try {
            int[] leagueIds = {39, 140, 135, 78, 61, 1};
            for (int leagueId : leagueIds) {
                try {
                    Map<String, Object> data = juheFootballClient.getFixturesByDate(dateStr, leagueId).block();
                    if (data == null || data.isEmpty()) continue;
                    if (isJuheResultEmpty(data)) continue;
                    Object responseObj = data.get("response");
                    if (!(responseObj instanceof List<?> responseList)) continue;
                    for (Object item : responseList) {
                        if (!(item instanceof Map<?, ?> itemMapRaw)) continue;
                        Map<String, Object> itemMap = (Map<String, Object>) itemMapRaw;
                        CrawlerMatch match = toCrawlerMatchFromJuhe(itemMap, dateStr);
                        if (match != null && isProductionLeague(match)) {
                            saveOrUpdateMatch(match);
                            result.add(match);
                        }
                    }
                } catch (Exception e) {
                    log.debug("聚合数据联赛 {} 拉取失败: {}", leagueId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("聚合数据兜底失败: {}", e.getMessage());
        }
        return result;
    }

    private boolean isJuheResultEmpty(Map<String, Object> data) {
        Object responseObj = data.get("response");
        if (responseObj instanceof List<?> list && !list.isEmpty()) return false;
        Object resultObj = data.get("result");
        if (resultObj instanceof Map<?, ?> map) {
            Object matchsObj = map.get("matchs");
            if (matchsObj instanceof List<?> list && !list.isEmpty()) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private CrawlerMatch toCrawlerMatchFromJuhe(Map<String, Object> item, String dateStr) {
        try {
            Map<String, Object> teams = (Map<String, Object>) item.get("teams");
            Map<String, Object> home = teams == null ? null : (Map<String, Object>) teams.get("home");
            Map<String, Object> away = teams == null ? null : (Map<String, Object>) teams.get("away");
            if (home == null || away == null) return null;

            String homeName = String.valueOf(home.getOrDefault("name", "")).trim();
            String awayName = String.valueOf(away.getOrDefault("name", "")).trim();
            if (homeName.isEmpty() || awayName.isEmpty()) return null;

            Map<String, Object> league = (Map<String, Object>) item.get("league");
            Map<String, Object> fixture = (Map<String, Object>) item.get("fixture");
            Map<String, Object> goals = (Map<String, Object>) item.get("goals");
            Map<String, Object> status = fixture == null ? null : (Map<String, Object>) fixture.get("status");

            CrawlerMatch match = new CrawlerMatch();
            match.setSource("juhe");
            match.setLeagueId(String.valueOf(league == null ? "" : league.getOrDefault("id", "")));
            match.setLeagueName(String.valueOf(league == null ? "其他联赛" : league.getOrDefault("name", "其他联赛")));
            match.setHomeTeamId(String.valueOf(home.getOrDefault("id", "")));
            match.setHomeTeamName(homeName);
            match.setHomeTeamLogo(String.valueOf(home.getOrDefault("logo", home.getOrDefault("crest", ""))));
            match.setAwayTeamId(String.valueOf(away.getOrDefault("id", "")));
            match.setAwayTeamName(awayName);
            match.setAwayTeamLogo(String.valueOf(away.getOrDefault("logo", away.getOrDefault("crest", ""))));

            String normStatus = MatchStatus.normalize(String.valueOf(status == null ? "NS" : status.getOrDefault("short", "NS")));
            match.setStatus(normStatus);

            if (MatchStatus.hasScore(normStatus) && goals != null) {
                match.setHomeScore(toInt(goals.get("home")));
                match.setAwayScore(toInt(goals.get("away")));
            }

            String extId = fixture != null ? String.valueOf(fixture.getOrDefault("id", "")) : "";
            if (extId.isBlank()) extId = homeName + "_" + awayName + "_" + dateStr;
            match.setExternalMatchId(extId);
            match.setFixtureId(toLong(extId));
            match.setMatchTime(parseFootballDataMatchTime(fixture != null ? String.valueOf(fixture.getOrDefault("date", dateStr)) : dateStr));
            match.setCreatedAt(LocalDateTime.now(BUSINESS_ZONE));
            match.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
            return match;
        } catch (Exception e) {
            log.debug("转换聚合数据比赛失败: {}", e.getMessage());
            return null;
        }
    }

    public List<CrawlerMatch> crawlFootballDataRecentMatches() {
        if (!dataSourceManager.isSourceEnabled("football-data")) {
            log.info("[Crawl] football-data 已停用（当前仅使用主源 {}）", dataSourceManager.primarySource());
            return Collections.emptyList();
        }
        String dateStr = dateString(new Date());
        return tryCrawlFromFootballData(dateStr);
    }

    public List<CrawlerMatch> crawlJuheTodayMatches() {
        if (!dataSourceManager.isSourceEnabled("juhe")) {
            log.info("[Crawl] juhe 已停用（当前仅使用主源 {}）", dataSourceManager.primarySource());
            return Collections.emptyList();
        }
        String dateStr = dateString(new Date());
        return tryCrawlFromJuhe(dateStr);
    }

    public List<CrawlerMatch> crawlWebFallbackTodayMatches() {
        String dateStr = dateString(new Date());
        return crawlFromConfiguredSources(dateStr, null);
    }

    public List<CrawlerMatch> crawlTodayMatches() {
        return crawlMatchesByDate(new Date());
    }

    public List<CrawlerMatch> crawlUpcomingMatches() {
        return withMatchWindowLock(() -> {
            List<CrawlerMatch> allMatches = new ArrayList<>();
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(BUSINESS_ZONE));
            cal.setTime(Date.from(java.time.LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toInstant()));
            // 包含今天以及之后 7 天，与前台 matches/window 的时间窗口保持一致。
            for (int i = 0; i <= 7; i++) {
                List<CrawlerMatch> dayMatches = crawlMatchesByDateInternal(cal.getTime());
                allMatches.addAll(dayMatches);
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
            return deduplicateMatches(allMatches);
        });
    }

    private List<CrawlerMatch> withMatchWindowLock(Supplier<List<CrawlerMatch>> action) {
        String token;
        try {
            token = null;
            for (int attempt = 1; attempt <= LOCK_ACQUIRE_ATTEMPTS; attempt++) {
                token = distributedLockService.tryLock(MATCH_WINDOW_LOCK, MATCH_WINDOW_LOCK_TTL);
                if (token != null) break;
                if (attempt < LOCK_ACQUIRE_ATTEMPTS) {
                    Thread.sleep(LOCK_RETRY_DELAY_MS);
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("[Crawl] 获取赛程采集互斥锁失败: {}", e.getMessage());
            return emptyResult();
        }
        if (token == null) {
            log.info("[Crawl] 已有今日/未来赛程采集任务运行，等待后仍未获取互斥锁，跳过本次请求");
            return emptyResult();
        }
        final String lockToken = token;
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "crawler-match-lock-renewer");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledFuture<?> renewal = renewer.scheduleAtFixedRate(() -> {
            if (!distributedLockService.renew(MATCH_WINDOW_LOCK, lockToken, MATCH_WINDOW_LOCK_TTL)) {
                log.warn("[Crawl] 赛程采集锁续租失败，当前任务将继续但不会再启动新的重叠任务");
            }
        }, MATCH_WINDOW_LOCK_TTL.toSeconds() / 3, MATCH_WINDOW_LOCK_TTL.toSeconds() / 3, TimeUnit.SECONDS);
        try {
            return action.get();
        } finally {
            renewal.cancel(false);
            renewer.shutdownNow();
            try {
                distributedLockService.unlock(MATCH_WINDOW_LOCK, lockToken);
            } catch (Exception e) {
                log.warn("[Crawl] 释放赛程采集互斥锁失败: {}", e.getMessage());
            }
        }
    }

    private List<CrawlerMatch> emptyResult() {
        return Collections.emptyList();
    }

    private String dateString(Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        formatter.setTimeZone(TimeZone.getTimeZone(BUSINESS_ZONE));
        return formatter.format(date == null ? new Date() : date);
    }

    public List<CrawlerMatch> crawlMatchesByLeagueAndDate(String leagueName, Date date) {
        String dateStr = dateString(date);
        List<CrawlerMatch> result = crawlFromConfiguredSources(dateStr, leagueName);
        log.info("按联赛 {} 爬取 {} 场比赛成功", leagueName, result.size());
        return result;
    }

    /**
     * Refresh the primary BBC score page for yesterday and today.  Yesterday
     * is included because late full-time corrections and matches finishing
     * around midnight are common.  Status transitions are useful even when
     * the source has not published a score yet (for example LIVE 0-0).
     */
    public int updateMatchScores() {
        return withMatchWindowLockInt(() -> updateMatchScoresInternal(scoreLiveLookbackDays));
    }

    /** 低频补偿任务：检查近几天的赛果修正，但不挤占每 5 分钟的实时刷新。 */
    public int backfillRecentScoreCorrections() {
        return withMatchWindowLockInt(() -> updateMatchScoresInternal(scoreLookbackDays));
    }

    private int updateMatchScoresInternal(int configuredLookback) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int totalChanged = 0;
        int totalSeen = 0;
        int lookback = Math.max(1, Math.min(7, configuredLookback));
        for (int offset = lookback; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            FetchResult result = fetchPrimarySnapshot(date);
            if (result == null || !result.success()) {
                log.warn("[ScoreUpdate] {} 主数据源请求失败: {}", date,
                        result == null ? "null result" : result.error());
                continue;
            }
            List<CrawlerMatch> incoming = toCrawlerMatches(
                    result.matches() == null ? List.of() : result.matches()).stream()
                    .filter(this::isProductionLeague)
                    .toList();
            totalSeen += incoming.size();
            totalChanged += applyRealtimeSnapshot(date, incoming);
        }
        log.info("[ScoreUpdate] 检查近 {} 天 {} 场主爬虫赛事，更新 {} 场状态或比分",
                lookback + 1, totalSeen, totalChanged);
        return totalChanged;
    }

    /**
     * 优先复用最近一次成功快照；失败响应不进入缓存，避免把一次网络异常
     * 传播给后续任务。缓存只用于同一调度周期的去重，不替代数据库快照。
     */
    private FetchResult fetchPrimarySnapshot(LocalDate date) {
        String key = date.toString();
        CachedPrimarySnapshot cached = recentPrimarySnapshots.get(key);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs() <= PRIMARY_SNAPSHOT_REUSE_MS) {
            log.debug("[ScoreUpdate] 复用 {} 最近主源快照，跳过重复请求", date);
            return cached.result();
        }
        FetchResult result = dataSourceManager.fetchMatches(key);
        if (result != null && result.success()) {
            recentPrimarySnapshots.put(key, new CachedPrimarySnapshot(result, System.currentTimeMillis()));
        }
        return result;
    }

    /** Reuse the same cross-task lock as today/upcoming ingestion. */
    private int withMatchWindowLockInt(Supplier<Integer> action) {
        java.util.concurrent.atomic.AtomicInteger result = new java.util.concurrent.atomic.AtomicInteger();
        withMatchWindowLock(() -> {
            result.set(action.get());
            return Collections.<CrawlerMatch>emptyList();
        });
        return result.get();
    }

    private int applyRealtimeSnapshot(LocalDate date, List<CrawlerMatch> incoming) {
        int changed = 0;
        for (CrawlerMatch sourceMatch : incoming) {
            CrawlerMatch existing = matchMapper.findByExternalId(
                    sourceMatch.getExternalMatchId(), sourceMatch.getSource());
            if (existing == null && sourceMatch.getFixtureId() != null) {
                existing = matchMapper.findByFixtureIdAndSource(
                        sourceMatch.getFixtureId(), sourceMatch.getSource());
            }
            if (existing == null && sourceMatch.getMatchTime() != null) {
                existing = matchMapper.findBySourceAndTeamsOnDate(
                        sourceMatch.getSource(), sourceMatch.getHomeTeamId(), sourceMatch.getAwayTeamId(),
                        sourceMatch.getHomeTeamName(), sourceMatch.getAwayTeamName(), sourceMatch.getMatchTime());
            }
            if (existing == null) {
                // A live/result page can contain an event that was missed by
                // the scheduled crawl.  Insert it through the same identity,
                // league and source guards as the normal ingestion path.
                if (saveOrUpdateMatch(sourceMatch) == SaveOutcome.INSERTED) changed++;
                continue;
            }
            if (mergeRealtimeState(existing, sourceMatch)) {
                existing.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
                matchMapper.updateById(existing);
                changed++;
            }
        }
        // Reconcile only today's rows.  Historical rows remain queryable even
        // when a provider removes an event from its daily page.
        if (date.equals(LocalDate.now(BUSINESS_ZONE))) {
            changed += reconcileRemovedScheduledMatches(date, dataSourceManager.primarySource(), incoming);
        }
        return changed;
    }

    private boolean mergeRealtimeState(CrawlerMatch existing, CrawlerMatch incoming) {
        boolean changed = false;
        String currentStatus = MatchStatus.normalize(existing.getStatus());
        String incomingStatus = MatchStatus.normalize(incoming.getStatus());
        boolean currentFinished = MatchStatus.isFinished(currentStatus);
        boolean currentRemoved = MatchStatus.SOURCE_REMOVED.equals(currentStatus);
        boolean incomingScheduled = MatchStatus.SCHEDULED.equals(incomingStatus)
                || incomingStatus.isBlank();

        // A completed result is monotonic: a later response that omits its
        // score must never roll FT/AWD back to NS/LIVE. A removed row may be
        // resurrected when the source publishes it again.
        if ((!currentFinished && !incomingScheduled) || currentRemoved) {
            if (!Objects.equals(currentStatus, incomingStatus)) {
                existing.setStatus(incomingStatus);
                changed = true;
            }
        }
        if (incoming.getHomeScore() != null
                && !Objects.equals(existing.getHomeScore(), incoming.getHomeScore())) {
            existing.setHomeScore(incoming.getHomeScore());
            changed = true;
        }
        if (incoming.getAwayScore() != null
                && !Objects.equals(existing.getAwayScore(), incoming.getAwayScore())) {
            existing.setAwayScore(incoming.getAwayScore());
            changed = true;
        }
        if (incoming.getMatchTime() != null
                && !Objects.equals(existing.getMatchTime(), incoming.getMatchTime())) {
            existing.setMatchTime(incoming.getMatchTime());
            changed = true;
        }
        String oldNote = existing.getNote();
        String newNote = incoming.getNote();
        if (newNote != null && !newNote.isBlank() && !Objects.equals(oldNote, newNote)) {
            existing.setNote(newNote);
            changed = true;
        }
        // The score page is also the best place to refresh team labels/logos.
        String oldHomeLogo = existing.getHomeTeamLogo();
        if (incoming.getHomeTeamLogo() != null && !incoming.getHomeTeamLogo().isBlank()
                && !Objects.equals(oldHomeLogo, incoming.getHomeTeamLogo())) {
            existing.setHomeTeamLogo(incoming.getHomeTeamLogo());
            changed = true;
        }
        String oldAwayLogo = existing.getAwayTeamLogo();
        if (incoming.getAwayTeamLogo() != null && !incoming.getAwayTeamLogo().isBlank()
                && !Objects.equals(oldAwayLogo, incoming.getAwayTeamLogo())) {
            existing.setAwayTeamLogo(incoming.getAwayTeamLogo());
            changed = true;
        }
        return changed;
    }

    private int reconcileRemovedScheduledMatches(LocalDate date, String source,
                                                 List<CrawlerMatch> observed) {
        if (date == null || source == null || source.isBlank()
                || !source.equalsIgnoreCase(dataSourceManager.primarySource())
                || !date.equals(LocalDate.now(BUSINESS_ZONE))) {
            return 0;
        }
        Set<String> externalIds = new HashSet<>();
        Set<String> teamKeys = new HashSet<>();
        for (CrawlerMatch match : observed == null ? List.<CrawlerMatch>of() : observed) {
            if (match.getExternalMatchId() != null && !match.getExternalMatchId().isBlank()) {
                externalIds.add(IdentityNormalizer.normalize(match.getExternalMatchId()));
            }
            teamKeys.add(teamKey(match.getHomeTeamId(), match.getAwayTeamId(),
                    match.getHomeTeamName(), match.getAwayTeamName()));
        }
        LocalDateTime cutoff = LocalDateTime.now(BUSINESS_ZONE).minusMinutes(30);
        int marked = 0;
        for (CrawlerMatch existing : matchMapper.findBySourceAndDate(source, date)) {
            String status = MatchStatus.normalize(existing.getStatus());
            if (MatchStatus.SOURCE_REMOVED.equals(status)
                    || MatchStatus.isFinished(status)
                    || MatchStatus.isLive(status)
                    || existing.getHomeScore() != null || existing.getAwayScore() != null
                    || existing.getMatchTime() == null || existing.getMatchTime().isAfter(cutoff)) {
                continue;
            }
            String external = IdentityNormalizer.normalize(existing.getExternalMatchId());
            String teams = teamKey(existing.getHomeTeamId(), existing.getAwayTeamId(),
                    existing.getHomeTeamName(), existing.getAwayTeamName());
            if ((!external.isBlank() && externalIds.contains(external)) || teamKeys.contains(teams)) {
                continue;
            }
            existing.setStatus(MatchStatus.SOURCE_REMOVED);
            existing.setNote("主数据源已不再返回该赛事，保留历史记录待核对");
            existing.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
            matchMapper.updateById(existing);
            marked++;
        }
        if (marked > 0) {
            log.info("[Crawl] {} 主数据源快照标记 {} 条过期赛程，避免继续显示状态待更新", date, marked);
        }
        return marked;
    }

    private String teamKey(String homeId, String awayId, String homeName, String awayName) {
        String home = IdentityNormalizer.normalize(homeId);
        if (home.isBlank()) home = IdentityNormalizer.normalize(homeName);
        String away = IdentityNormalizer.normalize(awayId);
        if (away.isBlank()) away = IdentityNormalizer.normalize(awayName);
        return home + "|" + away;
    }

    private SaveOutcome saveOrUpdateMatch(CrawlerMatch match) {
        if (match == null || match.getHomeTeamName() == null || match.getAwayTeamName() == null
                || !isProductionLeague(match)) return SaveOutcome.REJECTED;
        try {
            identityMappingService.ensureMatch(match);
            CrawlerMatch existing = matchMapper.findByExternalId(match.getExternalMatchId(), match.getSource());
            if (existing == null) {
                existing = matchMapper.findByFixtureIdAndSource(match.getFixtureId(), match.getSource());
            }
            if (existing == null) {
                existing = matchMapper.findBySourceAndTeamsOnDate(
                        match.getSource(), match.getHomeTeamId(), match.getAwayTeamId(),
                        match.getHomeTeamName(), match.getAwayTeamName(), match.getMatchTime());
            }
            if (existing != null) {
                // 数据源的身份字段也必须随上游结果同步。尤其是早期采集记录可能
                // 没有 fixture_id，后续同一场比赛补齐数字 ID 时不能继续保留 NULL。
                if (match.getExternalMatchId() != null && !match.getExternalMatchId().isBlank()) {
                    existing.setExternalMatchId(match.getExternalMatchId());
                }
                if (match.getFixtureId() != null && match.getFixtureId() > 0) {
                    existing.setFixtureId(match.getFixtureId());
                }
                mergeNonBlank(existing, match);
                existing.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
                matchMapper.updateById(existing);
                return SaveOutcome.UPDATED;
            } else {
                match.setCreatedAt(LocalDateTime.now(BUSINESS_ZONE));
                match.setUpdatedAt(LocalDateTime.now(BUSINESS_ZONE));
                matchMapper.insert(match);
                return SaveOutcome.INSERTED;
            }
        } catch (Exception e) {
            log.warn("保存比赛数据失败 source={}, externalId={}, error={}", match.getSource(), match.getExternalMatchId(), e.getMessage());
            return SaveOutcome.FAILED;
        }
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

    private enum SaveOutcome { INSERTED, UPDATED, REJECTED, FAILED }

    public List<CrawlerMatch> getMatchesFromDb(Date startDate, Date endDate) {
        if (startDate != null && endDate != null) {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(BUSINESS_ZONE));
            cal.setTime(startDate);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            Date start = cal.getTime();
            cal.setTime(endDate);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cal.add(Calendar.DAY_OF_MONTH, 1);
            // 结束时间取次日零点，才能包含当天全天的比赛
            return visibleMatches(matchMapper.findByTimeRange(start, cal.getTime()));
        }
        return visibleMatches(matchMapper.findByTimeRange(startDate, endDate));
    }

    public List<CrawlerMatch> getTodayMatches() {
        return visibleMatches(matchMapper.findByDate(new Date()));
    }

    public List<CrawlerMatch> getMatchesByLeagueFromDb(String leagueName) {
        if (leagueName == null || leagueName.isBlank()) return Collections.emptyList();
        return visibleMatches(matchMapper.findByLeagueName(leagueName));
    }

    public int countMatchesByDate(Date date) {
        // Count the same source-filtered, deduplicated view that public match
        // endpoints expose.  The old raw SQL count included rows from
        // disabled providers and made admin/health totals disagree with the
        // actual Match page.
        return getMatchesFromDb(date, date).size();
    }

    private List<CrawlerMatch> deduplicateMatches(List<CrawlerMatch> matches) {
        if (matches == null || matches.isEmpty()) return matches;
        Map<String, CrawlerMatch> existing = new LinkedHashMap<>();
        for (CrawlerMatch match : matches) {
            String key = buildMatchKey(match);
            existing.put(key, match);
        }
        return new ArrayList<>(existing.values());
    }

    private String buildMatchKey(CrawlerMatch match) {
        if (match != null && match.getMatchTime() == null && match.getExternalMatchId() != null
                && !match.getExternalMatchId().isBlank()) {
            return IdentityNormalizer.normalize(match.getSource()) + "|"
                    + IdentityNormalizer.normalize(match.getExternalMatchId());
        }
        return IdentityNormalizer.matchKey(match.getLeagueName(), match.getHomeTeamName(), match.getAwayTeamName(), match.getMatchTime());
    }

    boolean isProductionLeague(CrawlerMatch match) {
        if (match == null) return false;
        String id = match.getLeagueId() == null ? "" : match.getLeagueId().trim();
        // Once a provider supplies an ID it is authoritative.  Do not let a
        // corrupted/localized league_name (for example a Brazilian row labelled
        // “Serie A”) sneak an out-of-scope competition into production.
        if (!id.isBlank()) return PRODUCTION_LEAGUE_IDS.contains(id);
        return PRODUCTION_LEAGUE_NAMES.contains(IdentityNormalizer.normalize(match.getLeagueName()));
    }

    private void normalizeMatch(CrawlerMatch match, String leagueName, String dateStr) {
        if (match.getLeagueName() == null || match.getLeagueName().isBlank()) {
            match.setLeagueName(leagueName);
        }
        if (match.getExternalMatchId() == null || match.getExternalMatchId().isBlank()) {
            String timeSlot = match.getMatchTime() == null ? "unknown" : String.valueOf(
                    match.getMatchTime().atZone(BUSINESS_ZONE).toEpochSecond() / 60);
            match.setExternalMatchId(match.getHomeTeamName() + "_" + match.getAwayTeamName() + "_" + dateStr + "_" + timeSlot);
        }
        if (match.getStatus() != null) {
            match.setStatus(MatchStatus.normalize(match.getStatus()));
        }
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    public CrawlerMatch getMatchDetailByExternalId(String externalMatchId) {
        if (externalMatchId == null || externalMatchId.isBlank()) return null;
        // New public routes use the stable local matchId. Resolve it before
        // provider identifiers so a numeric local id cannot be confused with
        // another provider's fixture id.
        try {
            CrawlerMatch byLocalId = matchMapper.selectById(Long.valueOf(externalMatchId));
            if (byLocalId != null && dataSourceManager.isSourceEnabled(byLocalId.getSource())) return byLocalId;
        } catch (NumberFormatException ignored) {
            // The value may be a provider's non-numeric external id.
        }
        CrawlerMatch match = matchMapper.findByExternalId(externalMatchId, null);
        if (match != null && dataSourceManager.isSourceEnabled(match.getSource())) return match;
        // 预测历史传入的是 fixture_id；旧实现只查 external_match_id，导致“最近预测”详情经常为空。
        try {
            CrawlerMatch byPublicId = matchMapper.findByPublicId(Long.valueOf(externalMatchId));
            return byPublicId != null && dataSourceManager.isSourceEnabled(byPublicId.getSource()) ? byPublicId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public List<CrawlerMatch> searchMatches(String keyword) {
        if (keyword == null || keyword.isBlank()) return Collections.emptyList();
        return visibleMatches(matchMapper.searchMatches(keyword.trim()));
    }

    public List<CrawlerMatch> getRecentMatchesByTeam(String teamName, int limit) {
        if (teamName == null || teamName.isBlank()) return Collections.emptyList();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return visibleMatches(matchMapper.findRecentByTeamName(teamName.trim(), safeLimit));
    }

    public List<CrawlerMatch> getHeadToHead(String homeTeam, String awayTeam, int limit) {
        if (homeTeam == null || awayTeam == null || homeTeam.isBlank() || awayTeam.isBlank()) return Collections.emptyList();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return visibleMatches(matchMapper.findHeadToHead(homeTeam.trim(), awayTeam.trim(), safeLimit));
    }

    /**
     * Hide historical rows written by disabled providers and deduplicate the
     * remaining view.  We deliberately do not delete those rows here: they
     * remain available for a future migration/audit while the product only
     * exposes the configured primary source.
     */
    private List<CrawlerMatch> visibleMatches(List<CrawlerMatch> matches) {
        if (matches == null || matches.isEmpty()) return Collections.emptyList();
        List<CrawlerMatch> visible = matches.stream()
                .filter(Objects::nonNull)
                .filter(match -> dataSourceManager.isSourceEnabled(match.getSource()))
                .filter(this::isProductionLeague)
                .filter(match -> !MatchStatus.SOURCE_REMOVED.equals(MatchStatus.normalize(match.getStatus())))
                .toList();
        return deduplicateMatches(visible);
    }
}
