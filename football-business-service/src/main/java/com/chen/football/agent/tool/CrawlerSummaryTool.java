package com.chen.football.agent.tool;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.source.ProductionLeagueScope;
import com.chen.football.prediction.service.MatchPredictionPrecomputeService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Component
public class CrawlerSummaryTool implements AgentTool {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CrawlerMatchMapper crawlerMatchMapper;
    private final CrawlerProperties crawlerProperties;
    private final MatchPredictionPrecomputeService predictionPrecomputeService;

    public CrawlerSummaryTool(CrawlerMatchMapper crawlerMatchMapper,
                              CrawlerProperties crawlerProperties,
                              MatchPredictionPrecomputeService predictionPrecomputeService) {
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.crawlerProperties = crawlerProperties;
        this.predictionPrecomputeService = predictionPrecomputeService;
    }

    @Override
    public String name() {
        return "crawler_summary";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        String leagueName = string(context.get("leagueName"));
        int requestedLimit = Math.max(1, Math.min(toInt(context.get("limit"), 10), 200));
        String message = string(context.get("message"));
        boolean next24Hours = isNext24HoursRequest(message);
        LocalDateTime windowNow = LocalDateTime.now(BUSINESS_ZONE);
        LocalDateTime windowStart = parseWindowTime(context.get("from"));
        LocalDateTime windowEnd = parseWindowTime(context.get("to"));
        if (next24Hours && windowStart == null && windowEnd == null) {
            windowStart = windowNow;
            windowEnd = windowNow.plusHours(24);
        }
        boolean hasWindow = windowStart != null && windowEnd != null && windowEnd.isAfter(windowStart);
        // Keep today's finished rows in the response.  The previous
        // findUpcomingMatches() query started at now(), which made a user
        // asking “今天有没有比完的比赛” incorrectly receive an empty result.
        List<CrawlerMatch> matches = crawlerMatchMapper.findTodayAndUpcomingMatches(leagueName);
        String primarySource = crawlerProperties.getPrimarySource();
        if (crawlerProperties.isPrimaryOnly() && primarySource != null && !primarySource.isBlank()) {
            matches = matches.stream()
                    .filter(match -> primarySource.equalsIgnoreCase(match.getSource()))
                    .toList();
        }
        matches = matches.stream()
                .filter(match -> ProductionLeagueScope.isVisible(match, crawlerProperties))
                .toList();
        // 延期场次不是当前可供用户选择的“有赛程安排”比赛。
        matches = matches.stream()
                .filter(match -> !isPostponed(match.getStatus()))
                .toList();
        if (hasWindow) {
            LocalDateTime start = windowStart;
            LocalDateTime end = windowEnd;
            matches = matches.stream()
                    .filter(match -> match.getMatchTime() != null
                            && !match.getMatchTime().isBefore(start)
                            && match.getMatchTime().isBefore(end))
                    .toList();
        }
        boolean randomSelection = Boolean.parseBoolean(String.valueOf(context.getOrDefault("randomSelection", false)));

        MatchPredictionPrecomputeService.PredictionStatusResult predictionResult =
                predictionPrecomputeService.readPredictionStatuses(matches);
        Map<Long, Map<String, Object>> predictionStatuses = predictionResult.items();
        int responseLimit = next24Hours ? Math.min(Math.max(requestedLimit, 100), 200) : requestedLimit;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("leagueName", leagueName);
        data.put("limit", responseLimit);
        data.put("requestedLimit", requestedLimit);
        data.put("timeZone", BUSINESS_ZONE.getId());
        data.put("windowStart", hasWindow ? formatTime(windowStart) : null);
        data.put("windowEnd", hasWindow ? formatTime(windowEnd) : null);
        data.put("windowType", next24Hours ? "NEXT_24_HOURS" : "TODAY_AND_7_DAYS");
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<CrawlerMatch> todayMatches = matches.stream()
                .filter(match -> match.getMatchTime() != null && today.equals(match.getMatchTime().toLocalDate()))
                .toList();
        List<CrawlerMatch> finishedToday = todayMatches.stream().filter(this::isFinished).toList();
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        // A source can keep a past fixture in NS when it has not returned the
        // final score yet.  It must not be presented as "no match" or as a
        // confirmed finished match; expose it as an explicit data-sync gap.
        List<CrawlerMatch> pastUnresolvedToday = todayMatches.stream()
                .filter(match -> match.getMatchTime() != null
                        && match.getMatchTime().isBefore(now)
                        && !isFinished(match))
                .toList();
        List<CrawlerMatch> upcomingToday = todayMatches.stream()
                .filter(match -> match.getMatchTime() != null
                        && !match.getMatchTime().isBefore(now)
                        && !isFinished(match))
                .toList();
        // Put the date-specific facts before the broad seven-day list. The
        // prompt has a hard size limit, so the model must see today's rows
        // even when the database contains many upcoming fixtures.
        data.put("todayDate", today.toString());
        // A bounded window already has its canonical `matches` list. Do not
        // duplicate the same rows under the broad today* fields: doing so can
        // push the per-match predictionStatus out of the Agent prompt budget.
        data.put("todayMatches", hasWindow ? List.of() : todayMatches.stream()
                .map(match -> toMatchSummary(match, predictionStatuses, predictionResult.status()))
                .limit(Math.max(responseLimit, 20)).toList());
        data.put("todayFinished", hasWindow ? List.of() : finishedToday.stream()
                .map(match -> toMatchSummary(match, predictionStatuses, predictionResult.status()))
                .limit(Math.max(responseLimit, 20)).toList());
        data.put("todayPastUnresolved", hasWindow ? List.of() : pastUnresolvedToday.stream()
                .map(match -> toMatchSummary(match, predictionStatuses, predictionResult.status()))
                .limit(Math.max(responseLimit, 20)).toList());
        data.put("todayFinishedCount", finishedToday.size());
        data.put("todayUpcomingCount", upcomingToday.size());
        data.put("todayPastUnresolvedCount", pastUnresolvedToday.size());
        List<Map<String, Object>> returnedMatches = matches.stream()
                .map(match -> toMatchSummary(match, predictionStatuses, predictionResult.status()))
                .limit(responseLimit)
                .toList();
        data.put("matches", returnedMatches);
        data.put("total", matches.size());
        data.put("returned", returnedMatches.size());
        data.put("truncated", returnedMatches.size() < matches.size());
        data.put("source", primarySource);
        data.put("status", matches.isEmpty() ? "EMPTY" : "AVAILABLE");
        data.put("predictionStatus", predictionResult.status());
        data.put("predictionStatusMessage", predictionResult.message());
        data.put("predictionSummary", predictionSummary(matches, predictionStatuses, predictionResult.status()));
        data.put("message", matches.isEmpty()
                ? (hasWindow ? "当前时间窗口内没有可靠赛程" : "当前筛选条件下没有可靠赛程")
                : (hasWindow ? "已读取指定时间窗口内赛程" : "已读取今天及未来 7 天赛程"));
        if (randomSelection && !matches.isEmpty()) {
            int index = ThreadLocalRandom.current().nextInt(matches.size());
            data.put("selectionMode", "RANDOM");
            data.put("selectedIndex", index);
            data.put("selectedMatch", toSelection(matches.get(index)));
        }
        return data;
    }

