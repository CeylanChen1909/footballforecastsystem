package com.chen.football.crawler.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.common.client.FootballDataClient;
import com.chen.football.common.client.JuheFootballClient;
import com.chen.football.crawler.http.CrawlerHttpClient;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.parser.WorldFootballParser;
import com.chen.football.crawler.parser.Zq123Parser;
import com.chen.football.crawler.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 比赛数据爬取服务
 */
@Slf4j
@Service
public class MatchCrawlerService {

    private final CrawlerHttpClient httpClient;
    private final WorldFootballParser worldFootballParser;
    private final Zq123Parser parser;
    private final CrawlerMatchMapper matchMapper;
    private final RedisTemplate<Object, Object> redisTemplate;
    private final CrawlerProperties crawlerProperties;
    private final FootballDataClient footballDataClient;
    private final JuheFootballClient juheFootballClient;

    private static final Map<String, String> LEAGUE_URLS = Map.of(
            "英超", "/competition/co91/england-premier-league/results-and-standings/",
            "西甲", "/competition/co64/spain-la-liga/results-and-standings/",
            "意甲", "/competition/co33/italy-serie-a/results-and-standings/",
            "德甲", "/competition/co25/germany-bundesliga/results-and-standings/",
            "法甲", "/competition/co34/france-ligue-1/results-and-standings/",
            "中超", "/competition/co355/china-super-league/results-and-standings/"
    );
    private static final String FOOTBALL_DATA_FREE_TIER_COMPETITIONS = String.join(",",
            "WC", "CL", "BL1", "DED", "BSA", "PD", "FL1", "ELC", "PPL", "EC", "SA", "PL");

    public MatchCrawlerService(CrawlerHttpClient httpClient, WorldFootballParser worldFootballParser, Zq123Parser parser,
                                CrawlerMatchMapper matchMapper, RedisTemplate<Object, Object> redisTemplate,
                                CrawlerProperties crawlerProperties,
                                FootballDataClient footballDataClient,
                                JuheFootballClient juheFootballClient) {
        this.httpClient = httpClient;
        this.worldFootballParser = worldFootballParser;
        this.parser = parser;
        this.matchMapper = matchMapper;
        this.redisTemplate = redisTemplate;
        this.crawlerProperties = crawlerProperties;
        this.footballDataClient = footballDataClient;
        this.juheFootballClient = juheFootballClient;
    }

    /**
     * 爬取指定日期的所有比赛
     */
    public List<CrawlerMatch> crawlMatchesByDate(Date date) {
        String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(date);
        List<CrawlerMatch> allMatches = new ArrayList<>();

        log.info("开始爬取 {} 的比赛数据", dateStr);

        // 1) 优先查 football-data.org（单次调用拿当天所有可访问联赛的比赛）
        List<CrawlerMatch> footballDataMatches = tryCrawlFromFootballData(dateStr);
        allMatches.addAll(footballDataMatches);
        int footballDataCount = footballDataMatches.size();
        if (footballDataCount > 0) {
            log.info("football-data.org 优先返回 {} 场比赛", footballDataCount);
        }

        // 2) 再查聚合数据
        List<CrawlerMatch> juheMatches = tryCrawlFromJuhe(dateStr);
        mergeMatches(allMatches, juheMatches);
        int juheCount = juheMatches.size();
        if (juheCount > 0) {
            log.info("聚合数据补充返回 {} 场比赛", juheCount);
        }

        // 3) 再查数据库
        List<CrawlerMatch> dbMatches = getMatchesFromDb(date, date);
        dbMatches = deduplicateMatches(dbMatches);
        for (CrawlerMatch match : dbMatches) {
            normalizeMatch(match, Optional.ofNullable(match.getLeagueName()).orElse("数据库"), dateStr);
            saveOrUpdateMatch(match);
        }
        mergeMatches(allMatches, dbMatches);
        int dbCount = dbMatches.size();
        if (dbCount > 0) {
            log.info("数据库补充返回 {} 场比赛", dbCount);
        }

        // 4) 最后再走网页爬虫，作为兜底来源
        int webpageCount = crawlFromWebPages(dateStr, allMatches);

        int homepageCount = tryCrawlHomepageMatches();

        log.info("{} 共爬取 {} 场比赛（football-data {}，聚合源 {}，数据库 {}，网页源 {}，首页源 {}）", dateStr, allMatches.size(), footballDataCount, juheCount, dbCount, webpageCount, homepageCount);
        return allMatches;
    }

