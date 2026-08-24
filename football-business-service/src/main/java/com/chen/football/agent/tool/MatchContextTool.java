package com.chen.football.agent.tool;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.source.ProductionLeagueScope;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MatchContextTool implements AgentTool {

    private final CrawlerMatchMapper crawlerMatchMapper;
    private final CrawlerProperties crawlerProperties;

    public MatchContextTool(CrawlerMatchMapper crawlerMatchMapper, CrawlerProperties crawlerProperties) {
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.crawlerProperties = crawlerProperties;
    }

    @Override
    public String name() {
        return "match_context";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        Long fixtureId = toLong(context.get("fixtureId"));
        // crawler_matches 使用第三方字符串球队 ID；不要将非数字 ID 强转为 Long 后丢失。
        String homeTeamId = string(context.get("homeTeamId"));
        String awayTeamId = string(context.get("awayTeamId"));
        String homeTeamName = string(context.get("homeTeamName"));
        String awayTeamName = string(context.get("awayTeamName"));
        String leagueName = string(context.get("leagueName"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fixtureId", fixtureId);
        data.put("homeTeamId", homeTeamId);
        data.put("awayTeamId", awayTeamId);
        data.put("homeTeamName", homeTeamName);
        data.put("awayTeamName", awayTeamName);
        data.put("leagueName", leagueName);
        data.put("recentHomeMatches", visible(homeTeamName == null ? List.of() : crawlerMatchMapper.findCompletedRecentByTeamName(homeTeamName, 10)));
        data.put("recentAwayMatches", visible(awayTeamName == null ? List.of() : crawlerMatchMapper.findCompletedRecentByTeamName(awayTeamName, 10)));
        data.put("headToHead", visible((homeTeamName == null || awayTeamName == null) ? List.of() : crawlerMatchMapper.findHeadToHead(homeTeamName, awayTeamName, 10)));
        CrawlerMatch candidate = fixtureId == null ? null : crawlerMatchMapper.findByPublicId(fixtureId);
        if (candidate == null && fixtureId == null && homeTeamName != null && awayTeamName != null) {
            candidate = crawlerMatchMapper.findUpcomingByTeams(homeTeamName, awayTeamName, leagueName);
        }
        if (!ProductionLeagueScope.isVisible(candidate, crawlerProperties)) candidate = null;
        data.put("candidateMatches", candidate == null ? List.of() : List.of(candidate));
        if (candidate != null) {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("fixtureId", candidate.getFixtureId() != null ? candidate.getFixtureId() : candidate.getId());
            canonical.put("homeTeamId", candidate.getHomeTeamId());
            canonical.put("homeTeamName", candidate.getHomeTeamName());
            canonical.put("homeTeamLogo", candidate.getHomeTeamLogo());
            canonical.put("awayTeamId", candidate.getAwayTeamId());
            canonical.put("awayTeamName", candidate.getAwayTeamName());
            canonical.put("awayTeamLogo", candidate.getAwayTeamLogo());
            canonical.put("leagueName", candidate.getLeagueName());
            canonical.put("matchTime", candidate.getMatchTime());
            canonical.put("status", candidate.getStatus());
            canonical.put("source", candidate.getSource());
            canonical.put("sourceUpdatedAt", candidate.getUpdatedAt());
            data.put("canonicalMatch", canonical);
            boolean contextConflict = (homeTeamName != null && candidate.getHomeTeamName() != null
                    && !sameName(homeTeamName, candidate.getHomeTeamName()))
                    || (awayTeamName != null && candidate.getAwayTeamName() != null
                    && !sameName(awayTeamName, candidate.getAwayTeamName()));
            data.put("contextConflict", contextConflict);
            data.put("status", contextConflict ? "CONFLICT" : "AVAILABLE");
        } else {
            data.put("status", homeTeamName == null || awayTeamName == null ? "MISSING_INPUT" : "EMPTY");
        }
        data.put("message", candidate != null
                ? "已读取比赛及球队上下文"
                : (homeTeamName == null || awayTeamName == null ? "缺少比赛双方，暂不查询比赛资料" : "已识别双方，但当前没有可用比赛记录"));
        return data;
    }

    private boolean sameName(String left, String right) {
        return left.trim().equalsIgnoreCase(right.trim());
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
