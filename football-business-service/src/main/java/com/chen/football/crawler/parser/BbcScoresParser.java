package com.chen.football.crawler.parser;

import com.chen.football.common.dto.MatchStatus;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerStanding;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BBC Sport scores/fixtures 页面解析器。
 *
 * 页面虽然使用了动态 class，但比赛节点保留了相对稳定的语义属性：
 * data-event-id、data-participant-id、data-testid=score，以及比赛分组
 * 的 h2 联赛标题。解析只依赖这些属性，不依赖 ssrcss-* 样式类名。
 */
@Slf4j
@Component
public class BbcScoresParser {

    public static final String SOURCE = "bbc-scores";
    private static final int MAX_LEAGUE_ID_LENGTH = 32;
    /** BBC scores 页面使用英国本地时间；前台和数据库统一使用上海时间。 */
    private static final ZoneId BBC_ZONE = ZoneId.of("Europe/London");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern KICK_OFF = Pattern.compile("(?i)\\bkick\\s+off\\s+(\\d{1,2}):(\\d{2})");
    private static final Pattern CLOCK = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\b");

    public List<CrawlerMatch> parseMatchList(String html, LocalDate date) {
        List<CrawlerMatch> matches = new ArrayList<>();
        if (html == null || html.isBlank() || date == null) {
            return matches;
        }

        try {
            var document = Jsoup.parse(html);
            for (Element event : document.select("[data-event-id]")) {
                CrawlerMatch match = parseEvent(event, date);
                if (match != null) {
                    matches.add(match);
                }
            }
            // 某些页面重复渲染移动端和桌面端节点，按 BBC 事件 ID 去重。
            Map<String, CrawlerMatch> deduplicated = new LinkedHashMap<>();
            for (CrawlerMatch match : matches) {
                deduplicated.putIfAbsent(match.getExternalMatchId(), match);
            }
            matches = new ArrayList<>(deduplicated.values());
            log.info("[BBC] 解析到 {} 场比赛，date={}", matches.size(), date);
        } catch (Exception e) {
            log.warn("[BBC] 比赛解析失败: {}", e.getMessage());
        }
        return matches;
    }

    /**
     * 判断响应是否仍然是 BBC 赛程页，而不是反爬提示、错误页或登录页。
     * 这个校验必须在“空列表”之前执行，否则页面结构变化会被误判为当天没有比赛。
     */
    public boolean isValidScoresPage(String html) {
        if (html == null || html.isBlank()) return false;
        try {
            var document = Jsoup.parse(html);
            String title = clean(document.title()).toLowerCase(Locale.ROOT);
            boolean hasHeader = title.contains("scores") && title.contains("fixtures")
                    || !document.select("[data-testid=datepicker]").isEmpty();
            boolean hasMain = !document.select("[data-testid=main-content], main").isEmpty();
            return hasHeader && hasMain;
        } catch (Exception e) {
            return false;
        }
    }

