package com.chen.football.crawler.controller;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerStanding;
import com.chen.football.crawler.entity.CrawlerTeam;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.mapper.CrawlerTeamMapper;
import com.chen.football.crawler.service.DeepSeekPredictionService;
import com.chen.football.crawler.service.MatchCrawlerService;
import com.chen.football.crawler.service.StandingCrawlerService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * 爬虫数据API控制器
 */
@RestController
@RequestMapping("/api/crawler")
public class CrawlerController {

    private final MatchCrawlerService matchCrawlerService;
    private final StandingCrawlerService standingCrawlerService;
    private final CrawlerTeamMapper crawlerTeamMapper;
    private final CrawlerMatchMapper crawlerMatchMapper;
    private final DeepSeekPredictionService deepSeekPredictionService;

    public CrawlerController(MatchCrawlerService matchCrawlerService,
                             StandingCrawlerService standingCrawlerService,
                             CrawlerTeamMapper crawlerTeamMapper,
                             CrawlerMatchMapper crawlerMatchMapper,
                             DeepSeekPredictionService deepSeekPredictionService) {
        this.matchCrawlerService = matchCrawlerService;
        this.standingCrawlerService = standingCrawlerService;
        this.crawlerTeamMapper = crawlerTeamMapper;
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.deepSeekPredictionService = deepSeekPredictionService;
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
            List<CrawlerMatch> matches = crawlerMatchMapper.findUpcomingMatches();
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
            List<CrawlerMatch> matches = crawlerMatchMapper.findUpcomingMatches();
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
                                              @RequestParam(name = "date", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") Date date,
                                              @RequestParam(name = "page", defaultValue = "1") int page,
                                              @RequestParam(name = "size", defaultValue = "20") int size) {
        try {
            int safePage = Math.max(1, page);
            int safeSize = Math.max(1, Math.min(size, 100));
            int offset = (safePage - 1) * safeSize;
            LambdaQueryWrapper<CrawlerMatch> query = new LambdaQueryWrapper<CrawlerMatch>()
                    .orderByDesc(CrawlerMatch::getMatchTime)
                    .orderByDesc(CrawlerMatch::getId);
            if (keyword != null && !keyword.isBlank()) {
                query.and(q -> q.like(CrawlerMatch::getLeagueName, keyword)
                        .or().like(CrawlerMatch::getHomeTeamName, keyword)
                        .or().like(CrawlerMatch::getAwayTeamName, keyword));
            }
            if (status != null && !status.isBlank()) {
                query.eq(CrawlerMatch::getStatus, status);
            }
            if (date != null) {
                LocalDate requestedDate = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                query.ge(CrawlerMatch::getMatchTime, requestedDate.atStartOfDay())
                        .lt(CrawlerMatch::getMatchTime, requestedDate.plusDays(1).atStartOfDay());
            }
            List<CrawlerMatch> all = crawlerMatchMapper.selectList(query);
            List<CrawlerMatch> pageItems = all.stream().skip(offset).limit(safeSize).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("page", safePage);
            data.put("size", safeSize);
            data.put("total", all.size());
            data.put("response", formatMatches(pageItems));
            data.put("results", pageItems.size());
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
                                                @RequestParam(name = "size", defaultValue = "300") int size) {
        try {
            int safePage = Math.max(1, page);
            int safeSize = Math.max(1, Math.min(size, 500));
            int offset = (safePage - 1) * safeSize;
            LocalDate today = LocalDate.now();
            LocalDate windowStart = today.minusDays(0);
            LocalDate windowEnd = today.plusDays(7);
            LambdaQueryWrapper<CrawlerMatch> query = new LambdaQueryWrapper<CrawlerMatch>()
                    .ge(CrawlerMatch::getMatchTime, windowStart.atStartOfDay())
                    .lt(CrawlerMatch::getMatchTime, windowEnd.plusDays(1).atStartOfDay())
                    .orderByAsc(CrawlerMatch::getMatchTime)
                    .orderByAsc(CrawlerMatch::getFixtureId);
            List<CrawlerMatch> all = crawlerMatchMapper.selectList(query);
            List<CrawlerMatch> pageItems = all.stream().skip(offset).limit(safeSize).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("page", safePage);
            data.put("size", safeSize);
            data.put("total", all.size());
            data.put("windowStart", windowStart.toString());
            data.put("windowEnd", windowEnd.toString());
            data.put("response", formatMatches(pageItems));
            data.put("results", pageItems.size());
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }


    /**
     * 获取实时/进行中比赛
     */
    @GetMapping("/matches/live")
    public Map<String, Object> getLiveMatches() {
        try {
            List<CrawlerMatch> matches = crawlerMatchMapper.findLiveMatches();
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
     * 按日期获取比赛
     */
    @GetMapping("/matches/date/{date}")
    public Map<String, Object> getMatchesByDate(@PathVariable("date") String date) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            Date parseDate = sdf.parse(date);
            List<CrawlerMatch> matches = matchCrawlerService.crawlMatchesByDate(parseDate);

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
     * 按联赛获取比赛
     */
    @GetMapping("/matches/league/{leagueName}")
    public Map<String, Object> getMatchesByLeague(@PathVariable("leagueName") String leagueName,
                                                   @RequestParam(name = "date", required = false) String date) {
        try {
            Date targetDate = date == null || date.isBlank() ? new Date() : new SimpleDateFormat("yyyy-MM-dd").parse(date);
            List<CrawlerMatch> matches = matchCrawlerService.crawlMatchesByLeagueAndDate(leagueName, targetDate);
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
     * 热门比赛/推荐比赛
     */
    @GetMapping("/matches/hot")
    public Map<String, Object> getHotMatches(@RequestParam(name = "limit", defaultValue = "4") int limit) {
        try {
            int safeLimit = Math.max(1, Math.min(limit, 4));
            LocalDate today = LocalDate.now();
            LocalDateTime start = today.minusDays(7).atStartOfDay();
            LocalDateTime end = today.plusDays(7).plusDays(1).atStartOfDay();
            List<CrawlerMatch> candidates = crawlerMatchMapper.selectList(new LambdaQueryWrapper<CrawlerMatch>()
                    .ge(CrawlerMatch::getMatchTime, start)
                    .lt(CrawlerMatch::getMatchTime, end));

            long liveCount = candidates.stream().filter(this::isLiveMatch).count();
            long todayCount = candidates.stream().filter(m -> isSameDate(m.getMatchTime(), today)).count();
            long upcomingCount = candidates.stream().filter(m -> "NS".equalsIgnoreCase(String.valueOf(m.getStatus()))).count();

            List<CrawlerMatch> hotMatches = candidates.stream()
                    .sorted((a, b) -> Integer.compare(calculateHotScore(b, today), calculateHotScore(a, today)))
                    .limit(safeLimit)
                    .toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("limit", safeLimit);
            data.put("windowStart", today.minusDays(7).toString());
            data.put("windowEnd", today.plusDays(7).toString());
            data.put("response", formatMatches(hotMatches));
            data.put("results", hotMatches.size());
            data.put("summary", Map.of(
                    "live", liveCount,
                    "today", todayCount,
                    "upcoming", upcomingCount
            ));
            return Map.of("success", true, "message", "获取成功", "data", data);
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }




    /**
     * 获取积分榜
     */
    @GetMapping("/standings/{leagueId}")
    public Map<String, Object> getStandings(@PathVariable("leagueId") String leagueId,
                                            @RequestParam(name = "season", defaultValue = "") String season) {
        try {
            List<CrawlerStanding> standings = standingCrawlerService.crawlStandingsByLeague(leagueId);
            return buildSuccessResponse(formatStandings(standings), standings.size());
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
            return buildSuccessResponse(formatStandings(standings), standings.size());
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
            List<CrawlerTeam> teams = crawlerTeamMapper.findByLeague(leagueName);
            List<Map<String, Object>> response = teams.stream().map(team -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", team.getId() == null ? 0L : team.getId());
                item.put("name", team.getName() == null ? "" : team.getName());
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
     * 搜索球队（用于 team-service）
     */
    @GetMapping("/teams/search")
    public Map<String, Object> searchTeams(@RequestParam("name") String name) {
        try {
            List<CrawlerTeam> teams = crawlerTeamMapper.searchByName(name);
            List<Map<String, Object>> response = teams.stream().map(team -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", team.getId() == null ? 0L : team.getId());
                item.put("name", team.getName() == null ? "" : team.getName());
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
     * 手动触发爬取
     */
    @PostMapping("/trigger")
    public Map<String, Object> triggerCrawl(@RequestParam(name = "type", defaultValue = "matches") String type) {
        try {
            Object result = switch (type) {
                case "matches" -> matchCrawlerService.crawlTodayMatches();
                case "standings" -> standingCrawlerService.crawlAllStandings();
                case "upcoming" -> matchCrawlerService.crawlUpcomingMatches();
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
            List<CrawlerMatch> liveMatches = crawlerMatchMapper.findLiveMatches();
            List<CrawlerMatch> upcomingMatches = crawlerMatchMapper.findUpcomingMatches();

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
    public Map<String, Object> getStandingsByLeagueName(@PathVariable("leagueName") String leagueName) {
        try {
            List<CrawlerStanding> standings = standingCrawlerService.getStandingsByLeagueName(leagueName);
            return buildSuccessResponse(formatStandings(standings), standings.size());
        } catch (Exception e) {
            return buildFailureResponse(e);
        }
    }

    /**
     * 获取球队详情
     */
    @GetMapping("/teams/detail/{teamName}")
    public Map<String, Object> getTeamDetail(@PathVariable("teamName") String teamName,
                                             @RequestParam(name = "leagueName", required = false) String leagueName) {
        try {
            CrawlerTeam team = crawlerTeamMapper.findLatestByName(teamName);
            List<CrawlerMatch> recentMatches = matchCrawlerService.getRecentMatchesByTeam(teamName, 10);
            if (leagueName != null && !leagueName.isBlank()) {
                recentMatches = recentMatches.stream()
                        .filter(m -> leagueName.equals(m.getLeagueName()))
                        .toList();
            }

            Map<String, Object> form = buildTeamForm(recentMatches, teamName);
            Map<String, Object> teamInfo = team == null ? Map.of("name", teamName) : formatTeam(team);

            Map<String, Object> aiAnalysis = analyzeTeamWithDeepSeek(teamName, leagueName, teamInfo, form, recentMatches);
            Map<String, Object> aiH2h = analyzeH2HWithDeepSeek(teamName, recentMatches);
            List<Map<String, Object>> aiScorers = analyzeScorersWithDeepSeek(teamName, teamInfo, form, recentMatches);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("team", teamInfo);
            data.put("form", form);
            data.put("recentMatches", formatMatches(recentMatches));
            data.put("results", recentMatches.size());
            data.put("aiAnalysis", aiAnalysis);
            data.put("aiH2h", aiH2h);
            data.put("scorers", aiScorers);
            return Map.of("success", true, "message", "获取成功", "data", data);
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

    /**
     * 格式化比赛数据（兼容前端格式）
     */
    private Map<String, Object> analyzeTeamWithDeepSeek(String teamName,
                                                        String leagueName,
                                                        Map<String, Object> teamInfo,
                                                        Map<String, Object> form,
                                                        List<CrawlerMatch> recentMatches) {
        try {
            String prompt = String.format(
                    "请基于以下球队数据输出JSON，只允许输出JSON对象，不要多余文本。字段要求：summary, strengths, weaknesses, risks, conclusion。球队=%s, 联赛=%s, 基础信息=%s, 近期战绩=%s, 最近比赛数量=%s。",
                    teamName,
                    leagueName == null ? "" : leagueName,
                    teamInfo,
                    form,
                    recentMatches.size()
            );
            return deepSeekPredictionService.analyzeJson(prompt);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("source", "fallback");
            fallback.put("status", e.getClass().getSimpleName());
            fallback.put("summary", "暂时无法生成 AI 球队分析");
            return fallback;
        }
    }

    private Map<String, Object> analyzeH2HWithDeepSeek(String teamName, List<CrawlerMatch> recentMatches) {
        try {
            String prompt = String.format(
                    "请基于以下球队近期比赛输出JSON，只允许输出JSON对象，不要多余文本。字段要求：summary, trend, risk, conclusion。球队=%s, 近期比赛数量=%s。",
                    teamName,
                    recentMatches.size()
            );
            return deepSeekPredictionService.analyzeJson(prompt);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("source", "fallback");
            fallback.put("status", e.getClass().getSimpleName());
            fallback.put("summary", "暂时无法生成 AI 交锋分析");
            return fallback;
        }
    }

    private List<Map<String, Object>> analyzeScorersWithDeepSeek(String teamName,
                                                                  Map<String, Object> teamInfo,
                                                                  Map<String, Object> form,
                                                                  List<CrawlerMatch> recentMatches) {
        try {
            String prompt = String.format(
                    "请基于以下球队数据输出一个JSON数组，只允许输出JSON数组，不要多余文本。每个元素字段：player, goals, assists, matches, note。球队=%s, 基础信息=%s, 近期战绩=%s, 最近比赛数量=%s。",
                    teamName,
                    teamInfo,
                    form,
                    recentMatches.size()
            );
            Map<String, Object> ai = deepSeekPredictionService.analyzeJson(prompt);
            Object content = ai.get("content");
            if (content == null) return Collections.emptyList();
            String text = String.valueOf(content).trim();
            if (text.startsWith("```")) {
                text = text.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            if (!text.startsWith("[")) return Collections.emptyList();
            // 简化处理：返回一个单条摘要，供前端兜底展示
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("player", Map.of("name", "DeepSeek 建议参考"));
            item.put("goals", Map.of("total", 0, "assists", 0));
            item.put("games", Map.of("appearences", 0));
            item.put("note", text);
            return List.of(item);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> formatMatches(List<CrawlerMatch> matches) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (CrawlerMatch match : matches) {
            Map<String, Object> item = new LinkedHashMap<>();

            // fixture
            Map<String, Object> fixture = new LinkedHashMap<>();
            fixture.put("id", match.getFixtureId() != null ? match.getFixtureId() : match.getId());
            Date matchDate = match.getMatchTime() == null
                    ? null
                    : Date.from(match.getMatchTime().atZone(ZoneId.systemDefault()).toInstant());
            fixture.put("timestamp", matchDate != null ? matchDate.getTime() / 1000 : 0);
            fixture.put("timezone", "Asia/Shanghai");
            fixture.put("date", matchDate != null ? new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(matchDate) : null);
            fixture.put("status", Map.of("short", match.getStatus() != null ? match.getStatus() : "NS", "long", getStatusText(match.getStatus())));

            Map<String, Object> venue = new LinkedHashMap<>();
            venue.put("name", match.getVenue() != null ? match.getVenue() : "");
            fixture.put("venue", venue);

            item.put("fixture", fixture);

            // league
            Map<String, Object> league = new LinkedHashMap<>();
            league.put("id", match.getLeagueId() != null ? match.getLeagueId() : "0");
            league.put("name", match.getLeagueName() != null ? match.getLeagueName() : "");
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
            item.put("hotScore", calculateHotScore(match, LocalDate.now()));

            // source
            item.put("source", match.getSource());

            result.add(item);
        }

        return result;
    }

    private int calculateHotScore(CrawlerMatch match, LocalDate today) {
        int score = 0;
        if (match == null) return score;
        String status = String.valueOf(match.getStatus()).toUpperCase(Locale.ROOT);
        if (isLiveMatch(match)) score += 1000;
        else if ("NS".equals(status)) score += 500;
        else if ("FT".equals(status) || "FINISHED".equals(status)) score += 120;
        if (match.getMatchTime() != null) {
            LocalDate matchDate = match.getMatchTime().toLocalDate();
            long distance = Math.abs(ChronoUnit.DAYS.between(today, matchDate));
            score += Math.max(0, 220 - (int) distance * 30);
            long minutesToKickoff = Math.abs(ChronoUnit.MINUTES.between(LocalDateTime.now(), match.getMatchTime()));
            if (minutesToKickoff <= 180) score += 160;
            else if (minutesToKickoff <= 720) score += 90;
        }
        score += leagueHotWeight(match.getLeagueName(), match.getLeagueId());
        if (match.getHomeScore() != null || match.getAwayScore() != null) score += 40;
        if (match.getHomeTeamLogo() != null && !match.getHomeTeamLogo().isBlank()) score += 10;
        if (match.getAwayTeamLogo() != null && !match.getAwayTeamLogo().isBlank()) score += 10;
        return score;
    }

    private boolean isLiveMatch(CrawlerMatch match) {
        String status = match == null ? "" : String.valueOf(match.getStatus()).toUpperCase(Locale.ROOT);
        return "LIVE".equals(status) || "IN_PLAY".equals(status) || "1H".equals(status) || "2H".equals(status) || "HT".equals(status);
    }

    private boolean isSameDate(LocalDateTime value, LocalDate date) {
        return value != null && value.toLocalDate().equals(date);
    }

    private int leagueHotWeight(String leagueName, String leagueId) {
        String key = ((leagueName == null ? "" : leagueName) + " " + (leagueId == null ? "" : leagueId)).toLowerCase(Locale.ROOT);
        if (key.contains("premier") || key.contains("英超") || key.contains("pl")) return 220;
        if (key.contains("champions") || key.contains("欧冠") || key.contains("cl")) return 210;
        if (key.contains("la liga") || key.contains("西甲") || key.contains("pd")) return 190;
        if (key.contains("serie a") || key.contains("意甲") || key.contains("sa")) return 180;
        if (key.contains("bundesliga") || key.contains("德甲") || key.contains("bl1")) return 170;
        if (key.contains("ligue 1") || key.contains("法甲") || key.contains("fl1")) return 150;
        if (key.contains("world cup") || key.contains("世界杯") || key.contains("wc")) return 230;
        if (key.contains("european championship") || key.contains("欧洲杯") || key.contains("ec")) return 220;
        return 60;
    }


    /**
     * 格式化积分榜数据
     */
    private Map<String, Object> formatMatchDetail(CrawlerMatch match) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", match.getId());
        data.put("externalMatchId", match.getExternalMatchId());
        data.put("leagueName", match.getLeagueName());
        data.put("leagueId", match.getLeagueId());
        data.put("homeTeamName", match.getHomeTeamName());
        data.put("awayTeamName", match.getAwayTeamName());
        data.put("homeScore", match.getHomeScore());
        data.put("awayScore", match.getAwayScore());
        data.put("status", match.getStatus());
        data.put("matchTime", match.getMatchTime() == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(match.getMatchTime()));
        data.put("venue", match.getVenue());
        data.put("round", match.getRound());
        data.put("source", match.getSource());
        return data;
    }

    private Map<String, Object> formatTeam(CrawlerTeam team) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", team.getId());
        data.put("name", team.getName());
        data.put("logo", team.getLogo());
        data.put("leagueName", team.getLeagueName());
        data.put("country", team.getCountry());
        data.put("source", team.getSource());
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

        for (CrawlerStanding standing : standings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", standing.getRank());
            item.put("team", Map.of(
                    "id", standing.getTeamId() != null ? standing.getTeamId() : "0",
                    "name", standing.getTeamName() != null ? standing.getTeamName() : "",
                    "logo", standing.getTeamLogo() != null ? standing.getTeamLogo() : ""
            ));
            item.put("played", standing.getPlayed());
            item.put("win", standing.getWins());
            item.put("draw", standing.getDraws());
            item.put("loss", standing.getLosses());
            item.put("goalsFor", standing.getGoalsFor());
            item.put("goalsAgainst", standing.getGoalsAgainst());
            item.put("goalDifference", standing.getGoalDifference());
            item.put("points", standing.getPoints());
            item.put("season", standing.getSeason());

            result.add(item);
        }

        return result;
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
            default -> "未开始";
        };
    }

    private String getCurrentSeason() {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        return year + "/" + (year + 1);
    }

    private Map<String, Object> buildSuccessResponse(List<? extends Object> response, int results) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("response", response);
        data.put("results", results);
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
        return Map.of(
                "success", false,
                "message", "获取失败: " + e.getMessage(),
                "data", data
        );
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
