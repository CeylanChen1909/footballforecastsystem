package com.chen.football.prediction.service;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.common.dto.MatchPredictionRequest;
import com.chen.football.common.dto.MatchPredictionResponse;
import com.chen.football.prediction.entity.PredictionEntity;
import com.chen.football.prediction.mapper.PredictionMapper;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.service.IdentityMappingService;
import com.chen.football.crawler.service.IdentityNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Service
public class PersistencePredictionService {
    private static final String MODEL_VERSION = "elo-calibrated-v3";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(PersistencePredictionService.class);

    private final PredictionMapper predictionMapper;
    private final JdbcTemplate jdbcTemplate;
    private final WebClient pythonClient;
    private final boolean pythonEnabled;
    private final String pythonToken;
    private final IdentityMappingService identityMappingService;
    private final HistoricalMatchCacheService historicalMatchCacheService;
    private final PrematchFeatureService prematchFeatureService;
    private final ObjectMapper objectMapper;
    private final CrawlerProperties crawlerProperties;
    /** Production scope: PL, La Liga, Serie A, Bundesliga, Ligue 1, Eredivisie, Primeira Liga, Championship. */
    private static final Set<String> SUPPORTED_LEAGUE_IDS = Set.of(
            "39", "140", "135", "78", "61", "88", "94", "40",
            "PL", "PD", "SA", "BL1", "FL1", "DED", "PPL", "ELC",
            "bbc-premier-league", "bbc-spanish-la-liga", "bbc-italian-serie-a", "bbc-german-bundesliga",
            "bbc-french-ligue-one", "bbc-dutch-eredivisie", "bbc-portuguese-primeira-liga", "bbc-championship"
    );
    private static final Set<String> SUPPORTED_LEAGUE_NAMES = Set.of(
            "英超", "西甲", "德甲", "法甲", "意甲", "荷甲", "葡超", "英冠",
            "premierleague", "laliga", "bundesliga", "ligue1", "seriea",
            "eredivisie", "primeiraliga", "portugueseprimeiraliga", "portuguese primeira liga",
            "championship", "primeradivision"
    );

    public PersistencePredictionService(
            PredictionMapper predictionMapper,
            JdbcTemplate jdbcTemplate,
            IdentityMappingService identityMappingService,
            HistoricalMatchCacheService historicalMatchCacheService,
            PrematchFeatureService prematchFeatureService,
            ObjectMapper objectMapper,
            @Value("${python.inference.url:http://127.0.0.1:5001}") String pythonUrl,
            @Value("${python.inference.enabled:false}") boolean enabled,
            @Value("${python.inference.token:}") String pythonToken,
            CrawlerProperties crawlerProperties) {
        this.predictionMapper = predictionMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.identityMappingService = identityMappingService;
        this.historicalMatchCacheService = historicalMatchCacheService;
        this.prematchFeatureService = prematchFeatureService;
        this.objectMapper = objectMapper;
        this.pythonEnabled = enabled;
        this.pythonToken = pythonToken == null ? "" : pythonToken.trim();
        this.crawlerProperties = crawlerProperties;
        if (enabled) {
            this.pythonClient = WebClient.builder()
                    .baseUrl(pythonUrl)
                    .build();
        } else {
            this.pythonClient = null;
        }
    }