    /** 事件标记存在但一个比赛都解析不出来，通常代表 BBC 结构发生变化。 */
    public boolean hasEventMarkers(String html) {
        if (html == null || html.isBlank()) return false;
        try {
            return !Jsoup.parse(html).select("[data-event-id]").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析 BBC 联赛积分榜。BBC 的榜单使用语义化 aria-label 和稳定的
     * data-testid，不依赖会频繁变化的 CSS hash 类名；同时直接读取 badge
     * 图片地址，避免球队档案再去拼接 logo。
     */
    public List<CrawlerStanding> parseStandings(String html, String leagueName, String leagueId, String season) {
        List<CrawlerStanding> standings = new ArrayList<>();
        if (html == null || html.isBlank()) return standings;
        try {
            var document = Jsoup.parse(html);
            Elements rows = document.select("table[data-testid=football-table] tbody tr");
            if (rows.isEmpty()) rows = document.select("table[data-testid*=football] tbody tr");
            // BBC 偶尔会在地区页移除 data-testid，但表格语义仍然存在；
            // parseStandingRow 会继续校验排名、球队链接和 9 个统计列，避免把其他表格误当积分榜。
            if (rows.isEmpty()) rows = document.select("table tbody tr");
            for (Element row : rows) {
                CrawlerStanding standing = parseStandingRow(row, leagueName, leagueId, season);
                if (standing != null) standings.add(standing);
            }
            log.info("[BBC] 解析到 {} 条积分榜，league={}", standings.size(), leagueName);
        } catch (Exception e) {
            log.warn("[BBC] 积分榜解析失败，league={}, error={}", leagueName, e.getMessage());
        }
        return standings;
    }

    private CrawlerStanding parseStandingRow(Element row, String leagueName, String leagueId, String season) {
        Elements cells = row.select("td");
        if (cells.size() < 9) return null;
        Element teamCell = cells.get(0);
        Element teamLink = teamCell.selectFirst("a[href*='/teams/']");
        String teamName = clean(teamLink == null ? teamCell.text() : teamLink.text());
        if (teamName.isBlank()) return null;
        Element rank = teamCell.selectFirst("[class*=Rank]");
        String rankText = clean(rank == null ? "" : rank.text());
        if (!rankText.matches("\\d+")) return null;
        // 部分 BBC 表格把排名一起放进无障碍球队链接文本；只剥离“1 Arsenal”这类
        // 无标点前缀，不影响“1. FC ...”等球队正式名称。
        teamName = teamName.replaceFirst("^" + java.util.regex.Pattern.quote(rankText) + "\\s+", "").trim();
        if (teamName.isBlank()) return null;

        CrawlerStanding standing = new CrawlerStanding();
        standing.setLeagueName(leagueName);
        standing.setLeagueId(leagueId);
        standing.setSeason(season);
        standing.setSource("bbc-standings");
        standing.setRank(Integer.parseInt(rankText));
        standing.setTeamName(teamName);
        standing.setTeamId(teamLink == null ? "" : teamSlug(teamLink.attr("href")));
        Element badge = teamCell.selectFirst("img[data-testid^=badge-img]");
        standing.setTeamLogo(badge == null ? "" : clean(badge.attr("src")));
        standing.setPlayed(valueFor(cells, "Played", 1));
        standing.setWins(valueFor(cells, "Won", 2));
        standing.setDraws(valueFor(cells, "Drawn", 3));
        standing.setLosses(valueFor(cells, "Lost", 4));
        standing.setGoalsFor(valueFor(cells, "Goals For", 5));
        standing.setGoalsAgainst(valueFor(cells, "Goals Against", 6));
        standing.setGoalDifference(valueFor(cells, "Goal Difference", 7));
        standing.setPoints(valueFor(cells, "Points", 8));
        standing.setCreatedAt(LocalDateTime.now());
        standing.setUpdatedAt(LocalDateTime.now());
        return standing;
    }

    private int valueFor(Elements cells, String label, int fallbackIndex) {
        for (Element cell : cells) {
            if (label.equalsIgnoreCase(clean(cell.attr("aria-label")))) {
                return integer(cell.text());
            }
        }
        return cells.size() > fallbackIndex ? integer(cells.get(fallbackIndex).text()) : 0;
    }

    private int integer(String value) {
        String cleaned = clean(value).replace("+", "");
        try {
            return cleaned.isBlank() ? 0 : Integer.parseInt(cleaned);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String teamSlug(String href) {
        if (href == null || href.isBlank()) return "";
        String value = href.replaceAll("/$", "");
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private CrawlerMatch parseEvent(Element event, LocalDate date) {
        String eventId = clean(event.attr("data-event-id"));
        if (eventId.isBlank()) {
            return null;
        }

        List<Element> participants = event.select("[data-participant-id]");
        if (participants.size() < 2) {
            return null;
        }
        Element homeParticipant = participants.stream()
                .filter(item -> item.className().toLowerCase(Locale.ROOT).contains("home"))
                .findFirst()
                .orElse(participants.get(0));
        Element awayParticipant = participants.stream()
                .filter(item -> item.className().toLowerCase(Locale.ROOT).contains("away"))
                .findFirst()
                .orElse(participants.get(1));

        String homeName = teamName(homeParticipant);
        String awayName = teamName(awayParticipant);
        if (homeName.isBlank() || awayName.isBlank() || homeName.equalsIgnoreCase(awayName)) {
            return null;
        }

        LeagueRef league = findLeague(event);
        String eventText = clean(event.text());
        Integer[] scores = parseScore(event);
        String status = parseStatus(eventText, scores != null);

        CrawlerMatch match = new CrawlerMatch();
        match.setSource(SOURCE);
        match.setExternalMatchId(eventId);
        match.setLeagueId(league.id());
        match.setLeagueName(league.name());
        match.setHomeTeamId(clean(homeParticipant.attr("data-participant-id")));
        match.setHomeTeamName(homeName);
        match.setHomeTeamLogo(teamLogo(homeParticipant));
        match.setAwayTeamId(clean(awayParticipant.attr("data-participant-id")));
        match.setAwayTeamName(awayName);
        match.setAwayTeamLogo(teamLogo(awayParticipant));
        LocalDateTime kickoff = parseMatchTime(event, date);
        // BBC occasionally includes a generic “live” marker in the fixture
        // subtree (for example a live-score navigation label) even though the
        // event itself is a future kickoff.  Never persist a future fixture as
        // LIVE; the score updater should only expose a real in-play state.
        if (kickoff != null && MatchStatus.isLive(status)
                && kickoff.isAfter(LocalDateTime.now(DISPLAY_ZONE).plusMinutes(2))) {
            status = MatchStatus.SCHEDULED;
        }
        match.setStatus(status);
        match.setMatchTime(kickoff);
        if (kickoff == null) {
            match.setNote("KICKOFF_TIME_MISSING: BBC 页面未提供可解析的开球时间");
        }
        if (MatchStatus.hasScore(status) && scores != null) {
            match.setHomeScore(scores[0]);
            match.setAwayScore(scores[1]);
        }
        match.setCreatedAt(LocalDateTime.now());
        match.setUpdatedAt(LocalDateTime.now());
        return match;
    }

    private String teamName(Element participant) {
        Element wrapper = participant.selectFirst("[class*=TeamNameWrapper]");
        if (wrapper == null) {
            return clean(participant.text());
        }
        Element desktop = wrapper.selectFirst("[class*=DesktopValue]");
        if (desktop != null && !clean(desktop.text()).isBlank()) {
            return clean(desktop.text());
        }
        for (Element span : wrapper.select("span")) {
            String value = clean(span.text());
            if (!value.isBlank()) {
                return value;
            }
        }
        return clean(wrapper.text());
    }

    private String teamLogo(Element participant) {
        Element image = participant.selectFirst("img[data-testid^=badge-img]");
        if (image == null) {
            image = participant.selectFirst("img[src]");
        }
        return image == null ? null : clean(image.attr("src"));
    }

    private Integer[] parseScore(Element event) {
        Element score = event.selectFirst("[data-testid=score]");
        if (score == null) {
            return null;
        }
        List<Integer> values = new ArrayList<>();
        for (Element child : score.children()) {
            String value = clean(child.text());
            if (value.matches("\\d+")) {
                values.add(Integer.parseInt(value));
            }
        }
        if (values.size() < 2) {
            Matcher matcher = Pattern.compile("(\\d+)\\s*[|:/-]\\s*(\\d+)").matcher(clean(score.text()));
            if (matcher.find()) {
                return new Integer[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
            }
            return null;
        }
        return new Integer[]{values.get(0), values.get(values.size() - 1)};
    }

    private String parseStatus(String text, boolean hasScore) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("postponed")) return MatchStatus.POSTPONED;
        if (lower.contains("cancelled") || lower.contains("canceled")) return MatchStatus.CANCELLED;
        if (lower.contains("half time") || lower.contains("live") || lower.contains("minute")) return MatchStatus.LIVE;
        if (lower.contains("full time") || lower.contains("finished")) return MatchStatus.FINISHED;
        return hasScore ? MatchStatus.FINISHED : MatchStatus.SCHEDULED;
    }

    private LocalDateTime parseMatchTime(Element event, LocalDate date) {
        // 优先使用语义化 datetime/data-kickoff 属性；页面可见文案可能只显示
        // “今天”或因响应式布局被隐藏，单靠文本会把真实赛程误判为缺失。
        for (String selector : new String[]{"time[datetime]", "[data-kickoff]", "[data-time]"}) {
            Element timeElement = event.selectFirst(selector);
            if (timeElement == null) continue;
            String raw = clean(timeElement.hasAttr("datetime") ? timeElement.attr("datetime")
                    : timeElement.hasAttr("data-kickoff") ? timeElement.attr("data-kickoff")
                    : timeElement.attr("data-time"));
            LocalDateTime parsed = parseIsoTime(raw);
            if (parsed != null) return parsed;
        }
        String text = event.text();
        Matcher kickoff = KICK_OFF.matcher(text);
        boolean found = kickoff.find();
        if (!found) {
            Element time = event.selectFirst("time");
            if (time != null) {
                kickoff = CLOCK.matcher(clean(time.text()));
                found = kickoff.find();
            }
        }
        if (found) {
            try {
                int hour = Integer.parseInt(kickoff.group(1));
                int minute = Integer.parseInt(kickoff.group(2));
                if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                    LocalTime sourceTime = LocalTime.of(hour, minute);
                    return ZonedDateTime.of(date, sourceTime, BBC_ZONE)
                            .withZoneSameInstant(DISPLAY_ZONE)
                            .toLocalDateTime();
                }
            } catch (RuntimeException ignored) {
                // 页面时间异常时使用日期零点，不能丢弃整场比赛。
            }
        }
        // 不再把缺失时间伪装成当天 00:00。保留空值，让产品显示“时间待确认”，
        // 也避免比赛被归入错误日期或错误地触发赛前窗口。
        return null;
    }

    private LocalDateTime parseIsoTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw).atZone(DISPLAY_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(raw).atZoneSameInstant(DISPLAY_ZONE).toLocalDateTime();
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    // 无时区的 BBC 时间按伦敦本地时间解释，再转上海。
                    return LocalDateTime.parse(raw).atZone(BBC_ZONE).withZoneSameInstant(DISPLAY_ZONE).toLocalDateTime();
                } catch (DateTimeParseException ignoredFinal) {
                    return null;
                }
            }
        }
    }

    private LeagueRef findLeague(Element event) {
        Element current = event;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.parent()) {
            Element sibling = current.previousElementSibling();
            while (sibling != null) {
                Element heading = sibling.is("h2") ? sibling : sibling.selectFirst("h2");
                if (heading != null) {
                    String raw = clean(heading.text());
                    Element link = heading.selectFirst("a[href]");
                    String href = link == null ? "" : clean(link.attr("href"));
                    return new LeagueRef(normalizeLeague(raw), leagueId(raw, href));
                }
                sibling = sibling.previousElementSibling();
            }
        }
        return new LeagueRef("其他赛事", "bbc-other");
    }

    private String normalizeLeague(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (lower.contains("premier league")) return "英超";
        if (lower.contains("championship")) return "英冠";
        if (lower.contains("la liga")) return "西甲";
        if (lower.contains("serie a")) return "意甲";
        if (lower.contains("bundesliga")) return "德甲";
        if (lower.contains("ligue 1")) return "法甲";
        if (lower.contains("champions league")) return "欧冠";
        if (lower.contains("europa league")) return "欧联";
        if (lower.contains("eredivisie")) return "荷甲";
        if (lower.contains("scottish premiership")) return "苏超";
        if (lower.contains("major league soccer") || lower.equals("mls")) return "美职联";
        return raw == null || raw.isBlank() ? "其他赛事" : raw;
    }

    private String leagueId(String raw, String href) {
        String candidate = href == null ? "" : href.replaceAll("/$", "");
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < candidate.length()) {
            candidate = candidate.substring(0, slash);
            slash = candidate.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < candidate.length()) {
                candidate = candidate.substring(slash + 1);
            }
        }
        if (candidate.isBlank()) {
            candidate = raw == null ? "other" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        }
        String value = "bbc-" + candidate;
        if (value.length() <= MAX_LEAGUE_ID_LENGTH) {
            return value;
        }
        // crawler_matches.league_id 为 varchar(32)。BBC 对未识别赛事可能返回很长的
        // 页面 slug，截断时附带稳定 hash，避免不同联赛被截成同一个 ID。
        String suffix = "-" + Integer.toUnsignedString(value.hashCode(), 36);
        int prefixLength = Math.max(1, MAX_LEAGUE_ID_LENGTH - suffix.length());
        return value.substring(0, prefixLength) + suffix;
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private record LeagueRef(String name, String id) {
    }
}
