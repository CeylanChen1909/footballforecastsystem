package com.chen.football.prediction.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chen.football.common.dto.MatchPredictionRequest;
import com.chen.football.common.dto.MatchPredictionResponse;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 比赛级预测快照：一场比赛只生成一份模型结果，所有用户读取同一份结果。
 * 预计算与用户预测历史解耦，避免重复推理，也让 Match 页可以直接展示稳定结果。
 */
@Slf4j
@Service
public class MatchPredictionPrecomputeService {

    private static final String MODEL_VERSION = "elo-calibrated-v3";
    private static final String FEATURE_VERSION = "prematch-v1-gated";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> FINISHED_STATUSES = Set.of(
            "FT", "AET", "PEN", "CANC", "CANCELED", "CANCELLED", "ABD", "AWD", "WO");
    private static final Set<String> SUPPORTED_LEAGUE_IDS = Set.of(
            "39", "140", "135", "78", "61", "88", "94", "40",
            "PL", "PD", "SA", "BL1", "FL1", "DED", "PPL", "ELC",
            "bbc-premier-league", "bbc-spanish-la-liga", "bbc-italian-serie-a",
            "bbc-german-bundesliga", "bbc-french-ligue-one", "bbc-dutch-eredivisie",
            "bbc-portuguese-primeira-liga", "bbc-championship");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CrawlerMatchMapper crawlerMatchMapper;
    private final PersistencePredictionService predictionService;
    private final CrawlerProperties crawlerProperties;
    private final StringRedisTemplate redisTemplate;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> distributedLockTokens = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "match-prediction-precompute");
        thread.setDaemon(true);
        return thread;
    });

    public MatchPredictionPrecomputeService(JdbcTemplate jdbcTemplate,
                                            ObjectMapper objectMapper,
                                            CrawlerMatchMapper crawlerMatchMapper,
                                            PersistencePredictionService predictionService,
                                            CrawlerProperties crawlerProperties,
                                            StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.crawlerMatchMapper = crawlerMatchMapper;
        this.predictionService = predictionService;
        this.crawlerProperties = crawlerProperties;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    void ensureTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_match_prediction (" +
                "id BIGINT NOT NULL AUTO_INCREMENT," +
                "fixture_id BIGINT NOT NULL," +
                "external_match_id VARCHAR(64)," +
                "home_team_id VARCHAR(64), home_team_name VARCHAR(128), home_team_logo VARCHAR(512)," +
                "away_team_id VARCHAR(64), away_team_name VARCHAR(128), away_team_logo VARCHAR(512)," +
                "league_id VARCHAR(64), league_name VARCHAR(128), match_time DATETIME," +
                "status VARCHAR(16) NOT NULL DEFAULT 'PENDING'," +
                "result_label VARCHAR(16), home_win_prob DOUBLE, draw_prob DOUBLE, away_win_prob DOUBLE," +
                "model_version VARCHAR(64) NOT NULL, feature_version VARCHAR(64) NOT NULL," +
                "top_features_json TEXT, feature_meta_json MEDIUMTEXT, explanation VARCHAR(1024)," +
                "feature_complete TINYINT(1), feature_status VARCHAR(32), fallback_reason VARCHAR(512)," +
                "generated_at DATETIME, source_updated_at DATETIME, expires_at DATETIME," +
                "error_message VARCHAR(512), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "PRIMARY KEY (id), UNIQUE KEY uk_match_prediction_fixture_feature (fixture_id, feature_version)," +
                "KEY idx_match_prediction_status_time (status, match_time), KEY idx_match_prediction_generated (fixture_id, generated_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 兜底扫描：人工同步或其它入口写入赛程时，也能自动生成预测。 */
    @Scheduled(fixedRateString = "${prediction.precompute-fixed-rate-ms:300000}",
            initialDelayString = "${prediction.precompute-initial-delay-ms:30000}")
    public void scanUpcoming() {
        try {
            schedule(crawlerMatchMapper.findUpcomingMatches());
        } catch (Exception ex) {
            log.warn("比赛预测预计算扫描失败: {}", ex.getMessage());
        }
    }

    public int schedule(Collection<CrawlerMatch> matches) {
        if (matches == null) return 0;
        int queued = 0;
        List<CrawlerMatch> candidates = matches.stream()
                .filter(this::eligible)
                .filter(this::visibleSource)
                .sorted(Comparator.comparing(CrawlerMatch::getMatchTime))
                .limit(100)
                .toList();
        for (CrawlerMatch match : candidates) {
            Long fixtureId = effectiveFixtureId(match);
            if (tryAcquire(fixtureId)) {
                queued++;
                executor.submit(() -> generateOne(match));
            }
        }
        return queued;
    }

    /** 返回 READY 快照；没有快照时立即排队并返回 PENDING，不阻塞 HTTP 请求。 */
    public Map<String, Object> getOrSchedule(Long fixtureId) {
        if (fixtureId == null || fixtureId <= 0) {
            return Map.of("status", "UNAVAILABLE", "fixtureId", fixtureId == null ? 0L : fixtureId);
        }
        // New frontend routes use the stable local matchId. Resolve that first;
        // keep the provider-fixture fallback for old bookmarks and API clients.
        CrawlerMatch match = crawlerMatchMapper.selectById(fixtureId);
        if (match == null) match = crawlerMatchMapper.findByPublicId(fixtureId);
        if (match == null || !visibleSource(match)) return Map.of("status", "UNAVAILABLE", "fixtureId", fixtureId);
        Long effectiveId = effectiveFixtureId(match);
        if (effectiveId == null || effectiveId <= 0) return Map.of("status", "UNAVAILABLE", "fixtureId", fixtureId);

        Map<String, Object> current = findCurrent(effectiveId);
        if (current != null) { current.put("matchId", match.getId()); current.put("publicMatchId", match.getId() == null ? null : String.valueOf(match.getId())); }
        if (current != null && isFresh(current, match)) return current;
        if (current != null && "PENDING".equals(current.get("status"))
                && (inFlight.contains(effectiveId) || isRecent(current, 10))) return current;
        // 失败不是“永远生成中”。短暂失败窗口内直接返回失败状态，避免
        // 每次刷新页面都重复消耗线程和模型；定时扫描过窗口后再自动重试。
        if (current != null && "FAILED".equals(current.get("status")) && isRecent(current, 10)) return current;
        if (isFinished(match.getStatus()) && current == null) {
            return snapshotBase(match, "UNAVAILABLE");
        }
        int queuedCount = schedule(List.of(match));
        Map<String, Object> queuedSnapshot = findCurrent(effectiveId);
        if (queuedSnapshot != null) { queuedSnapshot.put("matchId", match.getId()); queuedSnapshot.put("publicMatchId", match.getId() == null ? null : String.valueOf(match.getId())); }
        if (queuedSnapshot != null && isFresh(queuedSnapshot, match)) return queuedSnapshot;
        if (queuedCount > 0 || inFlight.contains(effectiveId)) return snapshotBase(match, "PENDING");
        return queuedSnapshot == null ? snapshotBase(match, "PENDING") : queuedSnapshot;
    }

    private void generateOne(CrawlerMatch match) {
        Long fixtureId = effectiveFixtureId(match);
        try {
            upsertPending(match, fixtureId);
            MatchPredictionRequest request = new MatchPredictionRequest(
                    fixtureId,
                    match.getHomeTeamId(), match.getAwayTeamId(),
                    match.getHomeTeamName(), match.getAwayTeamName(),
                    match.getLeagueName(), parseInteger(match.getLeagueId()), null,
                    null, null, null, null,
                    null,
                    match.getMatchTime() == null ? null : match.getMatchTime().toString(),
                    match.getHomeTeamLogo(), match.getAwayTeamLogo());
            MatchPredictionResponse response = predictionService.predictOnly(request);
            if (response.predictionAvailable()) {
                upsertReady(match, fixtureId, response);
                log.info("比赛预测预计算完成: fixtureId={}, result={}, model={}", fixtureId,
                        response.resultLabel(), response.modelVersion());
            } else {
                upsertUnavailable(match, fixtureId, response);
                log.info("比赛预测暂不可用: fixtureId={}, reason={}", fixtureId, response.fallbackReason());
            }
        } catch (Exception ex) {
            log.warn("比赛预测预计算失败: fixtureId={}, error={}", fixtureId, ex.getMessage());
            upsertFailed(match, fixtureId, ex.getMessage());
        } finally {
            release(fixtureId);
        }
    }

    /** Cross-instance idempotency with a local fallback when Redis is down. */
    private boolean tryAcquire(Long fixtureId) {
        if (fixtureId == null || !inFlight.add(fixtureId)) return false;
        String key = "football:prediction:precompute:" + fixtureId;
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofMinutes(15));
            if (Boolean.FALSE.equals(acquired)) {
                inFlight.remove(fixtureId);
                return false;
            }
            if (Boolean.TRUE.equals(acquired)) distributedLockTokens.put(fixtureId, token);
            return true;
        } catch (Exception ex) {
            // Keep a single-instance local guard rather than making the whole
            // prediction pipeline unavailable when Redis is temporarily down.
            log.warn("预测分布式锁不可用，退回本地锁 fixtureId={}: {}", fixtureId, ex.getMessage());
            return true;
        }
    }

    private void release(Long fixtureId) {
        inFlight.remove(fixtureId);
        String token = distributedLockTokens.remove(fixtureId);
        if (token == null) return;
        String key = "football:prediction:precompute:" + fixtureId;
        try {
            String current = redisTemplate.opsForValue().get(key);
            if (token.equals(current)) redisTemplate.delete(key);
        } catch (Exception ex) {
            log.debug("释放预测分布式锁失败 fixtureId={}: {}", fixtureId, ex.getMessage());
        }
    }

    private void upsertPending(CrawlerMatch match, Long fixtureId) {
        jdbcTemplate.update("INSERT INTO t_match_prediction " +
                        "(fixture_id, external_match_id, home_team_id, home_team_name, home_team_logo, away_team_id, away_team_name, away_team_logo, league_id, league_name, match_time, status, model_version, feature_version, source_updated_at, error_message, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, NULL, CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE external_match_id=VALUES(external_match_id), home_team_id=VALUES(home_team_id), home_team_name=VALUES(home_team_name), home_team_logo=VALUES(home_team_logo), away_team_id=VALUES(away_team_id), away_team_name=VALUES(away_team_name), away_team_logo=VALUES(away_team_logo), league_id=VALUES(league_id), league_name=VALUES(league_name), match_time=VALUES(match_time), status='PENDING', source_updated_at=VALUES(source_updated_at), error_message=NULL, updated_at=CURRENT_TIMESTAMP",
                fixtureId, match.getExternalMatchId(), match.getHomeTeamId(), match.getHomeTeamName(), match.getHomeTeamLogo(),
                match.getAwayTeamId(), match.getAwayTeamName(), match.getAwayTeamLogo(), match.getLeagueId(), match.getLeagueName(),
                match.getMatchTime(), MODEL_VERSION, FEATURE_VERSION, match.getUpdatedAt());
    }

    private void upsertReady(CrawlerMatch match, Long fixtureId, MatchPredictionResponse response) {
        jdbcTemplate.update("INSERT INTO t_match_prediction " +
                        "(fixture_id, external_match_id, home_team_id, home_team_name, home_team_logo, away_team_id, away_team_name, away_team_logo, league_id, league_name, match_time, status, result_label, home_win_prob, draw_prob, away_win_prob, model_version, feature_version, top_features_json, feature_meta_json, explanation, feature_complete, feature_status, fallback_reason, generated_at, source_updated_at, expires_at, error_message, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, NULL, CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE external_match_id=VALUES(external_match_id), home_team_id=VALUES(home_team_id), home_team_name=VALUES(home_team_name), home_team_logo=VALUES(home_team_logo), away_team_id=VALUES(away_team_id), away_team_name=VALUES(away_team_name), away_team_logo=VALUES(away_team_logo), league_id=VALUES(league_id), league_name=VALUES(league_name), match_time=VALUES(match_time), status='READY', result_label=VALUES(result_label), home_win_prob=VALUES(home_win_prob), draw_prob=VALUES(draw_prob), away_win_prob=VALUES(away_win_prob), top_features_json=VALUES(top_features_json), feature_meta_json=VALUES(feature_meta_json), explanation=VALUES(explanation), feature_complete=VALUES(feature_complete), feature_status=VALUES(feature_status), fallback_reason=VALUES(fallback_reason), generated_at=CURRENT_TIMESTAMP, source_updated_at=VALUES(source_updated_at), expires_at=VALUES(expires_at), error_message=NULL, updated_at=CURRENT_TIMESTAMP",
                fixtureId, match.getExternalMatchId(), match.getHomeTeamId(), match.getHomeTeamName(), match.getHomeTeamLogo(),
                match.getAwayTeamId(), match.getAwayTeamName(), match.getAwayTeamLogo(), match.getLeagueId(), match.getLeagueName(), match.getMatchTime(),
                response.resultLabel(), response.homeWinProb(), response.drawProb(), response.awayWinProb(),
                response.modelVersion() == null ? MODEL_VERSION : response.modelVersion(), FEATURE_VERSION,
                writeJson(response.topFeatures()), writeJson(response.featureMeta()), response.explanation(), response.featureComplete(),
                response.featureStatus(), response.fallbackReason(), match.getUpdatedAt(), expiryFor(match));
    }

    private void upsertUnavailable(CrawlerMatch match, Long fixtureId, MatchPredictionResponse response) {
        jdbcTemplate.update("INSERT INTO t_match_prediction " +
                        "(fixture_id, external_match_id, home_team_id, home_team_name, home_team_logo, away_team_id, away_team_name, away_team_logo, league_id, league_name, match_time, status, model_version, feature_version, top_features_json, feature_meta_json, explanation, feature_complete, feature_status, fallback_reason, generated_at, source_updated_at, expires_at, error_message, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNAVAILABLE', ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, NULL, CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE external_match_id=VALUES(external_match_id), home_team_id=VALUES(home_team_id), home_team_name=VALUES(home_team_name), home_team_logo=VALUES(home_team_logo), away_team_id=VALUES(away_team_id), away_team_name=VALUES(away_team_name), away_team_logo=VALUES(away_team_logo), league_id=VALUES(league_id), league_name=VALUES(league_name), match_time=VALUES(match_time), status='UNAVAILABLE', result_label=NULL, home_win_prob=NULL, draw_prob=NULL, away_win_prob=NULL, top_features_json=VALUES(top_features_json), feature_meta_json=VALUES(feature_meta_json), explanation=VALUES(explanation), feature_complete=VALUES(feature_complete), feature_status=VALUES(feature_status), fallback_reason=VALUES(fallback_reason), generated_at=CURRENT_TIMESTAMP, source_updated_at=VALUES(source_updated_at), expires_at=VALUES(expires_at), error_message=NULL, updated_at=CURRENT_TIMESTAMP",
                fixtureId, match.getExternalMatchId(), match.getHomeTeamId(), match.getHomeTeamName(), match.getHomeTeamLogo(),
                match.getAwayTeamId(), match.getAwayTeamName(), match.getAwayTeamLogo(), match.getLeagueId(), match.getLeagueName(), match.getMatchTime(),
                response.modelVersion() == null ? MODEL_VERSION : response.modelVersion(), FEATURE_VERSION,
                writeJson(response.topFeatures()), writeJson(response.featureMeta()), response.explanation(), response.featureComplete(),
                response.featureStatus(), response.fallbackReason(), match.getUpdatedAt(), expiryFor(match));
    }

    private void upsertFailed(CrawlerMatch match, Long fixtureId, String error) {
        jdbcTemplate.update("INSERT INTO t_match_prediction (fixture_id, external_match_id, home_team_id, home_team_name, away_team_id, away_team_name, league_id, league_name, match_time, status, model_version, feature_version, generated_at, source_updated_at, error_message, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'FAILED', ?, ?, CURRENT_TIMESTAMP, ?, ?, CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE status='FAILED', generated_at=CURRENT_TIMESTAMP, source_updated_at=VALUES(source_updated_at), error_message=VALUES(error_message), updated_at=CURRENT_TIMESTAMP",
                fixtureId, match.getExternalMatchId(), match.getHomeTeamId(), match.getHomeTeamName(), match.getAwayTeamId(), match.getAwayTeamName(), match.getLeagueId(), match.getLeagueName(), match.getMatchTime(), MODEL_VERSION, FEATURE_VERSION, match.getUpdatedAt(), truncate(error));
    }

    private Map<String, Object> findCurrent(Long fixtureId) {
        try {
            // 模型版本可能从 baseline 切换为 Python 混合模型；按比赛和特征版本取最新快照，避免旧版本 PENDING 行遮蔽新版本 READY 行。
            // MySQL sorts NULL first for DESC; without the explicit expression a
            // newer PENDING row (generated_at=NULL) can mask an already READY
            // snapshot and make the UI stay in “prediction generating” forever.
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM t_match_prediction WHERE fixture_id=? AND feature_version=? ORDER BY (generated_at IS NULL) ASC, generated_at DESC, updated_at DESC LIMIT 1", fixtureId, FEATURE_VERSION);
            if (rows.isEmpty()) return null;
            Map<String, Object> source = rows.get(0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", stringValue(source.get("status")));
            row.put("predictionAvailable", "READY".equals(source.get("status")));
            row.put("fixtureId", longValue(source.get("fixture_id")));
            row.put("externalMatchId", stringValue(source.get("external_match_id")));
            row.put("homeTeamId", stringValue(source.get("home_team_id")));
            row.put("homeTeamName", stringValue(source.get("home_team_name")));
            row.put("homeTeamLogo", stringValue(source.get("home_team_logo")));
            row.put("awayTeamId", stringValue(source.get("away_team_id")));
            row.put("awayTeamName", stringValue(source.get("away_team_name")));
            row.put("awayTeamLogo", stringValue(source.get("away_team_logo")));
            row.put("leagueId", stringValue(source.get("league_id")));
            row.put("leagueName", stringValue(source.get("league_name")));
            row.put("matchTime", source.get("match_time"));
            row.put("resultLabel", stringValue(source.get("result_label")));
            row.put("homeWinProb", source.get("home_win_prob"));
            row.put("drawProb", source.get("draw_prob"));
            row.put("awayWinProb", source.get("away_win_prob"));
            row.put("modelVersion", stringValue(source.get("model_version")));
            row.put("qualityTier", qualityTier(source));
            row.put("featureVersion", stringValue(source.get("feature_version")));
            row.put("topFeatures", readJsonList(source.get("top_features_json")));
            row.put("featureMeta", readJsonMap(source.get("feature_meta_json")));
            row.put("explanation", stringValue(source.get("explanation")));
            row.put("featureComplete", booleanValue(source.get("feature_complete")));
            row.put("featureStatus", stringValue(source.get("feature_status")));
            row.put("fallbackReason", stringValue(source.get("fallback_reason")));
            row.put("generatedAt", source.get("generated_at"));
            row.put("sourceUpdatedAt", source.get("source_updated_at"));
            row.put("expiresAt", source.get("expires_at"));
            row.put("updatedAt", source.get("updated_at"));
            row.put("errorMessage", stringValue(source.get("error_message")));
            return row;
        } catch (Exception ex) {
            log.warn("读取比赛预测快照失败: fixtureId={}, error={}", fixtureId, ex.getMessage());
            return null;
        }
    }

    /**
     * 首页和比赛列表使用的公开统一预测快照。只返回比赛级表中的 READY/UNAVAILABLE
     * 状态，不暴露任何用户预测历史，避免访客看到“个人预测”伪装成全站模型结果。
     */
    public List<Map<String, Object>> listPublicSnapshots(int limit) {
        return readPublicSnapshotResult(limit).items();
    }

    /**
     * 批量读取赛程对应的比赛级预测状态。
     *
     * Agent 的赛程查询不能逐场调用 current 接口，否则一条“未来 24 小时
     * 赛程”就会触发 N 次查询甚至 N 次排队。这里一次性读取所有状态，保留
     * PENDING/FAILED/UNAVAILABLE，调用方才能区分“尚未生成”和“预测不可用”。
     */
    public PredictionStatusResult readPredictionStatuses(Collection<CrawlerMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return new PredictionStatusResult(Map.of(), "AVAILABLE", "没有需要查询预测状态的比赛");
        }
        List<Long> fixtureIds = matches.stream()
                .map(this::effectiveFixtureId)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (fixtureIds.isEmpty()) {
            return new PredictionStatusResult(Map.of(), "AVAILABLE", "比赛没有可用 fixture ID");
        }
        try {
            String placeholders = String.join(",", Collections.nCopies(fixtureIds.size(), "?"));
            String sql = "SELECT * FROM t_match_prediction WHERE feature_version=? AND fixture_id IN ("
                    + placeholders + ") ORDER BY (generated_at IS NULL) ASC, generated_at DESC, updated_at DESC";
            List<Object> args = new ArrayList<>();
            args.add(FEATURE_VERSION);
            args.addAll(fixtureIds);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
            Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map<String, Object> source : rows) {
                Long fixtureId = longValue(source.get("fixture_id"));
                if (fixtureId == null || result.containsKey(fixtureId)) continue;
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("status", stringValue(source.get("status")));
                status.put("predictionAvailable", "READY".equals(source.get("status")));
                status.put("resultLabel", stringValue(source.get("result_label")));
                status.put("homeWinProb", source.get("home_win_prob"));
                status.put("drawProb", source.get("draw_prob"));
                status.put("awayWinProb", source.get("away_win_prob"));
                status.put("modelVersion", stringValue(source.get("model_version")));
                status.put("qualityTier", qualityTier(source));
                status.put("featureVersion", stringValue(source.get("feature_version")));
                status.put("featureComplete", booleanValue(source.get("feature_complete")));
                status.put("featureStatus", stringValue(source.get("feature_status")));
                status.put("fallbackReason", stringValue(source.get("fallback_reason")));
                status.put("generatedAt", source.get("generated_at"));
                status.put("sourceUpdatedAt", source.get("source_updated_at"));
                status.put("expiresAt", source.get("expires_at"));
                status.put("updatedAt", source.get("updated_at"));
                status.put("errorMessage", stringValue(source.get("error_message")));
                result.put(fixtureId, status);
            }
            return new PredictionStatusResult(result, "AVAILABLE", "已读取比赛级预测状态");
        } catch (Exception ex) {
            log.warn("批量读取比赛预测状态失败: {}", ex.getMessage());
            return new PredictionStatusResult(Map.of(), "REQUEST_FAILED", "预测状态查询失败");
        }
    }

    public record PredictionStatusResult(Map<Long, Map<String, Object>> items,
                                         String status,
                                         String message) {}

    private String qualityTier(Map<String, Object> source) {
        String status = stringValue(source.get("status"));
        if ("UNAVAILABLE".equals(status)) return "INSUFFICIENT_DATA";
        String model = stringValue(source.get("model_version")).toLowerCase(Locale.ROOT);
        boolean complete = booleanValue(source.get("feature_complete"));
        if (model.startsWith("baseline")) return complete ? "BASELINE_COMPLETE" : "BASELINE_LIMITED";
        return complete ? "ENHANCED_COMPLETE" : "ENHANCED_LIMITED";
    }

    /**
     * Read the public prediction table without conflating an empty table with a
     * database/read failure.  Home uses this result to keep the fixture list
     * usable while showing an honest prediction-health state.
     */
    public PublicSnapshotResult readPublicSnapshotResult(int limit) {
        return readPublicSnapshotResult(limit, null, null);
    }

    /** Read snapshots for the exact home/matches window. */
    public PublicSnapshotResult readPublicSnapshotResult(int limit, LocalDateTime windowStart, LocalDateTime windowEndExclusive) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        try {
            String sourceFilter = primarySourceSql("cm");
            String localSourceFilter = primarySourceSql("local_cm");
            String timeFilter = windowStart != null && windowEndExclusive != null
                    ? "AND p.match_time >= ? AND p.match_time < ? "
                    : "AND p.match_time >= DATE_SUB(NOW(), INTERVAL 1 DAY) "
                    + "AND p.match_time <= DATE_ADD(NOW(), INTERVAL 30 DAY) ";
            List<Object> queryArgs = new ArrayList<>();
            if (windowStart != null && windowEndExclusive != null) {
                queryArgs.add(windowStart);
                queryArgs.add(windowEndExclusive);
            }
            String sql =
                    "SELECT p.*, COALESCE(cm.local_match_id, cm.provider_fixture_id, local_cm.local_id) AS public_match_id FROM t_match_prediction p "
                            + "LEFT JOIN (SELECT MIN(cm.id) AS local_match_id, cm.fixture_id AS provider_fixture_id FROM crawler_matches cm WHERE 1=1 " + sourceFilter + " AND (cm.status IS NULL OR cm.status <> 'SOURCE_REMOVED') GROUP BY cm.fixture_id) cm ON cm.provider_fixture_id=p.fixture_id "
                            + "LEFT JOIN (SELECT local_cm.id AS local_id FROM crawler_matches local_cm WHERE 1=1 " + localSourceFilter + " AND (local_cm.status IS NULL OR local_cm.status <> 'SOURCE_REMOVED')) local_cm ON local_cm.local_id=p.fixture_id "
                            + "WHERE p.status IN ('READY','UNAVAILABLE') "
                            + "AND (cm.provider_fixture_id IS NOT NULL OR local_cm.local_id IS NOT NULL) "
                            + timeFilter
                            + "ORDER BY p.match_time ASC, p.updated_at DESC LIMIT " + safeLimit;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, queryArgs.toArray());
            return mapSnapshotRows(rows);
        } catch (Exception ex) {
            log.warn("读取公开比赛预测快照失败: {}", ex.getMessage());
            return new PublicSnapshotResult(List.of(), Map.of(
                    "status", "REQUEST_FAILED",
                    "statusText", "预测数据读取失败",
                    "message", "预测服务暂时不可用，请稍后重试",
                    "resultCount", 0
            ));
        }
    }

    /**
     * Read snapshots only for the fixtures already selected by the home brief.
     * The general public-snapshot query needs two derived joins over all fixture
     * rows to resolve provider IDs. That is appropriate for a broad list, but
     * it can block the home request when the prediction table is being updated.
     * Home already has the canonical local/provider mapping, so an IN query is
     * both faster and safer.
     */
    public PublicSnapshotResult readPublicSnapshotResultForMatches(Collection<CrawlerMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return new PublicSnapshotResult(List.of(), Map.of(
                    "status", "AVAILABLE", "statusText", "预测快照可用",
                    "message", "当前窗口暂无已生成预测", "resultCount", 0));
        }
        try {
            Map<Long, Long> publicIds = new HashMap<>();
            List<Long> fixtureIds = new ArrayList<>();
            for (CrawlerMatch match : matches) {
                Long fixtureId = effectiveFixtureId(match);
                if (fixtureId == null || fixtureId <= 0 || publicIds.containsKey(fixtureId)) continue;
                fixtureIds.add(fixtureId);
                publicIds.put(fixtureId, match.getId());
            }
            if (fixtureIds.isEmpty()) {
                return new PublicSnapshotResult(List.of(), Map.of(
                        "status", "AVAILABLE", "statusText", "预测快照可用",
                        "message", "当前窗口暂无已生成预测", "resultCount", 0));
            }
            String placeholders = String.join(",", Collections.nCopies(fixtureIds.size(), "?"));
            String sql = "SELECT p.* FROM t_match_prediction p "
                    + "WHERE p.status IN ('READY','UNAVAILABLE') AND p.fixture_id IN (" + placeholders + ") "
                    + "ORDER BY p.match_time ASC, p.updated_at DESC";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, fixtureIds.toArray());
            List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
            for (Map<String, Object> source : rows) {
                Map<String, Object> copy = new LinkedHashMap<>(source);
                Long publicId = publicIds.get(longValue(source.get("fixture_id")));
                copy.put("public_match_id", publicId);
                normalized.add(copy);
            }
            return mapSnapshotRows(normalized);
        } catch (Exception ex) {
            log.warn("读取首页重点比赛预测快照失败: {}", ex.getMessage());
            return new PublicSnapshotResult(List.of(), Map.of(
                    "status", "REQUEST_FAILED", "statusText", "预测数据读取失败",
                    "message", "预测服务暂时不可用，赛程仍可浏览", "resultCount", 0));
        }
    }

    private PublicSnapshotResult mapSnapshotRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seenFixtures = new HashSet<>();
        for (Map<String, Object> source : rows) {
            String fixtureId = stringValue(source.get("fixture_id"));
            if (fixtureId == null || !seenFixtures.add(fixtureId)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", stringValue(source.get("status")));
            row.put("predictionAvailable", "READY".equals(source.get("status")));
            row.put("publicMatchId", stringValue(source.get("public_match_id")));
            row.put("matchId", stringValue(source.get("public_match_id")));
            row.put("fixtureId", longValue(source.get("fixture_id")));
            row.put("externalMatchId", stringValue(source.get("external_match_id")));
            row.put("homeTeamId", stringValue(source.get("home_team_id")));
            row.put("homeTeamName", stringValue(source.get("home_team_name")));
            row.put("homeTeamLogo", stringValue(source.get("home_team_logo")));
            row.put("awayTeamId", stringValue(source.get("away_team_id")));
            row.put("awayTeamName", stringValue(source.get("away_team_name")));
            row.put("awayTeamLogo", stringValue(source.get("away_team_logo")));
            row.put("leagueId", stringValue(source.get("league_id")));
            row.put("leagueName", stringValue(source.get("league_name")));
            row.put("matchTime", source.get("match_time"));
            row.put("resultLabel", stringValue(source.get("result_label")));
            row.put("homeWinProb", source.get("home_win_prob"));
            row.put("drawProb", source.get("draw_prob"));
            row.put("awayWinProb", source.get("away_win_prob"));
            row.put("modelVersion", stringValue(source.get("model_version")));
            row.put("featureVersion", stringValue(source.get("feature_version")));
            row.put("featureComplete", booleanValue(source.get("feature_complete")));
            row.put("featureStatus", stringValue(source.get("feature_status")));
            row.put("fallbackReason", stringValue(source.get("fallback_reason")));
            row.put("generatedAt", source.get("generated_at"));
            row.put("updatedAt", source.get("updated_at"));
            row.put("errorMessage", stringValue(source.get("error_message")));
            result.add(row);
        }
        Object lastUpdated = rows.stream()
                .map(row -> row.get("updated_at"))
                .filter(Objects::nonNull)
                .max(Comparator.comparing(Object::toString))
                .orElse(null);
        return new PublicSnapshotResult(result, Map.of(
                "status", "AVAILABLE", "statusText", "预测快照可用",
                "message", result.isEmpty() ? "当前窗口暂无已生成预测" : "已返回 " + result.size() + " 条预测快照",
                "lastSuccess", lastUpdated == null ? "" : lastUpdated.toString(),
                "resultCount", result.size()));
    }

    public record PublicSnapshotResult(List<Map<String, Object>> items, Map<String, Object> quality) {}

    private boolean isFresh(Map<String, Object> snapshot, CrawlerMatch match) {
        if (!"READY".equals(snapshot.get("status")) && !"UNAVAILABLE".equals(snapshot.get("status"))) return false;
        if ("READY".equals(snapshot.get("status")) && "DEFAULTED".equals(snapshot.get("featureStatus"))) return false;
        LocalDateTime generated = dateTime(snapshot.get("generatedAt"));
        LocalDateTime sourceUpdated = match.getUpdatedAt();
        LocalDateTime expires = dateTime(snapshot.get("expiresAt"));
        return generated != null && (sourceUpdated == null || !generated.isBefore(sourceUpdated))
                && (expires == null || expires.isAfter(LocalDateTime.now(BUSINESS_ZONE)));
    }

    private boolean isRecent(Map<String, Object> snapshot, int minutes) {
        LocalDateTime updated = dateTime(snapshot.get("updatedAt"));
        return updated != null && updated.isAfter(LocalDateTime.now(BUSINESS_ZONE).minusMinutes(minutes));
    }

    private Map<String, Object> snapshotBase(CrawlerMatch match, String status) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("status", status);
        base.put("matchId", match.getId());
        base.put("publicMatchId", match.getId() == null ? null : String.valueOf(match.getId()));
        base.put("fixtureId", effectiveFixtureId(match));
        base.put("externalMatchId", match.getExternalMatchId());
        base.put("homeTeamId", match.getHomeTeamId());
        base.put("homeTeamName", match.getHomeTeamName());
        base.put("homeTeamLogo", match.getHomeTeamLogo());
        base.put("awayTeamId", match.getAwayTeamId());
        base.put("awayTeamName", match.getAwayTeamName());
        base.put("awayTeamLogo", match.getAwayTeamLogo());
        base.put("leagueId", match.getLeagueId());
        base.put("leagueName", match.getLeagueName());
        base.put("matchTime", match.getMatchTime());
        base.put("modelVersion", MODEL_VERSION);
        base.put("featureVersion", FEATURE_VERSION);
        return base;
    }

    private boolean eligible(CrawlerMatch match) {
        return match != null && effectiveFixtureId(match) != null && effectiveFixtureId(match) > 0
                && match.getMatchTime() != null && !isFinished(match.getStatus())
                && isSupportedLeague(match);
    }

    private boolean visibleSource(CrawlerMatch match) {
        if (match == null || match.getSource() == null || match.getSource().isBlank()) return false;
        return !crawlerProperties.isPrimaryOnly()
                || (crawlerProperties.getPrimarySource() != null
                && crawlerProperties.getPrimarySource().equalsIgnoreCase(match.getSource().trim()));
    }

    private boolean isSupportedLeague(CrawlerMatch match) {
        String id = match.getLeagueId() == null ? "" : match.getLeagueId().trim();
        if (!id.isBlank()) return SUPPORTED_LEAGUE_IDS.contains(id);
        String name = com.chen.football.crawler.service.IdentityNormalizer.normalize(match.getLeagueName());
        return Set.of("premierleague", "laliga", "primeradivision", "seriea", "bundesliga",
                "ligue1", "eredivisie", "primeiraliga", "championship", "英超", "西甲", "德甲", "法甲", "意甲", "荷甲", "葡超", "英冠")
                .contains(name);
    }

    private Long effectiveFixtureId(CrawlerMatch match) {
        if (match == null) return null;
        return match.getFixtureId() != null && match.getFixtureId() > 0 ? match.getFixtureId() : match.getId();
    }

    private boolean isFinished(String status) {
        return status != null && FINISHED_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    private LocalDateTime expiryFor(CrawlerMatch match) {
        if (match.getMatchTime() == null) return LocalDateTime.now(BUSINESS_ZONE).plusHours(6);
        return match.getMatchTime().isAfter(LocalDateTime.now(BUSINESS_ZONE)) ? match.getMatchTime() : LocalDateTime.now(BUSINESS_ZONE).plusHours(6);
    }

    private String primarySourceSql(String alias) {
        if (!crawlerProperties.isPrimaryOnly() || crawlerProperties.getPrimarySource() == null
                || crawlerProperties.getPrimarySource().isBlank()) return "";
        return " AND " + alias + ".source = '" + crawlerProperties.getPrimarySource().replace("'", "''") + "'";
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value.trim()); } catch (Exception ignored) { return null; }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { return "{}"; }
    }

    private List<String> readJsonList(Object value) {
        try { return value == null ? List.of() : objectMapper.readValue(String.valueOf(value), new TypeReference<>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    private Map<String, Object> readJsonMap(Object value) {
        try { return value == null ? Map.of() : objectMapper.readValue(String.valueOf(value), new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; }
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value == null) return null;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private LocalDateTime dateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() > 512 ? value.substring(0, 512) : value;
    }
}
