package com.chen.football.agent.tool;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.source.ProductionLeagueScope;
import com.chen.football.match.service.MatchDetailsService;
import com.chen.football.prediction.service.PrematchFeatureService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Read-only, database-first view of injuries, lineups and historical xG
 * features.  It never refreshes a provider in a chat request.
 */
@Component
public class PrematchDataContextTool implements AgentTool {
    private final CrawlerMatchMapper crawlerMatchMapper;
    private final CrawlerProperties crawlerProperties;
    private final MatchDetailsService matchDetailsService;
    private final PrematchFeatureService prematchFeatureService;

    public PrematchDataContextTool(CrawlerMatchMapper crawlerMatchMapper,
                                   CrawlerProperties crawlerProperties,
                                   MatchDetailsService matchDetailsService,
                                   PrematchFeatureService prematchFeatureService) {
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.crawlerProperties = crawlerProperties;
        this.matchDetailsService = matchDetailsService;
        this.prematchFeatureService = prematchFeatureService;
    }

    @Override
    public String name() { return "prematch_data_context"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        Long fixtureId = number(context.get("fixtureId"));
        CrawlerMatch match = fixtureId == null ? resolveByTeams(context) : crawlerMatchMapper.findByPublicId(fixtureId);
        if (!ProductionLeagueScope.isVisible(match, crawlerProperties)) match = null;
        if (match == null) {
            return Map.of("status", fixtureId == null ? "MISSING_INPUT" : "EMPTY",
                    "message", fixtureId == null ? "缺少具体比赛，无法读取赛前数据" : "未找到该比赛的本地记录",
                    "source", "API-Football 赛前详情缓存 + Understat xG");
        }
        long publicId = match.getFixtureId() != null && match.getFixtureId() > 0 ? match.getFixtureId() : match.getId();
        Map<String, Object> details = matchDetailsService.getDetails(publicId, false);
        Map<String, Object> feature = prematchFeatureService.getSnapshot(publicId);
        Map<String, Object> statuses = details.get("statuses") instanceof Map<?, ?> raw ? cast(raw) : Map.of();
        Map<String, Object> quality = feature.get("dataQuality") instanceof Map<?, ?> raw ? cast(raw) : Map.of();

        List<?> lineups = details.get("details") instanceof Map<?, ?> raw && raw.get("lineups") instanceof List<?> list ? list : List.of();
        List<?> injuries = details.get("details") instanceof Map<?, ?> raw && raw.get("injuries") instanceof List<?> list ? list : List.of();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fixtureId", publicId);
        data.put("match", Map.of("homeTeamName", Objects.toString(match.getHomeTeamName(), ""),
                "awayTeamName", Objects.toString(match.getAwayTeamName(), ""),
                "leagueName", Objects.toString(match.getLeagueName(), ""),
                "matchTime", match.getMatchTime() == null ? "" : match.getMatchTime().toString()));
        data.put("lineups", lineupSummary(lineups));
        data.put("injuries", injurySummary(injuries));
        data.put("xg", xgSummary(feature, quality));
        data.put("statuses", statuses);
        data.put("featureStatus", feature.getOrDefault("status", "UNKNOWN"));
        data.put("source", "API-Football 赛前详情缓存 + Understat 历史 xG");
        data.put("observedAt", details.getOrDefault("lastUpdated", feature.get("sourceUpdatedAt")));
        String status = overallStatus(statuses, quality, lineups, injuries, feature);
        data.put("status", status);
        data.put("message", message(status));
        return data;
    }

    private CrawlerMatch resolveByTeams(Map<String, Object> context) {
        String home = text(context.get("homeTeamName"));
        String away = text(context.get("awayTeamName"));
        if (home.isBlank() || away.isBlank()) return null;
        return crawlerMatchMapper.findUpcomingByTeams(home, away, text(context.get("leagueName")));
    }

    private Map<String, Object> lineupSummary(List<?> rows) {
        int teams = 0, starters = 0;
        for (Object row : rows) if (row instanceof Map<?, ?> map) {
            teams++;
            Object start = map.get("startXI");
            if (start instanceof List<?> list) starters += list.size();
        }
        return Map.of("status", teams > 0 ? "AVAILABLE" : "EMPTY", "teams", teams, "starters", starters);
    }

    private Map<String, Object> injurySummary(List<?> rows) {
        Map<String, Integer> byTeam = new LinkedHashMap<>();
        for (Object row : rows) if (row instanceof Map<?, ?> map) {
            Object team = map.get("team");
            String teamName = team instanceof Map<?, ?> tm ? text(tm.get("name")) : "未知球队";
            byTeam.put(teamName, byTeam.getOrDefault(teamName, 0) + 1);
        }
        return Map.of("status", rows.isEmpty() ? "EMPTY" : "AVAILABLE", "total", rows.size(), "byTeam", byTeam);
    }

    private Map<String, Object> xgSummary(Map<String, Object> feature, Map<String, Object> quality) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", quality.getOrDefault("xgShots", "NOT_CONFIGURED"));
        result.put("homeRecentXg5", feature.getOrDefault("homeXg5", 0));
        result.put("awayRecentXg5", feature.getOrDefault("awayXg5", 0));
        result.put("homeRecentXga5", feature.getOrDefault("homeXga5", 0));
        result.put("awayRecentXga5", feature.getOrDefault("awayXga5", 0));
        result.put("source", "Understat/API-Football 历史快照");
        result.put("note", "这是两队近期历史 xG 均值，不是目标比赛的赛后 xG");
        return result;
    }

    private String overallStatus(Map<String, Object> statuses, Map<String, Object> quality,
                                 List<?> lineups, List<?> injuries, Map<String, Object> feature) {
        boolean any = !lineups.isEmpty() || !injuries.isEmpty() || "AVAILABLE".equalsIgnoreCase(text(quality.get("xgShots")));
        if (!any && statuses.values().stream().anyMatch(value -> Set.of("QUOTA_LIMITED", "REQUEST_FAILED").contains(text(value).toUpperCase(Locale.ROOT)))) return "REQUEST_FAILED";
        return any ? "AVAILABLE" : (feature.isEmpty() ? "EMPTY" : "PARTIAL");
    }

    private String message(String status) {
        return switch (status) {
            case "AVAILABLE" -> "已读取可核验的赛前增强数据";
            case "PARTIAL" -> "已读取部分赛前数据，仍有字段缺失";
            case "REQUEST_FAILED" -> "赛前数据源请求失败或额度受限，未使用猜测值";
            default -> "当前没有可核验的赛前增强数据";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Long number(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ex) { return null; } }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
