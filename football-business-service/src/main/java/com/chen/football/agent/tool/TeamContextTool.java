package com.chen.football.agent.tool;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerTeam;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.mapper.CrawlerTeamMapper;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.source.ProductionLeagueScope;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TeamContextTool implements AgentTool {

    private final CrawlerMatchMapper crawlerMatchMapper;
    private final CrawlerTeamMapper crawlerTeamMapper;
    private final CrawlerProperties crawlerProperties;

    public TeamContextTool(CrawlerMatchMapper crawlerMatchMapper, CrawlerTeamMapper crawlerTeamMapper, CrawlerProperties crawlerProperties) {
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.crawlerTeamMapper = crawlerTeamMapper;
        this.crawlerProperties = crawlerProperties;
    }

    @Override
    public String name() {
        return "team_context";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        Long teamId = toLong(context.get("teamId"));
        Long comparisonTeamId = toLong(context.get("comparisonTeamId"));
        String teamName = string(context.get("teamName"));
        String homeTeamName = string(context.get("homeTeamName"));
        String awayTeamName = string(context.get("awayTeamName"));
        String comparisonTeamName = string(context.get("comparisonTeamName"));
        if (teamName == null || teamName.isBlank()) teamName = homeTeamName;
        if (awayTeamName == null || awayTeamName.isBlank()) awayTeamName = comparisonTeamName;

        CrawlerTeam team = teamId == null ? null : crawlerTeamMapper.selectById(teamId);
        if ((teamName == null || teamName.isBlank()) && team != null) teamName = team.getName();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamId", teamId);
        data.put("teamName", teamName);
        data.put("awayTeamName", awayTeamName);
        data.put("comparisonTeamName", comparisonTeamName);
        if (teamName == null || teamName.isBlank()) {
            data.put("status", "MISSING_INPUT");
            data.put("message", "缺少球队名称，暂不查询球队资料");
            data.put("teamInfo", null);
            data.put("recentMatches", List.of());
            data.put("awayRecentMatches", List.of());
            return data;
        }

        if (team == null) team = crawlerTeamMapper.findPreferredByName(teamName);
        CrawlerTeam comparisonTeam = comparisonTeamId != null
                ? crawlerTeamMapper.selectById(comparisonTeamId)
                : (awayTeamName == null || awayTeamName.isBlank() ? null : crawlerTeamMapper.findPreferredByName(awayTeamName));
        if (!ProductionLeagueScope.isVisible(team, crawlerProperties)) team = null;
        if (!ProductionLeagueScope.isVisible(comparisonTeam, crawlerProperties)) comparisonTeam = null;
        List<CrawlerMatch> recentMatches = visible(crawlerMatchMapper.findCompletedRecentByTeamName(teamName, 10));
        List<CrawlerMatch> awayRecentMatches = awayTeamName == null || awayTeamName.isBlank()
                ? List.of() : visible(crawlerMatchMapper.findCompletedRecentByTeamName(awayTeamName, 10));

        data.put("teamInfo", team);
        data.put("teamId", team == null ? teamId : team.getId());
        data.put("comparisonTeamInfo", comparisonTeam);
        data.put("comparisonTeamId", comparisonTeam == null ? comparisonTeamId : comparisonTeam.getId());
        data.put("recentMatches", recentMatches);
        data.put("awayRecentMatches", awayRecentMatches);
        boolean hasPrimaryData = team != null || !recentMatches.isEmpty();
        boolean hasComparisonData = awayTeamName == null || awayTeamName.isBlank()
                || comparisonTeam != null || !awayRecentMatches.isEmpty();
        boolean hasRecentData = !recentMatches.isEmpty() || !awayRecentMatches.isEmpty();
        data.put("status", hasPrimaryData && hasComparisonData
                ? (hasRecentData ? "AVAILABLE" : "PARTIAL") : "EMPTY");
        data.put("message", hasPrimaryData
                ? (hasComparisonData ? (recentMatches.isEmpty() && awayRecentMatches.isEmpty()
                        ? "已读取球队资料，但双方暂无已完赛近期记录" : "已读取球队资料与已完赛近期记录")
                        : "主队资料可用，但对比球队暂无已完赛近期记录")
                : "已识别球队，但当前数据库没有可用资料");
        return data;
    }

    private List<CrawlerMatch> visible(List<CrawlerMatch> rows) {
        return rows == null ? List.of() : rows.stream()
                .filter(row -> ProductionLeagueScope.isVisible(row, crawlerProperties))
                .toList();
    }

    private Long toLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
