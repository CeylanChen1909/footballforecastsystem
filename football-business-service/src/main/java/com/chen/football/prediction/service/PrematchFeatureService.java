package com.chen.football.prediction.service;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.service.IdentityNormalizer;
import com.chen.football.common.config.CrawlerProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Builds an auditable, point-in-time prematch feature snapshot.
 *
 * The service deliberately runs from local rows and the read-only historical
 * cache. It never asks a provider for the target match's final statistics, so
 * a training/prediction request cannot leak post-match information. Optional
 * xG/shots/lineup/injury/market fields are retained as nullable quality-aware
 * values and can be filled by a provider adapter later.
 */
@Slf4j
@Service
public class PrematchFeatureService {
    private static final String VERSION = "prematch-v2-enrichment";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SUPPORTED_LEAGUES = Set.of(
            "39", "140", "135", "78", "61", "88", "94", "40",
            "PL", "PD", "SA", "BL1", "FL1", "DED", "PPL", "ELC",
            "bbc-premier-league", "bbc-spanish-la-liga", "bbc-italian-serie-a", "bbc-german-bundesliga",
            "bbc-french-ligue-one", "bbc-dutch-eredivisie", "bbc-portuguese-primeira-liga", "bbc-championship"
    );
    private static final Set<String> FINISHED = Set.of("FT", "AET", "PEN", "FINISHED");

    private final JdbcTemplate jdbcTemplate;
    private final CrawlerMatchMapper crawlerMatchMapper;
    private final HistoricalMatchCacheService historicalCache;
    private final ObjectMapper objectMapper;
    private final CrawlerProperties crawlerProperties;

    public PrematchFeatureService(JdbcTemplate jdbcTemplate,
                                  CrawlerMatchMapper crawlerMatchMapper,
                                  HistoricalMatchCacheService historicalCache,
                                  ObjectMapper objectMapper,
                                  CrawlerProperties crawlerProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.historicalCache = historicalCache;
        this.objectMapper = objectMapper;
        this.crawlerProperties = crawlerProperties;
    }

