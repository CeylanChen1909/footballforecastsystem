package com.chen.football.prediction.service;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.MatchPredictionRequest;
import com.chen.football.common.dto.MatchPredictionResponse;
import com.chen.football.prediction.entity.PredictionEntity;
import com.chen.football.prediction.mapper.PredictionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PersistencePredictionService {
    private static final String MODEL_VERSION = "xgboost-v2";

    private final PredictionMapper predictionMapper;
    private final JdbcTemplate jdbcTemplate;
    private final WebClient pythonClient;
    private final boolean pythonEnabled;

    public PersistencePredictionService(
            PredictionMapper predictionMapper,
            JdbcTemplate jdbcTemplate,
            @Value("${python.inference.url:http://127.0.0.1:5001}") String pythonUrl,
            @Value("${python.inference.enabled:false}") boolean enabled) {
        this.predictionMapper = predictionMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.pythonEnabled = enabled;
        if (enabled) {
            this.pythonClient = WebClient.builder()
                    .baseUrl(pythonUrl)
                    .build();
        } else {
            this.pythonClient = null;
        }
    }

    public MatchPredictionResponse predictAndSave(MatchPredictionRequest req) {
        MatchPredictionResponse r;

        if (pythonEnabled && pythonClient != null) {
            r = callPythonInference(req);
        } else {
            r = baselinePrediction(req);
        }

        Long userId = UserContext.getUserId();
        if (userId == null) {
            userId = req.userId();
        }
        if (userId == null) {
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
            LocalDateTime now = LocalDateTime.now();

            Long homeTeamId = req.homeTeamId() != null ? req.homeTeamId() : 0L;
            Long awayTeamId = req.awayTeamId() != null ? req.awayTeamId() : 0L;

            Double homeWinProb = req.homeWinProb() != null ? req.homeWinProb() : r.homeWinProb();
            Double drawProb = req.drawProb() != null ? req.drawProb() : r.drawProb();
            Double awayWinProb = req.awayWinProb() != null ? req.awayWinProb() : r.awayWinProb();
            String resultLabel = req.resultLabel() != null ? req.resultLabel() : r.resultLabel();
            String explanation = req.explanation() != null ? req.explanation() : r.explanation();
            String modelVersion = r.modelVersion() == null ? MODEL_VERSION : r.modelVersion();

            int rows = jdbcTemplate.update(
                    "INSERT INTO t_prediction (user_id, fixture_id, home_team_id, away_team_id, home_team_name, away_team_name, league_name, model_version, result_label, home_win_prob, draw_prob, away_win_prob, explanation, actual_result, is_correct, created_at, verified_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL)",
                    userId,
                    req.fixtureId(),
                    homeTeamId,
                    awayTeamId,
                    homeTeamName,
                    awayTeamName,
                    leagueName,
                    modelVersion,
                    resultLabel,
                    homeWinProb,
                    drawProb,
                    awayWinProb,
                    explanation,
                    now
            );
            if (rows <= 0) {
                System.err.println("保存预测历史失败: 插入行数为0");
            }
        } catch (Exception ex) {
            System.err.println("保存预测历史失败: " + ex.getMessage());
        }
        return r;
    }

    private MatchPredictionResponse callPythonInference(MatchPredictionRequest req) {
        try {
            FeatureContext features = buildFeatureContext(req);
            Map<String, Object> body = features.toInferencePayload(req);

            Map<String, Object> resp = pythonClient.post()
                    .uri("/predict")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (resp != null && resp.get("homeWinProb") != null) {
                @SuppressWarnings("unchecked")
                List<String> topFeatures = resp.get("topFeatures") instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : List.of();

                return new MatchPredictionResponse(
                        req.fixtureId(),
                        (String) resp.get("resultLabel"),
                        ((Number) resp.get("homeWinProb")).doubleValue(),
                        ((Number) resp.get("drawProb")).doubleValue(),
                        ((Number) resp.get("awayWinProb")).doubleValue(),
                        (String) resp.get("modelVersion"),
                        (String) resp.get("explanation"),
                        topFeatures
                );
            }
        } catch (Exception ex) {
            System.err.println("Python推理调用失败，使用baseline: " + ex.getMessage());
        }
        return baselinePrediction(req);
    }

    private FeatureContext buildFeatureContext(MatchPredictionRequest req) {
        TeamFeature home = loadTeamFeature(req.homeTeamId());
        TeamFeature away = loadTeamFeature(req.awayTeamId());
        H2hFeature h2h = loadH2hFeature(req.homeTeamId(), req.awayTeamId());

        return new FeatureContext(home, away, h2h);
    }

    private TeamFeature loadTeamFeature(Long teamId) {
        if (teamId == null) {
            return TeamFeature.defaultValue();
        }

        Double elo = queryDouble(
                "SELECT elo_rating FROM t_team_cache WHERE team_id = ? LIMIT 1",
                teamId,
                1500.0
        );

        Integer total = queryInt(
                "SELECT COUNT(1) FROM t_team_form WHERE team_id = ? LIMIT 1",
                teamId,
                0
        );

        Integer winCount = queryInt(
                "SELECT COUNT(1) FROM t_team_form WHERE team_id = ? AND result = 0 LIMIT 1",
                teamId,
                0
        );

        Double avgGoals = queryDouble(
                "SELECT AVG(goals) FROM t_team_form WHERE team_id = ?",
                teamId,
                1.5
        );

        Double avgLoss = queryDouble(
                "SELECT AVG(conceded) FROM t_team_form WHERE team_id = ?",
                teamId,
                1.2
        );

        Double avgCards = queryDouble(
                "SELECT AVG(yellow_cards + red_cards * 2) FROM t_team_form WHERE team_id = ?",
                teamId,
                1.5
        );

        LocalDateTime lastMatchTime = queryLocalDateTime(
                "SELECT MAX(match_time) FROM t_team_form WHERE team_id = ?",
                teamId
        );

        int daysRest = 7;
        if (lastMatchTime != null) {
            long days = java.time.Duration.between(lastMatchTime, LocalDateTime.now()).toDays();
            if (days < 0) {
                daysRest = 3;
            } else if (days > 20) {
                daysRest = 14;
            } else {
                daysRest = (int) days;
            }
        }

        double winRate = total == null || total == 0 ? 0.45 : (double) winCount / total;

        return new TeamFeature(
                safe(elo, 1500.0),
                bound01(winRate),
                safe(avgGoals, 1.5),
                safe(avgLoss, 1.2),
                safe(avgCards, 1.5),
                Math.max(daysRest, 1)
        );
    }

    private H2hFeature loadH2hFeature(Long homeTeamId, Long awayTeamId) {
        if (homeTeamId == null || awayTeamId == null) {
            return H2hFeature.defaultValue();
        }

        Integer homeWins = queryInt(
                "SELECT COUNT(1) FROM t_fixture_cache WHERE status = 'FT' AND home_team_id = ? AND away_team_id = ? AND home_goals > away_goals",
                new Object[]{homeTeamId, awayTeamId},
                0
        );

        Integer awayWins = queryInt(
                "SELECT COUNT(1) FROM t_fixture_cache WHERE status = 'FT' AND home_team_id = ? AND away_team_id = ? AND home_goals < away_goals",
                new Object[]{homeTeamId, awayTeamId},
                0
        );

        Integer draws = queryInt(
                "SELECT COUNT(1) FROM t_fixture_cache WHERE status = 'FT' AND ((home_team_id = ? AND away_team_id = ?) OR (home_team_id = ? AND away_team_id = ?)) AND home_goals = away_goals",
                new Object[]{homeTeamId, awayTeamId, awayTeamId, homeTeamId},
                0
        );

        return new H2hFeature(homeWins, draws, awayWins);
    }

    private MatchPredictionResponse baselinePrediction(MatchPredictionRequest req) {
        FeatureContext context = buildFeatureContext(req);
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

        String label = homeProb >= drawProb && homeProb >= awayProb ? "HOME_WIN"
                : awayProb >= homeProb ? "AWAY_WIN" : "DRAW";

        String explanation = String.format(
                "基于球队ELO、近期战绩、攻防表现与历史交锋综合评估：主胜%.1f%%，平局%.1f%%，客胜%.1f%%",
                homeProb * 100, drawProb * 100, awayProb * 100
        );

        return new MatchPredictionResponse(
                req.fixtureId(), label, homeProb, drawProb, awayProb,
                MODEL_VERSION, explanation
        );
    }

    public List<PredictionEntity> latest(int limit) {
        return predictionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                        .orderByDesc(PredictionEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        );
    }

    public List<PredictionEntity> latestByUser(Long userId, int limit) {
        return predictionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PredictionEntity>()
                        .eq(PredictionEntity::getUserId, userId)
                        .orderByDesc(PredictionEntity::getCreatedAt)
                        .last("LIMIT " + limit)
        );
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
        long correct = predictions.stream().filter(p -> p.getIsCorrect() != null && p.getIsCorrect() == 1).count();
        long wrong = predictions.stream().filter(p -> p.getIsCorrect() != null && p.getIsCorrect() == 0).count();
        double accuracy = total > 0 ? (double) correct / total * 100 : 0;

        Map<String, Long> byLabel = new HashMap<>();
        for (PredictionEntity p : predictions) {
            byLabel.merge(p.getResultLabel(), 1L, Long::sum);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("correct", correct);
        result.put("wrong", wrong);
        result.put("accuracy", Math.round(accuracy * 100.0) / 100.0);
        result.put("byResultLabel", byLabel);
        return result;
    }

    private Double queryDouble(String sql, Object arg, Double defaultValue) {
        try {
            Double v = jdbcTemplate.queryForObject(sql, Double.class, arg);
            return v == null ? defaultValue : v;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Integer queryInt(String sql, Object arg, Integer defaultValue) {
        try {
            Integer v = jdbcTemplate.queryForObject(sql, Integer.class, arg);
            return v == null ? defaultValue : v;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Integer queryInt(String sql, Object[] args, Integer defaultValue) {
        try {
            Integer v = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return v == null ? defaultValue : v;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private LocalDateTime queryLocalDateTime(String sql, Object arg) {
        try {
            return jdbcTemplate.queryForObject(sql, LocalDateTime.class, arg);
        } catch (Exception e) {
            return null;
        }
    }

    private double safe(Double val, double defaultVal) {
        return val == null || val.isNaN() || val.isInfinite() ? defaultVal : val;
    }

    private double bound01(double val) {
        return Math.max(0.0, Math.min(1.0, val));
    }

    private record TeamFeature(double elo, double winRate, double avgGoals, double avgLoss, double avgCards, int daysRest) {
        static TeamFeature defaultValue() {
            return new TeamFeature(1500.0, 0.45, 1.5, 1.2, 1.5, 7);
        }
    }

    private record H2hFeature(int homeWins, int draws, int awayWins) {
        static H2hFeature defaultValue() {
            return new H2hFeature(0, 0, 0);
        }
    }

    private record FeatureContext(TeamFeature home, TeamFeature away, H2hFeature h2h) {
        Map<String, Object> toInferencePayload(MatchPredictionRequest req) {
            Map<String, Object> body = new HashMap<>();
            body.put("fixture_id", req.fixtureId());
            body.put("home_team_id", req.homeTeamId());
            body.put("away_team_id", req.awayTeamId());
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
            return body;
        }
    }
}
