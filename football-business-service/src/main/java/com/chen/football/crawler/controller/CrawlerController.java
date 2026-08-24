package com.chen.football.crawler.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerStanding;
import com.chen.football.crawler.entity.CrawlerTeam;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.mapper.CrawlerStandingMapper;
import com.chen.football.crawler.mapper.CrawlerTeamMapper;
import com.chen.football.crawler.service.MatchCrawlerService;
import com.chen.football.crawler.service.MatchRecommendationService;
import com.chen.football.crawler.service.StandingCrawlerService;
import com.chen.football.crawler.service.StandingZoneRules;
import com.chen.football.crawler.service.IdentityMappingService;
import com.chen.football.crawler.service.EspnSquadCrawlerService;
import com.chen.football.prediction.service.MatchPredictionPrecomputeService;
import com.chen.football.crawler.source.LeagueNameNormalizer;
import com.chen.football.crawler.source.DataSourceManager;
import com.chen.football.crawler.source.ProductionLeagueScope;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.common.service.RedisCacheService;
import com.chen.football.common.client.ApiFootballClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 爬虫数据API控制器
 */
@RestController
@RequestMapping("/api/crawler")
@Slf4j
public class CrawlerController {

    /** All public match-date calculations use the product business timezone. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PRODUCTION_LEAGUE_IDS = Set.of(
            "39", "140", "135", "78", "61", "88", "94", "40", "PL", "PD", "SA", "BL1", "FL1", "DED", "PPL", "ELC",
            "bbc-premier-league", "bbc-spanish-la-liga", "bbc-italian-serie-a", "bbc-german-bundesliga",
            "bbc-french-ligue-one", "bbc-dutch-eredivisie", "bbc-portuguese-primeira-liga", "bbc-championship");
    private static final Set<String> PRODUCTION_LEAGUE_NAMES = Set.of(
            "英超", "西甲", "意甲", "德甲", "法甲", "荷甲", "葡超", "英冠", "Premier League", "La Liga", "Serie A", "Bundesliga", "Ligue 1", "Eredivisie", "Primeira Liga", "Championship");

    private final MatchCrawlerService matchCrawlerService;
    private final MatchRecommendationService matchRecommendationService;
    private final StandingCrawlerService standingCrawlerService;
    private final CrawlerTeamMapper crawlerTeamMapper;
    private final CrawlerStandingMapper crawlerStandingMapper;
    private final CrawlerMatchMapper crawlerMatchMapper;
    private final LeagueNameNormalizer leagueNameNormalizer;
    private final DataSourceManager dataSourceManager;
    private final MatchPredictionPrecomputeService predictionPrecomputeService;
    private final ApiFootballClient apiFootballClient;
    private final IdentityMappingService identityMappingService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EspnSquadCrawlerService espnSquadCrawlerService;
    private final RedisCacheService redisCacheService;
    private final Map<String, SquadCacheEntry> squadCache = new ConcurrentHashMap<>();

    @Value("${api-football.squad-enabled:true}")
    private boolean squadEnabled;

    public CrawlerController(MatchCrawlerService matchCrawlerService,
                             MatchRecommendationService matchRecommendationService,
                             StandingCrawlerService standingCrawlerService,
                             CrawlerTeamMapper crawlerTeamMapper,
                             CrawlerStandingMapper crawlerStandingMapper,
                             CrawlerMatchMapper crawlerMatchMapper,
                             LeagueNameNormalizer leagueNameNormalizer,
                             DataSourceManager dataSourceManager,
                             MatchPredictionPrecomputeService predictionPrecomputeService,
                             ApiFootballClient apiFootballClient,
                             IdentityMappingService identityMappingService,
                             JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper,
                             EspnSquadCrawlerService espnSquadCrawlerService,
                             RedisCacheService redisCacheService) {
        this.matchCrawlerService = matchCrawlerService;
        this.matchRecommendationService = matchRecommendationService;
        this.standingCrawlerService = standingCrawlerService;
        this.crawlerTeamMapper = crawlerTeamMapper;
        this.crawlerStandingMapper = crawlerStandingMapper;
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.leagueNameNormalizer = leagueNameNormalizer;
        this.dataSourceManager = dataSourceManager;
        this.predictionPrecomputeService = predictionPrecomputeService;
        this.apiFootballClient = apiFootballClient;
        this.identityMappingService = identityMappingService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.espnSquadCrawlerService = espnSquadCrawlerService;
        this.redisCacheService = redisCacheService;
    }

    /**
     * 阵容属于低频详情数据，使用数据库缓存跨进程复用，避免每次重启都消耗第三方额度。
     * 表由应用幂等初始化，已有部署无需手工执行迁移。
     */
    @PostConstruct
    void ensureSquadCacheTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_team_squad_cache (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT, cache_key VARCHAR(255) NOT NULL, " +
                    "team_name VARCHAR(128) NOT NULL, league_name VARCHAR(128), external_team_id VARCHAR(64), " +
                    "season VARCHAR(16), status VARCHAR(32) NOT NULL, message VARCHAR(512), source VARCHAR(64), " +
                    "squad_json LONGTEXT, fetched_at DATETIME NULL, expires_at DATETIME NULL, " +
                    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (id), UNIQUE KEY uk_team_squad_cache (cache_key), " +
                    "KEY idx_team_squad_name (team_name), KEY idx_team_squad_expiry (expires_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception ex) {
            // 赛事资料不应因缓存表权限/兼容性问题阻断比赛主链路。
            log.warn("球队阵容缓存表初始化失败，将仅使用进程缓存: {}", ex.getMessage());
        }
    }

    /**
     * 获取今日比赛
     */
    @GetMapping("/matches/today")
    public Map<String, Object> getTodayMatches() {
        try {
            List<CrawlerMatch> matches = matchCrawlerService.getTodayMatches();
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 获取未来比赛
     */
    @GetMapping("/matches/upcoming")
    public Map<String, Object> getUpcomingMatches() {
        try {
            List<CrawlerMatch> matches = visibleSourceMatches(crawlerMatchMapper.findUpcomingMatches());
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 获取最近比赛（从数据库读取，不重新爬取）
     */
    @GetMapping("/matches/db/upcoming")
    public Map<String, Object> getUpcomingMatchesFromDb() {
        try {
            List<CrawlerMatch> matches = visibleSourceMatches(crawlerMatchMapper.findUpcomingMatches());
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 分页获取最近比赛
     */
    @GetMapping("/matches/db/page")
    public Map<String, Object> getMatchesPage(@RequestParam(name = "keyword", required = false) String keyword,
                                              @RequestParam(name = "status", required = false) String status,
                                              @RequestParam(name = "date", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
                                              @RequestParam(name = "page", defaultValue = "1") int page,
                                              @RequestParam(name = "size", defaultValue = "20") int size) {
        try {
            int safePage = Math.max(1, page);
            int safeSize = Math.max(1, Math.min(size, 100));
            int offset = (safePage - 1) * safeSize;
            LambdaQueryWrapper<CrawlerMatch> query = new LambdaQueryWrapper<CrawlerMatch>()
                    .orderByDesc(CrawlerMatch::getMatchTime)
                    .orderByDesc(CrawlerMatch::getId);
            // Keep the provider predicate in SQL, then apply the canonical
            // production scope in Java.  The league-id/name representation
            // differs across sources (BBC uses provider slugs), and relying
            // on a compound MyBatis wrapper here previously returned an empty
            // page even though the date endpoint had visible matches.
            applyPrimarySourceFilter(query);
            if (keyword != null && !keyword.isBlank()) {
                query.and(q -> q.like(CrawlerMatch::getLeagueName, keyword)
                        .or().like(CrawlerMatch::getHomeTeamName, keyword)
                        .or().like(CrawlerMatch::getAwayTeamName, keyword));
            }
            if (status != null && !status.isBlank()) {
                query.eq(CrawlerMatch::getStatus, status);
            }
            if (date != null) {
                // Parse the calendar date directly. Converting yyyy-MM-dd to
                // java.util.Date first makes the result depend on the cloud
                // server's default timezone and can move today's boundary.
                query.ge(CrawlerMatch::getMatchTime, date.atStartOfDay())
                        .lt(CrawlerMatch::getMatchTime, date.plusDays(1).atStartOfDay());
            }
            List<CrawlerMatch> all = visibleSourceMatches(crawlerMatchMapper.selectList(query));
            List<CrawlerMatch> pageItems = all.stream().skip(offset).limit(safeSize).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("page", safePage);
            data.put("size", safeSize);
            data.put("total", all.size());
            data.put("response", formatMatches(pageItems));
            data.put("results", pageItems.size());
            data.put("dataQuality", dataQuality(pageItems.size()));
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 前台比赛页：只获取今天前后 7 天的比赛窗口
     */
    @GetMapping("/matches/window")
    public Map<String, Object> getMatchesWindow(@RequestParam(name = "page", defaultValue = "1") int page,
                                                @RequestParam(name = "size", defaultValue = "300") int size,
                                                @RequestParam(name = "pastDays", defaultValue = "0") int pastDays,
                                                @RequestParam(name = "futureDays", defaultValue = "7") int futureDays,
                                                @RequestParam(name = "from", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
                                                @RequestParam(name = "to", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to) {
        try {
            int safePage = Math.max(1, page);
            int safeSize = Math.max(1, Math.min(size, 500));
            int safePastDays = Math.max(0, Math.min(pastDays, 30));
            int safeFutureDays = Math.max(0, Math.min(futureDays, 30));
            int offset = (safePage - 1) * safeSize;
            LocalDate today = LocalDate.now(BUSINESS_ZONE);
            LocalDate windowStart = from == null ? today.minusDays(safePastDays) : from;
            LocalDate windowEnd = to == null ? today.plusDays(safeFutureDays) : to;
            if (windowStart.isAfter(windowEnd)) {
                return Map.of("success", false, "message", "查询起止日期无效", "data", Map.of());
            }
            if (ChronoUnit.DAYS.between(windowStart, windowEnd) > 30) {
                windowEnd = windowStart.plusDays(30);
            }
            LambdaQueryWrapper<CrawlerMatch> query = new LambdaQueryWrapper<CrawlerMatch>()
                    .ge(CrawlerMatch::getMatchTime, windowStart.atStartOfDay())
                    .lt(CrawlerMatch::getMatchTime, windowEnd.plusDays(1).atStartOfDay())
                    .orderByAsc(CrawlerMatch::getMatchTime)
                    .orderByAsc(CrawlerMatch::getFixtureId);
            applyPrimarySourceFilter(query);
            // Keep the source predicate in SQL, then apply the canonical
            // production scope in Java.  This avoids subtle MyBatis wrapper
            // grouping differences between MySQL versions and guarantees the
            // same visibility rule as the home endpoint.
            List<CrawlerMatch> allVisible = visibleSourceMatches(crawlerMatchMapper.selectList(query));
            long total = allVisible.size();
            List<CrawlerMatch> pageItems = allVisible.stream().skip(offset).limit(safeSize).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("page", safePage);
            data.put("size", safeSize);
            data.put("total", total);
            data.put("windowStart", windowStart.toString());
            data.put("windowEnd", windowEnd.toString());
            data.put("response", formatMatches(pageItems));
            data.put("results", pageItems.size());
            data.put("dataQuality", dataQuality(pageItems.size()));
            data.put("businessTimezone", BUSINESS_ZONE.getId());
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 首页公开摘要：一次返回同一时间窗口内的赛程、统一预测快照和数据质量。
     * 预测快照读取失败不会阻断赛程返回，避免首页把“暂无预测”误判成“暂无比赛”。
     */
    @GetMapping("/home/summary")
    public Map<String, Object> getHomeSummary(@RequestParam(name = "from", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
                                              @RequestParam(name = "to", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
                                              @RequestParam(name = "size", defaultValue = "300") int size) {
        long startedAt = System.nanoTime();
        try {
            LocalDate today = LocalDate.now(BUSINESS_ZONE);
            LocalDate windowStart = from == null ? today : from;
            LocalDate windowEnd = to == null ? today.plusDays(7) : to;
            if (windowStart.isAfter(windowEnd)) {
                return Map.of("success", false, "message", "查询起止日期无效", "data", Map.of());
            }
            if (ChronoUnit.DAYS.between(windowStart, windowEnd) > 30) windowEnd = windowStart.plusDays(30);
            int safeSize = Math.max(1, Math.min(size, 500));
            String cacheKey = "home:summary:" + windowStart + ":" + windowEnd + ":" + safeSize;
            Map<String, Object> cached = redisCacheService.get(cacheKey, Map.class);
            if (cached != null && !cached.isEmpty()) {
                log.debug("[HomeSummary] cache_hit window={}..{} matches={} latencyMs={}", windowStart, windowEnd, cached.getOrDefault("total", 0), (System.nanoTime() - startedAt) / 1_000_000);
                return Map.of("success", true, "message", "获取成功（缓存）", "data", cached);
            }
            LambdaQueryWrapper<CrawlerMatch> query = new LambdaQueryWrapper<CrawlerMatch>()
                    .ge(CrawlerMatch::getMatchTime, windowStart.atStartOfDay())
                    .lt(CrawlerMatch::getMatchTime, windowEnd.plusDays(1).atStartOfDay())
                    .orderByAsc(CrawlerMatch::getMatchTime)
                    .orderByAsc(CrawlerMatch::getFixtureId);
            applyPrimarySourceFilter(query);
            List<CrawlerMatch> matches = visibleSourceMatches(crawlerMatchMapper.selectList(query));
            long total = matches.size();
            if (matches.size() > safeSize) matches = matches.subList(0, safeSize);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("matches", formatMatches(matches));
            var predictionResult = predictionPrecomputeService.readPublicSnapshotResultForMatches(matches);
            data.put("predictions", predictionResult.items());
            data.put("predictionQuality", predictionResult.quality());
            data.put("total", total);
            data.put("windowStart", windowStart.toString());
            data.put("windowEnd", windowEnd.toString());
            data.put("dataQuality", dataQuality(matches.size()));
            data.put("generatedAt", java.time.Instant.now().toString());
            data.put("businessTimezone", BUSINESS_ZONE.getId());
            data.put("contractVersion", "home-summary-v2");
            redisCacheService.set(cacheKey, data, 30);
            log.debug("[HomeSummary] cache_miss window={}..{} matches={} predictions={} quality={} predictionQuality={} latencyMs={}", windowStart, windowEnd, matches.size(), predictionResult.items().size(), data.get("dataQuality"), predictionResult.quality().get("status"), (System.nanoTime() - startedAt) / 1_000_000);
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            log.warn("[HomeSummary] failed latencyMs={} error={}", (System.nanoTime() - startedAt) / 1_000_000, e.getMessage());
            return buildFailureResponse(e);
        }
    }

    /**
     * 首页轻量简报：只返回当前比赛日需要扫视的比赛，而不是完整赛程窗口。
     * 完整日期导航、联赛筛选和分页由 /matches/window 与 /matches/db/page 负责。
     */
    @GetMapping("/home/brief")
    public Map<String, Object> getHomeBrief(@RequestParam(name = "from", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
                                            @RequestParam(name = "to", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
                                            @RequestParam(name = "size", defaultValue = "40") int size) {
        long startedAt = System.nanoTime();
        try {
            LocalDate today = LocalDate.now(BUSINESS_ZONE);
            LocalDate windowStart = from == null ? today : from;
            LocalDate windowEnd = to == null ? today.plusDays(7) : to;
            if (windowStart.isAfter(windowEnd)) {
                return Map.of("success", false, "message", "查询起止日期无效", "data", Map.of());
            }
            if (ChronoUnit.DAYS.between(windowStart, windowEnd) > 30) {
                windowEnd = windowStart.plusDays(30);
            }
            int safeSize = Math.max(6, Math.min(size, 60));
            String cacheKey = "home:brief:" + windowStart + ":" + windowEnd + ":" + safeSize;
            Map<String, Object> cached = redisCacheService.get(cacheKey, Map.class);
            if (cached != null && !cached.isEmpty()) {
                return Map.of("success", true, "message", "获取成功（缓存）", "data", cached);
            }

            LambdaQueryWrapper<CrawlerMatch> query = new LambdaQueryWrapper<CrawlerMatch>()
                    .ge(CrawlerMatch::getMatchTime, windowStart.atStartOfDay())
                    .lt(CrawlerMatch::getMatchTime, windowEnd.plusDays(1).atStartOfDay())
                    .orderByAsc(CrawlerMatch::getMatchTime)
                    .orderByAsc(CrawlerMatch::getFixtureId);
            applyPrimarySourceFilter(query);
            List<CrawlerMatch> allMatches = visibleSourceMatches(crawlerMatchMapper.selectList(query));
            List<CrawlerMatch> liveMatches = allMatches.stream().filter(this::isLiveMatch).toList();
            List<CrawlerMatch> upcomingMatches = allMatches.stream()
                    .filter(match -> !isLiveMatch(match) && !isFinishedStatus(match.getStatus()))
                    .sorted(Comparator.comparing(CrawlerMatch::getMatchTime, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            List<CrawlerMatch> todayResults = allMatches.stream()
                    .filter(match -> isSameDate(match.getMatchTime(), today) && isFinishedStatus(match.getStatus()))
                    .sorted(Comparator.comparing(CrawlerMatch::getMatchTime, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();

            LinkedHashMap<Long, CrawlerMatch> selected = new LinkedHashMap<>();
            for (CrawlerMatch match : liveMatches) selected.put(match.getId(), match);
            for (CrawlerMatch match : upcomingMatches) {
                if (selected.size() >= safeSize) break;
                selected.put(match.getId(), match);
            }
            for (CrawlerMatch match : todayResults) {
                if (selected.size() >= safeSize) break;
                selected.put(match.getId(), match);
            }
            List<CrawlerMatch> briefMatches = new ArrayList<>(selected.values());
            var predictionResult = predictionPrecomputeService.readPublicSnapshotResultForMatches(briefMatches);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("matches", formatMatches(briefMatches));
            data.put("liveMatches", formatMatches(liveMatches.stream().limit(safeSize).toList()));
            data.put("nextMatches", formatMatches(upcomingMatches.stream().limit(Math.min(12, safeSize)).toList()));
            data.put("todayResults", formatMatches(todayResults.stream().limit(Math.min(8, safeSize)).toList()));
            data.put("predictions", predictionResult.items());
            data.put("predictionQuality", predictionResult.quality());
            data.put("total", allMatches.size());
            data.put("returned", briefMatches.size());
            data.put("windowStart", windowStart.toString());
            data.put("windowEnd", windowEnd.toString());
            data.put("dataQuality", dataQuality(briefMatches.size()));
            data.put("generatedAt", java.time.Instant.now().toString());
            data.put("businessTimezone", BUSINESS_ZONE.getId());
            data.put("contractVersion", "home-brief-v1");
            redisCacheService.set(cacheKey, data, 30);
            log.debug("[HomeBrief] cache_miss window={}..{} total={} returned={} latencyMs={}", windowStart, windowEnd, allMatches.size(), briefMatches.size(), (System.nanoTime() - startedAt) / 1_000_000);
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            log.warn("[HomeBrief] failed latencyMs={} error={}", (System.nanoTime() - startedAt) / 1_000_000, e.getMessage());
            return buildFailureResponse(e);
        }
    }

    /**
     * 获取实时/进行中比赛
     */
    @GetMapping("/matches/live")
    public Map<String, Object> getLiveMatches() {
        try {
            List<CrawlerMatch> matches = visibleSourceMatches(crawlerMatchMapper.findLiveMatches());
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 获取指定时间范围的比赛
     */
    @GetMapping("/matches/range")
    public Map<String, Object> getMatchesByRange(@RequestParam("start") @DateTimeFormat(pattern = "yyyy-MM-dd") Date start,
                                                 @RequestParam("end") @DateTimeFormat(pattern = "yyyy-MM-dd") Date end) {
        try {
            List<CrawlerMatch> matches = matchCrawlerService.getMatchesFromDb(start, end);
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 按日期读取比赛。采集由定时任务/管理员触发，公开读取接口不能
     * 因为访客刷新页面而反复消耗数据源额度。
     */
    @GetMapping("/matches/date/{date}")
    public Map<String, Object> getMatchesByDate(@PathVariable("date") String date) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setTimeZone(TimeZone.getTimeZone(BUSINESS_ZONE));
            Date parseDate = sdf.parse(date);
            List<CrawlerMatch> matches = matchCrawlerService.getMatchesFromDb(parseDate, parseDate);

            return Map.of(
                    "success", true,
                    "message", "获取成功",
                    "data", Map.of(
                            "response", formatMatches(matches),
                            "results", matches.size()
                    )
            );
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 从数据库获取比赛（不爬取）
     */
    @GetMapping("/matches/db/today")
    public Map<String, Object> getMatchesFromDb() {
        try {
            List<CrawlerMatch> matches = matchCrawlerService.getTodayMatches();
            return Map.of(
                    "success", true,
                    "message", "获取成功",
                    "data", Map.of(
                            "response", formatMatches(matches),
                            "results", matches.size()
                    )
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", "获取失败: " + e.getMessage(),
                    "data", Map.of("response", Collections.emptyList(), "results", 0)
            );
        }
    }

    /**
     * 按联赛读取比赛。公开 GET 接口不得隐式触发外部采集；需要补数时
     * 使用受保护的 /trigger 或 /task/run 管理员接口。
     */
    @GetMapping("/matches/league/{leagueName}")
    public Map<String, Object> getMatchesByLeague(@PathVariable("leagueName") String leagueName,
                                                   @RequestParam(name = "date", required = false) String date) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setTimeZone(TimeZone.getTimeZone(BUSINESS_ZONE));
            Date targetDate = date == null || date.isBlank() ? null : sdf.parse(date);
            List<CrawlerMatch> matches = targetDate == null
                    ? matchCrawlerService.getMatchesByLeagueFromDb(leagueName)
                    : matchCrawlerService.getMatchesFromDb(targetDate, targetDate).stream()
                    .filter(match -> leagueName.equalsIgnoreCase(match.getLeagueName()))
                    .toList();
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 联赛赛程统计
     */
    @GetMapping("/matches/league/{leagueName}/stats")
    public Map<String, Object> getLeagueMatchStats(@PathVariable("leagueName") String leagueName) {
        try {
            List<CrawlerMatch> all = matchCrawlerService.getMatchesByLeagueFromDb(leagueName);
            long liveCount = all.stream().filter(m -> "LIVE".equals(m.getStatus()) || "1H".equals(m.getStatus()) || "2H".equals(m.getStatus()) || "HT".equals(m.getStatus())).count();
            long finishedCount = all.stream().filter(m -> "FT".equals(m.getStatus())).count();
            long upcomingCount = all.stream().filter(m -> "NS".equals(m.getStatus())).count();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("leagueName", leagueName);
            data.put("total", all.size());
            data.put("live", liveCount);
            data.put("finished", finishedCount);
            data.put("upcoming", upcomingCount);
            data.put("matches", formatMatches(all));
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 获取单场比赛详情
     */
    @GetMapping("/matches/detail/{externalMatchId}")
    public Map<String, Object> getMatchDetail(@PathVariable("externalMatchId") String externalMatchId) {
        try {
            CrawlerMatch match = matchCrawlerService.getMatchDetailByExternalId(externalMatchId);
            if (match == null) {
                return Map.of(
                        "success", false,
                        "message", "未找到比赛",
                        "data", Map.of("response", Collections.emptyMap(), "results", 0)
                );
            }
            return buildSuccessResponse(List.of(formatMatchDetail(match)), 1);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }




    /**
     * Rolling window of explainable match highlights. The date parameter is
     * retained for older clients but the ranking is intentionally stable when
     * users move across the date rail. The old /matches/hot alias remains.
     */
    @GetMapping("/matches/recommendations")
    public Map<String, Object> getMatchRecommendations(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "mode", defaultValue = "focus") String mode,
            @RequestParam(name = "limit", defaultValue = "6") int limit) {
        try {
            MatchRecommendationService.RecommendationResult result = matchRecommendationService.recommend(date, mode, limit);
            return buildRecommendationResponse(result);
        } catch (Exception e) {
            log.warn("比赛焦点推荐读取失败 date={} mode={}: {}", date, mode, e.getMessage());
            return buildFailureResponse(e);
        }
    }

    /** Backward-compatible alias for clients that still call /matches/hot. */
    @GetMapping("/matches/hot")
    public Map<String, Object> getHotMatches(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "limit", defaultValue = "6") int limit) {
        try {
            return buildRecommendationResponse(matchRecommendationService.recommend(date, "focus", limit));
        } catch (Exception e) {
            log.warn("兼容热门推荐读取失败 date={}: {}", date, e.getMessage());
            return buildFailureResponse(e);
        }
    }

    private Map<String, Object> buildRecommendationResponse(MatchRecommendationService.RecommendationResult result) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (result != null && result.items != null) {
            for (MatchRecommendationService.RecommendationItem item : result.items) {
                if (item == null || item.match == null) continue;
                List<Map<String, Object>> formatted = formatMatches(List.of(item.match));
                if (formatted.isEmpty()) continue;
                Map<String, Object> row = new LinkedHashMap<>(formatted.get(0));
                Map<String, Object> recommendation = new LinkedHashMap<>();
                recommendation.put("rank", items.size() + 1);
                recommendation.put("score", item.score);
                recommendation.put("tier", item.tier);
                recommendation.put("reasonCodes", item.reasonCodes == null ? List.of() : item.reasonCodes);
                recommendation.put("reasonTexts", item.reasonTexts == null ? List.of() : item.reasonTexts);
                row.put("recommendation", recommendation);
                items.add(row);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("response", items);
        data.put("results", items.size());
        data.put("meta", result == null || result.meta == null ? Map.of() : result.meta);
        if (result != null && result.meta != null) data.putAll(result.meta);
        return Map.of("success", true, "message", "获取成功", "data", data);
    }




    /**
     * 读取积分榜快照。刷新积分榜使用受保护的 POST /standings/.../refresh。
     */
    @GetMapping("/standings/{leagueId}")
    public Map<String, Object> getStandings(@PathVariable("leagueId") String leagueId,
                                            @RequestParam(name = "season", defaultValue = "") String season) {
        try {
            String targetSeason = season == null || season.isBlank() ? getCurrentSeason() : season;
            List<CrawlerStanding> standings = standingCrawlerService.getStandingsByLeagueName(leagueId, targetSeason);
            return buildStandingsResponse(standings);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 从数据库获取积分榜
     */
    @GetMapping("/standings/{leagueId}/db")
    public Map<String, Object> getStandingsFromDb(@PathVariable("leagueId") String leagueId,
                                                  @RequestParam(name = "season", defaultValue = "") String season) {
        try {
            String targetSeason = season.isBlank() ? getCurrentSeason() : season;
            List<CrawlerStanding> standings = standingCrawlerService.getStandingsFromDb(leagueId, targetSeason);
            return buildStandingsResponse(standings);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 获取球队列表（用于 team-service）
     */
    @GetMapping("/teams/league/{leagueName}")
    public Map<String, Object> getTeamsByLeague(@PathVariable("leagueName") String leagueName) {
        try {
            List<CrawlerTeam> teams = visibleTeams(crawlerTeamMapper.findByLeague(leagueName));
            Map<String, CrawlerStanding> standingsByName = new LinkedHashMap<>();
            for (CrawlerStanding standing : standingCrawlerService.getStandingsByLeagueName(leagueName)) {
                if (standing.getTeamName() == null || standing.getTeamName().isBlank()) continue;
                standingsByName.putIfAbsent(teamNameKey(standing.getTeamName()), standing);
            }
            List<Map<String, Object>> response = teams.stream().map(team -> {
                Map<String, Object> item = new LinkedHashMap<>();
                CrawlerStanding standing = standingsByName.get(teamNameKey(team.getName()));
                String sourceTeamId = standing == null ? "" : String.valueOf(standing.getTeamId() == null ? "" : standing.getTeamId());
                item.put("id", sourceTeamId.isBlank() ? (team.getId() == null ? 0L : team.getId()) : sourceTeamId);
                item.put("teamId", sourceTeamId);
                item.put("name", team.getName() == null ? "" : team.getName());
                item.put("canonicalKey", identityMappingService.teamKey(sourceTeamId.isBlank() ? String.valueOf(team.getId() == null ? "" : team.getId()) : sourceTeamId, team.getName()));
                item.put("logo", standing != null && standing.getTeamLogo() != null && !standing.getTeamLogo().isBlank() ? standing.getTeamLogo() : (team.getLogo() == null ? "" : team.getLogo()));
                item.put("country", team.getCountry() == null ? "" : team.getCountry());
                item.put("league", team.getLeagueName() == null ? "" : team.getLeagueName());
                return item;
            }).toList();

            // 主爬虫源优先写入比赛表，球队档案表可能尚未回填；此时从已采集比赛
            // 补出俱乐部清单，避免“有比赛却没有俱乐部”的空页面。
            if (response.isEmpty()) {
                Map<String, Map<String, Object>> fallback = new LinkedHashMap<>();
                for (CrawlerStanding standing : standingsByName.values()) {
                    addTeamFromMatch(fallback, standing.getTeamId(), standing.getTeamName(), standing.getTeamLogo(), leagueName);
                }
                for (CrawlerMatch match : visibleSourceMatches(crawlerMatchMapper.findByLeagueName(leagueName))) {
                    addTeamFromMatch(fallback, match.getHomeTeamId(), match.getHomeTeamName(), match.getHomeTeamLogo(), leagueName);
                    addTeamFromMatch(fallback, match.getAwayTeamId(), match.getAwayTeamName(), match.getAwayTeamLogo(), leagueName);
                }
                response = new ArrayList<>(fallback.values());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("response", response);
            data.put("results", response.size());
            data.put("dataQuality", teamQuality(teams, response.size()));
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("response", Collections.emptyList());
            data.put("results", 0);
            data.put("dataQuality", Map.of("status", "SYNC_FAILED", "statusText", "球队资料同步失败", "message", e.getMessage() == null ? "请求失败" : e.getMessage()));
            return Map.of("success", false, "message", "获取失败: " + e.getMessage(), "data", data);
        }
    }

    private String teamNameKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('&', ' ').replaceAll("[^\\p{L}\\p{N}]+", "").trim();
    }

    /** Keep the public club directory aligned with the active source policy. */
    private List<CrawlerTeam> visibleTeams(List<CrawlerTeam> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        Map<String, CrawlerTeam> unique = new LinkedHashMap<>();
        for (CrawlerTeam team : rows) {
            if (team == null || team.getName() == null || team.getName().isBlank()) continue;
            if (!ProductionLeagueScope.isSupported(null, team.getLeagueName())) continue;
            String source = team.getSource();
            boolean allowed = !dataSourceManager.isPrimaryOnly()
                    || source == null || source.isBlank()
                    || "bbc-standings".equalsIgnoreCase(source)
                    || "bbc-scores".equalsIgnoreCase(source)
                    || "admin".equalsIgnoreCase(source);
            if (!allowed) continue;
            String key = teamNameKey(team.getName());
            CrawlerTeam existing = unique.get(key);
            if (existing == null || (team.getUpdatedAt() != null
                    && (existing.getUpdatedAt() == null || team.getUpdatedAt().isAfter(existing.getUpdatedAt())))) {
                unique.put(key, team);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private void addTeamFromMatch(Map<String, Map<String, Object>> target,
                                  String teamId, String teamName, String logo, String leagueName) {
        if (teamName == null || teamName.isBlank()) return;
        String key = teamName.trim().toLowerCase(Locale.ROOT);
        target.computeIfAbsent(key, ignored -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", teamId == null ? "" : teamId);
            item.put("name", teamName.trim());
            item.put("canonicalKey", identityMappingService.teamKey(teamId, teamName.trim()));
            item.put("logo", logo == null ? "" : logo);
            item.put("country", "");
            item.put("league", leagueName == null ? "" : leagueName);
            return item;
        });
    }

    private Map<String, Object> teamQuality(List<CrawlerTeam> teams, int results) {
        Map<String, Object> quality = new LinkedHashMap<>();
        if (results <= 0) {
            quality.put("status", "NO_DATA");
            quality.put("statusText", "暂无球队资料");
            quality.put("message", "该联赛当前没有已同步的球队档案");
            return quality;
        }
        LocalDateTime updatedAt = teams == null ? null : teams.stream().map(CrawlerTeam::getUpdatedAt)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        long ageMinutes = updatedAt == null ? -1 : Math.max(0, ChronoUnit.MINUTES.between(updatedAt, LocalDateTime.now(BUSINESS_ZONE)));
        quality.put("status", ageMinutes > 72 * 60 ? "STALE" : "AVAILABLE");
        quality.put("statusText", ageMinutes > 72 * 60 ? "球队资料较旧" : "球队资料可用");
        quality.put("message", "已返回 " + results + " 支球队");
        quality.put("source", teams == null ? "" : teams.stream().map(CrawlerTeam::getSource).filter(Objects::nonNull).filter(v -> !v.isBlank()).findFirst().orElse("unknown"));
        quality.put("lastSyncedAt", updatedAt == null ? "" : updatedAt.toString());
        quality.put("ageMinutes", ageMinutes);
        return quality;
    }

    /**
     * 搜索球队（用于 team-service）
     */
    @GetMapping("/teams/search")
    public Map<String, Object> searchTeams(@RequestParam("name") String name) {
        try {
            List<CrawlerTeam> teams = visibleTeams(crawlerTeamMapper.searchByName(name));
            List<Map<String, Object>> response = teams.stream().map(team -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", team.getId() == null ? 0L : team.getId());
                item.put("name", team.getName() == null ? "" : team.getName());
                item.put("canonicalKey", identityMappingService.teamKey(String.valueOf(team.getId() == null ? "" : team.getId()), team.getName()));
                item.put("logo", team.getLogo() == null ? "" : team.getLogo());
                item.put("country", team.getCountry() == null ? "" : team.getCountry());
                item.put("league", team.getLeagueName() == null ? "" : team.getLeagueName());
                return item;
            }).toList();

            return Map.of(
                    "success", true,
                    "message", "获取成功",
                    "data", Map.of("response", response, "results", response.size())
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", "获取失败: " + e.getMessage(),
                    "data", Map.of("response", Collections.emptyList(), "results", 0)
            );
        }
    }

    /**
     * 按需获取球队注册阵容。阵容属于详情增强数据，不参与比赛主采集链路；
     * 页面读取以内存/数据库缓存为主，只有缓存缺失或用户主动刷新时才访问外部来源。
     */
    @GetMapping("/teams/squad/{teamName}")
    public Map<String, Object> getTeamSquad(@PathVariable("teamName") String teamName,
                                            @RequestParam(name = "leagueName", required = false) String leagueName,
                                            @RequestParam(name = "teamId", required = false) String teamId,
                                            @RequestParam(name = "season", defaultValue = "0") int season,
                                            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        String safeName = teamName == null ? "" : teamName.trim();
        // v2 将 ESPN 公共阵容源纳入缓存键，避免复用早期 API-Football 的
        // NOT_CONFIGURED/EMPTY 结果，让已经打开过页面的用户仍然看到旧空状态。
        String cacheKey = "v2|" + safeName.toLowerCase(Locale.ROOT) + "|" + (leagueName == null ? "" : leagueName) + "|" + season;
        SquadCacheEntry cached = squadCache.get(cacheKey);
        if (!force && cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.payload();
        }
        Map<String, Object> persisted = force ? null : loadPersistedSquad(cacheKey, false);
        if (!force && persisted != null) {
            squadCache.put(cacheKey, new SquadCacheEntry(persisted, persistedSquadExpiry(persisted)));
            return persisted;
        }
        // Serve an expired but previously confirmed roster immediately. The
        // scheduled refresher will update it in the background, so a page
        // visit never has to wait for ESPN/Transfermarkt just because the TTL
        // elapsed.
        Map<String, Object> stale = force ? null : loadPersistedSquad(cacheKey, true);
        if (!force && stale != null) {
            squadCache.put(cacheKey, new SquadCacheEntry(stale, System.currentTimeMillis() + 5 * 60 * 1000L));
            return stale;
        }
        if (!squadEnabled) {
            return cacheSquad(cacheKey, squadPayload("NOT_CONFIGURED", "阵容数据源未启用", List.of(), null), safeName, leagueName, null, season);
        }
        if (safeName.isBlank()) {
            return cacheSquad(cacheKey, squadPayload("EMPTY", "缺少球队名称", List.of(), null), safeName, leagueName, null, season);
        }
        if (safeName.length() > 96 || safeName.contains("<") || safeName.contains(">")) {
            return cacheSquad(cacheKey, squadPayload("INVALID", "球队名称无效", List.of(), null), safeName, leagueName, null, season);
        }
        // Do not let an arbitrary public path become a proxy for unlimited
        // ESPN/API-Football requests.  A cache miss may fetch only a club that
        // already exists in the active local directory or match table.
        boolean knownTeam = parseApiTeamId(teamId) != null
                || !visibleTeams(crawlerTeamMapper.searchByName(safeName)).isEmpty()
                || !visibleSourceMatches(crawlerMatchMapper.searchMatches(safeName)).isEmpty();
        if (!knownTeam) {
            return cacheSquad(cacheKey, squadPayload("NOT_CONFIGURED", "当前球队不在已同步资料中", List.of(), null), safeName, leagueName, null, season);
        }

        // 阵容优先走 ESPN 公开球队页。它不写比赛表，也不消耗 API-Football
        // 额度；只有联赛未配置映射时才继续尝试旧 API-Football 逻辑。
        EspnSquadCrawlerService.Result espnResult = espnSquadCrawlerService.fetch(safeName, leagueName, season);
        if (!"NOT_CONFIGURED".equals(espnResult.status())) {
            return cacheSquad(cacheKey,
                    squadPayload(espnResult.status(), espnResult.message(), espnResult.players(), espnResult.teamId(), espnResult.source()),
                    safeName, leagueName, espnResult.teamId(), season, espnResult.source());
        }

        try {
            Long resolvedTeamId = parseApiTeamId(teamId);
            if (resolvedTeamId == null) {
                resolvedTeamId = resolveTeamIdFromRecentMatches(safeName);
            }
            if (resolvedTeamId == null) {
                resolvedTeamId = resolveTeamIdBySearch(safeName);
            }
            if (resolvedTeamId == null) {
                return cacheSquad(cacheKey, squadPayload("NOT_CONFIGURED", "当前主爬虫源未提供可映射的第三方球队编号，暂无法核验注册名单", List.of(), null), safeName, leagueName, null, season);
            }

            int targetSeason = season > 2000 ? season : LocalDate.now(BUSINESS_ZONE).getYear();
            Map<String, Object> raw = apiFootballClient.getTeamPlayers(resolvedTeamId, targetSeason).block();
            Map<String, Object> errors = raw == null || !(raw.get("errors") instanceof Map<?, ?> map)
                    ? Collections.emptyMap() : (Map<String, Object>) map;
            if (!errors.isEmpty()) {
                String detail = errors.values().stream().map(String::valueOf).findFirst().orElse("阵容源返回错误");
                String status = detail.toLowerCase(Locale.ROOT).matches(".*(quota|limit|plan|daily).*" ) ? "QUOTA_LIMITED" : "REQUEST_FAILED";
                return cacheSquad(cacheKey, squadPayload(status, detail, List.of(), resolvedTeamId), safeName, leagueName, resolvedTeamId, season);
            }
            List<Map<String, Object>> players = normalizeSquadResponse(raw == null ? null : raw.get("response"));
            String status = players.isEmpty() ? "EMPTY" : "AVAILABLE";
            String message = players.isEmpty() ? "阵容源未返回可核验的注册名单" : "已加载第三方阵容数据";
            return cacheSquad(cacheKey, squadPayload(status, message, players, resolvedTeamId), safeName, leagueName, resolvedTeamId, season);
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "阵容请求失败" : e.getMessage();
            String status = detail.toLowerCase(Locale.ROOT).matches(".*(quota|limit|plan|daily).*" ) ? "QUOTA_LIMITED" : "REQUEST_FAILED";
            return cacheSquad(cacheKey, squadPayload(status, detail, List.of(), null), safeName, leagueName, null, season);
        }
    }

    private Map<String, Object> cacheSquad(String key, Map<String, Object> payload, String teamName, String leagueName, Long teamId, int season) {
        return cacheSquad(key, payload, teamName, leagueName, teamId == null ? null : String.valueOf(teamId), season, "api-football");
    }

    private Map<String, Object> cacheSquad(String key, Map<String, Object> payload, String teamName, String leagueName,
                                           String teamId, int season, String source) {
        Object dataObject = payload.get("data");
        String status = dataObject instanceof Map<?, ?> data ? String.valueOf(data.get("status") == null ? "" : data.get("status")) : "";
        long ttl = "AVAILABLE".equals(status) || "EMPTY".equals(status)
                ? 12 * 60 * 60 * 1000L
                : "QUOTA_LIMITED".equals(status) ? 30 * 60 * 1000L : 5 * 60 * 1000L;
        if (dataObject instanceof Map<?, ?> data) {
            ((Map<String, Object>) data).put("lastSyncedAt", LocalDateTime.now(BUSINESS_ZONE).toString());
            ((Map<String, Object>) data).put("cacheState", "FRESH");
        }
        // 外部源临时失败时不要用空错误结果覆盖进程内最近一次有效名单。
        // 数据库层同样会保留 AVAILABLE 记录，下一次普通读取仍可走 stale cache。
        boolean preserveExisting = isTransientSquadStatus(status)
                && (hasUsableSquad(squadCache.get(key)) || hasPersistedUsableSquad(key));
        if (!preserveExisting) {
            squadCache.put(key, new SquadCacheEntry(payload, System.currentTimeMillis() + ttl));
        }
        persistSquad(key, teamName, leagueName, teamId, season, payload, ttl, source);
        return payload;
    }

    private boolean isTransientSquadStatus(String status) {
        return "REQUEST_FAILED".equals(status) || "QUOTA_LIMITED".equals(status) || "NOT_CONFIGURED".equals(status);
    }

    @SuppressWarnings("unchecked")
    private boolean hasUsableSquad(SquadCacheEntry entry) {
        if (entry == null || entry.expiresAt() <= System.currentTimeMillis()) return false;
        Object data = entry.payload().get("data");
        if (!(data instanceof Map<?, ?> map) || !"AVAILABLE".equals(String.valueOf(map.get("status")))) return false;
        return map.get("response") instanceof List<?> players && !players.isEmpty();
    }

    private boolean hasPersistedUsableSquad(String cacheKey) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, squad_json FROM t_team_squad_cache WHERE cache_key = ? LIMIT 1", cacheKey);
            if (rows.isEmpty() || !"AVAILABLE".equals(String.valueOf(rows.get(0).get("status")))) return false;
            Object json = rows.get(0).get("squad_json");
            return json != null && !String.valueOf(json).isBlank() && !"[]".equals(String.valueOf(json).trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Object> squadPayload(String status, String message, List<Map<String, Object>> players, Long teamId) {
        return squadPayload(status, message, players, teamId == null ? "" : String.valueOf(teamId), "api-football");
    }

    private Map<String, Object> squadPayload(String status, String message, List<Map<String, Object>> players,
                                             String teamId, String source) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("message", message);
        data.put("teamId", teamId == null ? "" : teamId);
        data.put("source", source == null ? "" : source);
        data.put("response", players == null ? List.of() : players);
        data.put("results", players == null ? 0 : players.size());
        return Map.of("success", true, "message", "获取阵容资料", "data", data);
    }

    /** 读取数据库缓存；allowExpired 仅用于 stale-while-revalidate 展示。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPersistedSquad(String cacheKey, boolean allowExpired) {
        try {
            String sql = "SELECT status, message, external_team_id, source, squad_json, fetched_at, expires_at "
                    + "FROM t_team_squad_cache WHERE cache_key = ? "
                    // stale-while-revalidate 只允许返回曾经成功抓到的名单；
                    // 请求失败/额度受限不能伪装成可展示的过期阵容。
                    + (allowExpired ? "AND status IN ('AVAILABLE', 'EMPTY') " : "AND (expires_at IS NULL OR expires_at > NOW()) ")
                    + "LIMIT 1";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, cacheKey);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            List<Map<String, Object>> players = List.of();
            Object json = row.get("squad_json");
            if (json != null && !String.valueOf(json).isBlank()) {
                players = objectMapper.readValue(String.valueOf(json), new TypeReference<List<Map<String, Object>>>() {});
            }
            String status = String.valueOf(row.getOrDefault("status", "EMPTY"));
            String message = String.valueOf(row.getOrDefault("message", ""));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", status);
            data.put("message", message);
            data.put("teamId", row.getOrDefault("external_team_id", ""));
            ensureSquadPhotoFallbacks(players);
            data.put("response", players);
            data.put("results", players.size());
            data.put("lastSyncedAt", row.get("fetched_at"));
            data.put("expiresAt", row.get("expires_at"));
            data.put("cacheState", allowExpired ? "STALE" : "PERSISTED");
            data.put("source", row.getOrDefault("source", ""));
            return Map.of("success", true, "message", "读取缓存的球队阵容", "data", data);
        } catch (Exception ex) {
            log.debug("读取球队阵容缓存失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Refresh a small batch of rosters whose cache is about to expire. The
     * public endpoint remains database-first; this task is the only routine
     * that proactively contacts the external roster source.
     */
    @Scheduled(initialDelayString = "${crawler.squad-refresh-initial-delay-ms:120000}",
            fixedDelayString = "${crawler.squad-refresh-fixed-delay-ms:21600000}")
    public void refreshSquadCache() {
        try {
            List<Map<String, Object>> due = jdbcTemplate.queryForList(
                    "SELECT team_name, league_name, external_team_id, season, source FROM t_team_squad_cache "
                            + "WHERE status IN ('AVAILABLE', 'EMPTY') AND (expires_at IS NULL OR expires_at <= DATE_ADD(NOW(), INTERVAL 6 HOUR)) "
                            + "ORDER BY expires_at ASC LIMIT 12");
            for (Map<String, Object> row : due) {
                String name = Objects.toString(row.get("team_name"), "").trim();
                if (name.isBlank()) continue;
                String leagueName = Objects.toString(row.get("league_name"), "").trim();
                String externalTeamId = Objects.toString(row.get("external_team_id"), "").trim();
                String source = Objects.toString(row.get("source"), "").trim();
                // ESPN 的球队编号与 API-Football 不同，不能在 ESPN 临时失败时
                // 误把 ESPN ID 当作 API-Football ID 使用。
                if (!source.startsWith("api-football")) externalTeamId = "";
                int cachedSeason = 0;
                try { cachedSeason = Integer.parseInt(String.valueOf(row.getOrDefault("season", "0"))); }
                catch (NumberFormatException ignored) { }
                getTeamSquad(name, leagueName, externalTeamId, cachedSeason, true);
            }
            if (!due.isEmpty()) log.info("[SquadCache] 定时刷新 {} 支即将过期的球队阵容", due.size());
        } catch (Exception ex) {
            // Cache refresh is best-effort and must never affect match APIs.
            log.warn("[SquadCache] 定时刷新失败: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void persistSquad(String cacheKey, String teamName, String leagueName, Long teamId, int season,
                              Map<String, Object> payload, long ttl) {
        persistSquad(cacheKey, teamName, leagueName, teamId == null ? null : String.valueOf(teamId), season, payload, ttl, "api-football");
    }

    private void persistSquad(String cacheKey, String teamName, String leagueName, String teamId, int season,
                              Map<String, Object> payload, long ttl, String source) {
        if (teamName == null || teamName.isBlank()) return;
        try {
            Map<String, Object> data = payload.get("data") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            String status = String.valueOf(data.getOrDefault("status", "EMPTY"));
            String message = String.valueOf(data.getOrDefault("message", ""));
            Object response = data.getOrDefault("response", List.of());
            String squadJson = objectMapper.writeValueAsString(response);
            if (isTransientSquadStatus(status)) {
                List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                        "SELECT status, squad_json FROM t_team_squad_cache WHERE cache_key = ? LIMIT 1", cacheKey);
                if (!existing.isEmpty()
                        && "AVAILABLE".equals(String.valueOf(existing.get(0).get("status")))
                        && existing.get(0).get("squad_json") != null
                        && !String.valueOf(existing.get(0).get("squad_json")).isBlank()
                        && !"[]".equals(String.valueOf(existing.get(0).get("squad_json")).trim())) {
                    log.warn("[SquadCache] 保留球队 {} 的最近有效阵容，跳过本次 {} 错误覆盖", teamName, status);
                    return;
                }
            }
            LocalDateTime fetchedAt = LocalDateTime.now(BUSINESS_ZONE);
            LocalDateTime expiresAt = fetchedAt.plusSeconds(Math.max(60L, ttl / 1000L));
            jdbcTemplate.update("INSERT INTO t_team_squad_cache (cache_key, team_name, league_name, external_team_id, season, status, message, source, squad_json, fetched_at, expires_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE team_name=VALUES(team_name), league_name=VALUES(league_name), external_team_id=VALUES(external_team_id), season=VALUES(season), status=VALUES(status), message=VALUES(message), source=VALUES(source), squad_json=VALUES(squad_json), fetched_at=NOW(), expires_at=VALUES(expires_at)",
                    cacheKey, teamName, leagueName == null ? "" : leagueName, teamId == null ? "" : teamId, season, status, message, source == null ? "" : source, squadJson, fetchedAt, expiresAt);
        } catch (Exception ex) {
            log.debug("写入球队阵容缓存失败: {}", ex.getMessage());
        }
    }

    private long persistedSquadExpiry(Map<String, Object> payload) {
        Object dataObject = payload.get("data");
        String status = "";
        if (dataObject instanceof Map<?, ?> data) {
            Object rawStatus = data.get("status");
            status = rawStatus == null ? "" : String.valueOf(rawStatus);
            Object rawExpiresAt = data.get("expiresAt");
            if (rawExpiresAt instanceof Date expiry) return expiry.getTime();
        }
        long ttl = "AVAILABLE".equals(status) || "EMPTY".equals(status) ? 12 * 60 * 60 * 1000L
                : "QUOTA_LIMITED".equals(status) ? 30 * 60 * 1000L : 5 * 60 * 1000L;
        return System.currentTimeMillis() + ttl;
    }

    /** Add portrait URLs to old cached ESPN rows created before avatar support. */
    private void ensureSquadPhotoFallbacks(List<Map<String, Object>> players) {
        if (players == null) return;
        for (Map<String, Object> player : players) {
            if (player == null) continue;
            String existing = Objects.toString(player.get("photo"), "").trim();
            String id = Objects.toString(player.get("id"), "").trim();
            if (existing.isBlank() && !id.isBlank()) {
                player.put("photo", "https://a.espncdn.com/i/headshots/soccer/players/full/" + id + ".png");
                player.put("photoSource", "espn-headshot");
            }
        }
    }

    private Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** API-Football 球队 ID 通常是小整数；BBC 等主爬虫源的长字符串数字 ID 不能直接复用。 */
    private Long parseApiTeamId(String value) {
        Long parsed = parsePositiveLong(value);
        return parsed != null && parsed <= 100000 ? parsed : null;
    }

    private Long resolveTeamIdFromRecentMatches(String teamName) {
        List<CrawlerMatch> recent = visibleSourceMatches(crawlerMatchMapper.findRecentByTeamName(teamName, 20));
        for (CrawlerMatch match : recent) {
            Long home = parseApiTeamId(match.getHomeTeamName() != null && teamName.equals(match.getHomeTeamName()) ? match.getHomeTeamId() : null);
            if (home != null) return home;
            Long away = parseApiTeamId(match.getAwayTeamName() != null && teamName.equals(match.getAwayTeamName()) ? match.getAwayTeamId() : null);
            if (away != null) return away;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Long resolveTeamIdBySearch(String teamName) {
        try {
            Map<String, Object> raw = apiFootballClient.searchTeams(teamName).block();
            Object response = raw == null ? null : raw.get("response");
            if (!(response instanceof List<?> list)) return null;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) continue;
                Object team = row.get("team");
                if (!(team instanceof Map<?, ?> teamMap)) continue;
                Long id = parsePositiveLong(String.valueOf(teamMap.get("id")));
                if (id != null) return id;
            }
        } catch (Exception ignored) {
            // 搜索失败时返回明确的未配置/不可用状态，不影响比赛主链路。
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeSquadResponse(Object response) {
        if (!(response instanceof List<?> list)) return List.of();
        List<Map<String, Object>> players = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) continue;
            Object playerObject = row.get("player");
            if (!(playerObject instanceof Map<?, ?> player)) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", player.get("id"));
            result.put("name", player.get("name"));
            result.put("firstname", player.get("firstname"));
            result.put("lastname", player.get("lastname"));
            result.put("age", player.get("age"));
            result.put("nationality", player.get("nationality"));
            result.put("photo", player.get("photo"));
            Object statisticsObject = row.get("statistics");
            if (statisticsObject instanceof List<?> statistics && !statistics.isEmpty() && statistics.get(0) instanceof Map<?, ?> statsRow) {
                Object gamesObject = statsRow.get("games");
                if (gamesObject instanceof Map<?, ?> games) {
                    result.put("position", games.get("position"));
                    result.put("number", games.get("number"));
                    result.put("appearances", games.get("appearences"));
                    result.put("starts", games.get("lineups"));
                }
                Object goalsObject = statsRow.get("goals");
                if (goalsObject instanceof Map<?, ?> goals) {
                    result.put("goals", goals.get("total"));
                    result.put("assists", goals.get("assists"));
                }
            }
            players.add(result);
        }
        return players;
    }

    private record SquadCacheEntry(Map<String, Object> payload, long expiresAt) {}

    /**
     * 手动触发爬取
     */
    @PostMapping("/trigger")
    public Map<String, Object> triggerCrawl(@RequestParam(name = "type", defaultValue = "matches") String type) {
        AdminGuard.requirePermission("CRAWLER");
        try {
            Object result = switch (type) {
                case "matches" -> {
                    List<CrawlerMatch> matches = matchCrawlerService.crawlTodayMatches();
                    predictionPrecomputeService.schedule(matches);
                    yield matches;
                }
                case "standings" -> standingCrawlerService.crawlAllStandings();
                case "upcoming" -> {
                    List<CrawlerMatch> matches = matchCrawlerService.crawlUpcomingMatches();
                    predictionPrecomputeService.schedule(matches);
                    yield matches;
                }
                case "live" -> {
                    matchCrawlerService.updateMatchScores();
                    yield "live-updated";
                }
                default -> throw new IllegalArgumentException("不支持的爬取类型: " + type);
            };
            return Map.of("success", true, "message", "爬取任务已触发", "data", Map.of("result", String.valueOf(result)));
        } catch (Exception e) {
            return Map.of("success", false, "message", "触发失败: " + e.getMessage());
        }
    }

    /**
     * 获取体育站首页汇总数据
     */
    @GetMapping("/dashboard")
    public Map<String, Object> getDashboard() {
        try {
            List<CrawlerMatch> todayMatches = matchCrawlerService.getTodayMatches();
            List<CrawlerMatch> liveMatches = visibleSourceMatches(crawlerMatchMapper.findLiveMatches());
            List<CrawlerMatch> upcomingMatches = visibleSourceMatches(crawlerMatchMapper.findUpcomingMatches());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("todayMatches", formatMatches(todayMatches));
            data.put("liveMatches", formatMatches(liveMatches));
            data.put("upcomingMatches", formatMatches(upcomingMatches));
            data.put("summary", Map.of(
                    "today", todayMatches.size(),
                    "live", liveMatches.size(),
                    "upcoming", upcomingMatches.size(),
                    "todayDbCount", matchCrawlerService.countMatchesByDate(new Date())
            ));

            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 根据数据库获取联赛比赛
     */
    @GetMapping("/matches/league/{leagueName}/db")
    public Map<String, Object> getMatchesByLeagueFromDb(@PathVariable("leagueName") String leagueName) {
        try {
            List<CrawlerMatch> matches = matchCrawlerService.getMatchesByLeagueFromDb(leagueName);
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 搜索比赛
     */
    @GetMapping("/matches/search")
    public Map<String, Object> searchMatches(@RequestParam("keyword") String keyword) {
        try {
            List<CrawlerMatch> matches = matchCrawlerService.searchMatches(keyword);
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 获取爬虫健康状态
     *
     * 该接口已迁移到 `CrawlerHealthController`，这里保留一个兼容别名，避免与新控制器冲突。
     */
    @GetMapping("/status")
    public Map<String, Object> getHealth() {
        return Map.of("success", true, "message", "获取成功", "data", Map.of("status", "ok"));
    }



    /**
     * 按联赛名称获取积分榜
     */
    @GetMapping("/standings/league/{leagueName}")
    public Map<String, Object> getStandingsByLeagueName(@PathVariable("leagueName") String leagueName,
                                                        @RequestParam(name = "season", required = false) String season) {
        try {
            List<CrawlerStanding> standings = standingCrawlerService.getStandingsByLeagueName(leagueName, season);
            return buildStandingsResponse(standings);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /** 获取联赛已有积分榜赛季，前端只展示真实可用的赛季选项。 */
    @GetMapping("/standings/league/{leagueName}/seasons")
    public Map<String, Object> getStandingSeasons(@PathVariable("leagueName") String leagueName) {
        try {
            List<String> seasons = standingCrawlerService.getSeasonsByLeagueName(leagueName);
            return Map.of("success", true, "message", "获取成功", "data", Map.of("response", seasons, "results", seasons.size()));
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /** 积分榜专属健康视图，不与比赛数据源健康状态混用。 */
    @GetMapping("/standings/health")
    public Map<String, Object> getStandingsHealth() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("primaryOnly", dataSourceManager.isPrimaryOnly());
        data.put("primarySource", dataSourceManager.primarySource());
        data.put("leagues", standingCrawlerService.getHealthSnapshot());
        data.put("checkedAt", java.time.Instant.now().toString());
        return Map.of("success", true, "message", "获取成功", "data", data);
    }

    /** 管理员按联赛补同步积分榜，前台刷新按钮只读数据库，不会匿名消耗外部额度。 */
    @PostMapping("/standings/league/{leagueName}/refresh")
    public Map<String, Object> refreshStandings(@PathVariable("leagueName") String leagueName) {
        AdminGuard.requirePermission("CRAWLER");
        try {
            List<CrawlerStanding> standings = standingCrawlerService.crawlStandingsByLeague(leagueName);
            return buildStandingsResponse(standings);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 两队交锋记录
     */
    @GetMapping("/matches/h2h")
    public Map<String, Object> getHeadToHead(@RequestParam("homeTeam") String homeTeam,
                                             @RequestParam("awayTeam") String awayTeam,
                                             @RequestParam(name = "limit", defaultValue = "10") int limit) {
        try {
            List<CrawlerMatch> matches = matchCrawlerService.getHeadToHead(homeTeam, awayTeam, limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("homeTeam", homeTeam);
            data.put("awayTeam", awayTeam);
            data.put("summary", buildHeadToHeadSummary(matches, homeTeam, awayTeam));
            data.put("matches", formatMatches(matches));
            data.put("results", matches.size());
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /** Apply the same source policy to legacy database reads as to crawler writes. */
    private void applyPrimarySourceFilter(LambdaQueryWrapper<CrawlerMatch> query) {
        if (dataSourceManager.isPrimaryOnly()) {
            query.eq(CrawlerMatch::getSource, dataSourceManager.primarySource());
        }
    }

    private void applyProductionLeagueFilter(LambdaQueryWrapper<CrawlerMatch> query) {
        query.and(q -> q.in(CrawlerMatch::getLeagueId, PRODUCTION_LEAGUE_IDS)
                .or().and(w -> w.in(CrawlerMatch::getLeagueName, PRODUCTION_LEAGUE_NAMES)
                        .and(z -> z.isNull(CrawlerMatch::getLeagueId).or().eq(CrawlerMatch::getLeagueId, ""))));
    }

    private List<CrawlerMatch> visibleSourceMatches(List<CrawlerMatch> matches) {
        if (matches == null || matches.isEmpty()) return Collections.emptyList();
        return matches.stream()
                .filter(Objects::nonNull)
                .filter(match -> dataSourceManager.isSourceEnabled(match.getSource()))
                .filter(match -> !com.chen.football.common.dto.MatchStatus.SOURCE_REMOVED.equals(
                        com.chen.football.common.dto.MatchStatus.normalize(match.getStatus())))
                .filter(match -> ProductionLeagueScope.isSupported(match.getLeagueId(), match.getLeagueName()))
                .toList();
    }

    private List<Map<String, Object>> formatMatches(List<CrawlerMatch> matches) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (CrawlerMatch match : matches) {
            Map<String, Object> item = new LinkedHashMap<>();

            // fixture
            Map<String, Object> fixture = new LinkedHashMap<>();
            // The public route key is always our local row id.  Provider IDs
            // are metadata only; mixing them into fixture.id caused old
            // clients to request the wrong match after source changes.
            fixture.put("id", match.getId());
            fixture.put("publicMatchId", match.getId() == null ? null : String.valueOf(match.getId()));
            fixture.put("providerFixtureId", match.getFixtureId());
            fixture.put("externalMatchId", match.getExternalMatchId());
            OffsetDateTime matchDate = match.getMatchTime() == null
                    ? null
                    : match.getMatchTime().atZone(BUSINESS_ZONE).toOffsetDateTime();
            fixture.put("timestamp", matchDate != null ? matchDate.toInstant().toEpochMilli() / 1000 : 0);
            fixture.put("timezone", BUSINESS_ZONE.getId());
            fixture.put("date", matchDate != null ? matchDate.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null);
            fixture.put("status", Map.of("short", match.getStatus() != null ? match.getStatus() : "NS", "long", getStatusText(match.getStatus())));

            Map<String, Object> venue = new LinkedHashMap<>();
            venue.put("name", match.getVenue() != null ? match.getVenue() : "");
            fixture.put("venue", venue);

            item.put("fixture", fixture);

            // league
            Map<String, Object> league = new LinkedHashMap<>();
            league.put("id", match.getLeagueId() != null ? match.getLeagueId() : "0");
            league.put("name", leagueNameNormalizer.normalize(match.getLeagueName(), match.getLeagueId(), match.getSource()));
            league.put("logo", "");
            league.put("round", match.getRound() != null ? match.getRound() : "");
            item.put("league", league);

            // teams
            Map<String, Object> teams = new LinkedHashMap<>();

            Map<String, Object> home = new LinkedHashMap<>();
            home.put("id", match.getHomeTeamId() != null ? match.getHomeTeamId() : "0");
            home.put("name", match.getHomeTeamName() != null ? match.getHomeTeamName() : "主队");
            home.put("logo", match.getHomeTeamLogo() != null ? match.getHomeTeamLogo() : "");

            Map<String, Object> away = new LinkedHashMap<>();
            away.put("id", match.getAwayTeamId() != null ? match.getAwayTeamId() : "0");
            away.put("name", match.getAwayTeamName() != null ? match.getAwayTeamName() : "客队");
            away.put("logo", match.getAwayTeamLogo() != null ? match.getAwayTeamLogo() : "");

            teams.put("home", home);
            teams.put("away", away);
            item.put("teams", teams);

            // goals
            Map<String, Object> goals = new LinkedHashMap<>();
            goals.put("home", match.getHomeScore() != null ? match.getHomeScore() : null);
            goals.put("away", match.getAwayScore() != null ? match.getAwayScore() : null);
            item.put("goals", goals);

            item.put("matchDate", match.getMatchTime() == null ? null : match.getMatchTime().toLocalDate().toString());
            // Keep the legacy field, but source it from the same explainable
            // ranking used by /matches/recommendations. This prevents the
            // match list and focus rail from showing two different notions of
            // "hot" for the same fixture.
            item.put("hotScore", matchRecommendationService.displayScore(match));
            // Keep the local row ID separate from the provider fixture ID. The
            // admin editor/delete endpoints address the database row, while
            // prediction/detail links use fixtureId/externalMatchId.
            item.put("id", match.getId());
            // Canonical public route key.  It is deliberately the local row id,
            // not a provider fixture id: BBC and other sources may not expose a
            // numeric fixture id, while prediction/favorite/reminder tables use
            // this stable internal key consistently.
            item.put("matchId", match.getId());
            item.put("publicMatchId", match.getId() == null ? null : String.valueOf(match.getId()));
            item.put("fixtureId", match.getFixtureId());

            // source
            item.put("source", match.getSource());
            // Stable source identifier used by prediction/detail links and admin reconciliation.
            // Keep it separate from fixture.id: fixture.id is the internal numeric key when a
            // source does not provide one, while externalMatchId is the source's own identity.
            item.put("externalMatchId", match.getExternalMatchId());

            result.add(item);
        }

        return result;
    }

    private boolean isLiveMatch(CrawlerMatch match) {
        String status = match == null ? "" : String.valueOf(match.getStatus()).toUpperCase(Locale.ROOT);
        return "LIVE".equals(status) || "IN_PLAY".equals(status) || "1H".equals(status) || "2H".equals(status) || "HT".equals(status);
    }

    private boolean isFinishedStatus(String status) {
        String value = status == null ? "" : status.toUpperCase(Locale.ROOT);
        return "FT".equals(value) || "AET".equals(value) || "PEN".equals(value) || "FINISHED".equals(value)
                || "AWARDED".equals(value) || "CANCELLED".equals(value) || "CANCELED".equals(value);
    }

    private boolean isSameDate(LocalDateTime value, LocalDate date) {
        return value != null && value.toLocalDate().equals(date);
    }

    /**
     * 格式化积分榜数据
     */
    private Map<String, Object> formatMatchDetail(CrawlerMatch match) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", match.getId());
        data.put("matchId", match.getId());
        data.put("publicMatchId", match.getId() == null ? null : String.valueOf(match.getId()));
        data.put("fixtureId", match.getFixtureId());
        data.put("externalMatchId", match.getExternalMatchId());
        data.put("identity", Map.of(
                "publicMatchId", match.getId() == null ? "" : String.valueOf(match.getId()),
                "providerFixtureId", match.getFixtureId() == null ? "" : String.valueOf(match.getFixtureId()),
                "externalMatchId", match.getExternalMatchId() == null ? "" : match.getExternalMatchId(),
                "source", match.getSource() == null ? "" : match.getSource()
        ));
        data.put("leagueName", match.getLeagueName());
        data.put("leagueId", match.getLeagueId());
        data.put("homeTeamName", match.getHomeTeamName());
        data.put("homeTeamId", match.getHomeTeamId());
        data.put("homeTeamLogo", match.getHomeTeamLogo());
        data.put("awayTeamName", match.getAwayTeamName());
        data.put("awayTeamId", match.getAwayTeamId());
        data.put("awayTeamLogo", match.getAwayTeamLogo());
        data.put("homeScore", match.getHomeScore());
        data.put("awayScore", match.getAwayScore());
        data.put("status", match.getStatus());
        data.put("matchTime", match.getMatchTime() == null ? null : match.getMatchTime().atZone(BUSINESS_ZONE).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("venue", match.getVenue());
        data.put("round", match.getRound());
        data.put("source", match.getSource());
        return data;
    }

    private Map<String, Object> buildTeamForm(List<CrawlerMatch> matches, String teamName) {
        int wins = 0;
        int draws = 0;
        int losses = 0;
        int goalsFor = 0;
        int goalsAgainst = 0;
        int homeWins = 0;
        int homeDraws = 0;
        int homeLosses = 0;
        int awayWins = 0;
        int awayDraws = 0;
        int awayLosses = 0;
        StringBuilder recentForm = new StringBuilder();

        for (CrawlerMatch match : matches) {
            boolean isHome = teamName != null && teamName.equals(match.getHomeTeamName());
            Integer gf = isHome ? match.getHomeScore() : match.getAwayScore();
            Integer ga = isHome ? match.getAwayScore() : match.getHomeScore();
            if (gf != null) goalsFor += gf;
            if (ga != null) goalsAgainst += ga;
            if (gf != null && ga != null) {
                char formChar;
                if (gf > ga) {
                    wins++;
                    formChar = 'W';
                    if (isHome) {
                        homeWins++;
                    } else {
                        awayWins++;
                    }
                } else if (gf.equals(ga)) {
                    draws++;
                    formChar = 'D';
                    if (isHome) {
                        homeDraws++;
                    } else {
                        awayDraws++;
                    }
                } else {
                    losses++;
                    formChar = 'L';
                    if (isHome) {
                        homeLosses++;
                    } else {
                        awayLosses++;
                    }
                }
                if (recentForm.length() < 5) {
                    recentForm.append(formChar);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matches", matches.size());
        data.put("wins", wins);
        data.put("draws", draws);
        data.put("losses", losses);
        data.put("goalsFor", goalsFor);
        data.put("goalsAgainst", goalsAgainst);
        data.put("recentForm", recentForm.toString());
        data.put("home", Map.of("wins", homeWins, "draws", homeDraws, "losses", homeLosses));
        data.put("away", Map.of("wins", awayWins, "draws", awayDraws, "losses", awayLosses));
        return data;
    }

    private Map<String, Object> buildHeadToHeadSummary(List<CrawlerMatch> matches, String homeTeam, String awayTeam) {
        int homeWins = 0;
        int awayWins = 0;
        int draws = 0;
        for (CrawlerMatch match : matches) {
            Integer homeScore = match.getHomeScore();
            Integer awayScore = match.getAwayScore();
            if (homeScore == null || awayScore == null) {
                continue;
            }
            if (homeTeam.equals(match.getHomeTeamName()) && awayTeam.equals(match.getAwayTeamName())) {
                if (homeScore > awayScore) homeWins++;
                else if (homeScore < awayScore) awayWins++;
                else draws++;
            } else if (homeTeam.equals(match.getAwayTeamName()) && awayTeam.equals(match.getHomeTeamName())) {
                if (awayScore > homeScore) homeWins++;
                else if (awayScore < homeScore) awayWins++;
                else draws++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("homeWins", homeWins);
        data.put("awayWins", awayWins);
        data.put("draws", draws);
        data.put("total", matches.size());
        return data;
    }

    private List<Map<String, Object>> formatStandings(List<CrawlerStanding> standings) {
        List<Map<String, Object>> result = new ArrayList<>();
        int total = standings == null ? 0 : standings.size();
        String leagueName = standings == null ? "" : standings.stream()
                .map(CrawlerStanding::getLeagueName)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        String season = standings == null ? "" : standings.stream()
                .map(CrawlerStanding::getSeason)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");

        for (CrawlerStanding standing : standings == null ? List.<CrawlerStanding>of() : standings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", standing.getRank());
            String teamId = standing.getTeamId() != null ? standing.getTeamId() : "";
            String teamName = standing.getTeamName() != null ? standing.getTeamName() : "";
            item.put("team", Map.of(
                    "id", teamId,
                    "canonicalKey", identityMappingService.teamKey(teamId, teamName),
                    "name", teamName,
                    "logo", standing.getTeamLogo() != null ? standing.getTeamLogo() : ""
            ));
            item.put("canonicalKey", identityMappingService.teamKey(teamId, teamName));
            StandingZoneRules.Zone zone = StandingZoneRules.resolve(leagueName, season, standing.getRank(), total);
            item.put("zone", zone.code());
            item.put("zoneLabel", zone.label());
            item.put("played", standing.getPlayed());
            item.put("win", standing.getWins());
            item.put("draw", standing.getDraws());
            item.put("loss", standing.getLosses());
            item.put("goalsFor", standing.getGoalsFor());
            item.put("goalsAgainst", standing.getGoalsAgainst());
            item.put("goalDifference", standing.getGoalDifference());
            item.put("points", standing.getPoints());
            item.put("season", standing.getSeason());
            item.put("source", standing.getSource());
            item.put("updatedAt", standing.getUpdatedAt());
            List<CrawlerMatch> recent = teamName.isBlank() ? List.of() : matchCrawlerService.getRecentMatchesByTeam(teamName, 5);
            Map<String, Object> form = buildTeamForm(recent, teamName);
            item.put("recentForm", form.getOrDefault("recentForm", ""));
            item.put("recentMatches", formatMatches(recent));
            item.put("formStats", form);

            result.add(item);
        }

        return result;
    }

    private Map<String, Object> buildStandingsResponse(List<CrawlerStanding> standings) {
        List<CrawlerStanding> safeStandings = standings == null ? List.of() : standings;
        List<Map<String, Object>> rows = formatStandings(safeStandings);
        String leagueName = safeStandings.stream()
                .map(CrawlerStanding::getLeagueName)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        String season = safeStandings.stream()
                .map(CrawlerStanding::getSeason)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("response", rows);
        data.put("results", rows.size());
        data.put("dataQuality", standingQuality(safeStandings));
        data.put("zoneRules", StandingZoneRules.describe(leagueName, season, rows.size()));
        return Map.of("success", true, "message", "获取成功", "data", data);
    }

    private Map<String, Object> standingQuality(List<CrawlerStanding> standings) {
        Map<String, Object> quality = new LinkedHashMap<>();
        if (standings == null || standings.isEmpty()) {
            quality.put("status", "NO_DATA");
            quality.put("statusText", "暂无积分榜");
            quality.put("message", "该联赛当前没有已同步的积分榜数据");
            quality.put("source", "");
            return quality;
        }
        LocalDateTime updatedAt = standings.stream().map(CrawlerStanding::getUpdatedAt)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        long ageMinutes = updatedAt == null ? -1 : Math.max(0, ChronoUnit.MINUTES.between(updatedAt, LocalDateTime.now(BUSINESS_ZONE)));
        boolean hasPlayedData = standings.stream().anyMatch(row -> number(row.getPlayed()) > 0
                || number(row.getPoints()) > 0 || number(row.getWins()) > 0
                || number(row.getDraws()) > 0 || number(row.getLosses()) > 0);
        String status = ageMinutes >= 0 && ageMinutes > 72 * 60
                ? "STALE"
                : hasPlayedData ? "AVAILABLE" : "PRESEASON";
        quality.put("status", status);
        quality.put("statusText", "STALE".equals(status) ? "数据较旧" : "PRESEASON".equals(status) ? "赛季尚未产生积分" : "数据可用");
        quality.put("message", "STALE".equals(status)
                ? "积分榜超过 72 小时未更新，请谨慎参考"
                : "PRESEASON".equals(status) ? "已返回 " + standings.size() + " 支球队，当前赛季尚未产生可验证积分"
                : "已返回 " + standings.size() + " 支球队");
        quality.put("source", standings.stream().map(CrawlerStanding::getSource).filter(Objects::nonNull).filter(v -> !v.isBlank()).findFirst().orElse("unknown"));
        quality.put("lastSyncedAt", updatedAt == null ? "" : updatedAt.toString());
        quality.put("ageMinutes", ageMinutes);
        quality.put("season", standings.stream().map(CrawlerStanding::getSeason).filter(Objects::nonNull).findFirst().orElse(""));
        return quality;
    }

    private int number(Integer value) {
        return value == null ? 0 : value;
    }

    private String getStatusText(String status) {
        if (status == null) return "未开始";
        return switch (status) {
            case "LIVE" -> "进行中";
            case "HT" -> "中场";
            case "FT" -> "完场";
            case "1H" -> "上半场";
            case "2H" -> "下半场";
            case "CANCEL" -> "取消";
            case "POSTP" -> "推迟";
            case "SOURCE_REMOVED" -> "赛程已变更";
            default -> "未开始";
        };
    }

    private String getCurrentSeason() {
        int year = LocalDate.now(BUSINESS_ZONE).getYear();
        return year + "/" + (year + 1);
    }

    private Map<String, Object> buildSuccessResponse(List<? extends Object> response, int results) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("response", response);
        data.put("results", results);
        data.put("dataQuality", dataQuality(results));
        return Map.of(
                "success", true,
                "message", "获取成功",
                "data", data
        );
    }

    private Map<String, Object> buildFailureResponse(Exception e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("response", Collections.emptyList());
        data.put("results", 0);
        data.put("dataQuality", Map.of("status", "SYNC_FAILED", "statusText", "数据同步失败", "message", e.getMessage() == null ? "请求失败" : e.getMessage()));
        return Map.of(
                "success", false,
                "message", "获取失败: " + e.getMessage(),
                "data", data
        );
    }

    private Map<String, Object> dataQuality(int results) {
        Map<String, Object> quality = new LinkedHashMap<>();
        List<Map<String, Object>> providers = dataSourceManager.snapshot().get("providers") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(v -> (Map<String, Object>) v).toList() : List.of();
        String primaryName = String.valueOf(dataSourceManager.snapshot().getOrDefault("primarySource", ""));
        Map<String, Object> primary = providers.stream()
                .filter(v -> primaryName.equalsIgnoreCase(String.valueOf(v.getOrDefault("name", ""))))
                .findFirst().orElse(providers.isEmpty() ? Map.of() : providers.get(0));
        String primaryStatus = String.valueOf(primary.getOrDefault("status", "UNKNOWN"));
        Number age = primary.get("dataAgeSeconds") instanceof Number n ? n : null;
        // Home is intentionally backed by the primary crawler only. A disabled
        // secondary provider must not make a healthy primary source look broken.
        boolean limited = "QUOTA_LIMITED".equals(primaryStatus);
        boolean failed = "REQUEST_FAILED".equals(primaryStatus);
        boolean notCovered = "UNKNOWN".equals(primaryStatus)
                || (age != null && age.longValue() > 36 * 60 * 60);
        boolean stale = age == null || age.longValue() > 6 * 60 * 60;
        quality.put("source", primaryName.isBlank() ? "主爬虫源" : primaryName);
        quality.put("sourceStatus", primaryStatus);
        quality.put("coverageStatus", primary.getOrDefault("coverageStatus", "NOT_CHECKED"));
        quality.put("dataAgeSeconds", age == null ? null : age.longValue());
        quality.put("lastSuccess", primary.getOrDefault("lastSuccess", null));
        quality.put("checkedAt", java.time.Instant.now().toString());
        if (results > 0) {
            String status = limited ? "PARTIAL_SOURCE_LIMITED" : failed ? "PARTIAL_SYNC_FAILED" : stale ? "AVAILABLE_STALE" : "AVAILABLE";
            quality.put("status", status);
            quality.put("freshness", stale ? "STALE" : "FRESH");
            quality.put("statusText", limited ? "已有数据，但数据源额度受限" : failed ? "已有数据，但最近同步失败" : stale ? "已有数据，但同步较旧" : "数据可用");
            quality.put("message", limited ? "已返回 " + results + " 场比赛；主数据源当前受额度或套餐限制" : failed ? "已返回 " + results + " 场比赛；主数据源最近一次同步失败" : stale ? "已返回 " + results + " 场比赛；" + (age == null ? "最后同步时间未知" : "最后同步已超过 6 小时") : "已返回 " + results + " 场比赛");
            return quality;
        }
        quality.put("status", limited ? "SOURCE_LIMITED" : failed ? "SYNC_FAILED" : notCovered ? "NOT_COVERED" : "NO_MATCHES");
        quality.put("statusText", limited ? "数据源额度受限" : failed ? "数据同步失败" : notCovered ? "数据源尚未覆盖" : "暂无已采集比赛");
        quality.put("message", limited ? "外部数据源当前受额度或套餐限制，请稍后重试" : failed ? "主数据源请求失败，请稍后重试" : notCovered ? "主数据源尚未确认该日期的赛程，不能等同于没有比赛" : "当前日期暂无已采集比赛，主数据源已完成本次检查");
        return quality;
    }

    private Map<String, Object> executeMatchQuery(Supplier<List<CrawlerMatch>> supplier) {
        try {
            List<CrawlerMatch> matches = supplier.get();
            return buildSuccessResponse(formatMatches(matches), matches.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }
}
