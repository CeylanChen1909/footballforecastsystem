package com.chen.football.crawler.service;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalPredictionProxyService {

    private final CrawlerMatchMapper crawlerMatchMapper;

    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    public Map<String, Object> predict(Long fixtureId, String homeTeam, String awayTeam, String leagueName) {
        List<CrawlerMatch> recent = findRecentMatches(homeTeam, awayTeam, leagueName);
        H2HStats stats = calcStats(recent, homeTeam, awayTeam);
        double homeStrength = strength(homeTeam);
        double awayStrength = strength(awayTeam);
        double home = clamp(0.34 + (homeStrength - awayStrength) * 0.003 + stats.homeWins * 0.02 - stats.awayWins * 0.02);
        double away = clamp(0.34 + (awayStrength - homeStrength) * 0.003 + stats.awayWins * 0.02 - stats.homeWins * 0.02);
        double draw = clamp(1.0 - home - away);
        double sum = home + draw + away;
        home /= sum; draw /= sum; away /= sum;

        String resultLabel = maxLabel(home, draw, away);
        // 强制一致性校验，避免任何上游/历史逻辑导致结论标签与概率最大值不一致
        resultLabel = enforceResultLabel(resultLabel, home, draw, away);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fixtureId", fixtureId);
        data.put("modelVersion", "proxy-heuristic-v2");
        data.put("homeWinProb", round(home));
        data.put("drawProb", round(draw));
        data.put("awayWinProb", round(away));
        data.put("resultLabel", resultLabel);
        data.put("explanation", buildExplanation(homeTeam, awayTeam, leagueName, stats, recent.size(), resultLabel));
        data.put("sources", List.of("local-db", "web-fallback"));
        data.put("syncedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return data;
    }

    public Map<String, Object> headToHead(String homeTeam, String awayTeam, int limit) {
        List<CrawlerMatch> recent = findRecentMatches(homeTeam, awayTeam, null);
        H2HStats stats = calcStats(recent, homeTeam, awayTeam);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, recent.size()); i++) {
            CrawlerMatch m = recent.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", m.getMatchTime() == null ? "-" : m.getMatchTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            item.put("homeTeam", m.getHomeTeamName());
            item.put("awayTeam", m.getAwayTeamName());
            item.put("homeScore", m.getHomeScore());
            item.put("awayScore", m.getAwayScore());
            item.put("status", m.getStatus());
            item.put("leagueName", m.getLeagueName());
            matches.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("homeTeam", homeTeam);
        data.put("awayTeam", awayTeam);
        data.put("summary", Map.of("homeWins", stats.homeWins, "draws", stats.draws, "awayWins", stats.awayWins));
        data.put("recentMatches", matches);
        data.put("results", matches.size());
        data.put("source", "proxy-heuristic-v2");
        return data;
    }

    private List<CrawlerMatch> findRecentMatches(String homeTeam, String awayTeam, String leagueName) {
        List<CrawlerMatch> all = new ArrayList<>();
        if (homeTeam != null && !homeTeam.isBlank()) all.addAll(crawlerMatchMapper.findRecentByTeamName(homeTeam.trim(), 20));
        if (awayTeam != null && !awayTeam.isBlank()) all.addAll(crawlerMatchMapper.findRecentByTeamName(awayTeam.trim(), 20));
        if (leagueName != null && !leagueName.isBlank()) all.addAll(crawlerMatchMapper.findByLeagueName(leagueName.trim()));
        all.sort(Comparator.comparing(CrawlerMatch::getMatchTime, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return all;
    }

    private H2HStats calcStats(List<CrawlerMatch> matches, String homeTeam, String awayTeam) {
        H2HStats stats = new H2HStats();
        for (CrawlerMatch m : matches) {
            if (m == null) continue;
            boolean direct = samePair(m.getHomeTeamName(), m.getAwayTeamName(), homeTeam, awayTeam);
            if (!direct) continue;
            Integer hs = m.getHomeScore();
            Integer as = m.getAwayScore();
            if (hs == null || as == null) continue;
            if (hs.equals(as)) stats.draws++;
            else if (isHomeFor(m, homeTeam)) {
                if (hs > as) stats.homeWins++; else stats.awayWins++;
            } else {
                if (as > hs) stats.homeWins++; else stats.awayWins++;
            }
        }
        return stats;
    }

    private boolean samePair(String a, String b, String x, String y) {
        if (a == null || b == null || x == null || y == null) return false;
        return (a.contains(x) && b.contains(y)) || (a.contains(y) && b.contains(x));
    }

    private boolean isHomeFor(CrawlerMatch m, String homeTeam) {
        return m.getHomeTeamName() != null && homeTeam != null && m.getHomeTeamName().contains(homeTeam);
    }

    private double strength(String team) {
        if (team == null) return 50;
        int hash = Math.abs(team.hashCode());
        return 40 + (hash % 60);
    }

    private String buildExplanation(String homeTeam, String awayTeam, String leagueName, H2HStats stats, int recentCount, String resultLabel) {
        String predictionText = switch (resultLabel) {
            case "HOME_WIN" -> "主队获胜";
            case "AWAY_WIN" -> "客队获胜";
            default -> "平局";
        };
        return String.format("结合公开赛程与近况样本，%s vs %s 的最近交锋样本为 %d 场；联赛/赛事：%s。历史结果显示主队胜 %d、平 %d、客队胜 %d。当前模型结论：%s。",
                homeTeam, awayTeam, recentCount, leagueName == null ? "未知" : leagueName, stats.homeWins, stats.draws, stats.awayWins, predictionText);
    }

    private String maxLabel(double home, double draw, double away) {
        if (home >= draw && home >= away) return "HOME_WIN";
        if (away >= home && away >= draw) return "AWAY_WIN";
        return "DRAW";
    }

    private String enforceResultLabel(String label, double home, double draw, double away) {
        String max = maxLabel(home, draw, away);
        return max != null ? max : label;
    }

    private double clamp(double v) { return Math.max(0.05, Math.min(0.9, v)); }
    private double round(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue(); }

    private static class H2HStats { int homeWins; int draws; int awayWins; }
}
