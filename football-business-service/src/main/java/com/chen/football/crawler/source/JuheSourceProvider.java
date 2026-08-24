package com.chen.football.crawler.source;

import com.chen.football.common.client.JuheFootballClient;
import com.chen.football.common.dto.FetchResult;
import com.chen.football.common.dto.MatchStatus;
import com.chen.football.common.dto.NormalizedMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class JuheSourceProvider implements MatchSourceProvider {

    private final JuheFootballClient client;

    public JuheSourceProvider(JuheFootballClient client) {
        this.client = client;
    }

    @Override
    public String name() { return "juhe"; }

    @Override
    public int priority() { return 3; }

    @Override
    public boolean isAvailable() { return client != null; }

    @Override
    public FetchResult fetchMatches(String date) {
        long start = System.currentTimeMillis();
        String firstError = null;
        try {
            List<NormalizedMatch> matches = new ArrayList<>();
            for (int leagueId : new int[]{39, 140, 135, 78, 61, 1}) {
                Map<String, Object> raw = client.getFixturesByDate(date, leagueId).block();
                String error = errorOf(raw);
                if (error != null) {
                    if (firstError == null) firstError = error;
                    if (DataSourceHealthTracker.isQuotaOrPlanError(error)) {
                        log.warn("[Juhe] 遇到额度/权限限制，停止继续请求其他联赛: {}", error);
                        return FetchResult.failure(name(), error, System.currentTimeMillis() - start);
                    }
                    continue;
                }
                matches.addAll(convert(raw, date));
            }
            if (matches.isEmpty() && firstError != null) {
                return FetchResult.failure(name(), firstError, System.currentTimeMillis() - start);
            }
            return FetchResult.success(name(), matches, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[Juhe] fetchMatches failed: {}", e.getMessage());
            return FetchResult.failure(name(), e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public FetchResult fetchMatchesByLeague(int leagueId, int season) {
        long start = System.currentTimeMillis();
        try {
            String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            Map<String, Object> raw = client.getFixturesByDate(today, leagueId).block();
            String error = errorOf(raw);
            if (error != null) {
                return FetchResult.failure(name(), error, System.currentTimeMillis() - start);
            }
            return FetchResult.success(name(), convert(raw, today), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[Juhe] fetchMatchesByLeague failed: {}", e.getMessage());
            return FetchResult.failure(name(), e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @SuppressWarnings("unchecked")
    private List<NormalizedMatch> convert(Map<String, Object> raw, String fallbackDate) {
        List<NormalizedMatch> result = new ArrayList<>();
        if (raw == null) return result;
        Object responseObj = raw.get("response");
        if (!(responseObj instanceof List<?> responseList)) return result;
        for (Object item : responseList) {
            if (!(item instanceof Map<?, ?> itemMap)) continue;
            NormalizedMatch match = convertItem((Map<String, Object>) itemMap, fallbackDate);
            if (match != null) result.add(match);
        }
        return result;
    }

    private String errorOf(Map<String, Object> raw) {
        if (raw == null) return "empty response";
        Object error = raw.get("error");
        return error == null || String.valueOf(error).isBlank() ? null : String.valueOf(error);
    }

    @SuppressWarnings("unchecked")
    private NormalizedMatch convertItem(Map<String, Object> item, String fallbackDate) {
        try {
            Map<String, Object> teams = (Map<String, Object>) item.get("teams");
            Map<String, Object> home = teams == null ? null : (Map<String, Object>) teams.get("home");
            Map<String, Object> away = teams == null ? null : (Map<String, Object>) teams.get("away");
            if (home == null || away == null) return null;

            String homeName = s(home.get("name"));
            String awayName = s(away.get("name"));
            if (homeName.isBlank() || awayName.isBlank()) return null;

            Map<String, Object> league = (Map<String, Object>) item.get("league");
            Map<String, Object> fixture = (Map<String, Object>) item.get("fixture");
            Map<String, Object> goals = (Map<String, Object>) item.get("goals");
            Map<String, Object> status = fixture == null ? null : (Map<String, Object>) fixture.get("status");
            String normStatus = MatchStatus.normalize(s(status == null ? "NS" : status.getOrDefault("short", "NS")));
            Integer homeScore = MatchStatus.hasScore(normStatus) && goals != null ? score(goals.get("home")) : null;
            Integer awayScore = MatchStatus.hasScore(normStatus) && goals != null ? score(goals.get("away")) : null;
            String extId = fixture != null ? s(fixture.getOrDefault("id", "")) : "";
            if (extId.isBlank()) extId = homeName + "_" + awayName + "_" + fallbackDate;
            return new NormalizedMatch(
                    name(),
                    extId,
                    longValue(extId),
                    league == null ? "" : s(league.get("id")),
                    league == null ? "" : s(league.get("name")),
                    s(home.get("id")),
                    homeName,
                    s(home.getOrDefault("logo", home.getOrDefault("crest", ""))),
                    s(away.get("id")),
                    awayName,
                    s(away.getOrDefault("logo", away.getOrDefault("crest", ""))),
                    homeScore,
                    awayScore,
                    normStatus,
                    toDateTime(fixture == null ? fallbackDate : s(fixture.getOrDefault("date", fallbackDate))),
                    null,
                    null
            );
        } catch (Exception e) {
            log.debug("[Juhe] convert failed: {}", e.getMessage());
            return null;
        }
    }

    private String s(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    private Long longValue(Object v) { try { return v == null ? null : Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; } }
    private Integer score(Object v) { try { return v == null ? null : Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; } }
    private LocalDateTime toDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(raw, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        } catch (Exception ignored) {
            // 聚合数据通常返回 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm，保留可用的开赛时间。
            try {
                return LocalDateTime.parse(raw, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception ignoredLocalDateTime) {
                try {
                    return java.time.LocalDate.parse(raw).atStartOfDay();
                } catch (Exception ignoredDate) {
                    return null;
                }
            }
        }
    }
}
