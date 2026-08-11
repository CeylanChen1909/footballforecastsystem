package com.chen.football.crawler.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.common.client.JuheFootballClient;
import com.chen.football.crawler.entity.CrawlerStanding;
import com.chen.football.crawler.http.CrawlerHttpClient;
import com.chen.football.crawler.mapper.CrawlerStandingMapper;
import com.chen.football.crawler.parser.WorldFootballParser;
import com.chen.football.crawler.parser.Zq123Parser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
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
    private final Zq123Parser parser;
    private final CrawlerStandingMapper standingMapper;
    private final JuheFootballClient juheFootballClient;

    // 当前赛季
    private static final String CURRENT_SEASON = LocalDate.now().getYear() + "/" + (LocalDate.now().getYear() + 1);

    // 联赛配置
    private static final Map<String, String> LEAGUE_CONFIGS = Map.of(
            "英超", "PL",
            "西甲", "LALIGA",
            "意甲", "SA",
            "德甲", "BL1",
            "法甲", "Ligue1",
            "中超", "CSL",
            "欧冠", "CL"
    );

    // 聚合数据联赛 ID
    private static final Map<String, Integer> JUHE_LEAGUE_ID_MAP = Map.of(
            "英超", 39,
            "西甲", 140,
            "意甲", 135,
            "德甲", 78,
            "法甲", 61,
            "中超", 1
    );

    public StandingCrawlerService(CrawlerHttpClient httpClient, WorldFootballParser worldFootballParser, Zq123Parser parser,
                                   CrawlerStandingMapper standingMapper,
                                   JuheFootballClient juheFootballClient) {
        this.httpClient = httpClient;
        this.worldFootballParser = worldFootballParser;
        this.parser = parser;
        this.standingMapper = standingMapper;
        this.juheFootballClient = juheFootballClient;
    }

    /**
     * 爬取指定联赛的积分榜
     */
    public List<CrawlerStanding> crawlStandingsByLeague(String leagueName) {
        String leagueId = LEAGUE_CONFIGS.getOrDefault(leagueName, leagueName);
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
                standing.setCreatedAt(java.time.LocalDateTime.now());
                standing.setUpdatedAt(java.time.LocalDateTime.now());
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
                old.setUpdatedAt(java.time.LocalDateTime.now());
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
        List<CrawlerStanding> standings = standingMapper.findByLeagueAndSeason(leagueId, season);
        return standings == null ? new ArrayList<>() : standings;
    }

    /**
     * 按联赛名称获取积分榜
     */
    public List<CrawlerStanding> getStandingsByLeagueName(String leagueName) {
        if (leagueName == null || leagueName.isBlank()) {
            return new ArrayList<>();
        }
        List<CrawlerStanding> standings = standingMapper.findByLeagueName(leagueName);
        return standings == null ? new ArrayList<>() : standings;
    }

    /**
     * 获取联赛最新积分榜
     */
    public List<CrawlerStanding> getLatestStandingsByLeague(String leagueId) {
        if (leagueId == null || leagueId.isBlank()) {
            return new ArrayList<>();
        }
        CrawlerStanding latest = standingMapper.findLatestByLeague(leagueId);
        if (latest == null) {
            return new ArrayList<>();
        }
        return getStandingsFromDb(latest.getLeagueId(), latest.getSeason());
    }
}
