package com.chen.football.match.service;

import com.chen.football.common.client.ApiFootballClient;
import com.chen.football.common.client.ApiFootballClient.BudgetBucket;
import com.chen.football.common.i18n.I18nText;
import com.chen.football.common.i18n.LabelCatalog;
import com.chen.football.common.client.FootballDataClient;
import com.chen.football.common.config.ApiFootballProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 比赛详情数据中台：将 API-Football 的事件、阵容、统计和球员表现缓存到本地，
 * 页面只依赖这个统一接口，不直接耦合任何外部数据源。
 */
@Slf4j
@Service
public class MatchDetailsService {

    private static final String SOURCE = "api-football";
    private static final List<String> TYPES = List.of("events", "lineups", "statistics", "players", "injuries", "odds");

    private final ApiFootballClient apiFootballClient;
    private final FootballDataClient footballDataClient;
    private final ApiFootballProperties apiFootballProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    /**
     * P0-4: whether {@code t_match_detail_snapshot.failure_count} exists.
     * The column is created by {@link #ensureSchema()} for fresh databases;
     * production needs the one-line ALTER (see reports/acq.md §P0-4) before
     * the counter is populated. Probed lazily once; {@code false} simply
     * skips the counter instead of breaking the snapshot write.
     */
    private volatile Boolean failureCountSupported;

    public MatchDetailsService(ApiFootballClient apiFootballClient,
                               FootballDataClient footballDataClient,
                               ApiFootballProperties apiFootballProperties,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper) {
        this.apiFootballClient = apiFootballClient;
        this.footballDataClient = footballDataClient;
        this.apiFootballProperties = apiFootballProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 兼容已有数据库：项目没有强制 Flyway/Liquibase，因此启动时只创建新增表。 */
    @PostConstruct
    public void ensureSchema() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_match_detail_snapshot (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  fixture_id BIGINT NOT NULL,
                  detail_type VARCHAR(32) NOT NULL,
                  source VARCHAR(64) NOT NULL,
                  payload_json LONGTEXT NOT NULL,
                  status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
                  error_message VARCHAR(1000) NULL,
                  failure_count INT NOT NULL DEFAULT 0,
                  fetched_at DATETIME NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_match_detail_source (fixture_id, detail_type, source),
                  KEY idx_match_detail_fixture (fixture_id),
                  KEY idx_match_detail_fetched (fetched_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    /** P0-4: lazily probes for the {@code failure_count} column (see field doc). */
    boolean failureCountSupported() {
        Boolean cached = failureCountSupported;
        if (cached != null) return cached;
        boolean supported = false;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = 't_match_detail_snapshot' "
                            + "AND column_name = 'failure_count'",
                    Integer.class);
            supported = count != null && count > 0;
        } catch (Exception ignored) {
            supported = false;
        }
        failureCountSupported = supported;
        return supported;
    }

    public Map<String, Object> getDetails(Long fixtureId, boolean refresh) {
        return getDetails(fixtureId, refresh, BudgetBucket.ON_DEMAND);
    }

    /**
     * Bucket-aware variant of {@link #getDetails(Long, boolean)}.
     * The scheduled refresh task passes {@link BudgetBucket#SCHEDULED};
     * user-triggered reads (match page, agent tool) use the default
     * {@link BudgetBucket#ON_DEMAND} so they draw on the on-demand reserve.
     */
    public Map<String, Object> getDetails(Long fixtureId, boolean refresh, BudgetBucket bucket) {
        if (fixtureId == null) {
            return errorBundle(null, "fixtureId 不能为空");
        }
        Map<String, Object> match = loadMatch(fixtureId);
        boolean apiFootballMatch = match.isEmpty() || SOURCE.equalsIgnoreCase(string(match.get("source")));
        Long providerFixtureId = number(match.get("fixture_id"));
        if (providerFixtureId == null || providerFixtureId <= 0) providerFixtureId = fixtureId;
        Map<String, Snapshot> snapshots;
        if (!apiFootballMatch) {
            // BBC 等来源只有本地公开 ID，没有 API-Football fixture_id。
            // 不读取同数字 ID 的 API 缓存，也不向 API-Football 发起错误请求。
            snapshots = unsupportedSnapshots(string(match.get("source")));
        } else {
            snapshots = loadSnapshots(fixtureId);
            if (refresh || snapshots.isEmpty()) {
                refreshMissing(fixtureId, providerFixtureId, match, snapshots, refresh, bucket);
                snapshots = loadSnapshots(fixtureId);
            }
        }
        return bundle(fixtureId, match, snapshots);
    }

    public Map<String, Object> refresh(Long fixtureId) {
        return refresh(fixtureId, BudgetBucket.ON_DEMAND);
    }

    /**
     * Bucket-aware variant of {@link #refresh(Long)}.
     * The scheduled refresh task passes {@link BudgetBucket#SCHEDULED}.
     */
    public Map<String, Object> refresh(Long fixtureId, BudgetBucket bucket) {
        // Manual refresh is authenticated but still user-triggerable. Avoid
        // spending the provider quota repeatedly when a user double-clicks
        // or several clients refresh the same fixture at once.
        // P0-4: the recency guard only counts NORMAL rows — a recent failure
        // must not masquerade as a fresh fetch and block the next retry.
        try {
            Integer recent = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_match_detail_snapshot WHERE fixture_id=? AND status='NORMAL' AND fetched_at >= DATE_SUB(NOW(), INTERVAL 10 MINUTE)",
                    Integer.class, fixtureId);
            if (recent != null && recent > 0) return getDetails(fixtureId, false, bucket);
        } catch (Exception ignored) { }
        return getDetails(fixtureId, true, bucket);
    }

