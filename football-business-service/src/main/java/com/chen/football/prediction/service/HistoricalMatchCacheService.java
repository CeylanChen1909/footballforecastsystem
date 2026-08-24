package com.chen.football.prediction.service;

import com.chen.football.crawler.service.IdentityNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Provides finished historical fixtures from the local football-data cache.
 *
 * The live crawler is intentionally BBC-only, so it is not expected to contain
 * several seasons of completed matches.  This read-only cache fills that gap
 * during feature construction without inserting a second provider into the
 * production match table or making a network request on the prediction path.
 */
@Slf4j
@Service
public class HistoricalMatchCacheService {
    private static final Map<String, String> LEAGUE_NAMES = Map.of(
            "PL", "Premier League",
            "PD", "Primera Division",
            "SA", "Serie A",
            "BL1", "Bundesliga",
            "FL1", "Ligue 1",
            "DED", "Eredivisie",
            "PPL", "Primeira Liga",
            "ELC", "Championship"
    );

    private final ObjectMapper objectMapper;
    private final String configuredPath;
    private volatile List<CachedMatch> matches = List.of();

    public HistoricalMatchCacheService(
            ObjectMapper objectMapper,
            @Value("${historical.match-cache-path:football-ml-service/data_cache}") String configuredPath) {
        this.objectMapper = objectMapper;
        this.configuredPath = configuredPath;
    }

    @PostConstruct
    void load() {
        reload();
    }

    /**
     * 缓存目录由训练/采集任务更新，不能只在进程启动时读取一次。
     * 重新加载是读操作且采用 volatile 快照，不会阻塞预测请求。
     */
    @Scheduled(fixedDelayString = "${historical.match-cache-reload-ms:21600000}", initialDelayString = "${historical.match-cache-reload-initial-delay-ms:21600000}")
    public synchronized void reload() {
        Path directory = resolvePath(configuredPath);
        if (!Files.isDirectory(directory)) {
            log.warn("历史比赛缓存目录不存在，预测将只使用数据库: {}", directory.toAbsolutePath());
            matches = List.of();
            return;
        }
        Map<String, CachedMatch> loaded = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().startsWith("football-data-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> readFile(path, loaded));
        } catch (IOException ex) {
            log.warn("读取历史比赛缓存失败: {}", ex.getMessage());
        }
        List<CachedMatch> snapshot = new ArrayList<>(loaded.values());
        snapshot.sort(Comparator.comparing(CachedMatch::matchTime, Comparator.nullsLast(Comparator.naturalOrder())));
        matches = List.copyOf(snapshot);
        log.info("历史比赛缓存已加载: path={}, finishedMatches={}", directory.toAbsolutePath(), matches.size());
    }

    public List<Map<String, Object>> teamMatches(String teamId, String teamName, LocalDateTime before, int limit) {
        return matches.stream()
                .filter(match -> before == null || match.matchTime().isBefore(before))
                .filter(match -> matchesTeam(teamId, teamName, match.homeTeamId(), match.homeTeamName(), match.homeShortName())
                        || matchesTeam(teamId, teamName, match.awayTeamId(), match.awayTeamName(), match.awayShortName()))
                .sorted(Comparator.comparing(CachedMatch::matchTime).reversed())
                .limit(Math.max(1, limit))
                .map(CachedMatch::toRow)
                .toList();
    }

    public List<Map<String, Object>> headToHead(String homeTeamId, String awayTeamId,
                                                 String homeTeamName, String awayTeamName,
                                                 LocalDateTime before, int limit) {
        return matches.stream()
                .filter(match -> before == null || match.matchTime().isBefore(before))
                .filter(match -> (matchesTeam(homeTeamId, homeTeamName, match.homeTeamId(), match.homeTeamName(), match.homeShortName())
                        && matchesTeam(awayTeamId, awayTeamName, match.awayTeamId(), match.awayTeamName(), match.awayShortName()))
                        || (matchesTeam(awayTeamId, awayTeamName, match.homeTeamId(), match.homeTeamName(), match.homeShortName())
                        && matchesTeam(homeTeamId, homeTeamName, match.awayTeamId(), match.awayTeamName(), match.awayShortName())))
                .sorted(Comparator.comparing(CachedMatch::matchTime).reversed())
                .limit(Math.max(1, limit))
                .map(CachedMatch::toRow)
                .toList();
    }