    /**
     * 尝试爬取首页比赛数据
     */
    private int tryCrawlHomepageMatches() {
        int count = 0;
        try {
            String baseUrl = Optional.ofNullable(crawlerProperties.getWorldFootball())
                    .map(CrawlerProperties.WorldFootball::getBaseUrl)
                    .orElse("https://www.worldfootball.net");

            Map<String, String> backupUrls = Map.of(
                    baseUrl + "/competition/co91/england-premier-league/results-and-standings/", "英超",
                    baseUrl + "/competition/co64/spain-la-liga/results-and-standings/", "西甲",
                    baseUrl + "/competition/co33/italy-serie-a/results-and-standings/", "意甲"
            );

            for (Map.Entry<String, String> entry : backupUrls.entrySet()) {
                String url = entry.getKey();
                String leagueName = entry.getValue();
                String html = httpClient.getHtml(url);
                if (html != null && !html.isBlank()) {
                    List<CrawlerMatch> matches = worldFootballParser.parseMatchList(html, leagueName);
                    for (CrawlerMatch match : matches) {
                        if (match.getLeagueName() == null) {
                            match.setLeagueName(detectLeagueFromHtml(html, match));
                        }
                        normalizeMatch(match, leagueName, "");
                        saveOrUpdateMatch(match);
                    }
                    count += matches.size();
                    if (!matches.isEmpty()) {
                        log.info("从 {} 额外爬取 {} 场比赛", url, matches.size());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("备用数据源爬取失败: {}", e.getMessage());
        }
        return count;
    }

    private int crawlFromWebPages(String dateStr, List<CrawlerMatch> allMatches) {
        int count = 0;
        for (Map.Entry<String, String> entry : LEAGUE_URLS.entrySet()) {
            String leagueName = entry.getKey();
            String baseUrl = entry.getValue();

            try {
                String html = httpClient.getHtml(baseUrl + dateStr);
                if (html != null && !html.isEmpty()) {
                    List<CrawlerMatch> matches = worldFootballParser.parseMatchList(html, leagueName);
                    if (matches.isEmpty()) {
                        matches = parser.parseMatchList(html);
                    }
                    matches = deduplicateMatches(matches);
                    for (CrawlerMatch match : matches) {
                        if (match.getLeagueName() == null) {
                            match.setLeagueName(leagueName);
                        }
                        normalizeMatch(match, leagueName, dateStr);
                        saveOrUpdateMatch(match);
                    }
                    allMatches.addAll(matches);
                    count += matches.size();
                    log.info("爬取 {} 联赛 {} 场比赛成功", leagueName, matches.size());
                }
            } catch (Exception e) {
                log.error("爬取 {} 联赛失败: {}", leagueName, e.getMessage());
            }
        }
        return count;
    }

    /**
     * 优先从 football-data.org 拉取并转换为 CrawlerMatch
     */
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
                if (!(item instanceof Map<?, ?> itemMapRaw)) {
                    continue;
                }
                Map<String, Object> itemMap = (Map<String, Object>) itemMapRaw;
                CrawlerMatch match = toCrawlerMatchFromFootballData(itemMap, dateStr);
                if (match != null) {
                    saveOrUpdateMatch(match);
                    result.add(match);
                }
            }
        } catch (Exception e) {
            log.warn("football-data 兜底失败: {}", e.getMessage());
        }
        return result;
    }

    private boolean isFootballDataResultEmpty(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return true;
        }
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
            if (fixture == null || home == null || away == null) {
                return null;
            }

            String homeName = String.valueOf(home.getOrDefault("name", "")).trim();
            String awayName = String.valueOf(away.getOrDefault("name", "")).trim();
            if (homeName.isEmpty() || awayName.isEmpty()) {
                return null;
            }

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
            match.setStatus(normalizeFootballDataStatus(String.valueOf(status == null ? "NS" : status.getOrDefault("short", "NS"))));
            match.setExternalMatchId(!fixtureId.isBlank() ? fixtureId : (homeName + "_" + awayName + "_" + dateStr));
            match.setFixtureId(toLong(fixtureId));
            match.setMatchTime(parseFootballDataMatchTime(matchTime).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());