    /** Fetch one detail type without spending requests on unrelated endpoints. */
    public Map<String, Object> refreshType(Long fixtureId, String type) {
        return refreshType(fixtureId, type, BudgetBucket.ON_DEMAND);
    }

    /** Bucket-aware variant of {@link #refreshType(Long, String)}. */
    public Map<String, Object> refreshType(Long fixtureId, String type, BudgetBucket bucket) {
        if (fixtureId == null || !TYPES.contains(type)) return errorBundle(fixtureId, "不支持的详情类型");
        Map<String, Object> match = loadMatch(fixtureId);
        if (!match.isEmpty() && !SOURCE.equalsIgnoreCase(string(match.get("source")))) {
            return getDetails(fixtureId, false, bucket);
        }
        if (apiFootballProperties.getApiKey() == null || apiFootballProperties.getApiKey().isBlank()) {
            saveSnapshot(fixtureId, type, List.of(), "NOT_CONFIGURED", "API_FOOTBALL_KEY 未配置");
        } else {
            fetchAndSave(fixtureId, number(match.get("fixture_id")) == null ? fixtureId : number(match.get("fixture_id")), type, bucket);
        }
        return bundle(fixtureId, match, loadSnapshots(fixtureId));
    }

    /**
     * Fetch provider-backed prematch details for a primary-source match whose
     * public row has no API-Football fixture_id.  The storage id remains the
     * application's public match id, while providerFixtureId is used only for
     * the external request.  This enriches predictions without inserting a
     * second fixture source or changing fixture identity.
     */
    public void refreshAlias(Long storageFixtureId, Long providerFixtureId) {
        refreshAlias(storageFixtureId, providerFixtureId, List.of("lineups", "injuries", "odds"));
    }

    /**
     * Enrich a primary-source row with a provider fixture id without changing
     * the row's identity.  Historical statistics are opt-in because they are
     * useful for rolling xG features only after the match is finished.
     */
    public void refreshAlias(Long storageFixtureId, Long providerFixtureId, List<String> types) {
        refreshAlias(storageFixtureId, providerFixtureId, types, BudgetBucket.ON_DEMAND);
    }