    /**
     * 预测快照字段是兼容性迁移：已有开发库/用户库不需要手工重建整张预测表。
     * DDL 同时写入 sql/football_forecast.sql，启动时迁移用于已经存在的数据库。
     */
    @PostConstruct
    void ensurePredictionSnapshotColumns() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        String[] statements = {
                "ALTER TABLE t_prediction ADD COLUMN match_time DATETIME NULL",
                "ALTER TABLE t_prediction ADD COLUMN home_team_logo VARCHAR(512) NULL",
                "ALTER TABLE t_prediction ADD COLUMN away_team_logo VARCHAR(512) NULL"
        };
        for (String statement : statements) {
            try {
                jdbcTemplate.execute(statement);
            } catch (Exception ignored) {
                // 重复启动时列已存在；其它兼容性问题不应阻断预测服务启动。
                log.debug("预测快照字段已存在或当前数据库暂不可迁移: {}", statement);
            }
        }
    }

    @Transactional
    public MatchPredictionResponse predictAndSave(MatchPredictionRequest req) {
        MatchPredictionResponse r = predictOnly(req);
        if (!r.predictionAvailable()) {
            log.info("预测未保存：比赛特征未通过可用性门槛 fixtureId={}, reason={}", req.fixtureId(), r.fallbackReason());
            return r;
        }

        Long userId = UserContext.getUserId();
        if (userId == null) {
            // Never trust a userId supplied in a request body; authorization
            // is established only by the signed session context.
            return r;
        }

        try {
            String homeTeamName = req.homeTeamName();
            if (homeTeamName == null || homeTeamName.isBlank()) {
                homeTeamName = req.homeTeamId() != null ? "球队" + req.homeTeamId() : "主队";
            }
            String awayTeamName = req.awayTeamName();
            if (awayTeamName == null || awayTeamName.isBlank()) {
                awayTeamName = req.awayTeamId() != null ? "球队" + req.awayTeamId() : "客队";
            }
            String leagueName = req.leagueName() == null ? "" : req.leagueName();
            LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);

            // crawler_matches 使用第三方字符串 ID；历史表仍兼容数字 ID，非数字 ID 保存为空但不影响预测。
            Long homeTeamId = toLongOrNull(req.homeTeamId());
            Long awayTeamId = toLongOrNull(req.awayTeamId());

            Double homeWinProb = req.homeWinProb() != null ? req.homeWinProb() : r.homeWinProb();
            Double drawProb = req.drawProb() != null ? req.drawProb() : r.drawProb();
            Double awayWinProb = req.awayWinProb() != null ? req.awayWinProb() : r.awayWinProb();
            String resultLabel = req.resultLabel() != null ? req.resultLabel() : r.resultLabel();
            String explanation = req.explanation() != null ? req.explanation() : r.explanation();
            String modelVersion = r.modelVersion() == null ? MODEL_VERSION : r.modelVersion();
            LocalDateTime matchTime = parseMatchTime(req.matchTime());
            String homeTeamLogo = blankToNull(req.homeTeamLogo());
            String awayTeamLogo = blankToNull(req.awayTeamLogo());

            int rows = jdbcTemplate.update(
                    "INSERT INTO t_prediction (user_id, fixture_id, home_team_id, away_team_id, home_team_name, away_team_name, league_name, match_time, home_team_logo, away_team_logo, model_version, result_label, home_win_prob, draw_prob, away_win_prob, explanation, actual_result, is_correct, created_at, verified_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL)",
                    userId,
                    req.fixtureId(),
                    homeTeamId,
                    awayTeamId,
                    homeTeamName,
                    awayTeamName,
                    leagueName,
                    matchTime,
                    homeTeamLogo,
                    awayTeamLogo,
                    modelVersion,
                    resultLabel,
                    homeWinProb,
                    drawProb,
                    awayWinProb,
                    explanation,
                    now
            );
            if (rows <= 0) {
                log.warn("保存预测历史失败: 插入行数为0");
            }
        } catch (Exception ex) {
            log.error("保存预测历史失败: {}", ex.getMessage(), ex);
        }
        return r;
    }

    /**
     * 仅执行一次模型推理，不写入用户历史。
     * 比赛级预测预计算使用此入口，避免同一场比赛被每个用户重复保存/推理。
     */
    public MatchPredictionResponse predictOnly(MatchPredictionRequest req) {
        FeatureContext features = buildFeatureContext(req);
        if (!features.isPredictionReady(req)) {
            if (features.isLimitedReady(req)) {
                // 有至少一场双方历史时，使用可解释的 ELO + Poisson 保守基线。
                // 这比把 0/缺失值塞进训练模型更安全，同时不再让新赛季
                // 或升班马被一律显示为“完全没有预测”。
                return baselinePrediction(req, features, "历史样本有限，使用 ELO+Poisson 保守基线");
            }
            return unavailablePrediction(req, features, features.gateReason(req));
        }
        if (pythonEnabled && pythonClient != null && features.isPythonReady(req)) {
            return callPythonInference(req, features);
        }
        String reason = !features.optional.missing.isEmpty()
                ? "赛前增强特征缺失，使用透明 ELO+Poisson 基线"
                : "未启用 Python 模型";
        return baselinePrediction(req, features, reason);
    }

    private MatchPredictionResponse callPythonInference(MatchPredictionRequest req, FeatureContext features) {
        try {
            Map<String, Object> body = features.toInferencePayload(req);

            WebClient.RequestHeadersSpec<?> request = pythonClient.post()
                    .uri("/predict")
                    .bodyValue(body);
            if (!pythonToken.isBlank()) {
                request = request.header("X-ML-Internal-Token", pythonToken);
            }
            Map<String, Object> resp = request
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (resp != null && resp.get("homeWinProb") != null) {
                @SuppressWarnings("unchecked")
                List<String> topFeatures = resp.get("topFeatures") instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : List.of();
                Map<String, Object> featureMeta = new LinkedHashMap<>(features.metadata());
                if (resp.get("modelQuality") instanceof Map<?, ?> quality) {
                    quality.forEach((key, value) -> featureMeta.put("model_" + key, value));
                }
                if (resp.get("qualityGate") instanceof Map<?, ?> qualityGate) {
                    featureMeta.put("model_quality_gate", qualityGate);
                }
                for (String key : List.of("confidenceStatus", "confidenceLabel", "decisionMargin", "abstainThreshold", "recommendation", "modelRoute")) {
                    if (resp.containsKey(key)) featureMeta.put(key, resp.get(key));
                }
                String returnedModel = String.valueOf(resp.getOrDefault("modelVersion", ""));
                featureMeta.put("modelKind", returnedModel.toLowerCase(Locale.ROOT).startsWith("baseline") ? "BASELINE" : "ENHANCED");
                featureMeta.put("qualityTier", features.isComplete() ? "ENHANCED_COMPLETE" : "ENHANCED_LIMITED");

                return new MatchPredictionResponse(
                        req.fixtureId(),
                        (String) resp.get("resultLabel"),
                        ((Number) resp.get("homeWinProb")).doubleValue(),
                        ((Number) resp.get("drawProb")).doubleValue(),
                        ((Number) resp.get("awayWinProb")).doubleValue(),
                        returnedModel,
                        (String) resp.get("explanation"),
                        topFeatures,
                        features.isComplete(),
                        features.status(),
                        features.isComplete() ? null : "部分特征缺失：" + features.metadata().get("missing"),
                        featureMeta,
                        true
                );
            }
        } catch (Exception ex) {
            log.warn("Python推理调用失败，使用baseline: {}", ex.getMessage());
            return baselinePrediction(req, features, "Python 推理服务不可用：" + ex.getMessage());
        }
        return baselinePrediction(req, features, "Python 返回了无效预测结果");
    }

    private FeatureContext buildFeatureContext(MatchPredictionRequest req) {
        LocalDateTime target = parseMatchTime(req.matchTime());
        if (target == null) target = LocalDateTime.now(BUSINESS_ZONE);
        TeamFeature home = loadTeamFeature(req.homeTeamId(), req.homeTeamName(), target);
        TeamFeature away = loadTeamFeature(req.awayTeamId(), req.awayTeamName(), target);
        H2hFeature h2h = loadH2hFeature(req.homeTeamId(), req.awayTeamId(), req.homeTeamName(), req.awayTeamName(), target);
        Map<String, Object> prematch = prematchFeatureService.getSnapshot(req.fixtureId());
        boolean stalePrematch = prematch.isEmpty()
                || "NO_HISTORY".equals(String.valueOf(prematch.get("status")))
                || "ERROR".equals(String.valueOf(prematch.get("status")))
                || (prematch.get("completeness") instanceof Number n && n.doubleValue() < 0.5);
        if (stalePrematch && req.fixtureId() != null && target != null) {
            CrawlerMatch targetMatch = new CrawlerMatch();
            targetMatch.setId(req.fixtureId());
            targetMatch.setFixtureId(req.fixtureId());
            targetMatch.setMatchTime(target);
            targetMatch.setLeagueId(req.leagueId() == null ? null : String.valueOf(req.leagueId()));
            targetMatch.setLeagueName(req.leagueName());
            targetMatch.setHomeTeamId(req.homeTeamId());
            targetMatch.setAwayTeamId(req.awayTeamId());
            targetMatch.setHomeTeamName(req.homeTeamName());
            targetMatch.setAwayTeamName(req.awayTeamName());
            prematch = prematchFeatureService.refreshForMatch(targetMatch);
        }
        return new FeatureContext(home, away, h2h, loadOptionalFeatures(prematch), prematch, target);
    }

    /**
     * Target-fixture statistics are intentionally not used here: a finished
     * fixture snapshot contains post-match values and would leak the label.
     * Historical xG/shots are supplied by PrematchFeatureService instead.
     */
    private OptionalFeatures loadOptionalFeatures(Map<String, Object> prematch) {
        if (prematch == null || prematch.isEmpty()) return OptionalFeatures.empty();
        Double homeXg = number(prematch.get("homeXg5"));
        Double awayXg = number(prematch.get("awayXg5"));
        Double homeShots = number(prematch.get("homeShots5"));
        Double awayShots = number(prematch.get("awayShots5"));
        Double homeOnTarget = number(prematch.get("homeShotsOnTarget5"));
        Double awayOnTarget = number(prematch.get("awayShotsOnTarget5"));
        Set<String> missing = new LinkedHashSet<>();
        Map<?, ?> quality = prematch.get("dataQuality") instanceof Map<?, ?> map ? map : Map.of();
        if (!"AVAILABLE".equals(String.valueOf(quality.get("xgShots")))) {
            missing.add("xg");
            missing.add("shots");
            missing.add("shotsOnTarget");
        }
        if (!"AVAILABLE".equals(String.valueOf(quality.get("lineups")))) missing.add("lineups");
        if (!"AVAILABLE".equals(String.valueOf(quality.get("injuries")))) missing.add("injuries");
        if (!"AVAILABLE".equals(String.valueOf(quality.get("odds")))) missing.add("odds");
        return new OptionalFeatures(homeXg, awayXg, homeShots, awayShots,
                homeOnTarget, awayOnTarget, missing);
    }

    private Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return null;
        try { return Double.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; }
    }

    private Double findNumber(JsonNode node, String... keys) {
        if (node == null) return null;
        if (node.isObject()) {
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && value.isNumber()) return value.doubleValue();
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                Double found = findNumber(fields.next().getValue(), keys);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Double found = findNumber(child, keys);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Double firstNonNull(Double first, Double second) { return first != null ? first : second; }

    /**
     * 预测和赛程使用同一张 crawler_matches 表，避免 crawler_matches 与 t_fixture_cache 脱节。
     * 只统计最近 10 场已结束比赛，缺少数据时明确记录缺失原因，而不是静默伪造完整特征。
     */
    private TeamFeature loadTeamFeature(String teamId, String teamName, LocalDateTime targetTime) {
        List<Map<String, Object>> matches = queryTeamMatches(teamId, teamName, targetTime, 10);
        if (matches.isEmpty()) {
            return TeamFeature.defaultValue("未找到该球队的已结束比赛");
        }

        int wins = 0, draws = 0;
        double goals = 0;
        double conceded = 0;
        LocalDateTime lastMatchTime = null;
        int valid = 0;
        for (Map<String, Object> match : matches) {
            boolean home = sameCanonicalTeam(teamId, teamName,
                    stringValue(match.get("home_team_id")), stringValue(match.get("home_team_name")));
            Integer homeGoals = intValue(match.get("home_score"));
            Integer awayGoals = intValue(match.get("away_score"));
            if (homeGoals == null || awayGoals == null) continue;
            int scored = home ? homeGoals : awayGoals;
            int concededGoals = home ? awayGoals : homeGoals;
            goals += scored;
            conceded += concededGoals;
            if (scored > concededGoals) wins++;
            if (scored == concededGoals) draws++;
            valid++;
            LocalDateTime playedAt = localDateTimeValue(match.get("match_time"));
            if (playedAt != null && (lastMatchTime == null || playedAt.isAfter(lastMatchTime))) lastMatchTime = playedAt;
        }
        if (valid == 0) return TeamFeature.defaultValue("比赛缺少完整比分");

        double elo = calculateElo(teamId, teamName, targetTime);
        // 有已结束比赛就代表 ELO 至少有可追溯的基准值；1500 可能是实际计算结果，不能据此误报缺失。
        String source = matches.stream().anyMatch(row -> "historical-cache".equals(stringValue(row.get("source"))))
                ? (matches.stream().anyMatch(row -> "crawler_matches".equals(stringValue(row.get("source"))))
                ? "crawler_matches+historical_cache" : "historical_cache")
                : "crawler_matches";
        return new TeamFeature(elo, bound01((wins + draws * 0.5) / valid), goals / valid, conceded / valid,
                1.5, calculateDaysRest(lastMatchTime, targetTime), valid, new LinkedHashSet<>(), source,
                identityMappingService.teamKey(teamId, teamName));
    }

    private List<Map<String, Object>> queryTeamMatches(String teamId, String teamName, LocalDateTime targetTime, int limit) {
        String sql = "SELECT id, fixture_id, source, league_id, league_name, home_team_id, away_team_id, home_team_name, away_team_name, home_score, away_score, match_time "
                + "FROM crawler_matches WHERE status IN ('FT','AET','PEN') AND home_score IS NOT NULL AND away_score IS NOT NULL "
                + "AND match_time < ?" + primarySourceFilter() + " ORDER BY match_time DESC LIMIT 5000";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, targetTime).stream()
                    .filter(row -> sameCanonicalTeam(teamId, teamName,
                            stringValue(row.get("home_team_id")), stringValue(row.get("home_team_name")))
                            || sameCanonicalTeam(teamId, teamName,
                            stringValue(row.get("away_team_id")), stringValue(row.get("away_team_name"))))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            rows.addAll(historicalMatchCacheService.teamMatches(teamId, teamName, targetTime, 50));
            rows.sort(Comparator.comparing(this::localDateTimeValue,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return dedupeRows(rows, limit);
        } catch (Exception e) {
            log.warn("读取球队近期战绩失败: {}", e.getMessage());
            return List.of();
        }
    }

    private H2hFeature loadH2hFeature(String homeTeamId, String awayTeamId, String homeTeamName, String awayTeamName, LocalDateTime targetTime) {
        if (isBlank(homeTeamId) && isBlank(homeTeamName) || isBlank(awayTeamId) && isBlank(awayTeamName)) {
            return H2hFeature.defaultValue();
        }
        String sql = "SELECT home_team_id, away_team_id, home_team_name, away_team_name, home_score, away_score, league_name, match_time "
                + "FROM crawler_matches WHERE status IN ('FT','AET','PEN') AND home_score IS NOT NULL AND away_score IS NOT NULL "
                + "AND match_time < ?" + primarySourceFilter() + " ORDER BY match_time DESC LIMIT 5000";
        try {
            List<Map<String, Object>> allDbRows = jdbcTemplate.queryForList(sql, targetTime);
            List<Map<String, Object>> rows = allDbRows.stream()
                    .filter(row -> sameCanonicalTeam(homeTeamId, homeTeamName, stringValue(row.get("home_team_id")), stringValue(row.get("home_team_name")))
                            && sameCanonicalTeam(awayTeamId, awayTeamName, stringValue(row.get("away_team_id")), stringValue(row.get("away_team_name"))))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            rows = rows.stream().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            List<Map<String, Object>> reverse = allDbRows.stream()
                    .filter(row -> sameCanonicalTeam(awayTeamId, awayTeamName, stringValue(row.get("home_team_id")), stringValue(row.get("home_team_name")))
                            && sameCanonicalTeam(homeTeamId, homeTeamName, stringValue(row.get("away_team_id")), stringValue(row.get("away_team_name"))))
                    .toList();
            rows.addAll(reverse);
            rows.addAll(historicalMatchCacheService.headToHead(homeTeamId, awayTeamId, homeTeamName, awayTeamName, targetTime, 10));
            rows.sort(Comparator.comparing(this::localDateTimeValue,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            rows = dedupeRows(rows, 5);
            int homeWins = 0, draws = 0, awayWins = 0;
            for (Map<String, Object> row : rows) {
                Integer hg = intValue(row.get("home_score"));
                Integer ag = intValue(row.get("away_score"));
                if (hg == null || ag == null) continue;
                boolean originalOrder = sameCanonicalTeam(homeTeamId, homeTeamName,
                        stringValue(row.get("home_team_id")), stringValue(row.get("home_team_name")));
                if (hg.equals(ag)) draws++;
                else if ((originalOrder && hg > ag) || (!originalOrder && ag > hg)) homeWins++;
                else awayWins++;
            }
            return new H2hFeature(homeWins, draws, awayWins, rows.isEmpty() ? Set.of("h2h") : Set.of());
        } catch (Exception e) {
            log.warn("读取历史交锋失败: {}", e.getMessage());
            return H2hFeature.defaultValue();
        }
    }

    private MatchPredictionResponse baselinePrediction(MatchPredictionRequest req) {
        FeatureContext context = buildFeatureContext(req);
        return context.isPredictionReady(req)
                ? baselinePrediction(req, context, "未启用 Python 模型")
                : unavailablePrediction(req, context, context.gateReason(req));
    }

    private MatchPredictionResponse baselinePrediction(MatchPredictionRequest req, FeatureContext context, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>(context.metadata());
        metadata.put("modelKind", "BASELINE");
        metadata.put("qualityTier", context.isComplete() ? "BASELINE_COMPLETE" : "BASELINE_LIMITED");
        double homeStrength = context.home.elo + context.home.winRate * 180 + context.home.avgGoals * 40 - context.home.avgLoss * 30;
        double awayStrength = context.away.elo + context.away.winRate * 180 + context.away.avgGoals * 40 - context.away.avgLoss * 30;

        double h2hBias = (context.h2h.homeWins - context.h2h.awayWins) * 8;
        double restBias = (context.home.daysRest - context.away.daysRest) * 1.5;

        double scoreDiff = (homeStrength - awayStrength) + 45 + h2hBias + restBias;

        double homeProb = 1 / (1 + Math.pow(10, -scoreDiff / 400));
        double awayProb = 1 / (1 + Math.pow(10, scoreDiff / 400));
        double drawBase = 0.22 + Math.max(0.0, 0.08 - Math.abs(scoreDiff) / 1200.0);

        double total = homeProb + awayProb + drawBase;
        homeProb /= total;
        awayProb /= total;
        double drawProb = drawBase / total;

        // Poisson 进球分布补足只看胜率的盲点；使用滚动场均进失球，
        // 并与 ELO 结果做保守融合，避免小样本时概率过度极化。
        double homeLambda = Math.max(0.2, Math.min(4.5,
                0.58 * context.home.avgGoals + 0.42 * context.away.avgLoss + 0.12));
        double awayLambda = Math.max(0.15, Math.min(4.0,
                0.58 * context.away.avgGoals + 0.42 * context.home.avgLoss));
        double[] poisson = poissonProbabilities(homeLambda, awayLambda);
        homeProb = 0.62 * homeProb + 0.38 * poisson[0];
        drawProb = 0.62 * drawProb + 0.38 * poisson[1];
        awayProb = 0.62 * awayProb + 0.38 * poisson[2];
        total = homeProb + drawProb + awayProb;
        homeProb /= total; drawProb /= total; awayProb /= total;

        String label = homeProb >= drawProb && homeProb >= awayProb ? "HOME_WIN"
                : awayProb >= homeProb ? "AWAY_WIN" : "DRAW";

        String explanation = String.format(
                "基于球队ELO、近期战绩、攻防表现与历史交锋综合评估：主胜%.1f%%，平局%.1f%%，客胜%.1f%%",
                homeProb * 100, drawProb * 100, awayProb * 100
        );

        return new MatchPredictionResponse(
                req.fixtureId(), label, homeProb, drawProb, awayProb,
                "baseline-elo-poisson-v2", explanation, List.of(), context.isComplete(), context.status(), reason, metadata, true
        );
    }

    private double[] poissonProbabilities(double homeMean, double awayMean) {
        double homeWin = 0, draw = 0, awayWin = 0;
        double[] homeGoals = new double[9], awayGoals = new double[9];
        for (int i = 0; i < 9; i++) {
            homeGoals[i] = Math.exp(-homeMean) * Math.pow(homeMean, i) / factorial(i);
            awayGoals[i] = Math.exp(-awayMean) * Math.pow(awayMean, i) / factorial(i);
        }
        for (int h = 0; h < 9; h++) for (int a = 0; a < 9; a++) {
            double value = homeGoals[h] * awayGoals[a];
            if (h > a) homeWin += value; else if (h == a) draw += value; else awayWin += value;
        }
        double total = Math.max(1e-9, homeWin + draw + awayWin);
        return new double[]{homeWin / total, draw / total, awayWin / total};
    }

    private double factorial(int value) {
        double result = 1.0;
        for (int i = 2; i <= value; i++) result *= i;
        return result;
    }

    private MatchPredictionResponse unavailablePrediction(MatchPredictionRequest req, FeatureContext context, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>(context.metadata());
        metadata.put("gateReason", reason);
        metadata.put("modelKind", "UNAVAILABLE");
        metadata.put("qualityTier", "INSUFFICIENT_DATA");
        return new MatchPredictionResponse(req.fixtureId(), null, 0, 0, 0, MODEL_VERSION,
                "数据不足，暂不生成预测。" + reason, List.of(), false, "UNAVAILABLE", reason, metadata, false);
    }

    private List<Map<String, Object>> dedupeRows(List<Map<String, Object>> rows, int limit) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        List<Map<String, Object>> unique = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            LocalDateTime when = localDateTimeValue(row.get("match_time"));
            String key = IdentityNormalizer.normalize(stringValue(row.get("league_name"))) + "|"
                    + (when == null ? "" : when.toLocalDate());
            List<Map<String, Object>> bucket = buckets.computeIfAbsent(key, ignored -> new ArrayList<>());
            boolean duplicate = bucket.stream().anyMatch(existing -> sameFixtureRows(existing, row));
            if (!duplicate) {
                bucket.add(row);
                unique.add(row);
            }
        }
        return unique.stream().limit(Math.max(1, limit)).toList();
    }

    private boolean sameFixtureRows(Map<String, Object> first, Map<String, Object> second) {
        LocalDateTime firstTime = localDateTimeValue(first.get("match_time"));
        LocalDateTime secondTime = localDateTimeValue(second.get("match_time"));
        String firstFixture = stringValue(first.get("fixture_id"));
        String secondFixture = stringValue(second.get("fixture_id"));
        if (!firstFixture.isBlank() && firstFixture.equals(secondFixture)) return true;
        // 日期相同并不等于同一场：补赛、双赛日和青年队比赛都可能共用一天。
        // 没有可比较的开球时间时宁可保留两条样本，也不要静默吞掉历史数据。
        if (firstTime == null || secondTime == null || !firstTime.toLocalDate().equals(secondTime.toLocalDate())) return false;
        if (Math.abs(java.time.Duration.between(firstTime, secondTime).toMinutes()) > 120) return false;
        return IdentityNormalizer.compatible(stringValue(first.get("home_team_name")), stringValue(second.get("home_team_name")))
                && IdentityNormalizer.compatible(stringValue(first.get("away_team_name")), stringValue(second.get("away_team_name")));
    }

    public List<PredictionEntity> latest(int limit) {
        return enrichMatchSnapshots(predictionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                        .orderByDesc(PredictionEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        ));
    }

    public List<PredictionEntity> latestByUser(Long userId, int limit) {
        return enrichMatchSnapshots(predictionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                        .eq(PredictionEntity::getUserId, userId)
                        .orderByDesc(PredictionEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        ));
    }

    /** 用户预测历史游标分页，避免前端用“加载更多”反复替换整张列表。 */
    public Map<String, Object> historyPage(Long userId, Long cursor, int size) {
        int safeSize = Math.max(1, Math.min(size, 50));
        if (userId == null) {
            return Map.of("items", List.of(), "hasMore", false, "nextCursor", "");
        }
        var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                .eq(PredictionEntity::getUserId, userId)
                .orderByDesc(PredictionEntity::getId);
        if (cursor != null && cursor > 0) query.lt(PredictionEntity::getId, cursor);
        List<PredictionEntity> rows = enrichMatchSnapshots(predictionMapper.selectList(query.last("LIMIT " + (safeSize + 1))));
        boolean hasMore = rows.size() > safeSize;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, safeSize));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("hasMore", hasMore);
        result.put("nextCursor", rows.isEmpty() ? "" : String.valueOf(rows.get(rows.size() - 1).getId()));
        return result;
    }

    /** 今日创建的预测 */
    public List<PredictionEntity> today(int limit) {
        return enrichMatchSnapshots(predictionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                        .ge(PredictionEntity::getCreatedAt, LocalDate.now(BUSINESS_ZONE).atStartOfDay())
                        .orderByDesc(PredictionEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        ));
    }

    public List<PredictionEntity> todayByUser(Long userId, int limit) {
        if (userId == null) return List.of();
        return enrichMatchSnapshots(predictionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                        .eq(PredictionEntity::getUserId, userId)
                        .ge(PredictionEntity::getCreatedAt, LocalDate.now(BUSINESS_ZONE).atStartOfDay())
                        .orderByDesc(PredictionEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        ));
    }

    /** 指定比赛的历史预测 */
    public List<PredictionEntity> byFixture(Long fixtureId, int limit) {
        if (fixtureId == null || fixtureId <= 0) return List.of();
        List<Long> ids = new ArrayList<>();
        ids.add(fixtureId);
        try {
            List<Map<String, Object>> local = jdbcTemplate.queryForList(
                    "SELECT id, fixture_id FROM crawler_matches WHERE id=? LIMIT 1", fixtureId);
            if (!local.isEmpty()) {
                Long providerId = toLongObject(local.get(0).get("fixture_id"));
                if (providerId != null && providerId > 0 && !ids.contains(providerId)) ids.add(providerId);
            }
        } catch (Exception ignored) { }
        return enrichMatchSnapshots(predictionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                        .in(PredictionEntity::getFixtureId, ids)
                        .orderByDesc(PredictionEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        ));
    }

    public List<PredictionEntity> byFixtureForUser(Long userId, Long fixtureId, int limit) {
        if (userId == null || fixtureId == null || fixtureId <= 0) return List.of();
        List<Long> ids = new ArrayList<>();
        ids.add(fixtureId);
        try {
            List<Map<String, Object>> local = jdbcTemplate.queryForList(
                    "SELECT fixture_id FROM crawler_matches WHERE id=? LIMIT 1", fixtureId);
            if (!local.isEmpty()) {
                Long providerId = toLongObject(local.get(0).get("fixture_id"));
                if (providerId != null && providerId > 0 && !ids.contains(providerId)) ids.add(providerId);
            }
        } catch (Exception ignored) { }
        return enrichMatchSnapshots(predictionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                        .eq(PredictionEntity::getUserId, userId)
                        .in(PredictionEntity::getFixtureId, ids)
                        .orderByDesc(PredictionEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        ));
    }

    /**
     * 兼容迁移前创建的预测：如果历史行没有快照，则从当前赛程表按 fixture_id 回填到响应对象。
     * 这不会篡改预测结果，只补齐打开预测页所需的展示上下文。
     */
    private List<PredictionEntity> enrichMatchSnapshots(List<PredictionEntity> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        List<Long> fixtureIds = rows.stream()
                .map(PredictionEntity::getFixtureId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fixtureIds.isEmpty()) return rows;
        String placeholders = String.join(",", Collections.nCopies(fixtureIds.size(), "?"));
        try {
            List<Map<String, Object>> matches = jdbcTemplate.queryForList(
                    "SELECT id, fixture_id, home_team_id, away_team_id, home_team_name, away_team_name, league_name, match_time, home_team_logo, away_team_logo " +
                            "FROM crawler_matches WHERE fixture_id IN (" + placeholders + ") OR id IN (" + placeholders + ") ORDER BY updated_at DESC",
                    Stream.concat(fixtureIds.stream(), fixtureIds.stream()).toArray()
            );
            Map<Long, Map<String, Object>> byFixture = new HashMap<>();
            for (Map<String, Object> match : matches) {
                Long id = toLongObject(match.get("fixture_id"));
                if (id != null) byFixture.putIfAbsent(id, match);
                Long localId = toLongObject(match.get("id"));
                if (localId != null) byFixture.putIfAbsent(localId, match);
            }
            for (PredictionEntity row : rows) {
                Map<String, Object> match = byFixture.get(row.getFixtureId());
                if (match == null) continue;
                row.setMatchId(toLongObject(match.get("id")));
                if (row.getHomeTeamId() == null) row.setHomeTeamId(toLongObject(match.get("home_team_id")));
                if (row.getAwayTeamId() == null) row.setAwayTeamId(toLongObject(match.get("away_team_id")));
                if (isBlank(row.getHomeTeamName())) row.setHomeTeamName(stringValue(match.get("home_team_name")));
                if (isBlank(row.getAwayTeamName())) row.setAwayTeamName(stringValue(match.get("away_team_name")));
                if (isBlank(row.getLeagueName())) row.setLeagueName(stringValue(match.get("league_name")));
                if (row.getMatchTime() == null) row.setMatchTime(localDateTimeValue(match.get("match_time")));
                if (isBlank(row.getHomeTeamLogo())) row.setHomeTeamLogo(stringValue(match.get("home_team_logo")));
                if (isBlank(row.getAwayTeamLogo())) row.setAwayTeamLogo(stringValue(match.get("away_team_logo")));
            }
            // 部分旧数据只保留了 API-Football 数字球队 ID；用稳定的官方图片路径补齐展示，避免历史卡片出现空白 Logo。
            for (PredictionEntity row : rows) {
                if (isBlank(row.getHomeTeamLogo()) && row.getHomeTeamId() != null) {
                    row.setHomeTeamLogo(defaultTeamLogo(row.getHomeTeamId()));
                }
                if (isBlank(row.getAwayTeamLogo()) && row.getAwayTeamId() != null) {
                    row.setAwayTeamLogo(defaultTeamLogo(row.getAwayTeamId()));
                }
            }
        } catch (Exception ex) {
            log.debug("补充预测比赛快照失败: {}", ex.getMessage());
        }
        return rows;
    }

    public Map<String, Object> getStatistics(Long userId) {
        List<PredictionEntity> predictions;
        if (userId != null) {
            predictions = predictionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                            .eq(PredictionEntity::getUserId, userId)
            );
        } else {
            predictions = predictionMapper.selectList(null);
        }

        int total = predictions.size();
        int pending = (int) predictions.stream().filter(p -> p.getVerifiedAt() == null).count();
        int evaluated = total - pending;
        long correct = predictions.stream().filter(p -> p.getIsCorrect() != null && p.getIsCorrect() == 1).count();
        long wrong = predictions.stream().filter(p -> p.getIsCorrect() != null && p.getIsCorrect() == 0).count();
        double accuracy = evaluated > 0 ? (double) correct / evaluated * 100 : 0;
        LocalDateTime recentSince = LocalDateTime.now(BUSINESS_ZONE).minusDays(7);
        List<PredictionEntity> recent = predictions.stream().filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(recentSince)).toList();
        long recentEvaluated = recent.stream().filter(p -> p.getVerifiedAt() != null).count();
        long recentCorrect = recent.stream().filter(p -> p.getVerifiedAt() != null && Integer.valueOf(1).equals(p.getIsCorrect())).count();

        Map<String, Long> byLabel = new HashMap<>();
        for (PredictionEntity p : predictions) {
            byLabel.merge(p.getResultLabel(), 1L, Long::sum);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("evaluated", evaluated);
        result.put("pending", pending);
        result.put("correct", correct);
        result.put("wrong", wrong);
        result.put("accuracy", Math.round(accuracy * 100.0) / 100.0);
        result.put("recent7dTotal", recent.size());
        result.put("recent7dEvaluated", recentEvaluated);
        result.put("recent7dAccuracy", recentEvaluated == 0 ? null : Math.round(recentCorrect * 10000.0 / recentEvaluated) / 100.0);
        result.put("byResultLabel", byLabel);
        result.put("verificationStatus", evaluated == 0 ? "NO_EVALUATED_RESULTS" : pending > 0 ? "PARTIAL" : "COMPLETE");
        return result;
    }

    public Map<String, Object> getPublicPerformance(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        LocalDateTime cutoff = LocalDateTime.now(BUSINESS_ZONE).minusDays(safeDays);
        List<Map<String, Object>> rows;
        try {
            // Public performance must be calculated from the shared match
            // snapshot, never from individual user prediction history.
            rows = jdbcTemplate.queryForList(
                    "SELECT p.result_label,p.model_version,p.league_name,p.home_win_prob,p.draw_prob,p.away_win_prob,"
                            + "m.home_score,m.away_score,m.status,m.source "
                            + "FROM t_match_prediction p JOIN crawler_matches m ON m.id=p.fixture_id "
                            + "WHERE p.status='READY' AND p.generated_at >= ? "
                            + "AND m.status IN ('FT','AET','PEN','FINISHED') "
                            + "AND m.home_score IS NOT NULL AND m.away_score IS NOT NULL "
                            + primarySourceFilter("m"), cutoff);
        } catch (Exception ex) {
            log.warn("读取比赛级公共预测表现失败: {}", ex.getMessage());
            rows = List.of();
        }
        long evaluated = 0;
        long correct = 0;
        Map<String, Long> byLabel = new LinkedHashMap<>();
        Map<String, Long> byLeague = new LinkedHashMap<>();
        List<String> modelVersions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String predicted = stringValue(row.get("result_label"));
            String actual = actualLabel(row.get("home_score"), row.get("away_score"));
            if (predicted.isBlank() || actual.isBlank()) continue;
            evaluated++;
            if (predicted.equals(actual)) correct++;
            byLabel.merge(predicted, 1L, Long::sum);
            byLeague.merge(stringValue(row.get("league_name")), 1L, Long::sum);
            String model = stringValue(row.get("model_version"));
            if (!model.isBlank() && !modelVersions.contains(model)) modelVersions.add(model);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowDays", safeDays);
        result.put("total", rows.size());
        result.put("evaluated", evaluated);
        result.put("accuracy", evaluated == 0 ? null : Math.round(correct * 10000.0 / evaluated) / 100.0);
        result.put("confidence", evaluated >= 30 ? "STABLE" : evaluated >= 10 ? "LIMITED" : "LOW_SAMPLE");
        result.put("modelVersions", modelVersions);
        result.put("byLabel", byLabel);
        result.put("byLeague", byLeague);
        result.put("scope", "global_match_snapshots");
        result.put("qualityNote", evaluated < 10 ? "样本不足，不代表模型长期准确率" : "仅统计主爬虫源已完赛比赛级快照");
        return result;
    }

    private String actualLabel(Object homeScore, Object awayScore) {
        Integer home = intValue(homeScore);
        Integer away = intValue(awayScore);
        if (home == null || away == null) return "";
        return home > away ? "HOME_WIN" : home.equals(away) ? "DRAW" : "AWAY_WIN";
    }

    private boolean supportedLeagueName(String value) {
        if (isBlank(value)) return false;
        String normalized = IdentityNormalizer.normalize(value);
        return SUPPORTED_LEAGUE_NAMES.contains(normalized);
    }

    private double bound01(double val) {
        return Math.max(0.0, Math.min(1.0, val));
    }

    private double calculateElo(String teamId, String teamName, LocalDateTime targetTime) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT home_team_id, away_team_id, home_team_name, away_team_name, home_score, away_score, league_name, match_time "
                            + "FROM crawler_matches WHERE status IN ('FT','AET','PEN') AND home_score IS NOT NULL AND away_score IS NOT NULL "
                            + "AND match_time < ?" + primarySourceFilter() + " ORDER BY match_time ASC", targetTime);
        } catch (Exception e) {
            return 1500.0;
        }
        rows = new ArrayList<>(rows);
        rows.addAll(historicalMatchCacheService.allFinishedBefore(targetTime));
        rows.sort(Comparator.comparing(this::localDateTimeValue,
                Comparator.nullsLast(Comparator.naturalOrder())));
        if (rows.isEmpty()) return 1500.0;
        rows = dedupeRows(rows, rows.size());
        Map<String, Double> ratings = new HashMap<>();
        boolean found = false;
        for (Map<String, Object> row : rows) {
            String homeId = stringValue(row.get("home_team_id"));
            String awayId = stringValue(row.get("away_team_id"));
            String homeName = stringValue(row.get("home_team_name"));
            String awayName = stringValue(row.get("away_team_name"));
            String homeKey = teamKey(homeId, homeName);
            String awayKey = teamKey(awayId, awayName);
            if (homeKey == null || awayKey == null) continue;
            double homeRating = ratings.getOrDefault(homeKey, 1500.0);
            double awayRating = ratings.getOrDefault(awayKey, 1500.0);
            Integer hg = intValue(row.get("home_score"));
            Integer ag = intValue(row.get("away_score"));
            if (hg == null || ag == null) continue;
            double expectedHome = 1.0 / (1.0 + Math.pow(10, -((homeRating + 65.0) - awayRating) / 400.0));
            double actualHome = hg > ag ? 1.0 : hg.equals(ag) ? 0.5 : 0.0;
            double delta = 28.0 * (actualHome - expectedHome);
            ratings.put(homeKey, homeRating + delta);
            ratings.put(awayKey, awayRating - delta);
            if (sameCanonicalTeam(teamId, teamName, homeId, homeName)
                    || sameCanonicalTeam(teamId, teamName, awayId, awayName)) found = true;
        }
        return found ? ratings.getOrDefault(teamKey(teamId, teamName), 1500.0) : 1500.0;
    }

    private int calculateDaysRest(LocalDateTime lastMatchTime, LocalDateTime targetTime) {
        if (lastMatchTime == null) return 7;
        long days = Duration.between(lastMatchTime, targetTime == null ? LocalDateTime.now(BUSINESS_ZONE) : targetTime).toDays();
        if (days < 0) return 3;
        if (days > 20) return 14;
        return Math.max(1, (int) days);
    }

    private boolean sameTeam(String expectedId, String expectedName, String actualId, String actualName) {
        return (!isBlank(expectedId) && expectedId.equals(actualId))
                || (!isBlank(expectedName) && expectedName.equals(actualName));
    }

    private boolean sameCanonicalTeam(String expectedId, String expectedName, String actualId, String actualName) {
        if (sameTeam(expectedId, expectedName, actualId, actualName)) return true;
        if (IdentityNormalizer.compatible(expectedName, actualName)) return true;
        String expected = identityMappingService.teamKey(expectedId, expectedName);
        String actual = identityMappingService.teamKey(actualId, actualName);
        return !expected.endsWith(":unknown") && expected.equals(actual);
    }

    private String teamKey(String id, String name) {
        if (isBlank(id) && isBlank(name)) return null;
        String canonical = identityMappingService.teamKey(id, name);
        return canonical.endsWith(":unknown") ? null : canonical;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String primarySourceFilter() {
        return primarySourceFilter(null);
    }

    private String primarySourceFilter(String alias) {
        if (!crawlerProperties.isPrimaryOnly() || crawlerProperties.getPrimarySource() == null
                || crawlerProperties.getPrimarySource().isBlank()) return "";
        String column = (alias == null || alias.isBlank() ? "source" : alias + ".source");
        return " AND " + column + " = '" + crawlerProperties.getPrimarySource().replace("'", "''") + "'";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(value)); } catch (Exception e) { return null; }
    }

    private LocalDateTime localDateTimeValue(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    private LocalDateTime parseMatchTime(String value) {
        if (isBlank(value)) return null;
        String normalized = value.trim();
        try { return LocalDateTime.parse(normalized); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(normalized).toInstant().atZone(BUSINESS_ZONE).toLocalDateTime(); } catch (Exception ignored) { }
        try { return Instant.parse(normalized).atZone(BUSINESS_ZONE).toLocalDateTime(); } catch (Exception ignored) { }
        return null;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private Long toLongObject(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; }
    }

    private String defaultTeamLogo(Long teamId) {
        return "https://media.api-sports.io/football/teams/" + teamId + ".png";
    }

    private Long toLongOrNull(String value) {
        if (isBlank(value)) return null;
        try { return Long.valueOf(value); } catch (Exception e) { return null; }
    }

    private record TeamFeature(double elo, double winRate, double avgGoals, double avgLoss, double avgCards,
                               int daysRest, int sampleSize, Set<String> missing, String source, String canonicalKey) {
        static TeamFeature defaultValue(String reason) {
            return new TeamFeature(1500.0, 0.45, 1.5, 1.2, 1.5, 7, 0,
                    new LinkedHashSet<>(Set.of("elo", "form", reason)), "default", "");
        }
    }

    private record H2hFeature(int homeWins, int draws, int awayWins, Set<String> missing) {
        static H2hFeature defaultValue() {
            return new H2hFeature(0, 0, 0, new LinkedHashSet<>(Set.of("h2h")));
        }
    }

    private record OptionalFeatures(Double homeXg, Double awayXg, Double homeShots, Double awayShots,
                                    Double homeShotsOnTarget, Double awayShotsOnTarget, Set<String> missing) {
        static OptionalFeatures empty() {
            return new OptionalFeatures(null, null, null, null, null, null,
                    new LinkedHashSet<>(Set.of("xg", "shots", "shotsOnTarget", "lineups", "injuries", "odds")));
        }
    }

    private record FeatureContext(TeamFeature home, TeamFeature away, H2hFeature h2h,
                                   OptionalFeatures optional, Map<String, Object> prematch,
                                   LocalDateTime targetTime) {
        // H2H is an optional enrichment.  It must not mark a prediction as
        // incomplete when both teams have the required recent-match samples.
        boolean isComplete() {
            return home.sampleSize >= 3 && away.sampleSize >= 3
                    && home.missing.isEmpty() && away.missing.isEmpty()
                    && optional.missing.isEmpty();
        }
        boolean isPredictionReady(MatchPredictionRequest req) {
            return supportedLeague(req) && home.sampleSize >= 3 && away.sampleSize >= 3
                    && !home.source.equals("default") && !away.source.equals("default")
                    && home.missing.isEmpty() && away.missing.isEmpty();
        }
        boolean isPythonReady(MatchPredictionRequest req) {
            return isPredictionReady(req) && optional.missing.isEmpty();
        }
        boolean isLimitedReady(MatchPredictionRequest req) {
            return supportedLeague(req) && home.sampleSize >= 1 && away.sampleSize >= 1
                    && !home.source.equals("default") && !away.source.equals("default");
        }
        String gateReason(MatchPredictionRequest req) {
            List<String> reasons = new ArrayList<>();
            if (!supportedLeague(req)) reasons.add("联赛不在模型覆盖范围");
            if (home.sampleSize < 1) reasons.add("主队没有可用历史样本");
            else if (home.sampleSize < 3) reasons.add("主队历史样本有限(" + home.sampleSize + "/3)");
            if (away.sampleSize < 1) reasons.add("客队没有可用历史样本");
            else if (away.sampleSize < 3) reasons.add("客队历史样本有限(" + away.sampleSize + "/3)");
            if (!home.missing.isEmpty() || !away.missing.isEmpty()) reasons.add("核心特征缺失");
            return String.join("；", reasons);
        }
        private boolean supportedLeague(MatchPredictionRequest req) {
            if (req == null) return false;
            if (req.leagueId() != null && SUPPORTED_LEAGUE_IDS.contains(String.valueOf(req.leagueId()))) return true;
            String name = IdentityNormalizer.normalize(req.leagueName());
            return SUPPORTED_LEAGUE_NAMES.stream().anyMatch(name::equals);
        }
        String status() {
            if (home.source.equals("default") && away.source.equals("default")) return "DEFAULTED";
            if (isComplete() && home.sampleSize >= 3 && away.sampleSize >= 3) return "COMPLETE";
            if (home.sampleSize >= 1 && away.sampleSize >= 1) return "LIMITED";
            return "PARTIAL";
        }
        Map<String, Object> metadata() {
            Set<String> missing = new LinkedHashSet<>();
            missing.addAll(home.missing);
            missing.addAll(away.missing);
            missing.addAll(h2h.missing);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("homeSource", home.source);
            data.put("awaySource", away.source);
            data.put("missing", missing);
            data.put("h2hMatches", h2h.homeWins + h2h.draws + h2h.awayWins);
            data.put("homeSampleSize", home.sampleSize);
            data.put("awaySampleSize", away.sampleSize);
            String qualityTier = home.sampleSize >= 3 && away.sampleSize >= 3
                    && home.missing.isEmpty() && away.missing.isEmpty()
                    ? "FULL" : (home.sampleSize >= 1 && away.sampleSize >= 1 ? "LIMITED" : "INSUFFICIENT");
            data.put("qualityTier", qualityTier);
            data.put("homeCanonicalKey", home.canonicalKey);
            data.put("awayCanonicalKey", away.canonicalKey);
            data.put("featureAsOf", targetTime);
            data.put("prematchSnapshot", prematch);
            data.put("optionalFeatures", Map.of(
                    "homeXg", optional.homeXg == null ? 0 : optional.homeXg,
                    "awayXg", optional.awayXg == null ? 0 : optional.awayXg,
                    "homeShots", optional.homeShots == null ? 0 : optional.homeShots,
                    "awayShots", optional.awayShots == null ? 0 : optional.awayShots,
                    "homeShotsOnTarget", optional.homeShotsOnTarget == null ? 0 : optional.homeShotsOnTarget,
                    "awayShotsOnTarget", optional.awayShotsOnTarget == null ? 0 : optional.awayShotsOnTarget,
                    "missing", optional.missing));
            return data;
        }

        Map<String, Object> toInferencePayload(MatchPredictionRequest req) {
            Map<String, Object> body = new HashMap<>();
            body.put("fixture_id", req.fixtureId());
            body.put("home_team_id", req.homeTeamId());
            body.put("away_team_id", req.awayTeamId());
            // The ML service uses the league only for specialist routing. The
            // other configured leagues intentionally fall back to the global
            // model and are not rejected by this metadata.
            body.put("league_id", req.leagueId());
            body.put("league_name", req.leagueName());
            body.put("home_elo", home.elo);
            body.put("away_elo", away.elo);
            body.put("home_win_rate", home.winRate);
            body.put("away_win_rate", away.winRate);
            body.put("home_avg_goals", home.avgGoals);
            body.put("away_avg_goals", away.avgGoals);
            body.put("home_avg_loss", home.avgLoss);
            body.put("away_avg_loss", away.avgLoss);
            body.put("home_avg_cards", home.avgCards);
            body.put("away_avg_cards", away.avgCards);
            body.put("home_days_rest", home.daysRest);
            body.put("away_days_rest", away.daysRest);
            body.put("h2h_home_wins", h2h.homeWins);
            body.put("h2h_draws", h2h.draws);
            body.put("h2h_away_wins", h2h.awayWins);
            // Optional cached pre-match features. The current deployed model keeps
            // the 20-feature contract; future bundles can opt into these keys.
            body.put("home_xg", optional.homeXg == null ? 0 : optional.homeXg);
            body.put("away_xg", optional.awayXg == null ? 0 : optional.awayXg);
            body.put("home_shots", optional.homeShots == null ? 0 : optional.homeShots);
            body.put("away_shots", optional.awayShots == null ? 0 : optional.awayShots);
            body.put("home_shots_on_target", optional.homeShotsOnTarget == null ? 0 : optional.homeShotsOnTarget);
            body.put("away_shots_on_target", optional.awayShotsOnTarget == null ? 0 : optional.awayShotsOnTarget);
            body.put("optional_feature_missing", optional.missing);
            // Keep the Java/Python contract explicit. Missing provider data is
            // sent as 0 and is visible through prematchSnapshot metadata.
            putPrematch(body, "home_rank", "homeRank", 0);
            putPrematch(body, "away_rank", "awayRank", 0);
            putPrematch(body, "rank_diff", "rankDiff", 0);
            putPrematch(body, "home_points_per_match", "homePointsPerMatch", 0.0);
            putPrematch(body, "away_points_per_match", "awayPointsPerMatch", 0.0);
            putPrematch(body, "points_per_match_diff", "pointsPerMatchDiff", 0.0);
            putPrematch(body, "home_goal_diff_per_match", "homeGoalDiffPerMatch", 0.0);
            putPrematch(body, "away_goal_diff_per_match", "awayGoalDiffPerMatch", 0.0);
            putPrematch(body, "goal_diff_per_match_diff", "goalDiffPerMatchDiff", 0.0);
            putPrematch(body, "home_form_5", "homeForm5", 0.5);
            putPrematch(body, "away_form_5", "awayForm5", 0.5);
            putPrematch(body, "home_form_10", "homeForm10", 0.5);
            putPrematch(body, "away_form_10", "awayForm10", 0.5);
            putPrematch(body, "home_home_form_5", "homeHomeForm5", 0.5);
            putPrematch(body, "away_away_form_5", "awayAwayForm5", 0.5);
            putPrematch(body, "home_matches_14d", "homeMatches14d", 0);
            putPrematch(body, "away_matches_14d", "awayMatches14d", 0);
            putPrematch(body, "matches_14d_diff", "matches14dDiff", 0);
            putPrematch(body, "home_xg_5", "homeXg5", 0.0);
            putPrematch(body, "away_xg_5", "awayXg5", 0.0);
            putPrematch(body, "home_xga_5", "homeXga5", 0.0);
            putPrematch(body, "away_xga_5", "awayXga5", 0.0);
            body.put("home_xg_available", Boolean.TRUE.equals(prematch.get("homeXgAvailable")));
            body.put("away_xg_available", Boolean.TRUE.equals(prematch.get("awayXgAvailable")));
            body.put("home_xga_available", Boolean.TRUE.equals(prematch.get("homeXgaAvailable")));
            body.put("away_xga_available", Boolean.TRUE.equals(prematch.get("awayXgaAvailable")));
            body.put("xg_coverage", prematch.getOrDefault("xgCoverage", 0.0));
            putPrematch(body, "home_shots_5", "homeShots5", 0.0);
            putPrematch(body, "away_shots_5", "awayShots5", 0.0);
            putPrematch(body, "home_shots_on_target_5", "homeShotsOnTarget5", 0.0);
            putPrematch(body, "away_shots_on_target_5", "awayShotsOnTarget5", 0.0);
            putPrematch(body, "home_lineup_stability", "homeLineupStability", 0.0);
            putPrematch(body, "away_lineup_stability", "awayLineupStability", 0.0);
            putPrematch(body, "home_injury_impact", "homeInjuryImpact", 0.0);
            putPrematch(body, "away_injury_impact", "awayInjuryImpact", 0.0);
            putPrematch(body, "market_home_prob", "marketHomeProb", 0.0);
            putPrematch(body, "market_draw_prob", "marketDrawProb", 0.0);
            putPrematch(body, "market_away_prob", "marketAwayProb", 0.0);
            return body;
        }

        private void putPrematch(Map<String, Object> body, String key, String snapshotKey, Object fallback) {
            Object value = prematch.get(snapshotKey);
            body.put(key, value == null ? fallback : value);
        }
    }
}