    @PostConstruct
    void ensureTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_prematch_feature_snapshot (" +
                "fixture_id BIGINT NOT NULL PRIMARY KEY," +
                "feature_version VARCHAR(64) NOT NULL," +
                "cutoff_time DATETIME NULL," +
                "source_updated_at DATETIME NULL," +
                "status VARCHAR(32) NOT NULL," +
                "completeness DECIMAL(6,4) NOT NULL DEFAULT 0," +
                "source VARCHAR(64) NOT NULL DEFAULT 'local-history'," +
                "payload_json MEDIUMTEXT NULL," +
                "error_message VARCHAR(512) NULL," +
                "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "INDEX idx_prematch_cutoff (cutoff_time), INDEX idx_prematch_status (status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** Refresh a single upcoming fixture; safe to call repeatedly. */
    public Map<String, Object> refreshForMatch(CrawlerMatch target) {
        if (target == null || target.getId() == null || target.getMatchTime() == null || !visibleSource(target)) {
            return Map.of("status", "INVALID", "completeness", 0.0);
        }
        long publicFixtureId = target.getFixtureId() != null && target.getFixtureId() > 0
                ? target.getFixtureId() : target.getId();
        LocalDateTime cutoff = target.getMatchTime();
        try {
            List<Map<String, Object>> rows = historicalRows(cutoff, target);
            Map<String, Object> payload = calculate(rows, target, cutoff);
            double completeness = ((Number) payload.getOrDefault("completeness", 0.0)).doubleValue();
            String status = completeness >= 0.70 ? "READY" : completeness > 0 ? "PARTIAL" : "NO_HISTORY";
            jdbcTemplate.update("INSERT INTO t_prematch_feature_snapshot " +
                            "(fixture_id,feature_version,cutoff_time,source_updated_at,status,completeness,source,payload_json,error_message) " +
                            "VALUES (?,?,?,?,?,?,?,?,NULL) ON DUPLICATE KEY UPDATE feature_version=VALUES(feature_version)," +
                            "cutoff_time=VALUES(cutoff_time),source_updated_at=VALUES(source_updated_at),status=VALUES(status)," +
                            "completeness=VALUES(completeness),source=VALUES(source),payload_json=VALUES(payload_json),error_message=NULL",
                    publicFixtureId, VERSION, cutoff, LocalDateTime.now(BUSINESS_ZONE), status, completeness,
                    "local-history", objectMapper.writeValueAsString(payload));
            payload.put("fixtureId", publicFixtureId);
            payload.put("status", status);
            return payload;
        } catch (Exception ex) {
            log.warn("赛前特征构建失败 fixtureId={}: {}", publicFixtureId, ex.getMessage());
            try {
                jdbcTemplate.update("INSERT INTO t_prematch_feature_snapshot " +
                                "(fixture_id,feature_version,cutoff_time,source_updated_at,status,completeness,source,payload_json,error_message) " +
                                "VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),error_message=VALUES(error_message),updated_at=CURRENT_TIMESTAMP",
                                publicFixtureId, VERSION, cutoff, LocalDateTime.now(BUSINESS_ZONE), "ERROR", 0.0, "local-history", "{}", ex.getMessage());
            } catch (Exception ignored) { }
            return Map.of("fixtureId", publicFixtureId, "status", "ERROR", "completeness", 0.0);
        }
    }

    public Map<String, Object> getSnapshot(Long fixtureId) {
        if (fixtureId == null || fixtureId <= 0) return Map.of();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT payload_json,status,completeness,cutoff_time,feature_version,source FROM t_prematch_feature_snapshot WHERE fixture_id=?",
                    fixtureId);
            if (rows.isEmpty()) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            String json = Objects.toString(rows.get(0).get("payload_json"), "{}");
            result.putAll(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { }));
            result.put("status", rows.get(0).get("status"));
            result.put("completeness", rows.get(0).get("completeness"));
            result.put("featureVersion", rows.get(0).get("feature_version"));
            result.put("source", rows.get(0).get("source"));
            result.put("cutoffTime", rows.get(0).get("cutoff_time"));
            return result;
        } catch (Exception ex) {
            log.debug("读取赛前特征快照失败 fixtureId={}: {}", fixtureId, ex.getMessage());
            return Map.of();
        }
    }

    @Scheduled(fixedDelayString = "${prediction.prematch-enrichment-fixed-delay-ms:900000}")
    public void refreshUpcoming() {
        try {
            crawlerMatchMapper.findUpcomingMatches().stream()
                    .filter(this::visibleSource)
                    .filter(this::supported)
                    .filter(match -> match.getMatchTime() != null && match.getMatchTime().isBefore(LocalDateTime.now(BUSINESS_ZONE).plusDays(7)))
                    .forEach(this::refreshForMatch);
        } catch (Exception ex) {
            log.warn("定时刷新赛前特征失败: {}", ex.getMessage());
        }
    }

    private boolean supported(CrawlerMatch m) {
        String id = m.getLeagueId() == null ? "" : m.getLeagueId().trim();
        if (!id.isBlank()) return SUPPORTED_LEAGUES.contains(id);
        String name = IdentityNormalizer.normalize(m.getLeagueName());
        return Set.of("英超", "西甲", "意甲", "德甲", "法甲", "荷甲", "葡超", "英冠",
                "premierleague", "laliga", "seriea", "bundesliga", "ligue1", "eredivisie", "primeiraliga", "championship").contains(name);
    }

    private boolean visibleSource(CrawlerMatch match) {
        if (match == null || match.getSource() == null || match.getSource().isBlank()) return false;
        return !crawlerProperties.isPrimaryOnly()
                || (crawlerProperties.getPrimarySource() != null
                && crawlerProperties.getPrimarySource().equalsIgnoreCase(match.getSource().trim()));
    }

    private List<Map<String, Object>> historicalRows(LocalDateTime cutoff, CrawlerMatch target) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sourceFilter = primarySourceFilter();
        rows.addAll(jdbcTemplate.queryForList(("SELECT fixture_id,source,league_id,league_name,home_team_id,away_team_id,home_team_name,away_team_name,home_score,away_score,match_time FROM crawler_matches WHERE status IN ('FT','AET','PEN') AND home_score IS NOT NULL AND away_score IS NOT NULL AND match_time < ?" + sourceFilter + " ORDER BY match_time ASC LIMIT 20000"), cutoff));
        rows.addAll(historicalCache.allFinishedBefore(cutoff));
        String targetLeague = leagueKey(target.getLeagueId(), target.getLeagueName());
        List<Map<String, Object>> filtered = rows.stream()
                .filter(row -> supportedRow(row))
                .filter(row -> targetLeague.isBlank() || targetLeague.equals(leagueKey(Objects.toString(row.get("league_id"), ""), Objects.toString(row.get("league_name"), ""))))
                .toList();
        // 去重不能只依赖字符串 matchKey：数据库里的 BBC 短名与缓存里的
        // 官方名可能不同，但仍然是同一场比赛。优先保留数据库行，避免
        // 同一结果被重复计入滚动样本。
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        List<Map<String, Object>> unique = new ArrayList<>();
        for (Map<String, Object> row : filtered) {
            LocalDateTime date = toDate(row.get("match_time"));
            String bucketKey = leagueKey(Objects.toString(row.get("league_id"), ""), Objects.toString(row.get("league_name"), ""))
                    + "|" + (date == null ? "" : date.toLocalDate());
            List<Map<String, Object>> bucket = buckets.computeIfAbsent(bucketKey, ignored -> new ArrayList<>());
            boolean duplicate = bucket.stream().anyMatch(existing -> sameHistoricalFixture(existing, row));
            if (!duplicate) unique.add(row);
            if (!duplicate) bucket.add(row);
        }
        return unique.stream().sorted(Comparator.comparing(this::toDate, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    private String primarySourceFilter() {
        if (!crawlerProperties.isPrimaryOnly() || crawlerProperties.getPrimarySource() == null
                || crawlerProperties.getPrimarySource().isBlank()) return "";
        return " AND source = '" + crawlerProperties.getPrimarySource().replace("'", "''") + "'";
    }

    private boolean sameHistoricalFixture(Map<String, Object> first, Map<String, Object> second) {
        if (!leagueKey(Objects.toString(first.get("league_id"), ""), Objects.toString(first.get("league_name"), ""))
                .equals(leagueKey(Objects.toString(second.get("league_id"), ""), Objects.toString(second.get("league_name"), "")))) return false;
        LocalDateTime firstDate = toDate(first.get("match_time")), secondDate = toDate(second.get("match_time"));
        String firstFixture = Objects.toString(first.get("fixture_id"), "");
        String secondFixture = Objects.toString(second.get("fixture_id"), "");
        if (!firstFixture.isBlank() && firstFixture.equals(secondFixture)) return true;
        if (firstDate == null || secondDate == null || !firstDate.toLocalDate().equals(secondDate.toLocalDate())) return false;
        // 同一天的同两队不必然是同一场（补赛/双赛日）。只有开球时间
        // 接近时才去重，缺少时间的两条记录一律保留。
        if (Math.abs(java.time.Duration.between(firstDate, secondDate).toMinutes()) > 120) return false;
        return IdentityNormalizer.compatible(Objects.toString(first.get("home_team_name"), ""), Objects.toString(second.get("home_team_name"), ""))
                && IdentityNormalizer.compatible(Objects.toString(first.get("away_team_name"), ""), Objects.toString(second.get("away_team_name"), ""));
    }

    private boolean supportedRow(Map<String, Object> row) {
        String id = Objects.toString(row.get("league_id"), "");
        if (!id.isBlank() && SUPPORTED_LEAGUES.contains(id)) return true;
        return !id.isBlank() ? false : supportedName(Objects.toString(row.get("league_name"), ""));
    }

    private boolean supportedName(String value) {
        String n = IdentityNormalizer.normalize(value);
        return Set.of("premierleague", "laliga", "primeradivision", "seriea", "bundesliga", "ligue1", "eredivisie", "primeiraliga", "portugueseprimeiraliga", "championship", "英超", "西甲", "意甲", "德甲", "法甲", "荷甲", "葡超", "英冠").contains(n);
    }

    private String leagueKey(String id, String name) {
        String value = id == null || id.isBlank() ? name : id;
        if (value == null) return "";
        String normalized = IdentityNormalizer.normalize(value);
        return switch (normalized) {
            case "39", "pl", "premierleague", "bbcpremierleague", "英超" -> "pl";
            case "140", "pd", "laliga", "bbcspanishlaliga", "primeradivision", "西甲" -> "pd";
            case "135", "sa", "seriea", "bbcitalianseriea", "意甲" -> "sa";
            case "78", "bl1", "bundesliga", "bbcgermanbundesliga", "德甲" -> "bl1";
            case "61", "fl1", "ligue1", "bbcfrenchligueone", "法甲" -> "fl1";
            case "88", "ded", "eredivisie", "bbcdutcheredivisie", "荷甲" -> "ded";
            case "94", "ppl", "primeiraliga", "portugueseprimeiraliga", "bbcportugueseprimeiraliga", "葡超" -> "ppl";
            case "40", "elc", "championship", "bbcchampionship", "英冠" -> "elc";
            default -> normalized;
        };
    }

    private Map<String, Object> calculate(List<Map<String, Object>> rows, CrawlerMatch target, LocalDateTime cutoff) {
        String home = IdentityNormalizer.normalize(target.getHomeTeamName());
        String away = IdentityNormalizer.normalize(target.getAwayTeamName());
        Map<String, List<History>> byTeam = new HashMap<>();
        Map<String, Table> table = new HashMap<>();
        Map<String, DetailStats> detailStats = loadHistoricalDetailStats(cutoff);
        Map<String, List<XgPoint>> understatXg = loadUnderstatTeamXg(cutoff);
        for (Map<String, Object> row : rows) {
            LocalDateTime when = toDate(row.get("match_time"));
            if (when == null || !when.isBefore(cutoff)) continue;
            int hg = number(row.get("home_score")), ag = number(row.get("away_score"));
            String h = IdentityNormalizer.normalize(Objects.toString(row.get("home_team_name"), ""));
            String a = IdentityNormalizer.normalize(Objects.toString(row.get("away_team_name"), ""));
            if (h.isBlank() || a.isBlank()) continue;
            String fixture = Objects.toString(row.get("fixture_id"), "");
            DetailStats hStats = detailStats.get(fixture + "|" + Objects.toString(row.get("home_team_id"), ""));
            DetailStats aStats = detailStats.get(fixture + "|" + Objects.toString(row.get("away_team_id"), ""));
            if (hStats == null) hStats = findUnderstatXg(understatXg, h, when);
            if (aStats == null) aStats = findUnderstatXg(understatXg, a, when);
            add(byTeam, h, new History(when, hg, ag, true, hStats));
            add(byTeam, a, new History(when, ag, hg, false, aStats));
            Table ht = table.computeIfAbsent(h, k -> new Table()), at = table.computeIfAbsent(a, k -> new Table());
            ht.add(hg, ag); at.add(ag, hg);
        }
        List<History> homeHistory = compatibleHistory(byTeam, home);
        List<History> awayHistory = compatibleHistory(byTeam, away);
        TeamStats hs = stats(homeHistory, compatibleTable(table, home), cutoff);
        TeamStats as = stats(awayHistory, compatibleTable(table, away), cutoff);
        double homeXg5 = hs.xg5 > 0 ? hs.xg5 : understatAverage(understatXg, home, false, 5);
        double awayXg5 = as.xg5 > 0 ? as.xg5 : understatAverage(understatXg, away, false, 5);
        double homeXga5 = hs.xga5 > 0 ? hs.xga5 : understatAverage(understatXg, home, true, 5);
        double awayXga5 = as.xga5 > 0 ? as.xga5 : understatAverage(understatXg, away, true, 5);
        int homeRank = rank(home, table), awayRank = rank(away, table);
        Enrichment enrichment = loadTargetEnrichment(target.getFixtureId() != null ? target.getFixtureId() : target.getId(), cutoff,
                target.getHomeTeamId(), target.getAwayTeamId());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("featureVersion", VERSION); p.put("cutoffTime", cutoff); p.put("homeSampleSize", hs.sample); p.put("awaySampleSize", as.sample);
        p.put("homeHistoryTier", historyTier(hs.sample)); p.put("awayHistoryTier", historyTier(as.sample));
        p.put("homeRank", homeRank); p.put("awayRank", awayRank); p.put("rankDiff", homeRank - awayRank);
        p.put("homePointsPerMatch", hs.ppm); p.put("awayPointsPerMatch", as.ppm); p.put("pointsPerMatchDiff", hs.ppm - as.ppm);
        p.put("homeGoalDiffPerMatch", hs.gdpm); p.put("awayGoalDiffPerMatch", as.gdpm); p.put("goalDiffPerMatchDiff", hs.gdpm - as.gdpm);
        p.put("homeForm5", hs.form5); p.put("awayForm5", as.form5); p.put("homeForm10", hs.form10); p.put("awayForm10", as.form10);
        p.put("homeHomeForm5", hs.homeForm5); p.put("awayAwayForm5", as.awayForm5);
        p.put("homeMatches14d", hs.matches14d); p.put("awayMatches14d", as.matches14d); p.put("matches14dDiff", hs.matches14d - as.matches14d);
        p.put("homeXg5", homeXg5); p.put("awayXg5", awayXg5); p.put("homeXga5", homeXga5); p.put("awayXga5", awayXga5);
        p.put("homeShots5", hs.shots5); p.put("awayShots5", as.shots5); p.put("homeShotsOnTarget5", hs.onTarget5); p.put("awayShotsOnTarget5", as.onTarget5);
        // Keep missingness explicit. Zero is a neutral model input, not a
        // claim that the team generated zero xG or shots.
        p.put("homeXgAvailable", homeXg5 > 0); p.put("awayXgAvailable", awayXg5 > 0);
        p.put("homeXgaAvailable", homeXga5 > 0); p.put("awayXgaAvailable", awayXga5 > 0);
        p.put("homeShotsAvailable", hs.shots5 > 0); p.put("awayShotsAvailable", as.shots5 > 0);
        p.put("xgCoverage", (homeXg5 > 0 ? 0.25 : 0.0) + (awayXg5 > 0 ? 0.25 : 0.0)
                + (homeXga5 > 0 ? 0.25 : 0.0) + (awayXga5 > 0 ? 0.25 : 0.0));
        // Optional providers can update these values later. They are explicit
        // zero/unknown values, never fabricated estimates.
        p.put("homeLineupStability", enrichment.homeLineupStability); p.put("awayLineupStability", enrichment.awayLineupStability);
        p.put("homeInjuryImpact", enrichment.homeInjuryImpact); p.put("awayInjuryImpact", enrichment.awayInjuryImpact);
        p.put("marketHomeProb", enrichment.homeProb); p.put("marketDrawProb", enrichment.drawProb); p.put("marketAwayProb", enrichment.awayProb);
        p.put("dataQuality", Map.of("historySource", "crawler_matches+historical-cache", "historyTier", historyTier(Math.min(hs.sample, as.sample)), "xgShots", (homeXg5 > 0 || awayXg5 > 0) ? "AVAILABLE" : "NOT_CONFIGURED", "lineups", enrichment.lineups ? "AVAILABLE" : "NOT_CONFIGURED", "injuries", enrichment.injuries ? "AVAILABLE" : "NOT_CONFIGURED", "odds", enrichment.odds ? "AVAILABLE" : "NOT_CONFIGURED"));
        p.put("completeness", Math.min(1.0, (hs.sample >= 3 ? 0.35 : 0.0) + (as.sample >= 3 ? 0.35 : 0.0) + (homeRank > 0 && awayRank > 0 ? 0.20 : 0.0) + ((homeXg5 > 0 || awayXg5 > 0) ? 0.05 : 0.0) + (enrichment.lineups ? 0.025 : 0.0) + (enrichment.injuries ? 0.025 : 0.0)));
        return p;
    }

    private List<History> compatibleHistory(Map<String, List<History>> byTeam, String requested) {
        if (requested == null || requested.isBlank()) return List.of();
        return byTeam.entrySet().stream()
                .filter(entry -> IdentityNormalizer.compatible(requested, entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .sorted(Comparator.comparing(History::when, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private Table compatibleTable(Map<String, Table> table, String requested) {
        return table.entrySet().stream()
                .filter(entry -> IdentityNormalizer.compatible(requested, entry.getKey()))
                .map(Map.Entry::getValue)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private String historyTier(int sample) {
        return sample >= 5 ? "ROBUST" : sample >= 3 ? "READY" : sample >= 1 ? "LIMITED" : "NONE";
    }

    private Enrichment loadTargetEnrichment(Long fixtureId, LocalDateTime cutoff, String homeTeamId, String awayTeamId) {
        if (fixtureId == null || fixtureId <= 0) return Enrichment.empty();
        boolean lineups = false, injuries = false, odds = false;
        double homeStability = 0, awayStability = 0, homeInjury = 0, awayInjury = 0, hp = 0, dp = 0, ap = 0;
        try {
            List<Map<String, Object>> snapshots = jdbcTemplate.queryForList("SELECT detail_type,payload_json FROM t_match_detail_snapshot WHERE fixture_id=? AND fetched_at <= ?", fixtureId, cutoff);
            for (Map<String, Object> row : snapshots) {
                String type = Objects.toString(row.get("detail_type"), "");
                JsonNode root = objectMapper.readTree(Objects.toString(row.get("payload_json"), "[]"));
                if ("lineups".equals(type) && root.isArray()) {
                    for (JsonNode team : root) {
                        JsonNode starters = team.path("startXI");
                        if (!starters.isArray() || starters.isEmpty()) continue;
                        double stability = Math.min(1.0, starters.size() / 11.0);
                        String teamId = team.path("team").path("id").asText("");
                        if (teamId.isBlank()) continue;
                        // Team identity is provider-specific; use array order as
                        // a deterministic fallback when no shared ID exists.
                        if (teamId.equals(Objects.toString(homeTeamId, ""))) homeStability = stability;
                        else if (teamId.equals(Objects.toString(awayTeamId, ""))) awayStability = stability;
                        else if (homeStability == 0) homeStability = stability; else awayStability = stability;
                        lineups = true;
                    }
                } else if ("injuries".equals(type) && root.isArray()) {
                    for (JsonNode item : root) {
                        String teamId = item.path("team").path("id").asText("");
                        double impact = item.path("player").path("type").asText("").toLowerCase(Locale.ROOT).contains("susp") ? 1.0 : 0.5;
                        if (teamId.isBlank()) continue;
                        if (teamId.equals(Objects.toString(homeTeamId, ""))) homeInjury += impact;
                        else if (teamId.equals(Objects.toString(awayTeamId, ""))) awayInjury += impact;
                        else if (homeInjury == 0) homeInjury += impact; else awayInjury += impact;
                        injuries = true;
                    }
                    homeInjury = Math.min(1.0, homeInjury / 5.0); awayInjury = Math.min(1.0, awayInjury / 5.0);
                } else if ("odds".equals(type) && root.isArray()) {
                    double[] implied = firstOdds(root);
                    if (implied[0] > 0) { hp = implied[0]; dp = implied[1]; ap = implied[2]; odds = true; }
                }
            }
        } catch (Exception ex) { log.debug("读取目标赛前增强数据失败 fixtureId={}: {}", fixtureId, ex.getMessage()); }
        return new Enrichment(homeStability, awayStability, homeInjury, awayInjury, hp, dp, ap, lineups, injuries, odds);
    }

    private double[] firstOdds(JsonNode root) {
        double h = 0, d = 0, a = 0;
        JsonNode rows = root.isArray() ? root : root.path("response");
        if (!rows.isArray()) rows = root.isObject() ? root.path("bookmakers") : rows;
        for (JsonNode row : rows) {
            JsonNode bookmakers = row.has("bookmakers") ? row.path("bookmakers") : (row.has("bets") ?
                    objectArray(row) : row);
            if (!bookmakers.isArray()) continue;
            for (JsonNode bookmaker : bookmakers) {
                for (JsonNode bet : bookmaker.path("bets")) for (JsonNode value : bet.path("values")) {
                    String label = value.path("value").asText("").toLowerCase(Locale.ROOT);
                    double odd = decimal(value.path("odd")); if (odd <= 1) continue;
                    if (label.contains("home") || label.equals("1")) h = 1 / odd;
                    else if (label.contains("draw") || label.equals("x")) d = 1 / odd;
                    else if (label.contains("away") || label.equals("2")) a = 1 / odd;
                    if (h > 0 && d > 0 && a > 0) break;
                }
                if (h > 0 && d > 0 && a > 0) break;
            }
            if (h > 0 && d > 0 && a > 0) break;
        }
        double total = h + d + a; return total > 0 ? new double[]{h / total, d / total, a / total} : new double[]{0, 0, 0};
    }

    private JsonNode objectArray(JsonNode node) {
        return node.isObject() && node.has("bets") ? objectMapper.createArrayNode().add(node) : node;
    }

    /** Parse API-Football statistics snapshots already stored locally.
     * MatchDetailsService stores the provider's response array (not the outer
     * HTTP envelope), so accept both shapes for old and new rows. */
    private Map<String, DetailStats> loadHistoricalDetailStats(LocalDateTime cutoff) {
        Map<String, DetailStats> result = new HashMap<>();
        try {
            List<Map<String, Object>> snapshots = jdbcTemplate.queryForList(
                    "SELECT fixture_id,detail_type,payload_json FROM t_match_detail_snapshot WHERE detail_type IN ('statistics','xg') AND fetched_at < ? ORDER BY fetched_at DESC LIMIT 20000", cutoff);
            Map<String, Map<String, DetailStats>> byFixture = new LinkedHashMap<>();
            for (Map<String, Object> row : snapshots) {
                String fixture = Objects.toString(row.get("fixture_id"), "");
                    JsonNode root = objectMapper.readTree(Objects.toString(row.get("payload_json"), "{}"));
                JsonNode response = root.isArray() ? root : root.path("response");
                if (!response.isArray()) continue;
                Map<String, DetailStats> fixtureStats = byFixture.computeIfAbsent(fixture, key -> new LinkedHashMap<>());
                for (JsonNode team : response) {
                    String teamId = team.path("team").path("id").asText("");
                    if (teamId.isBlank()) continue;
                    double xg = 0, shots = 0, onTarget = 0;
                    JsonNode stats = team.path("statistics");
                    if (stats.isArray()) for (JsonNode item : stats) {
                        String type = item.path("type").asText("").toLowerCase(Locale.ROOT);
                        double value = decimal(item.path("value"));
                        if (type.contains("expected") || type.equals("xg")) xg = value;
                        if (type.equals("total shots") || type.equals("shots")) shots = value;
                        if (type.contains("shots on goal") || type.contains("shots on target")) onTarget = value;
                    }
                    fixtureStats.putIfAbsent(teamId, new DetailStats(xg, 0, shots, onTarget));
                }
            }
            for (Map.Entry<String, Map<String, DetailStats>> entry : byFixture.entrySet()) {
                Map<String, DetailStats> fixtureStats = entry.getValue();
                for (Map.Entry<String, DetailStats> team : fixtureStats.entrySet()) {
                    double opponentXg = fixtureStats.entrySet().stream()
                            .filter(other -> !other.getKey().equals(team.getKey()))
                            .mapToDouble(other -> other.getValue().xg)
                            .filter(value -> value > 0)
                            .findFirst().orElse(0);
                    DetailStats value = team.getValue();
                    result.putIfAbsent(entry.getKey() + "|" + team.getKey(),
                            new DetailStats(value.xg, opponentXg, value.shots, value.onTarget));
                }
            }
        } catch (Exception ex) {
            log.debug("读取历史统计快照失败: {}", ex.getMessage());
        }
        return result;
    }

    /** Understat is keyed by its own match/team ids, so it cannot always be
     * joined to a BBC fixture. Keep it as a date/team keyed fallback and only
     * use observations strictly before the target kickoff. */
    private Map<String, List<XgPoint>> loadUnderstatTeamXg(LocalDateTime cutoff) {
        Map<String, List<XgPoint>> result = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT team_name,match_time,xg,xga FROM t_understat_team_xg_cache WHERE match_time < ? ORDER BY match_time DESC LIMIT 30000", cutoff);
            for (Map<String, Object> row : rows) {
                String name = IdentityNormalizer.normalize(Objects.toString(row.get("team_name"), ""));
                LocalDateTime when = toDate(row.get("match_time"));
                double xg = decimal(row.get("xg")), xga = decimal(row.get("xga"));
                if (name.isBlank() || when == null || xg <= 0 || xga <= 0) continue;
                result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(new XgPoint(when, xg, xga));
            }
        } catch (Exception ex) {
            log.debug("读取 Understat 球队 xG 缓存失败: {}", ex.getMessage());
        }
        return result;
    }

    private DetailStats findUnderstatXg(Map<String, List<XgPoint>> byTeam, String team, LocalDateTime when) {
        if (team == null || team.isBlank() || when == null) return null;
        XgPoint best = null;
        long distance = Long.MAX_VALUE;
        for (Map.Entry<String, List<XgPoint>> entry : byTeam.entrySet()) {
            if (!IdentityNormalizer.compatible(team, entry.getKey())) continue;
            for (XgPoint point : entry.getValue()) {
                long minutes = Math.abs(java.time.Duration.between(point.when(), when).toMinutes());
                // Understat timestamps are commonly UTC while crawler rows are
                // Asia/Shanghai; allow that fixed offset, not an arbitrary date.
                if (minutes <= 12 * 60 && minutes < distance) {
                    best = point;
                    distance = minutes;
                }
            }
        }
        return best == null ? null : new DetailStats(best.xg(), best.xga(), 0, 0);
    }

    private double understatAverage(Map<String, List<XgPoint>> byTeam, String team, boolean against, int count) {
        if (team == null || team.isBlank()) return 0;
        return byTeam.entrySet().stream()
                .filter(entry -> IdentityNormalizer.compatible(team, entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .sorted(Comparator.comparing(XgPoint::when).reversed())
                .limit(Math.max(1, count))
                .mapToDouble(point -> against ? point.xga() : point.xg())
                .filter(value -> value > 0)
                .average().orElse(0);
    }

    private double decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        if (node.isNumber()) return node.doubleValue();
        try { return Double.parseDouble(node.asText().replace("%", "").trim()); } catch (Exception e) { return 0; }
    }

    private double decimal(Object value) {
        try { return value == null ? 0 : Double.parseDouble(Objects.toString(value, "0").replace(",", "").trim()); }
        catch (Exception e) { return 0; }
    }

    private void add(Map<String, List<History>> map, String key, History value) { map.computeIfAbsent(key, k -> new ArrayList<>()).add(value); }

    private TeamStats stats(List<History> all, Table table, LocalDateTime cutoff) {
        all = all.stream().sorted(Comparator.comparing(History::when).reversed()).toList();
        List<History> recent = all.stream().limit(10).toList();
        return TeamStats.from(recent, all, table, cutoff);
    }

    private int rank(String team, Map<String, Table> table) {
        List<String> order = table.entrySet().stream().sorted(Map.Entry.<String, Table>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
        int index = order.indexOf(team);
        if (index < 0) index = 0;
        else return index + 1;
        for (int i = 0; i < order.size(); i++) {
            if (IdentityNormalizer.compatible(team, order.get(i))) return i + 1;
        }
        return 0;
    }

    private int number(Object value) { if (value instanceof Number n) return n.intValue(); try { return Integer.parseInt(Objects.toString(value, "0")); } catch (Exception e) { return 0; } }
    private LocalDateTime toDate(Object value) { if (value instanceof LocalDateTime t) return t; if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime(); try { return LocalDateTime.parse(Objects.toString(value)); } catch (Exception e) { return null; } }

    private record DetailStats(double xg, double xga, double shots, double onTarget) { }
    private record XgPoint(LocalDateTime when, double xg, double xga) { }
    private record Enrichment(double homeLineupStability, double awayLineupStability, double homeInjuryImpact, double awayInjuryImpact,
                              double homeProb, double drawProb, double awayProb, boolean lineups, boolean injuries, boolean odds) {
        static Enrichment empty() { return new Enrichment(0, 0, 0, 0, 0, 0, 0, false, false, false); }
    }
    private record History(LocalDateTime when, int goals, int conceded, boolean home, DetailStats detail) { }
    private static final class Table implements Comparable<Table> {
        int played; int points; int goalDiff; int goalsFor;
        void add(int gf, int ga) { played++; goalsFor += gf; goalDiff += gf - ga; points += gf > ga ? 3 : gf == ga ? 1 : 0; }
        public int compareTo(Table o) { return Comparator.comparingInt((Table t) -> t.points).thenComparingInt(t -> t.goalDiff).thenComparingInt(t -> t.goalsFor).compare(this, o); }
    }
    private record TeamStats(int sample, double ppm, double gdpm, double form5, double form10, double homeForm5, double awayForm5, int matches14d, double xg5, double xga5, double shots5, double onTarget5) {
        static TeamStats from(List<History> recent, List<History> all, Table table, LocalDateTime cutoff) {
            double ppm = table == null ? 0 : table.points / (double) Math.max(1, table.played);
            double gdpm = table == null ? 0 : table.goalDiff / (double) Math.max(1, table.played);
            double f5 = form(recent.stream().limit(5).toList()), f10 = form(recent);
            double hf = form(recent.stream().filter(History::home).limit(5).toList());
            double af = form(recent.stream().filter(h -> !h.home()).limit(5).toList());
            int density = (int) all.stream().filter(h -> h.when() != null && ChronoUnit.DAYS.between(h.when(), cutoff) >= 0 && ChronoUnit.DAYS.between(h.when(), cutoff) <= 14).count();
            List<DetailStats> details = recent.stream().map(History::detail).filter(Objects::nonNull).toList();
            double xg = details.stream().mapToDouble(DetailStats::xg).filter(v -> v > 0).average().orElse(0);
            double xga = details.stream().mapToDouble(DetailStats::xga).filter(v -> v > 0).average().orElse(0);
            double shots = details.stream().mapToDouble(DetailStats::shots).filter(v -> v > 0).average().orElse(0);
            double onTarget = details.stream().mapToDouble(DetailStats::onTarget).filter(v -> v > 0).average().orElse(0);
            return new TeamStats(recent.size(), ppm, gdpm, f5, f10, hf, af, density, xg, xga, shots, onTarget);
        }
        private static double form(List<History> list) { if (list.isEmpty()) return 0.5; return list.stream().mapToDouble(h -> h.goals() > h.conceded() ? 1 : h.goals() == h.conceded() ? .5 : 0).average().orElse(.5); }
    }
}
