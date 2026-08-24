package com.chen.football.crawler.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.common.client.JuheFootballClient;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.entity.CrawlerStanding;
import com.chen.football.crawler.entity.CrawlerTeam;
import com.chen.football.crawler.http.CrawlerHttpClient;
import com.chen.football.crawler.mapper.CrawlerStandingMapper;
import com.chen.football.crawler.mapper.CrawlerTeamMapper;
import com.chen.football.crawler.parser.BbcScoresParser;
import com.chen.football.crawler.parser.WorldFootballParser;
import com.chen.football.crawler.parser.Zq123Parser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分榜爬取服务
 */
@Slf4j
@Service
public class StandingCrawlerService {

    private final CrawlerHttpClient httpClient;
    private final WorldFootballParser worldFootballParser;
    private final BbcScoresParser bbcScoresParser;
    private final Zq123Parser parser;
    private final CrawlerStandingMapper standingMapper;
    private final CrawlerTeamMapper crawlerTeamMapper;
    private final JuheFootballClient juheFootballClient;
    private final CrawlerProperties crawlerProperties;

    // 当前赛季
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CURRENT_SEASON = LocalDate.now(BUSINESS_ZONE).getYear() + "/" + (LocalDate.now(BUSINESS_ZONE).getYear() + 1);

    // 联赛配置
    private static final Map<String, String> LEAGUE_CONFIGS = Map.of(
            "英超", "PL",
            "西甲", "LALIGA",
            "意甲", "SA",
            "德甲", "BL1",
            "法甲", "Ligue1",
            "荷甲", "DED",
            "葡超", "PPL",
            "英冠", "ELC"
    );

    /** BBC 公开积分榜页面，和 BBC 赛程源共享球队 badge 与名称。 */
    private static final Map<String, String> BBC_STANDING_PATHS = Map.of(
            "英超", "premier-league",
            "西甲", "spanish-la-liga",
            "意甲", "italian-serie-a",
            "德甲", "german-bundesliga",
            "法甲", "french-ligue-one",
            "荷甲", "dutch-eredivisie",
            "葡超", "portuguese-primeira-liga",
            "英冠", "championship"
    );

    // 聚合数据联赛 ID
    private static final Map<String, Integer> JUHE_LEAGUE_ID_MAP = Map.of(
            "英超", 39,
            "西甲", 140,
            "意甲", 135,
            "德甲", 78,
            "法甲", 61,
            "荷甲", 88,
            "葡超", 94,
            "英冠", 40
    );

    /** 兼容历史英文名、Provider ID 和 BBC slug；数据库快照统一按中文联赛名读取。 */
    private static final Map<String, String> LEAGUE_ALIASES = Map.ofEntries(
            Map.entry("英超", "英超"), Map.entry("premierleague", "英超"), Map.entry("epl", "英超"), Map.entry("pl", "英超"), Map.entry("bbcpremierleague", "英超"),
            Map.entry("西甲", "西甲"), Map.entry("laliga", "西甲"), Map.entry("primeradivision", "西甲"), Map.entry("pd", "西甲"), Map.entry("bbcspanishlaliga", "西甲"),
            Map.entry("意甲", "意甲"), Map.entry("seriea", "意甲"), Map.entry("sa", "意甲"), Map.entry("bbcitalianseriea", "意甲"),
            Map.entry("德甲", "德甲"), Map.entry("bundesliga", "德甲"), Map.entry("bl1", "德甲"), Map.entry("bbcgermanbundesliga", "德甲"),
            Map.entry("法甲", "法甲"), Map.entry("ligue1", "法甲"), Map.entry("fl1", "法甲"), Map.entry("bbcfrenchligueone", "法甲"),
            Map.entry("荷甲", "荷甲"), Map.entry("eredivisie", "荷甲"), Map.entry("ded", "荷甲"), Map.entry("bbcdutcheredivisie", "荷甲"),
            Map.entry("葡超", "葡超"), Map.entry("primeiraliga", "葡超"), Map.entry("ppl", "葡超"), Map.entry("bbcportugueseprimeiraliga", "葡超"),
            Map.entry("英冠", "英冠"), Map.entry("championship", "英冠"), Map.entry("elc", "英冠"), Map.entry("bbcchampionship", "英冠")
    );