    /**
     * Bucket-aware variant of {@link #refreshAlias(Long, Long, List)}.
     * The scheduled refresh task passes {@link BudgetBucket#SCHEDULED} so its
     * detail calls draw on the scheduled share, never the on-demand reserve.
     */
    public void refreshAlias(Long storageFixtureId, Long providerFixtureId, List<String> types, BudgetBucket bucket) {
        if (storageFixtureId == null || storageFixtureId <= 0 || providerFixtureId == null || providerFixtureId <= 0) {
            return;
        }
        for (String type : types == null ? List.<String>of() : types) {
            if (!List.of("lineups", "injuries", "odds", "statistics").contains(type)) continue;
            try {
                // P0-4: only a NORMAL row counts as "recently fetched". A recent
                // failure/empty row must not block the next retry round.
                Integer recent = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_match_detail_snapshot WHERE fixture_id=? AND detail_type=? AND status='NORMAL' AND fetched_at >= DATE_SUB(NOW(), INTERVAL 30 MINUTE)",
                        Integer.class, storageFixtureId, type);
                if (recent != null && recent > 0) continue;
            } catch (Exception ignored) { }
            fetchAndSave(storageFixtureId, providerFixtureId, type, bucket);
        }
    }

    private void refreshMissing(Long storageFixtureId,
                                Long providerFixtureId,
                                Map<String, Object> match,
                                Map<String, Snapshot> snapshots,
                                boolean force) {
        refreshMissing(storageFixtureId, providerFixtureId, match, snapshots, force, BudgetBucket.ON_DEMAND);
    }

    private void refreshMissing(Long storageFixtureId,
                                Long providerFixtureId,
                                Map<String, Object> match,
                                Map<String, Snapshot> snapshots,
                                boolean force,
                                BudgetBucket bucket) {
        if (apiFootballProperties.getApiKey() == null || apiFootballProperties.getApiKey().isBlank()) {
            for (String type : TYPES) {
                if (force || !snapshots.containsKey(type)) {
                    saveSnapshot(storageFixtureId, type, List.of(), "NOT_CONFIGURED", "API_FOOTBALL_KEY 未配置");
                }
            }
            return;
        }

        String status = string(match.get("status"));
        boolean played = "FT".equalsIgnoreCase(status) || "LIVE".equalsIgnoreCase(status)
                || "HT".equalsIgnoreCase(status) || "ET".equalsIgnoreCase(status)
                || "PEN".equalsIgnoreCase(status);
        // 未开始比赛不请求球员表现/赛后统计；阵容、伤停和赔率可在赛前获取。
        List<String> requested = played ? TYPES : List.of("lineups", "injuries", "odds");
        for (String type : requested) {
            if (!force && snapshots.containsKey(type)) continue;
            fetchAndSave(storageFixtureId, providerFixtureId, type, bucket);
        }
        // 未开始比赛的其他类型明确标记为 UNSUPPORTED，避免前端无限转圈。
        for (String type : TYPES) {
            if (!requested.contains(type) && (force || !snapshots.containsKey(type))) {
                saveSnapshot(storageFixtureId, type, List.of(), "UNSUPPORTED", "比赛尚未开始，暂无该类数据");
            }
        }
    }

    private void fetchAndSave(Long fixtureId, String type) {
        fetchAndSave(fixtureId, fixtureId, type, BudgetBucket.ON_DEMAND);
    }

    private void fetchAndSave(Long storageFixtureId, Long providerFixtureId, String type) {
        fetchAndSave(storageFixtureId, providerFixtureId, type, BudgetBucket.ON_DEMAND);
    }

    private void fetchAndSave(Long storageFixtureId, Long providerFixtureId, String type, BudgetBucket bucket) {
        try {
            Map<String, Object> raw = switch (type) {
                case "events" -> apiFootballClient.getFixtureEvents(providerFixtureId, bucket).block();
                case "lineups" -> apiFootballClient.getFixtureLineups(providerFixtureId, bucket).block();
                case "statistics" -> apiFootballClient.getFixtureStatistics(providerFixtureId, bucket).block();
                case "players" -> apiFootballClient.getFixturePlayers(providerFixtureId, bucket).block();
                case "injuries" -> apiFootballClient.getFixtureInjuries(providerFixtureId, bucket).block();
                case "odds" -> apiFootballClient.getFixtureOdds(providerFixtureId, bucket).block();
                default -> null;
            };
            if (raw == null) {
                saveSnapshot(storageFixtureId, type, List.of(), "REQUEST_FAILED", "数据源返回空响应");
                return;
            }
            String error = extractError(raw);
            if (error != null) {
                saveSnapshot(storageFixtureId, type, List.of(), classifyError(error), error);
                return;
            }
            Object payload = raw.get("response");
            if (!(payload instanceof List<?>)) payload = List.of();
            String status = ((List<?>) payload).isEmpty() ? "EMPTY" : "NORMAL";
            saveSnapshot(storageFixtureId, type, payload, status, null);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            saveSnapshot(storageFixtureId, type, List.of(), classifyError(error), error);
            log.warn("[MatchDetails] fetch {} providerFixture={} storageFixture={} failed: {}", type, providerFixtureId, storageFixtureId, error);
        }
    }

    private Map<String, Object> bundle(Long fixtureId,
                                       Map<String, Object> match,
                                       Map<String, Snapshot> snapshots) {
        enrichMatchDisplay(match);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fixtureId", fixtureId);
        data.put("fixture", match);
        data.put("source", match.isEmpty() ? SOURCE : string(match.getOrDefault("source", SOURCE)));
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> statuses = new LinkedHashMap<>();
        List<String> available = new ArrayList<>();
        LocalDateTime latest = null;
        for (String type : TYPES) {
            Snapshot snapshot = snapshots.get(type);
            details.put(type, snapshot == null ? List.of() : parsePayload(snapshot.payloadJson()));
            statuses.put(type, snapshot == null ? "UNKNOWN" : snapshot.status());
            if (snapshot != null && "NORMAL".equals(snapshot.status())) available.add(type);
            if (snapshot != null && snapshot.fetchedAt() != null
                    && (latest == null || snapshot.fetchedAt().isAfter(latest))) {
                latest = snapshot.fetchedAt();
            }
        }
        // Understat xG snapshots are imported by a separate bounded task;
        // expose them alongside API-Football details without allowing the
        // detail refresh path to request this provider on demand.
        Snapshot xg = snapshots.get("xg");
        details.put("xg", xg == null ? List.of() : parsePayload(xg.payloadJson()));
        statuses.put("xg", xg == null ? "UNKNOWN" : xg.status());
        if (xg != null && "NORMAL".equals(xg.status())) available.add("xg");
        if (xg != null && xg.fetchedAt() != null && (latest == null || xg.fetchedAt().isAfter(latest))) latest = xg.fetchedAt();
        data.put("details", details);
        data.put("statuses", statuses);
        data.put("available", available);
        data.put("lastUpdated", latest == null ? null : latest.toString());
        data.put("dataStatus", aggregateStatus(statuses));
        data.put("dataStatusText", statusText(aggregateStatus(statuses)));
        data.put("refreshable", SOURCE.equalsIgnoreCase(String.valueOf(data.get("source")))
                && apiFootballProperties.getApiKey() != null && !apiFootballProperties.getApiKey().isBlank());
        data.put("providerBudget", apiFootballClient.budgetSnapshot());
        if (!"NORMAL".equals(data.get("dataStatus")) && !match.isEmpty()) {
            data.put("fallback", loadFootballDataFallback(match));
        }
        return data;
    }

    /** Adds display-only names without changing persisted snake_case values. */
    private void enrichMatchDisplay(Map<String, Object> match) {
        if (match == null || match.isEmpty()) return;
        match.put("league_display_name", LabelCatalog.leagueDisplay(string(match.get("league_name"))));
        match.put("league_name_en", LabelCatalog.leagueEnglish(string(match.get("league_name"))));
        match.put("home_team_display_name", LabelCatalog.teamDisplay(string(match.get("home_team_name"))));
        match.put("home_team_name_en", LabelCatalog.teamEnglish(string(match.get("home_team_name"))));
        match.put("away_team_display_name", LabelCatalog.teamDisplay(string(match.get("away_team_name"))));
        match.put("away_team_name_en", LabelCatalog.teamEnglish(string(match.get("away_team_name"))));
    }

    private Map<String, Object> errorBundle(Long fixtureId, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fixtureId", fixtureId);
        data.put("dataStatus", "REQUEST_FAILED");
        data.put("dataStatusText", message);
        return data;
    }

    /**
     * football-data 只作为基础赛程兜底，不伪装成事件/阵容/统计数据源。
     * API-Football 额度受限时仍能告诉页面该场比赛是否存在以及基础比分。
     */
    private Map<String, Object> loadFootballDataFallback(Map<String, Object> match) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("source", "football-data");
        fallback.put("status", "EMPTY");
        fallback.put("statusText", "未找到基础赛程");
        Object time = match.get("match_time");
        if (time == null) return fallback;
        String date = String.valueOf(time).replace('T', ' ');
        if (date.length() < 10) return fallback;
        date = date.substring(0, 10);
        try {
            Map<String, Object> raw = footballDataClient.getMatches(date, date, null, null).block();
            if (raw == null) {
                fallback.put("status", "REQUEST_FAILED");
                fallback.put("statusText", "football-data 无响应");
                return fallback;
            }
            Object error = raw.get("error");
            if (error != null && !String.valueOf(error).isBlank()) {
                fallback.put("status", "REQUEST_FAILED");
                fallback.put("statusText", String.valueOf(error));
                return fallback;
            }
            Object response = raw.get("response");
            if (response instanceof List<?> list) {
                String home = normalize(string(match.get("home_team_name")));
                String away = normalize(string(match.get("away_team_name")));
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> candidate)) continue;
                    Map<?, ?> teams = candidate.get("teams") instanceof Map<?, ?> map ? map : Map.of();
                    Map<?, ?> candidateHome = teams.get("home") instanceof Map<?, ?> map ? map : Map.of();
                    Map<?, ?> candidateAway = teams.get("away") instanceof Map<?, ?> map ? map : Map.of();
                    Object candidateHomeValue = candidateHome.containsKey("name") ? candidateHome.get("name") : "";
                    Object candidateAwayValue = candidateAway.containsKey("name") ? candidateAway.get("name") : "";
                    String candidateHomeName = normalize(String.valueOf(candidateHomeValue));
                    String candidateAwayName = normalize(String.valueOf(candidateAwayValue));
                    boolean same = (!home.isBlank() && !away.isBlank())
                            && ((candidateHomeName.contains(home) && candidateAwayName.contains(away))
                            || (candidateHomeName.contains(away) && candidateAwayName.contains(home)));
                    if (same) {
                        fallback.put("status", "NORMAL");
                        fallback.put("statusText", "基础赛程可用");
                        fallback.put("fixture", candidate);
                        return fallback;
                    }
                }
            }
        } catch (Exception e) {
            fallback.put("status", "REQUEST_FAILED");
            fallback.put("statusText", e.getMessage() == null ? "football-data 请求失败" : e.getMessage());
        }
        return fallback;
    }

    private Map<String, Object> loadMatch(Long fixtureId) {
        try {
            Map<String, Object> match = jdbcTemplate.queryForMap("""
                    SELECT id, fixture_id, external_match_id, league_id, league_name,
                           home_team_id, home_team_name, away_team_id, away_team_name,
                           match_time, status, home_score, away_score, venue, round, source
                    FROM crawler_matches
                    WHERE id = ?
                    LIMIT 1
                    """, fixtureId);
            return match;
        } catch (Exception e) {
            try {
                return jdbcTemplate.queryForMap("""
                        SELECT id, fixture_id, external_match_id, league_id, league_name,
                               home_team_id, home_team_name, away_team_id, away_team_name,
                               match_time, status, home_score, away_score, venue, round, source
                        FROM crawler_matches
                        WHERE fixture_id = ?
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """, fixtureId);
            } catch (Exception ignored) {
                return new LinkedHashMap<>();
            }
        }
    }

    private Long number(Object value) {
        if (value == null) return null;
        try { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    private Map<String, Snapshot> unsupportedSnapshots(String source) {
        Map<String, Snapshot> result = new LinkedHashMap<>();
        String message = (source == null || source.isBlank() ? "当前数据源" : source)
                + " 不提供事件、阵容和技术统计详情";
        for (String type : TYPES) {
            result.put(type, new Snapshot(type, source, "[]", "UNSUPPORTED", message, null));
        }
        return result;
    }

    private Map<String, Snapshot> loadSnapshots(Long fixtureId) {
        Map<String, Snapshot> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT detail_type, source, payload_json, status, error_message, fetched_at
                FROM t_match_detail_snapshot
                WHERE fixture_id = ?
                ORDER BY fetched_at DESC
                """, rs -> {
            String type = rs.getString("detail_type");
            result.putIfAbsent(type, new Snapshot(type, rs.getString("source"), rs.getString("payload_json"),
                    rs.getString("status"), rs.getString("error_message"),
                    rs.getTimestamp("fetched_at") == null ? null : rs.getTimestamp("fetched_at").toLocalDateTime()));
        }, fixtureId);
        return result;
    }

    /**
     * P0-4: an empty/failed result must never overwrite a NORMAL row.
     * <p>
     * Production evidence (2026-11-23, read-only query): 282/522 snapshot rows
     * are REQUEST_FAILED and every single one carries a provider
     * "429 Too Many Requests" error — the old unconditional upsert let each
     * failed burst replace the last good payload and refresh {@code fetched_at},
     * which then made the refresh task believe the fixture was "recently
     * fetched" and skip the next retry. Lineups are the visible casualty:
     * only 1/140 rows is still NORMAL.
     * <p>
     * Rules:
     * <ul>
     *   <li>NORMAL result → plain upsert (payload + status + fetched_at), as before.</li>
     *   <li>Non-NORMAL result (EMPTY / REQUEST_FAILED / QUOTA_LIMITED /
     *       NOT_CONFIGURED / UNSUPPORTED) over an existing NORMAL row → only
     *       {@code error_message} (+ {@code failure_count} when the column
     *       exists) is updated. Payload, status and {@code fetched_at} stay
     *       untouched, so the last good data keeps serving and the NORMAL-only
     *       skip logic can still schedule a retry.</li>
     *   <li>Non-NORMAL result with no NORMAL row → plain upsert (insert the
     *       failure, or replace a previous failure/empty row), so the UI still
     *       shows REQUEST_FAILED instead of UNKNOWN.</li>
     * </ul>
     * The guard is a single conditional UPDATE
     * ({@code ... AND status='NORMAL'}), so the check-and-protect is atomic
     * even if a scheduled round and a user refresh race on the same fixture.
     * Known trade-off (by design, per P0-4 spec): if the provider genuinely
     * retracts data, a stale NORMAL row persists until a fresh NORMAL arrives.
     */
    private void saveSnapshot(Long fixtureId, String type, Object payload, String status, String error) {
        try {
            String json = objectMapper.writeValueAsString(payload == null ? List.of() : payload);
            if (!"NORMAL".equals(status)) {
                int protectedRows = protectNormalRow(fixtureId, type, error);
                if (protectedRows > 0) return;
            }
            // A fresh NORMAL result resets the failure counter; a failure that
            // lands here (no NORMAL row to protect) leaves the counter alone
            // so consecutive failures keep accumulating.
            if ("NORMAL".equals(status) && failureCountSupported()) {
                jdbcTemplate.update("""
                        INSERT INTO t_match_detail_snapshot
                          (fixture_id, detail_type, source, payload_json, status, error_message, failure_count, fetched_at)
                        VALUES (?, ?, ?, ?, ?, ?, 0, NOW())
                        ON DUPLICATE KEY UPDATE payload_json = VALUES(payload_json), status = VALUES(status),
                          error_message = VALUES(error_message), failure_count = 0, fetched_at = VALUES(fetched_at)
                        """, fixtureId, type, SOURCE, json, status, error);
                return;
            }
            jdbcTemplate.update("""
                    INSERT INTO t_match_detail_snapshot
                      (fixture_id, detail_type, source, payload_json, status, error_message, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE payload_json = VALUES(payload_json), status = VALUES(status),
                      error_message = VALUES(error_message), fetched_at = VALUES(fetched_at)
                    """, fixtureId, type, SOURCE, json, status, error);
        } catch (Exception e) {
            log.warn("[MatchDetails] save {} fixture={} failed: {}", type, fixtureId, e.getMessage());
        }
    }

    /**
     * Records a failed/empty observation on top of an existing NORMAL row
     * without touching its payload, status or {@code fetched_at}.
     *
     * @return 1 if a NORMAL row was protected, 0 if there is no NORMAL row
     *         (caller then falls through to the plain upsert).
     */
    private int protectNormalRow(Long fixtureId, String type, String error) {
        try {
            if (failureCountSupported()) {
                return jdbcTemplate.update(
                        "UPDATE t_match_detail_snapshot SET error_message = ?, failure_count = failure_count + 1 "
                                + "WHERE fixture_id = ? AND detail_type = ? AND source = ? AND status = 'NORMAL'",
                        error, fixtureId, type, SOURCE);
            }
            return jdbcTemplate.update(
                    "UPDATE t_match_detail_snapshot SET error_message = ? "
                            + "WHERE fixture_id = ? AND detail_type = ? AND source = ? AND status = 'NORMAL'",
                    error, fixtureId, type, SOURCE);
        } catch (Exception e) {
            log.debug("[MatchDetails] protect NORMAL row {} fixture={} failed: {}", type, fixtureId, e.getMessage());
            return 0;
        }
    }

    private Object parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<Object>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractError(Map<String, Object> raw) {
        Object errors = raw.get("errors");
        if (errors instanceof Map<?, ?> map && !map.isEmpty()) return map.toString();
        if (errors instanceof String text && !text.isBlank()) return text;
        return null;
    }

    private String classifyError(String error) {
        String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (normalized.contains("limit") || normalized.contains("quota")
                || normalized.contains("plan") || normalized.contains("subscription")
                || normalized.contains("forbidden") || normalized.contains("access to this date")
                || normalized.contains("daily_budget")) {
            return "QUOTA_LIMITED";
        }
        // P0-4: a provider 429 is per-minute throttling, NOT daily-budget
        // exhaustion — it is retryable in the next round (and the P0-2 request
        // pacing exists to stop re-triggering it). Production evidence:
        // all 282 REQUEST_FAILED rows (2026-11-23) are 429s of this form.
        // Kept as REQUEST_FAILED (retryable) rather than QUOTA_LIMITED, which
        // is reserved for daily-budget / plan errors that must not be retried.
        if (normalized.contains("429") || normalized.contains("too many requests")) {
            return "REQUEST_FAILED";
        }
        return "REQUEST_FAILED";
    }

    private String aggregateStatus(Map<String, Object> statuses) {
        if (statuses.values().stream().anyMatch(v -> "QUOTA_LIMITED".equals(v))) return "QUOTA_LIMITED";
        if (statuses.values().stream().anyMatch(v -> "REQUEST_FAILED".equals(v))) return "REQUEST_FAILED";
        boolean normal = statuses.values().stream().anyMatch(v -> "NORMAL".equals(v));
        boolean incomplete = statuses.values().stream().anyMatch(v -> !"NORMAL".equals(v));
        if (normal && incomplete) return "PARTIAL";
        if (normal) return "NORMAL";
        if (statuses.values().stream().anyMatch(v -> "NOT_CONFIGURED".equals(v))) return "NOT_CONFIGURED";
        if (statuses.values().stream().anyMatch(v -> "UNSUPPORTED".equals(v))) return "UNSUPPORTED";
        return "EMPTY";
    }

    private String statusText(String status) {
        return switch (status) {
            case "NORMAL" -> I18nText.of("正常", "Normal");
            case "PARTIAL" -> I18nText.of("部分可用", "Partially available");
            case "EMPTY" -> I18nText.of("空数据", "No data");
            case "QUOTA_LIMITED" -> I18nText.of("额度受限", "Quota limited");
            case "REQUEST_FAILED" -> I18nText.of("请求失败", "Request failed");
            case "NOT_CONFIGURED" -> I18nText.of("未配置数据源", "Data source not configured");
            case "UNSUPPORTED" -> I18nText.of("该比赛阶段不提供此数据", "This data is not available for this match stage");
            default -> I18nText.of("未检测", "Not checked");
        };
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·•]", "");
    }

    private record Snapshot(String type, String source, String payloadJson, String status,
                            String errorMessage, LocalDateTime fetchedAt) {}
}