    private Map<String, Object> toMatchSummary(CrawlerMatch match,
                                                Map<Long, Map<String, Object>> predictionStatuses,
                                                String predictionLookupStatus) {
        Map<String, Object> row = new LinkedHashMap<>();
        Long fixtureId = effectiveFixtureId(match);
        row.put("id", match.getId());
        row.put("fixtureId", fixtureId);
        row.put("externalMatchId", match.getExternalMatchId());
        row.put("source", match.getSource());
        row.put("leagueId", match.getLeagueId());
        row.put("leagueName", match.getLeagueName());
        row.put("homeTeamId", match.getHomeTeamId());
        row.put("homeTeamName", match.getHomeTeamName());
        row.put("homeTeamLogo", match.getHomeTeamLogo());
        row.put("awayTeamId", match.getAwayTeamId());
        row.put("awayTeamName", match.getAwayTeamName());
        row.put("awayTeamLogo", match.getAwayTeamLogo());
        row.put("homeScore", match.getHomeScore());
        row.put("awayScore", match.getAwayScore());
        row.put("status", match.getStatus());
        row.put("matchTime", match.getMatchTime() == null ? null : formatTime(match.getMatchTime()));
        row.put("timeZone", BUSINESS_ZONE.getId());

        Map<String, Object> prediction = fixtureId == null ? null : predictionStatuses.get(fixtureId);
        String status = prediction == null
                ? ("REQUEST_FAILED".equals(predictionLookupStatus) ? "NOT_READ" : "NOT_GENERATED")
                : String.valueOf(prediction.getOrDefault("status", "NOT_GENERATED"));
        row.put("predictionStatus", status);
        row.put("predictionAvailable", prediction == null ? null : prediction.get("predictionAvailable"));
        if (prediction != null) {
            row.put("predictionResultLabel", prediction.get("resultLabel"));
            row.put("predictionGeneratedAt", prediction.get("generatedAt"));
            row.put("predictionUpdatedAt", prediction.get("updatedAt"));
            row.put("predictionFeatureStatus", prediction.get("featureStatus"));
            row.put("predictionFallbackReason", prediction.get("fallbackReason"));
            row.put("predictionModelVersion", prediction.get("modelVersion"));
        }
        if ("NOT_READ".equals(status)) row.put("predictionStatusMessage", "预测状态查询失败");
        else if ("NOT_GENERATED".equals(status)) row.put("predictionStatusMessage", "尚未生成比赛级预测快照");
        return row;
    }