    public StandingCrawlerService(CrawlerHttpClient httpClient, WorldFootballParser worldFootballParser, BbcScoresParser bbcScoresParser, Zq123Parser parser,
                                   CrawlerStandingMapper standingMapper, CrawlerTeamMapper crawlerTeamMapper,
                                   JuheFootballClient juheFootballClient,
                                   CrawlerProperties crawlerProperties) {
        this.httpClient = httpClient;
        this.worldFootballParser = worldFootballParser;
        this.bbcScoresParser = bbcScoresParser;
        this.parser = parser;
        this.standingMapper = standingMapper;
        this.crawlerTeamMapper = crawlerTeamMapper;
        this.juheFootballClient = juheFootballClient;
        this.crawlerProperties = crawlerProperties;
    }

    /**
     * 爬取指定联赛的积分榜
     */
    public List<CrawlerStanding> crawlStandingsByLeague(String leagueName) {
        leagueName = canonicalLeagueName(leagueName);
        String leagueId = LEAGUE_CONFIGS.getOrDefault(leagueName, leagueName);

        // primary-only 模式下，积分榜也走 BBC 主爬虫源，避免旧聚合数据的中文球队
        // 与 BBC 英文球队并存。BBC 请求失败时保留数据库旧快照，不用空数据覆盖它。
        if (isBbcPrimary()) {
            List<CrawlerStanding> bbcStandings = tryCrawlStandingsFromBbc(leagueName, leagueId);
            if (!bbcStandings.isEmpty()) {
                replaceSnapshot(leagueName, leagueId, bbcStandings);
                return bbcStandings;
            }
            if (crawlerProperties.isPrimaryOnly()) {
                log.warn("BBC 积分榜采集失败，primary-only 模式保留旧快照: league={}", leagueName);
                // 失败不能把“旧快照仍可用”伪装成“没有积分榜”；返回旧快照，
                // 由 dataQuality 的 STALE/更新时间告诉调用方它并非最新数据。
                return getStandingsByLeagueName(leagueName, "");
            }
        }
        if (crawlerProperties.isPrimaryOnly()
                && !"web-crawler".equalsIgnoreCase(crawlerProperties.getPrimarySource())) {
            log.info("积分榜采集已停用：当前仅允许主源 {}", crawlerProperties.getPrimarySource());
            return new ArrayList<>();
        }
        String url = buildStandingsUrl(leagueId);

        log.info("开始爬取 {} 联赛积分榜: {}", leagueName, url);

        try {
            String html = httpClient.getHtml(url);
            if (html != null && !html.isEmpty()) {
                List<CrawlerStanding> standings = worldFootballParser.parseStandings(html, leagueName, leagueId, CURRENT_SEASON);
                if (standings.isEmpty()) {
                    standings = parser.parseStandings(html, leagueName, leagueId, CURRENT_SEASON);
                }

                if (!standings.isEmpty()) {
                    for (CrawlerStanding standing : standings) {
                        saveOrUpdateStanding(standing);
                    }
                    log.info("爬取 {} 联赛积分榜 {} 条成功", leagueName, standings.size());
                    return standings;
                }
            }
        } catch (Exception e) {
            log.warn("网页源爬取 {} 联赛积分榜失败，切换聚合数据: {}", leagueName, e.getMessage());
        }

        List<CrawlerStanding> juheStandings = tryCrawlStandingsFromJuhe(leagueName, leagueId);
        for (CrawlerStanding standing : juheStandings) {
            saveOrUpdateStanding(standing);
        }
        log.info("聚合数据兜底 {} 联赛积分榜 {} 条", leagueName, juheStandings.size());
        return juheStandings;
    }

    private boolean isBbcPrimary() {
        return "bbc-scores".equalsIgnoreCase(crawlerProperties.getPrimarySource())
                && crawlerProperties.getBbc() != null
                && crawlerProperties.getBbc().isEnabled();
    }