    public List<Map<String, Object>> allFinishedBefore(LocalDateTime before) {
        return matches.stream()
                .filter(match -> before == null || match.matchTime().isBefore(before))
                .sorted(Comparator.comparing(CachedMatch::matchTime))
                .map(CachedMatch::toRow)
                .toList();
    }

    public int size() {
        return matches.size();
    }

    private boolean matchesTeam(String expectedId, String expectedName, String actualId, String actualName, String actualShortName) {
        if (expectedId != null && !expectedId.isBlank() && expectedId.equals(actualId)) return true;
        String expected = IdentityNormalizer.normalize(expectedName);
        String actual = IdentityNormalizer.normalize(actualName);
        String shortName = IdentityNormalizer.normalize(actualShortName);
        return !expected.isBlank() && (IdentityNormalizer.compatible(expected, actual)
                || IdentityNormalizer.compatible(expected, shortName));
    }

    private void readFile(Path path, Map<String, CachedMatch> target) {
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            if (!root.isArray()) return;
            for (JsonNode node : root) {
                if (!"FINISHED".equalsIgnoreCase(node.path("status").asText())) continue;
                JsonNode fullTime = node.path("score").path("fullTime");
                if (!fullTime.path("home").isNumber() || !fullTime.path("away").isNumber()) continue;
                String code = node.path("competition").path("code").asText();
                if (!LEAGUE_NAMES.containsKey(code)) continue;
                LocalDateTime matchTime = parseTime(node.path("utcDate").asText(null));
                String homeName = node.path("homeTeam").path("name").asText(null);
                String awayName = node.path("awayTeam").path("name").asText(null);
                if (matchTime == null || homeName == null || awayName == null) continue;
                CachedMatch cached = new CachedMatch(
                        String.valueOf(node.path("id").asLong()), code, LEAGUE_NAMES.get(code),
                        String.valueOf(node.path("homeTeam").path("id").asLong()), homeName,
                        node.path("homeTeam").path("shortName").asText(homeName),
                        String.valueOf(node.path("awayTeam").path("id").asLong()), awayName,
                        node.path("awayTeam").path("shortName").asText(awayName),
                        fullTime.path("home").intValue(), fullTime.path("away").intValue(), matchTime);
                // 同一 fixture 可能同时出现在按日期和按联赛导出的文件中；
                // 以来源+联赛+fixtureId 去重，避免历史样本被重复计数。
                String dedupeKey = code + ":" + cached.fixtureId();
                target.putIfAbsent(dedupeKey, cached);
            }
        } catch (Exception ex) {
            log.debug("忽略损坏的历史缓存文件 {}: {}", path.getFileName(), ex.getMessage());
        }
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private Path resolvePath(String value) {
        Path path = Paths.get(value == null || value.isBlank() ? "football-ml-service/data_cache" : value);
        return path.isAbsolute() ? path : Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
    }

    private record CachedMatch(String fixtureId, String leagueId, String leagueName,
                               String homeTeamId, String homeTeamName, String homeShortName,
                               String awayTeamId, String awayTeamName, String awayShortName,
                               int homeScore, int awayScore, LocalDateTime matchTime) {
        Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fixtureId);
            row.put("fixture_id", fixtureId);
            row.put("source", "historical-cache");
            row.put("status", "FINISHED");
            row.put("league_id", leagueId);
            row.put("league_name", leagueName);
            row.put("home_team_id", homeTeamId);
            row.put("away_team_id", awayTeamId);
            // Short names are the closest common denominator with BBC's score
            // feed (e.g. “Marseille” vs “Olympique de Marseille”).
            row.put("home_team_name", homeShortName == null || homeShortName.isBlank() ? homeTeamName : homeShortName);
            row.put("away_team_name", awayShortName == null || awayShortName.isBlank() ? awayTeamName : awayShortName);
            row.put("home_score", homeScore);
            row.put("away_score", awayScore);
            row.put("match_time", matchTime);
            row.put("updated_at", matchTime);
            return row;
        }
    }
}
