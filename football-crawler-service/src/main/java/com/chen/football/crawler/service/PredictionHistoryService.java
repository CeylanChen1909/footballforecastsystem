package com.chen.football.crawler.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class PredictionHistoryService {

    private final JdbcTemplate jdbcTemplate;

    public void add(Long userId, Map<String, Object> record) {
        String sql = "INSERT INTO t_prediction_history (user_id, fixture_id, home_team_id, away_team_id, home_team_name, away_team_name, league_name, home_win_prob, draw_prob, away_win_prob, result_label, explanation, is_correct, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        jdbcTemplate.update(sql,
                userId,
                asLong(record.get("fixtureId")),
                asString(record.get("homeTeamId")),
                asString(record.get("awayTeamId")),
                asString(record.get("homeTeamName")),
                asString(record.get("awayTeamName")),
                asString(record.get("leagueName")),
                asDouble(record.get("homeWinProb")),
                asDouble(record.get("drawProb")),
                asDouble(record.get("awayWinProb")),
                asString(record.get("resultLabel")),
                asString(record.get("explanation")),
                record.get("isCorrect") == null ? null : asInteger(record.get("isCorrect"))
        );
    }

    public List<Map<String, Object>> list(Long userId, int limit) {
        String sql = "SELECT id, user_id, fixture_id, home_team_id, away_team_id, home_team_name, away_team_name, league_name, home_win_prob, draw_prob, away_win_prob, result_label, explanation, is_correct, created_at FROM t_prediction_history WHERE user_id = ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id"));
            item.put("userId", rs.getLong("user_id"));
            item.put("fixtureId", rs.getLong("fixture_id"));
            item.put("homeTeamId", rs.getString("home_team_id"));
            item.put("awayTeamId", rs.getString("away_team_id"));
            item.put("homeTeamName", rs.getString("home_team_name"));
            item.put("awayTeamName", rs.getString("away_team_name"));
            item.put("leagueName", rs.getString("league_name"));
            item.put("homeWinProb", rs.getBigDecimal("home_win_prob"));
            item.put("drawProb", rs.getBigDecimal("draw_prob"));
            item.put("awayWinProb", rs.getBigDecimal("away_win_prob"));
            item.put("resultLabel", rs.getString("result_label"));
            item.put("explanation", rs.getString("explanation"));
            item.put("isCorrect", rs.getObject("is_correct"));
            item.put("createdAt", rs.getTimestamp("created_at"));
            return item;
        }, userId, limit);
    }

    private Long asLong(Object v) { return v == null ? null : Long.valueOf(String.valueOf(v)); }
    private String asString(Object v) { return v == null ? null : String.valueOf(v); }
    private Double asDouble(Object v) { return v == null ? null : Double.valueOf(String.valueOf(v)); }
    private Integer asInteger(Object v) { return v == null ? null : Integer.valueOf(String.valueOf(v)); }
}
