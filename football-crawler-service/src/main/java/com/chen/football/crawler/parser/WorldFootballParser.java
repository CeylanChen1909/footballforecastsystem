package com.chen.football.crawler.parser;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerStanding;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * worldfootball.net 解析器
 * 作为主爬源，优先采集赛果、赛程和积分榜
 */
@Slf4j
@Component
public class WorldFootballParser {

    private static final String SOURCE = "worldfootball";
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+)\\s*[:\\-]\\s*(\\d+)");
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");

    public List<CrawlerMatch> parseMatchList(String html, String leagueName) {
        List<CrawlerMatch> matches = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return matches;
        }

        try {
            Document doc = Jsoup.parse(html);
            String detectedLeague = normalizeLeagueName(leagueName, doc.title(), doc.text());
            log.debug("worldfootball 页面标题: {}", doc.title());
            log.debug("worldfootball 页面正文长度: {}", doc.text().length());

            Elements candidateTables = doc.select("table.standard_tabelle, table.tablesorter, .competition-table table, table.results, table[class*=table]");
            if (candidateTables.isEmpty()) {
                candidateTables = doc.select("table");
            }

            for (Element table : candidateTables) {
                if (!looksLikeMatchTable(table)) {
                    continue;
                }
                matches.addAll(parseMatchTable(table, detectedLeague));
            }

            if (matches.isEmpty()) {
                Elements rows = doc.select("tr");
                for (Element row : rows) {
                    CrawlerMatch match = parseMatchRow(row, detectedLeague);
                    if (match != null) {
                        matches.add(match);
                    }
                }
            }

            log.info("worldfootball 解析到 {} 场比赛", matches.size());
        } catch (Exception e) {
            log.warn("worldfootball 比赛解析失败: {}", e.getMessage());
        }
        return matches;
    }

    public List<CrawlerStanding> parseStandings(String html, String leagueName, String leagueId, String season) {
        List<CrawlerStanding> standings = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return standings;
        }

        try {
            Document doc = Jsoup.parse(html);
            String detectedLeague = normalizeLeagueName(leagueName, doc.title(), doc.text());
            Elements tables = doc.select("table.standard_tabelle, table.tablesorter, .standing table, .competition-table table, table");
            for (Element table : tables) {
                Elements rows = table.select("tr");
                for (Element row : rows) {
                    CrawlerStanding standing = parseStandingRow(row, detectedLeague, leagueId, season);
                    if (standing != null) {
                        standings.add(standing);
                    }
                }
            }
            if (standings.isEmpty()) {
                Elements rows = doc.select("tr");
                for (Element row : rows) {
                    CrawlerStanding standing = parseStandingRow(row, detectedLeague, leagueId, season);
                    if (standing != null) {
                        standings.add(standing);
                    }
                }
            }
            log.info("worldfootball 解析到 {} 条积分榜", standings.size());
        } catch (Exception e) {
            log.warn("worldfootball 积分榜解析失败: {}", e.getMessage());
        }
        return standings;
    }

    private List<CrawlerMatch> parseMatchTable(Element table, String detectedLeague) {
        List<CrawlerMatch> matches = new ArrayList<>();
        for (Element row : table.select("tr")) {
            CrawlerMatch match = parseMatchRow(row, detectedLeague);
            if (match != null) {
                matches.add(match);
            }
        }
        return matches;
    }

    private boolean looksLikeMatchTable(Element table) {
        String text = table.text().toLowerCase(Locale.ROOT);
        return text.contains("vs") || text.contains("-:-") || text.contains(":") || text.contains("finished") || text.contains("postponed");
    }

    private CrawlerMatch parseMatchRow(Element row, String detectedLeague) {
        Elements cells = row.select("td");
        if (cells.size() < 3) {
            return null;
        }

        String rowText = row.text().trim();
        if (rowText.isBlank()) {
            return null;
        }

        String homeTeam;
        String awayTeam;
        String scoreText = "";
        String timeText = rowText;

        if (cells.size() >= 4) {
            homeTeam = clean(cells.get(1).text());
            scoreText = cells.get(2).text();
            awayTeam = clean(cells.get(3).text());
        } else {
            String[] parts = rowText.split("\\s{2,}");
            if (parts.length < 3) {
                return null;
            }
            timeText = parts[0];
            homeTeam = clean(parts[1]);
            awayTeam = clean(parts[parts.length - 1]);
            if (parts.length >= 4) {
                scoreText = parts[2];
            }
        }

        if (homeTeam.isBlank() || awayTeam.isBlank()) {
            return null;
        }

        if (looksLikeHeaderRow(homeTeam, awayTeam, rowText)) {
            return null;
        }

        CrawlerMatch match = new CrawlerMatch();
        match.setSource(SOURCE);
        match.setLeagueName(detectedLeague);
        match.setHomeTeamName(homeTeam);
        match.setAwayTeamName(awayTeam);
        match.setExternalMatchId(buildExternalId(homeTeam, awayTeam, rowText));
        match.setStatus(parseStatus(rowText));
        match.setMatchTime(parseMatchTime(timeText + " " + rowText));

        Integer[] score = parseScore(scoreText.isBlank() ? rowText : scoreText);
        if (score != null) {
            match.setHomeScore(score[0]);
            match.setAwayScore(score[1]);
            if ("NS".equals(match.getStatus())) {
                match.setStatus("FT");
            }
        }

        match.setCreatedAt(java.time.LocalDateTime.now());
        match.setUpdatedAt(java.time.LocalDateTime.now());
        return match;
    }

    private boolean looksLikeHeaderRow(String homeTeam, String awayTeam, String rowText) {
        String text = (homeTeam + " " + awayTeam + " " + rowText).toLowerCase(Locale.ROOT);
        return text.contains("round") || text.contains("home") || text.contains("away") || text.contains("date");
    }

    private CrawlerStanding parseStandingRow(Element row, String leagueName, String leagueId, String season) {
        Elements cells = row.select("td");
        if (cells.size() < 5) {
            return null;
        }

        String rankText = clean(cells.get(0).text());
        if (!rankText.matches("\\d+")) {
            return null;
        }

        CrawlerStanding standing = new CrawlerStanding();
        standing.setLeagueName(leagueName);
        standing.setLeagueId(leagueId);
        standing.setSeason(season);
        standing.setSource(SOURCE);
        standing.setRank(Integer.parseInt(rankText));
        standing.setTeamName(clean(cells.get(1).text()));
        standing.setPlayed(toInt(cells, 2));
        standing.setWins(toInt(cells, 3));
        standing.setDraws(toInt(cells, 4));
        standing.setLosses(toInt(cells, 5));
        standing.setGoalsFor(toInt(cells, 6));
        standing.setGoalsAgainst(toInt(cells, 7));
        standing.setGoalDifference(toInt(cells, 8));
        standing.setPoints(toInt(cells, 9));
        standing.setCreatedAt(java.time.LocalDateTime.now());
        standing.setUpdatedAt(java.time.LocalDateTime.now());
        return standing;
    }

    private Integer[] parseScore(String scoreText) {
        if (scoreText == null) {
            return null;
        }
        Matcher matcher = SCORE_PATTERN.matcher(scoreText);
        if (matcher.find()) {
            return new Integer[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        }
        return null;
    }

    private LocalDateTime parseMatchTime(String rowText) {
        if (rowText == null) {
            return LocalDateTime.now();
        }
        Matcher matcher = DATE_PATTERN.matcher(rowText);
        if (matcher.find()) {
            try {
                int day = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int year = Integer.parseInt(matcher.group(3));
                return java.time.LocalDate.of(year, month, day).atStartOfDay();
            } catch (Exception ignored) {
            }
        }
        return LocalDateTime.now();
    }

    private String normalizeLeagueName(String fallback, String title, String bodyText) {
        String text = ((title == null ? "" : title) + " " + (bodyText == null ? "" : bodyText)).toLowerCase(Locale.ROOT);
        if (text.contains("premier league")) return "英超";
        if (text.contains("la liga")) return "西甲";
        if (text.contains("serie a")) return "意甲";
        if (text.contains("bundesliga")) return "德甲";
        if (text.contains("ligue 1")) return "法甲";
        if (text.contains("champions league")) return "欧冠";
        return fallback == null || fallback.isBlank() ? "worldfootball" : fallback;
    }

    private String parseStatus(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("finished") || lower.contains("final") || lower.contains("ft")) {
            return "FT";
        }
        if (lower.contains("live") || lower.contains("minute")) {
            return "LIVE";
        }
        if (lower.contains("postponed") || lower.contains("suspended")) {
            return "POSTP";
        }
        return "NS";
    }

    private int toInt(Elements cells, int index) {
        if (cells.size() <= index) {
            return 0;
        }
        return toInt(cells.get(index).text());
    }

    private int toInt(String value) {
        try {
            String cleaned = clean(value).replace("+", "");
            return cleaned.isBlank() ? 0 : Integer.parseInt(cleaned);
        } catch (Exception e) {
            return 0;
        }
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String buildExternalId(String homeTeam, String awayTeam, String rowText) {
        return (homeTeam + "_" + awayTeam + "_" + rowText).replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "_");
    }
}