    private List<CrawlerStanding> tryCrawlStandingsFromBbc(String leagueName, String leagueId) {
        String path = BBC_STANDING_PATHS.get(leagueName);
        if (path == null || crawlerProperties.getBbc() == null) return new ArrayList<>();
        try {
            String base = crawlerProperties.getBbc().getBaseUrl() == null
                    ? "https://www.bbc.com" : crawlerProperties.getBbc().getBaseUrl().replaceAll("/$", "");
            String url = base + "/sport/football/" + path + "/table";
            String html = httpClient.getHtmlDirect(url, crawlerProperties.getBbc().getUserAgent(), Duration.ofSeconds(25));
            return bbcScoresParser.parseStandings(html, leagueName, leagueId, CURRENT_SEASON);
        } catch (Exception ex) {
            log.warn("BBC 积分榜请求失败，league={}, error={}", leagueName, ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** 用一份 BBC 快照替换旧聚合快照，保证一个联赛只保留一套球队名称和 logo。 */
    private void replaceSnapshot(String leagueName, String leagueId, List<CrawlerStanding> standings) {
        if (standings == null || standings.isEmpty()) return;
        String season = standings.get(0).getSeason();
        try {
            // crawler_standings 是当前快照表；清掉旧聚合来源的历史行，避免同一联赛
            // 在无 season 参数的兼容接口中混出两套排名和球队名称。
            standingMapper.delete(new LambdaQueryWrapper<CrawlerStanding>()
                    .in(CrawlerStanding::getLeagueName, leagueAliases(leagueName)));
            crawlerTeamMapper.delete(new LambdaQueryWrapper<CrawlerTeam>()
                    .in(CrawlerTeam::getLeagueName, leagueAliases(leagueName)));
            for (CrawlerStanding standing : standings) {
                standingMapper.insert(standing);
                CrawlerTeam team = new CrawlerTeam();
                team.setName(standing.getTeamName());
                team.setLogo(standing.getTeamLogo());
                team.setLeagueName(leagueName);
                team.setCountry("");
                team.setSource("bbc-standings");
                team.setCreatedAt(java.time.LocalDateTime.now(BUSINESS_ZONE));
                team.setUpdatedAt(java.time.LocalDateTime.now(BUSINESS_ZONE));
                crawlerTeamMapper.insert(team);
            }
            log.info("BBC 积分榜快照已替换: league={}, teams={}, source=bbc-standings", leagueName, standings.size());
        } catch (Exception ex) {
            log.warn("替换 BBC 积分榜快照失败，league={}, error={}", leagueName, ex.getMessage());
        }
    }

    /**
     * 爬取所有联赛的积分榜
     */
    public List<CrawlerStanding> crawlAllStandings() {
        List<CrawlerStanding> allStandings = new ArrayList<>();

        for (String leagueName : LEAGUE_CONFIGS.keySet()) {
            try {
                List<CrawlerStanding> standings = crawlStandingsByLeague(leagueName);
                allStandings.addAll(standings);
            } catch (Exception e) {
                log.error("爬取 {} 积分榜异常: {}", leagueName, e.getMessage());
            }
        }

        return allStandings;
    }

    /**
     * 国内源兜底：从聚合数据获取积分榜并转换
     */
    @SuppressWarnings("unchecked")
    private List<CrawlerStanding> tryCrawlStandingsFromJuhe(String leagueName, String leagueId) {
        Integer juheLeagueId = JUHE_LEAGUE_ID_MAP.get(leagueName);
        if (juheLeagueId == null) {
            return new ArrayList<>();
        }

        try {
            Map<String, Object> data = juheFootballClient.getStandings(juheLeagueId).block();
            if (data == null) {
                return new ArrayList<>();
            }

            Object responseObj = data.get("response");
            if (!(responseObj instanceof List<?> responseList)) {
                return new ArrayList<>();
            }

            List<CrawlerStanding> result = new ArrayList<>();
            for (Object rowObj : responseList) {
                if (!(rowObj instanceof Map<?, ?> rowRaw)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) rowRaw;
                CrawlerStanding standing = new CrawlerStanding();
                standing.setLeagueName(leagueName);
                standing.setLeagueId(leagueId);
                standing.setSeason(CURRENT_SEASON);
                standing.setTeamName(String.valueOf(row.getOrDefault("team", "")));
                standing.setRank(toInt(row.get("position"), 0));
                standing.setPlayed(toInt(row.get("played"), 0));
                standing.setWins(toInt(row.get("won"), 0));
                standing.setDraws(toInt(row.get("drawn"), 0));
                standing.setLosses(toInt(row.get("lost"), 0));
                standing.setGoalsFor(toInt(row.get("goalsFor"), 0));
                standing.setGoalsAgainst(toInt(row.get("goalsAgainst"), 0));
                standing.setGoalDifference(toInt(row.get("goalDifference"), 0));
                standing.setPoints(toInt(row.get("points"), 0));
                standing.setTeamLogo(String.valueOf(row.getOrDefault("teamLogo", "")));
                standing.setSource("juhe");
                standing.setCreatedAt(java.time.LocalDateTime.now(BUSINESS_ZONE));
                standing.setUpdatedAt(java.time.LocalDateTime.now(BUSINESS_ZONE));
                if (standing.getTeamName() != null && !standing.getTeamName().isBlank()) {
                    result.add(standing);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("聚合数据积分榜拉取失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private int toInt(Object value, int defaultVal) {
        if (value == null) return defaultVal;
        try {
            return Integer.parseInt(String.valueOf(value).replace("+", "").trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    /**
     * 构建积分榜URL
     */
    private String buildStandingsUrl(String leagueId) {
        return switch (leagueId) {
            case "PL" -> "https://www.worldfootball.net/competition/co91/england-premier-league/results-and-standings/";
            case "LALIGA" -> "https://www.worldfootball.net/competition/co64/spain-la-liga/results-and-standings/";
            case "SA" -> "https://www.worldfootball.net/competition/co33/italy-serie-a/results-and-standings/";
            case "BL1" -> "https://www.worldfootball.net/competition/co25/germany-bundesliga/results-and-standings/";
            case "Ligue1" -> "https://www.worldfootball.net/competition/co34/france-ligue-1/results-and-standings/";
            case "CSL" -> "https://www.worldfootball.net/competition/co314/china-super-league/results-and-standings/";
            case "CL" -> "https://www.worldfootball.net/competition/co19/uefa-champions-league/results-and-standings/";
            case "DED" -> "https://www.worldfootball.net/competition/co3/netherlands-eredivisie/results-and-standings/";
            case "PPL" -> "https://www.worldfootball.net/competition/co32/portugal-primeira-liga/results-and-standings/";
            case "ELC" -> "https://www.worldfootball.net/competition/co14/england-championship/results-and-standings/";
            default -> "https://www.worldfootball.net/competition/standings/";
        };
    }

    /**
     * 保存或更新积分榜数据
     */
    private void saveOrUpdateStanding(CrawlerStanding standing) {
        try {
            List<CrawlerStanding> existing = standingMapper.selectList(
                    new LambdaQueryWrapper<CrawlerStanding>()
                            .eq(CrawlerStanding::getLeagueId, standing.getLeagueId())
                            .eq(CrawlerStanding::getSeason, standing.getSeason())
                            .eq(CrawlerStanding::getTeamName, standing.getTeamName())
            );

            if (!existing.isEmpty()) {
                CrawlerStanding old = existing.get(0);
                old.setRank(standing.getRank());
                old.setPlayed(standing.getPlayed());
                old.setWins(standing.getWins());
                old.setDraws(standing.getDraws());
                old.setLosses(standing.getLosses());
                old.setGoalsFor(standing.getGoalsFor());
                old.setGoalsAgainst(standing.getGoalsAgainst());
                old.setGoalDifference(standing.getGoalDifference());
                old.setPoints(standing.getPoints());
                old.setTeamLogo(standing.getTeamLogo());
                old.setUpdatedAt(java.time.LocalDateTime.now(BUSINESS_ZONE));
                standingMapper.updateById(old);
            } else {
                standingMapper.insert(standing);
            }
        } catch (Exception e) {
            log.debug("保存积分榜数据失败: {}", e.getMessage());
        }
    }

    /**
     * 获取数据库中的积分榜
     */
    public List<CrawlerStanding> getStandingsFromDb(String leagueId, String season) {
        if (leagueId == null || leagueId.isBlank() || season == null || season.isBlank()) {
            return new ArrayList<>();
        }
        String canonicalName = canonicalLeagueName(leagueId);
        String canonicalId = LEAGUE_CONFIGS.getOrDefault(canonicalName, leagueId.trim());
        List<CrawlerStanding> standings = standingMapper.findByLeagueAndSeason(canonicalId, canonicalSeason(season));
        return visibleStandings(standings);
    }

    /**
     * 按联赛名称获取积分榜
     */
    public List<CrawlerStanding> getStandingsByLeagueName(String leagueName) {
        return getStandingsByLeagueName(leagueName, "");
    }

    /** 按联赛与赛季获取积分榜，避免前端展示一个无法切换的假赛季筛选器。 */
    public List<CrawlerStanding> getStandingsByLeagueName(String leagueName, String season) {
        if (leagueName == null || leagueName.isBlank()) {
            return new ArrayList<>();
        }
        String canonicalName = canonicalLeagueName(leagueName);
        List<CrawlerStanding> standings = findByLeagueName(canonicalName, canonicalSeason(season));
        return visibleStandings(standings);
    }

    public List<String> getSeasonsByLeagueName(String leagueName) {
        if (leagueName == null || leagueName.isBlank()) return new ArrayList<>();
        String canonicalName = canonicalLeagueName(leagueName);
        List<String> seasons = findSeasonsByLeagueName(canonicalName);
        if (!crawlerProperties.isPrimaryOnly()) return seasons == null ? new ArrayList<>() : seasons;
        // Only expose seasons backed by the configured primary standing
        // snapshot.  Returning stale Juhe/API rows here made the UI claim a
        // league was available after that provider had been disabled.
        List<CrawlerStanding> visible = findByLeagueName(canonicalName, "");
        if (visible == null) return new ArrayList<>();
        return visible.stream()
                .filter(this::isVisibleStanding)
                .map(CrawlerStanding::getSeason)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .sorted(java.util.Comparator.reverseOrder())
                .toList();
    }

    /**
     * 获取联赛最新积分榜
     */
    public List<CrawlerStanding> getLatestStandingsByLeague(String leagueId) {
        if (leagueId == null || leagueId.isBlank()) {
            return new ArrayList<>();
        }
        String canonicalName = canonicalLeagueName(leagueId);
        String canonicalId = LEAGUE_CONFIGS.getOrDefault(canonicalName, leagueId.trim());
        CrawlerStanding latest = standingMapper.findLatestByLeague(canonicalId);
        if (latest == null) {
            return new ArrayList<>();
        }
        return getStandingsFromDb(latest.getLeagueId(), latest.getSeason());
    }

    /** 独立积分榜健康快照，避免比赛数据源 NORMAL 掩盖积分榜缺失。 */
    public List<Map<String, Object>> getHealthSnapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String leagueName : LEAGUE_CONFIGS.keySet()) {
            List<CrawlerStanding> rows = getStandingsByLeagueName(leagueName, "");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("leagueName", leagueName);
            item.put("rows", rows.size());
            LocalDateTime updatedAt = rows.stream().map(CrawlerStanding::getUpdatedAt)
                    .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            long ageMinutes = updatedAt == null ? -1 : Math.max(0, ChronoUnit.MINUTES.between(updatedAt, LocalDateTime.now(BUSINESS_ZONE)));
            boolean hasPlayed = rows.stream().anyMatch(row -> number(row.getPlayed()) > 0 || number(row.getPoints()) > 0
                    || number(row.getWins()) > 0 || number(row.getDraws()) > 0 || number(row.getLosses()) > 0);
            String status = rows.isEmpty() ? "NO_DATA" : ageMinutes > 72 * 60 ? "STALE" : hasPlayed ? "AVAILABLE" : "PRESEASON";
            item.put("status", status);
            item.put("season", rows.stream().map(CrawlerStanding::getSeason).filter(v -> v != null && !v.isBlank()).findFirst().orElse(""));
            item.put("source", rows.stream().map(CrawlerStanding::getSource).filter(v -> v != null && !v.isBlank()).findFirst().orElse(""));
            item.put("lastSyncedAt", updatedAt == null ? "" : updatedAt.toString());
            item.put("ageMinutes", ageMinutes);
            result.add(item);
        }
        return result;
    }

    private List<CrawlerStanding> visibleStandings(List<CrawlerStanding> rows) {
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        return rows.stream().filter(this::isVisibleStanding).toList();
    }

    private boolean isVisibleStanding(CrawlerStanding standing) {
        if (standing == null) return false;
        if (!crawlerProperties.isPrimaryOnly()) return true;
        // BBC standings share the BBC scores crawler as the configured primary
        // source but use a dedicated table snapshot label.
        return "bbc-standings".equalsIgnoreCase(standing.getSource());
    }

    private int number(Integer value) {
        return value == null ? 0 : value;
    }

    private List<CrawlerStanding> findByLeagueName(String leagueName, String season) {
        List<CrawlerStanding> primary = queryLeagueName(leagueName, season);
        if (!primary.isEmpty()) return primary;
        Map<String, CrawlerStanding> merged = new LinkedHashMap<>();
        for (String alias : leagueAliases(leagueName)) {
            if (alias.equals(leagueName)) continue;
            for (CrawlerStanding row : queryLeagueName(alias, season)) {
                String key = String.valueOf(row.getLeagueId()) + "|" + String.valueOf(row.getSeason()) + "|" + String.valueOf(row.getTeamName());
                merged.putIfAbsent(key, row);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<CrawlerStanding> queryLeagueName(String leagueName, String season) {
        return season == null || season.isBlank()
                ? standingMapper.findByLeagueName(leagueName)
                : standingMapper.findByLeagueNameAndSeason(leagueName, season);
    }

    private List<String> findSeasonsByLeagueName(String leagueName) {
        List<String> primary = standingMapper.findSeasonsByLeagueName(leagueName);
        if (primary != null && !primary.isEmpty()) return primary;
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        for (String alias : leagueAliases(leagueName)) {
            if (alias.equals(leagueName)) continue;
            List<String> rows = standingMapper.findSeasonsByLeagueName(alias);
            if (rows != null) merged.addAll(rows);
        }
        return new ArrayList<>(merged);
    }

    private List<String> leagueAliases(String canonicalName) {
        return switch (canonicalName) {
            case "英超" -> List.of("英超", "Premier League", "premier league", "EPL", "PL", "bbc-premier-league");
            case "西甲" -> List.of("西甲", "La Liga", "la liga", "Primera Division", "PD", "bbc-spanish-la-liga");
            case "意甲" -> List.of("意甲", "Serie A", "serie a", "SA", "bbc-italian-serie-a");
            case "德甲" -> List.of("德甲", "Bundesliga", "BL1", "bbc-german-bundesliga");
            case "法甲" -> List.of("法甲", "Ligue 1", "ligue 1", "FL1", "bbc-french-ligue-one");
            case "荷甲" -> List.of("荷甲", "Eredivisie", "DED", "bbc-dutch-eredivisie");
            case "葡超" -> List.of("葡超", "Primeira Liga", "PPL", "bbc-portuguese-primeira-liga");
            case "英冠" -> List.of("英冠", "Championship", "ELC", "bbc-championship");
            default -> List.of(canonicalName);
        };
    }

    private String canonicalLeagueName(String value) {
        if (value == null || value.isBlank()) return "";
        String key = value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_\\-]+", "");
        return LEAGUE_ALIASES.getOrDefault(key, value.trim());
    }

    private String canonicalSeason(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replace('－', '-').replace('–', '-');
        if (normalized.matches("\\d{4}")) {
            int start = Integer.parseInt(normalized);
            return start + "/" + (start + 1);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{4})[-/]((?:\\d{2})|(?:\\d{4}))").matcher(normalized);
        if (!matcher.matches()) return normalized;
        int start = Integer.parseInt(matcher.group(1));
        int end = Integer.parseInt(matcher.group(2));
        if (matcher.group(2).length() == 2) end += (start / 100) * 100;
        return start + "/" + end;
    }
}
