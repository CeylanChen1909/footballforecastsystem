package com.chen.football.crawler.source;

import com.chen.football.common.client.FootballDataClient;
import com.chen.football.common.dto.FetchResult;
import com.chen.football.common.dto.MatchStatus;
import com.chen.football.common.dto.NormalizedMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class FootballDataSourceProvider implements MatchSourceProvider {

    private final FootballDataClient client;

    public FootballDataSourceProvider(FootballDataClient client) {
        this.client = client;
    }

    @Override
    public String name() { return "football-data"; }

    @Override
    public int priority() { return 2; }

    @Override
    public boolean isAvailable() { return client != null; }

    @Override
    public FetchResult fetchMatches(String date) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> raw = client.getMatches(date, date, null, null).block();
            return toResult(raw, start);
        } catch (Exception e) {
            log.warn("[FootballData] fetchMatches failed: {}", e.getMessage());
            return FetchResult.failure(name(), e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public FetchResult fetchMatchesByLeague(int leagueId, int season) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> raw = client.getMatches(null, null, null, null).block();
            return toResult(raw, start);
        } catch (Exception e) {
            log.warn("[FootballData] fetchMatchesByLeague failed: {}", e.getMessage());
            return FetchResult.failure(name(), e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    @SuppressWarnings("unchecked")
    private FetchResult toResult(Map<String, Object> raw, long start) {
        if (raw == null) return FetchResult.failure(name(), "empty response", System.currentTimeMillis() - start);
        Object error = raw.get("error");
        if (error != null && !String.valueOf(error).isBlank()) {
            return FetchResult.failure(name(), String.valueOf(error), System.currentTimeMillis() - start);
        }
        Object responseObj = raw.get("response");
        if (!(responseObj instanceof List<?> responseList)) {
            return FetchResult.failure(name(), "no response array", System.currentTimeMillis() - start);
        }
        List<NormalizedMatch> matches = new ArrayList<>();
        for (Object item : responseList) {
            if (!(item instanceof Map<?, ?> itemMap)) continue;
            NormalizedMatch match = convert((Map<String, Object>) itemMap);
            if (match != null) matches.add(match);
        }
        return FetchResult.success(name(), matches, System.currentTimeMillis() - start);
    }

    @SuppressWarnings("unchecked")
    private NormalizedMatch convert(Map<String, Object> item) {
        try {
            Map<String, Object> fixture = (Map<String, Object>) item.get("fixture");
            Map<String, Object> league = (Map<String, Object>) item.get("league");
            Map<String, Object> teams = (Map<String, Object>) item.get("teams");
            Map<String, Object> goals = (Map<String, Object>) item.get("goals");
            if (fixture == null || teams == null) return null;
            Map<String, Object> home = (Map<String, Object>) teams.get("home");
            Map<String, Object> away = (Map<String, Object>) teams.get("away");
            if (home == null || away == null) return null;

            String homeName = s(home.get("name"));
            String awayName = s(away.get("name"));
            if (homeName.isBlank() || awayName.isBlank()) return null;

            String rawStatus = s(((Map<String, Object>) fixture.getOrDefault("status", Map.of())).get("short"));
            String status = MatchStatus.normalize(rawStatus);
            Integer homeScore = MatchStatus.hasScore(status) ? toInt(goals == null ? null : goals.get("home")) : null;
            Integer awayScore = MatchStatus.hasScore(status) ? toInt(goals == null ? null : goals.get("away")) : null;

            Long fixtureId = toLong(fixture.get("id"));
            LocalDateTime matchTime = toDateTime(fixture.get("date"));
            return new NormalizedMatch(
                    name(),
                    fixtureId != null ? String.valueOf(fixtureId) : (homeName + "_" + awayName),
                    fixtureId,
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
                    status,
                    matchTime,
                    fixture.get("venue") instanceof Map<?, ?> venue ? s(venue.get("name")) : null,
                    null
            );
        } catch (Exception e) {
            log.debug("[FootballData] convert failed: {}", e.getMessage());
            return null;
        }
    }

    private String s(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    private Long toLong(Object v) { try { return v == null ? null : Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; } }
    private Integer toInt(Object v) { try { return v == null ? null : Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; } }
    private LocalDateTime toDateTime(Object v) {
        try {
            if (v == null) return null;
            if (v instanceof Number n) {
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneId.of("Asia/Shanghai"));
            }
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            // ISO 8601 带时区偏移（如 2026-08-18T00:30:00+00:00 / Z）：统一转为本地时间
            try {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(s, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return odt.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            } catch (Exception ignored) {
                // 无偏移的普通时间串，按本地时间处理
                String plain = s.replace("T", " ");
                if (plain.length() >= 19) plain = plain.substring(0, 19);
                return LocalDateTime.parse(plain, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        } catch (Exception e) {
            return null;
        }
    }
}