            if (goals != null) {
                match.setHomeScore(toInt(goals.get("home")));
                match.setAwayScore(toInt(goals.get("away")));
            }

            match.setCreatedAt(java.time.LocalDateTime.now());
            match.setUpdatedAt(java.time.LocalDateTime.now());
            return match;
        } catch (Exception e) {
            log.debug("转换 football-data 比赛数据失败: {}", e.getMessage());
            return null;
        }
    }

    private Date parseFootballDataMatchTime(String utcDate) {
        try {
            if (utcDate == null || utcDate.isBlank()) {
                return new Date();
            }
            String cleaned = utcDate.replace("T", " ").replace("Z", "");
            if (cleaned.length() >= 19) {
                cleaned = cleaned.substring(0, 19);
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(cleaned);
        } catch (Exception e) {
            return new Date();
        }
    }

    /**
     * 国内源兜底：从聚合数据拉取并转换为 CrawlerMatch
     */
    @SuppressWarnings("unchecked")
    private List<CrawlerMatch> tryCrawlFromJuhe(String dateStr) {
        List<CrawlerMatch> result = new ArrayList<>();
        try {
            int[] leagueIds = {39, 140, 135, 78, 61, 1}; // 英超/西甲/意甲/德甲/法甲/中超
            for (int leagueId : leagueIds) {
                try {
                    Map<String, Object> data = juheFootballClient.getFixturesByDate(dateStr, leagueId).block();
                    if (data == null || data.isEmpty()) {
                        continue;
                    }
                    if (isJuheResultEmpty(data)) {
                        continue;
                    }
                    Object responseObj = data.get("response");
                    if (!(responseObj instanceof List<?> responseList)) {
                        continue;
                    }

                    for (Object item : responseList) {
                        if (!(item instanceof Map<?, ?> itemMapRaw)) {
                            continue;
                        }
                        Map<String, Object> itemMap = (Map<String, Object>) itemMapRaw;
                        CrawlerMatch match = toCrawlerMatchFromJuhe(itemMap, dateStr);
                        if (match != null) {
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
        if (responseObj instanceof List<?> list && !list.isEmpty()) {
            return false;
        }
        Object resultObj = data.get("result");
        if (resultObj instanceof Map<?, ?> map) {
            Object matchsObj = map.get("matchs");
            if (matchsObj instanceof List<?> list && !list.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private CrawlerMatch toCrawlerMatchFromJuhe(Map<String, Object> item, String dateStr) {
        try {
            Map<String, Object> teams = (Map<String, Object>) item.get("teams");
            Map<String, Object> home = teams == null ? null : (Map<String, Object>) teams.get("home");
            Map<String, Object> away = teams == null ? null : (Map<String, Object>) teams.get("away");
            if (home == null || away == null) {
                return null;
            }

            String homeName = String.valueOf(home.getOrDefault("name", "")).trim();
            String awayName = String.valueOf(away.getOrDefault("name", "")).trim();
            if (homeName.isEmpty() || awayName.isEmpty()) {
                return null;
            }

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
            match.setHomeTeamLogo(String.valueOf(home.getOrDefault("logo", "")));
            match.setAwayTeamId(String.valueOf(away.getOrDefault("id", "")));
            match.setAwayTeamName(awayName);
            match.setAwayTeamLogo(String.valueOf(away.getOrDefault("logo", "")));
            match.setStatus(String.valueOf(status == null ? "NS" : status.getOrDefault("short", "NS")));

            Integer fixtureId = toInt(fixture == null ? null : fixture.get("id"));
            String rawDate = fixture == null ? null : String.valueOf(fixture.getOrDefault("date", ""));
            String rawTime = fixture == null ? null : String.valueOf(fixture.getOrDefault("time", ""));
            LocalDateTime matchTime = parseJuheMatchTime(rawDate, rawTime);
            if (matchTime == null) {
                return null;
            }
            String matchDateKey = matchTime.toLocalDate().toString();
            match.setExternalMatchId((fixtureId != null ? String.valueOf(fixtureId) : (homeName + "_" + awayName + "_" + matchDateKey)));
            match.setMatchTime(matchTime);

            if (goals != null) {
                match.setHomeScore(toInt(goals.get("home")));
                match.setAwayScore(toInt(goals.get("away")));
            }

            match.setCreatedAt(java.time.LocalDateTime.now());
            match.setUpdatedAt(java.time.LocalDateTime.now());
            return match;
        } catch (Exception e) {
            log.debug("转换聚合比赛数据失败: {}", e.getMessage());
            return null;
        }
    }

    private LocalDateTime parseJuheMatchTime(String dateStr, String timeStr) {
        try {
            if (dateStr == null || dateStr.isBlank()) return null;
            String safeDate = dateStr.trim();
            if (safeDate.length() > 10) safeDate = safeDate.substring(0, 10);
            if (!safeDate.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
            String safeTime = (timeStr == null || timeStr.isBlank()) ? "00:00" : timeStr.trim();
            if (safeTime.length() >= 5) safeTime = safeTime.substring(0, 5);
            Date parsed = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(safeDate + " " + safeTime);
            return parsed.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty() || "-".equals(s)) return null;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
            return Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeFootballDataStatus(String status) {
        if (status == null || status.isBlank()) return "NS";
        return switch (status) {
            case "SCHEDULED", "TIMED" -> "NS";
            case "LIVE", "IN_PLAY", "PAUSED" -> "LIVE";
            case "FINISHED" -> "FT";
            case "POSTPONED" -> "POSTP";
            case "CANCELLED", "CANCELED", "SUSPENDED" -> "CANCEL";
            default -> status;
        };
    }

    private void normalizeMatch(CrawlerMatch match, String leagueName, String dateStr) {
        if (match == null) {
            return;
        }
        if (match.getSource() == null || match.getSource().isBlank()) {
            match.setSource("worldfootball");
        }
        if (match.getLeagueName() == null || match.getLeagueName().isBlank()) {
            match.setLeagueName(leagueName);
        }
        if (match.getLeagueId() == null || match.getLeagueId().isBlank()) {
            match.setLeagueId(leagueName);
        }
        if (match.getStatus() == null || match.getStatus().isBlank()) {
            match.setStatus("NS");
        }
        if (match.getExternalMatchId() == null || match.getExternalMatchId().isBlank()) {
            String externalId = String.join("_",
                    Optional.ofNullable(match.getLeagueName()).orElse(leagueName),
                    Optional.ofNullable(match.getHomeTeamName()).orElse("home"),
                    Optional.ofNullable(match.getAwayTeamName()).orElse("away"),
                    dateStr);
            match.setExternalMatchId(externalId);
        }
    }

    private List<CrawlerMatch> deduplicateMatches(List<CrawlerMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, CrawlerMatch> unique = new LinkedHashMap<>();
        for (CrawlerMatch match : matches) {
            if (match == null) {
                continue;
            }
            String key = String.join("|",
                    Optional.ofNullable(match.getSource()).orElse(""),
                    Optional.ofNullable(match.getLeagueName()).orElse(""),
                    Optional.ofNullable(match.getHomeTeamName()).orElse(""),
                    Optional.ofNullable(match.getAwayTeamName()).orElse(""),
                    Optional.ofNullable(match.getMatchTime()).map(d -> new SimpleDateFormat("yyyy-MM-dd").format(d)).orElse(""));
            unique.putIfAbsent(key, match);
        }
        return new ArrayList<>(unique.values());
    }

    private void mergeMatches(List<CrawlerMatch> target, List<CrawlerMatch> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        Map<String, CrawlerMatch> existing = new LinkedHashMap<>();
        for (CrawlerMatch match : target) {
            existing.put(buildMatchKey(match), match);
        }
        for (CrawlerMatch match : source) {
            String key = buildMatchKey(match);
            if (!existing.containsKey(key)) {
                target.add(match);
                existing.put(key, match);
            }
        }
    }

    private String buildMatchKey(CrawlerMatch match) {
        if (match == null) {
            return "";
        }
        return String.join("|",
                Optional.ofNullable(match.getSource()).orElse(""),
                Optional.ofNullable(match.getLeagueName()).orElse(""),
                Optional.ofNullable(match.getHomeTeamName()).orElse(""),
                Optional.ofNullable(match.getAwayTeamName()).orElse(""),
                Optional.ofNullable(match.getMatchTime()).map(d -> new SimpleDateFormat("yyyy-MM-dd").format(d)).orElse(""));
    }

    /**
     * 从HTML内容中检测联赛名称
     */
    private String detectLeagueFromHtml(String html, CrawlerMatch match) {
        // 简化实现，返回默认联赛
        return "其他联赛";
    }

    /**
     * 单独拉取 football-data 从今天开始未来 3 天的比赛（用于同步任务拆分）
     */
    public List<CrawlerMatch> crawlFootballDataRecentMatches() {
        List<CrawlerMatch> allMatches = new ArrayList<>();
        Calendar cal = Calendar.getInstance();

        for (int i = 0; i < 3; i++) {
            Date cursor = cal.getTime();
            String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(cursor);
            List<CrawlerMatch> dayMatches = tryCrawlFromFootballData(dateStr);
            mergeMatches(allMatches, dayMatches);
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        return deduplicateMatches(allMatches);
    }


    /**
     * 单独拉取 juhe 今日比赛（用于同步任务拆分）
     */
    public List<CrawlerMatch> crawlJuheTodayMatches() {
        String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        return tryCrawlFromJuhe(dateStr);
    }

    /**
     * 单独拉取网页爬虫今日比赛（用于同步任务拆分）
     */
    public List<CrawlerMatch> crawlWebFallbackTodayMatches() {
        String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        List<CrawlerMatch> allMatches = new ArrayList<>();
        crawlFromWebPages(dateStr, allMatches);
        return allMatches;
    }

    /**
     * 爬取今日比赛
     */
    public List<CrawlerMatch> crawlTodayMatches() {
        return crawlMatchesByDate(new Date());
    }

    /**
     * 爬取近期比赛（今天起往后7天）
     */
    public List<CrawlerMatch> crawlUpcomingMatches() {
        List<CrawlerMatch> allMatches = new ArrayList<>();
        Calendar cal = Calendar.getInstance();

        for (int i = 0; i < 7; i++) {
            List<CrawlerMatch> dayMatches = crawlMatchesByDate(cal.getTime());
            allMatches.addAll(dayMatches);
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        return allMatches;
    }

    /**
     * 爬取指定联赛的比赛（按天）
     */
    public List<CrawlerMatch> crawlMatchesByLeagueAndDate(String leagueName, Date date) {
        String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(date);
        List<CrawlerMatch> result = new ArrayList<>();

        try {
            String leagueUrl = resolveLeagueUrl(leagueName);
            if (leagueUrl == null) {
                log.warn("未配置联赛 {} 的爬取地址", leagueName);
                return result;
            }

            String html = httpClient.getHtml(crawlerProperties.getWorldFootball().getBaseUrl() + leagueUrl);
            if (html == null || html.isBlank()) {
                return result;
            }

            List<CrawlerMatch> matches = worldFootballParser.parseMatchList(html, leagueName);
            if (matches.isEmpty()) {
                matches = parser.parseMatchList(html);
            }
            matches = deduplicateMatches(matches);
            for (CrawlerMatch match : matches) {
                normalizeMatch(match, leagueName, dateStr);
                saveOrUpdateMatch(match);
            }
            result.addAll(matches);
            log.info("按联赛 {} 爬取 {} 场比赛成功", leagueName, matches.size());
        } catch (Exception e) {
            log.warn("按联赛 {} 爬取失败: {}", leagueName, e.getMessage());
        }

        return result;
    }

    private String resolveLeagueUrl(String leagueName) {
        if (leagueName == null) {
            return null;
        }
        return switch (leagueName) {
            case "英超" -> "/competition/co91/england-premier-league/results-and-standings/";
            case "西甲" -> "/competition/co64/spain-la-liga/results-and-standings/";
            case "意甲" -> "/competition/co33/italy-serie-a/results-and-standings/";
            case "德甲" -> "/competition/co25/germany-bundesliga/results-and-standings/";
            case "法甲" -> "/competition/co34/france-ligue-1/results-and-standings/";
            case "中超" -> "/competition/co355/china-super-league/results-and-standings/";
            case "欧冠" -> "/competition/co19/uefa-champions-league/results-and-standings/";
            default -> null;
        };
    }

    /**
     * 更新比赛比分（实时爬取）
     */
    public void updateMatchScores() {
        List<CrawlerMatch> liveMatches = matchMapper.selectList(
                new LambdaQueryWrapper<CrawlerMatch>()
                        .in(CrawlerMatch::getStatus, Arrays.asList("NS", "LIVE", "1H", "2H", "HT"))
                        .gt(CrawlerMatch::getMatchTime, new Date())
        );

        log.info("需要更新 {} 场比赛的比分", liveMatches.size());

        for (CrawlerMatch match : liveMatches) {
            try {
                updateSingleMatchScore(match);
            } catch (Exception e) {
                log.debug("更新比赛 {} 比分失败: {}", match.getExternalMatchId(), e.getMessage());
            }
        }
    }

    /**
     * 更新单场比赛比分
     */
    private void updateSingleMatchScore(CrawlerMatch match) {
        // 这里可以实现实时比分爬取逻辑
        // 由于实时比分通常需要WebSocket或轮询，这里简化实现
        String cacheKey = "crawler:match:" + match.getExternalMatchId();

        // 检查缓存，避免频繁请求
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey))) {
            return;
        }

        // 预留扩展点：后续可在此接入实时比分采集
        redisTemplate.opsForValue().set(cacheKey, "1", 5, TimeUnit.MINUTES);
    }

    /**
     * 保存或更新比赛数据
     */
    private void saveOrUpdateMatch(CrawlerMatch match) {
        if (match == null || match.getHomeTeamName() == null || match.getAwayTeamName() == null) {
            return;
        }

        try {
            CrawlerMatch existing = matchMapper.findByExternalId(match.getExternalMatchId(), match.getSource());

            if (existing != null) {
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
                existing.setUpdatedAt(java.time.LocalDateTime.now());
                matchMapper.updateById(existing);
            } else {
                match.setCreatedAt(java.time.LocalDateTime.now());
                match.setUpdatedAt(java.time.LocalDateTime.now());
                matchMapper.insert(match);
            }
        } catch (Exception e) {
            log.debug("保存比赛数据失败: {}", e.getMessage());
        }
    }

    /**
     * 从数据库获取比赛
     */
    public List<CrawlerMatch> getMatchesFromDb(Date startDate, Date endDate) {
        return matchMapper.findByTimeRange(startDate, endDate);
    }

    /**
     * 从数据库获取今日比赛
     */
    public List<CrawlerMatch> getTodayMatches() {
        return matchMapper.findByDate(new Date());
    }

    /**
     * 从数据库获取指定联赛的比赛
     */
    public List<CrawlerMatch> getMatchesByLeagueFromDb(String leagueName) {
        if (leagueName == null || leagueName.isBlank()) {
            return Collections.emptyList();
        }
        List<CrawlerMatch> matches = matchMapper.findByLeagueName(leagueName);
        return matches == null ? Collections.emptyList() : matches;
    }

    /**
     * 获取单场比赛详情（按数据库最新记录）
     */
    public CrawlerMatch getMatchDetailByExternalId(String externalMatchId) {
        if (externalMatchId == null || externalMatchId.isBlank()) {
            return null;
        }
        return matchMapper.findLatestByExternalId(externalMatchId);
    }

    /**
     * 从数据库搜索比赛
     */
    public List<CrawlerMatch> searchMatches(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        List<CrawlerMatch> matches = matchMapper.searchMatches(keyword.trim());
        return matches == null ? Collections.emptyList() : matches;
    }

    /**
     * 获取球队最近比赛
     */
    public List<CrawlerMatch> getRecentMatchesByTeam(String teamName, int limit) {
        if (teamName == null || teamName.isBlank()) {
            return Collections.emptyList();
        }
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<CrawlerMatch> matches = matchMapper.findRecentByTeamName(teamName.trim(), safeLimit);
        return matches == null ? Collections.emptyList() : matches;
    }

    /**
     * 获取两队交锋记录
     */
    public List<CrawlerMatch> getHeadToHead(String homeTeam, String awayTeam, int limit) {
        if (homeTeam == null || homeTeam.isBlank() || awayTeam == null || awayTeam.isBlank()) {
            return Collections.emptyList();
        }
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<CrawlerMatch> matches = matchMapper.findHeadToHead(homeTeam.trim(), awayTeam.trim(), safeLimit);
        return matches == null ? Collections.emptyList() : matches;
    }

    /**
     * 统计某天比赛数量
     */
    public int countMatchesByDate(Date date) {
        Integer count = matchMapper.countByDate(date);
        return count == null ? 0 : count;
    }
}