    private Map<String, Integer> predictionSummary(List<CrawlerMatch> matches,
                                                    Map<Long, Map<String, Object>> predictionStatuses,
                                                    String lookupStatus) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (CrawlerMatch match : matches) {
            Long fixtureId = effectiveFixtureId(match);
            Map<String, Object> prediction = fixtureId == null ? null : predictionStatuses.get(fixtureId);
            String status = prediction == null
                    ? ("REQUEST_FAILED".equals(lookupStatus) ? "NOT_READ" : "NOT_GENERATED")
                    : String.valueOf(prediction.getOrDefault("status", "NOT_GENERATED"));
            summary.merge(status, 1, Integer::sum);
        }
        return summary;
    }

    private boolean isNext24HoursRequest(String message) {
        if (message == null || message.isBlank()) return false;
        String normalized = message.replace("　", " ");
        return normalized.matches("(?s).*(接下来|未来|后续|今后)\\s*24\\s*小时.*")
                || normalized.matches("(?s).*未来\\s*24\\s*小时.*");
    }

    private LocalDateTime parseWindowTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        String raw = String.valueOf(value).trim();
        try { return OffsetDateTime.parse(raw).atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime(); }
        catch (Exception ignored) { }
        try { return Instant.parse(raw).atZone(BUSINESS_ZONE).toLocalDateTime(); }
        catch (Exception ignored) { }
        try { return LocalDateTime.parse(raw); }
        catch (Exception ignored) { }
        try { return LocalDate.parse(raw).atStartOfDay(); }
        catch (Exception ignored) { return null; }
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
    }

    private Long effectiveFixtureId(CrawlerMatch match) {
        if (match == null) return null;
        return match.getFixtureId() != null && match.getFixtureId() > 0 ? match.getFixtureId() : match.getId();
    }

    private Map<String, Object> toSelection(CrawlerMatch match) {
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("id", match.getId());
        selected.put("fixtureId", match.getFixtureId() != null ? match.getFixtureId() : match.getId());
        selected.put("homeTeamId", match.getHomeTeamId());
        selected.put("homeTeamName", match.getHomeTeamName());
        selected.put("homeTeamLogo", match.getHomeTeamLogo());
        selected.put("awayTeamId", match.getAwayTeamId());
        selected.put("awayTeamName", match.getAwayTeamName());
        selected.put("awayTeamLogo", match.getAwayTeamLogo());
        selected.put("leagueName", match.getLeagueName());
        selected.put("matchTime", match.getMatchTime() == null ? null : formatTime(match.getMatchTime()));
        selected.put("status", match.getStatus());
        selected.put("source", match.getSource());
        return selected;
    }

    private boolean isPostponed(String status) {
        if (status == null) return false;
        String normalized = status.trim().toUpperCase();
        return normalized.equals("PST") || normalized.equals("POSTPONED") || normalized.equals("PPD");
    }

    private boolean isFinished(CrawlerMatch match) {
        if (match == null) return false;
        if (match.getHomeScore() != null && match.getAwayScore() != null) return true;
        String status = match.getStatus();
        if (status == null) return false;
        return switch (status.trim().toUpperCase()) {
            case "FT", "AET", "PEN", "FINISHED" -> true;
            default -> false;
        };
    }

    private int toInt(Object value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
