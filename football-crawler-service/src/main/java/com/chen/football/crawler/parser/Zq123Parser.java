package com.chen.football.crawler.parser;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerStanding;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 球探网数据解析器
 * 数据源: https://www.zq123.com
 */
@Slf4j
@Component
public class Zq123Parser {

    private static final String SOURCE = "zq123";

    // 匹配日期格式: 2024-01-15 或 2024/01/15
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})[/-](\\d{2})[/-](\\d{2})");
    // 匹配时间格式: 14:30 或 02:30
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");
    // 匹配比分格式: 2-1 或 0-0
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+)-(\\d+)");

    /**
     * 解析比赛列表页面
     */
    public List<CrawlerMatch> parseMatchList(String html) {
        List<CrawlerMatch> matches = new ArrayList<>();

        if (html == null || html.isEmpty()) {
            log.warn("HTML内容为空");
            return matches;
        }

        if (looksLikeBlockedPage(html)) {
            log.warn("检测到疑似反爬/拦截页面，跳过解析");
            return matches;
        }

        try {
            Document doc = Jsoup.parse(html);

            // 尝试多种选择器来匹配比赛数据
            Elements matchElements = doc.select("div[id^=match_], tr.match_, div.event, .match-item, .football-match");

            if (matchElements.isEmpty()) {
                // 如果没找到，尝试更通用的选择器
                matchElements = doc.select(".league-table tr, .match-list .item, .game-list li");
            }

            for (Element element : matchElements) {
                try {
                    CrawlerMatch match = parseMatchElement(element);
                    if (match != null) {
                        matches.add(match);
                    }
                } catch (Exception e) {
                    log.debug("解析单个比赛元素失败: {}", e.getMessage());
                }
            }

            log.info("解析到 {} 场比赛", matches.size());
        } catch (Exception e) {
            log.error("解析比赛列表失败: {}", e.getMessage(), e);
        }

        return matches;
    }

    /**
     * 解析单个比赛元素
     */
    private CrawlerMatch parseMatchElement(Element element) {
        CrawlerMatch match = new CrawlerMatch();
        match.setSource(SOURCE);

        // 尝试提取联赛名称
        Element leagueElement = element.selectFirst(".league-name, .lname, [class*=league]");
        if (leagueElement != null) {
            match.setLeagueName(leagueElement.text().trim());
        }

        // 尝试提取主队
        Element homeElement = element.selectFirst(".home-team, .hteam, .team-home, [class*=home]");
        if (homeElement != null) {
            match.setHomeTeamName(cleanTeamName(homeElement.text()));
            // 尝试提取队标
            Element homeLogo = homeElement.selectFirst("img[src*=logo], img[src*=team]");
            if (homeLogo != null) {
                match.setHomeTeamLogo(homeLogo.attr("abs:src"));
            }
        }

        // 尝试提取客队
        Element awayElement = element.selectFirst(".away-team, .ateam, .team-away, [class*=away]");
        if (awayElement != null) {
            match.setAwayTeamName(cleanTeamName(awayElement.text()));
            // 尝试提取队标
            Element awayLogo = awayElement.selectFirst("img[src*=logo], img[src*=team]");
            if (awayLogo != null) {
                match.setAwayTeamLogo(awayLogo.attr("abs:src"));
            }
        }

        // 尝试提取比赛时间
        Element timeElement = element.selectFirst(".match-time, .time, [class*=time]");
        if (timeElement != null) {
            String timeText = timeElement.text().trim();
            match.setMatchTime(parseMatchTime(timeText));
        }

        // 尝试提取比分
        Element scoreElement = element.selectFirst(".score, .result, [class*=score]");
        if (scoreElement != null) {
            String scoreText = scoreElement.text().trim();
            Integer[] scores = parseScore(scoreText);
            if (scores != null) {
                match.setHomeScore(scores[0]);
                match.setAwayScore(scores[1]);
                match.setStatus("FT");
            }
        }

        // 尝试提取比赛状态
        Element statusElement = element.selectFirst(".status, [class*=status]");
        if (statusElement != null) {
            match.setStatus(parseStatus(statusElement.text()));
        }

        // 提取外部ID
        String id = element.id();
        if (id != null && !id.isEmpty()) {
            match.setExternalMatchId(extractMatchId(id));
        }

        // 只返回有效的比赛数据
        if (match.getHomeTeamName() != null && match.getAwayTeamName() != null) {
            match.setCreatedAt(java.time.LocalDateTime.now());
            match.setUpdatedAt(java.time.LocalDateTime.now());
            return match;
        }

        return null;
    }

    /**
     * 解析积分榜页面
     */
    public List<CrawlerStanding> parseStandings(String html, String leagueName, String leagueId, String season) {
        List<CrawlerStanding> standings = new ArrayList<>();

        if (html == null || html.isEmpty()) {
            return standings;
        }

        try {
            Document doc = Jsoup.parse(html);

            // 查找积分榜表格
            Elements tableElements = doc.select("table.standing, table.scoretable, .league-table table, table[class*=rank]");

            for (Element table : tableElements) {
                Elements rows = table.select("tbody tr, tr[class*=team]");

                for (Element row : rows) {
                    try {
                        CrawlerStanding standing = parseStandingRow(row, leagueName, leagueId, season);
                        if (standing != null) {
                            standings.add(standing);
                        }
                    } catch (Exception e) {
                        log.debug("解析积分榜行失败: {}", e.getMessage());
                    }
                }
            }

            log.info("解析到 {} 条积分榜数据", standings.size());
        } catch (Exception e) {
            log.error("解析积分榜失败: {}", e.getMessage(), e);
        }

        return standings;
    }

    /**
     * 解析积分榜单行
     */
    private CrawlerStanding parseStandingRow(Element row, String leagueName, String leagueId, String season) {
        Elements cells = row.select("td, th");
        if (cells.size() < 10) {
            return null;
        }

        CrawlerStanding standing = new CrawlerStanding();
        standing.setLeagueName(leagueName);
        standing.setLeagueId(leagueId);
        standing.setSeason(season);
        standing.setSource(SOURCE);

        try {
            // 排名
            String rankText = cells.get(0).text().trim();
            standing.setRank(Integer.parseInt(rankText));

            // 球队名称
            Element teamCell = cells.get(1);
            standing.setTeamName(cleanTeamName(teamCell.text()));
            Element teamLogo = teamCell.selectFirst("img");
            if (teamLogo != null) {
                standing.setTeamLogo(teamLogo.attr("abs:src"));
            }

            // 场次
            standing.setPlayed(parseIntSafe(cells.get(2).text()));

            // 胜
            standing.setWins(parseIntSafe(cells.get(3).text()));

            // 平
            standing.setDraws(parseIntSafe(cells.get(4).text()));

            // 负
            standing.setLosses(parseIntSafe(cells.get(5).text()));

            // 进球
            standing.setGoalsFor(parseIntSafe(cells.get(6).text()));

            // 失球
            standing.setGoalsAgainst(parseIntSafe(cells.get(7).text()));

            // 净胜球
            String gdText = cells.get(8).text().trim();
            standing.setGoalDifference(parseIntSafe(gdText.replace("+", "")));

            // 积分
            standing.setPoints(parseIntSafe(cells.get(9).text()));

            standing.setCreatedAt(java.time.LocalDateTime.now());
            standing.setUpdatedAt(java.time.LocalDateTime.now());

            return standing;
        } catch (Exception e) {
            log.debug("解析积分榜单元格失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析比赛时间
     */
    private java.time.LocalDateTime parseMatchTime(String timeText) {
        try {
            String text = timeText == null ? "" : timeText.trim();
            if (text.isEmpty()) {
                return java.time.LocalDateTime.now();
            }

            Matcher dateMatcher = DATE_PATTERN.matcher(text);
            Matcher timeMatcher = TIME_PATTERN.matcher(text);
            String datePart = null;
            String timePart = null;

            if (dateMatcher.find()) {
                datePart = dateMatcher.group(1) + "-" + dateMatcher.group(2) + "-" + dateMatcher.group(3);
            }
            if (timeMatcher.find()) {
                timePart = String.format("%02d:%s", Integer.parseInt(timeMatcher.group(1)), timeMatcher.group(2));
            }

            String[] formats = {
                    "yyyy-MM-dd HH:mm",
                    "yyyy/MM/dd HH:mm",
                    "yyyy年MM月dd日 HH:mm",
                    "MM-dd HH:mm",
                    "MM/dd HH:mm",
                    "HH:mm"
            };

            String candidate = datePart != null && timePart != null ? datePart + " " + timePart : text;
            for (String format : formats) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat(format);
                    sdf.setLenient(false);
                    Date parsed = sdf.parse(candidate);
                    if (parsed != null) {
                        return parsed.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                    }
                } catch (ParseException ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("解析时间失败: {}", timeText);
        }
        return java.time.LocalDateTime.now();
    }

    /**
     * 解析比分
     */
    private Integer[] parseScore(String scoreText) {
        Matcher matcher = SCORE_PATTERN.matcher(scoreText);
        if (matcher.find()) {
            return new Integer[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        }
        return null;
    }

    /**
     * 解析比赛状态
     */
    private String parseStatus(String statusText) {
        String text = statusText.toUpperCase();
        if (text.contains("LIVE") || text.contains("进行中") || text.contains("直播")) {
            return "LIVE";
        } else if (text.contains("半场") || text.contains("HT")) {
            return "HT";
        } else if (text.contains("完") || text.contains("FT") || text.contains("结束")) {
            return "FT";
        } else if (text.contains("推迟") || text.contains("延期")) {
            return "POSTP";
        } else if (text.contains("取消")) {
            return "CANCEL";
        }
        return "NS";
    }

    /**
     * 清理球队名称
     */
    private String cleanTeamName(String name) {
        if (name == null) return null;
        // 移除多余空白和特殊字符
        return name.replaceAll("\\s+", " ").trim();
    }

    /**
     * 提取比赛ID
     */
    private String extractMatchId(String id) {
        Matcher matcher = Pattern.compile("\\d+").matcher(id);
        return matcher.find() ? matcher.group() : id;
    }

    private boolean looksLikeBlockedPage(String html) {
        String text = html.toLowerCase(Locale.ROOT);
        return text.contains("captcha") || text.contains("access denied") || text.contains("forbidden") || text.contains("cloudflare");
    }

    /**
     * 安全解析整数
     */
    private int parseIntSafe(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
