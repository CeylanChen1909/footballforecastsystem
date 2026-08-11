package com.chen.football.crawler.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listTeams(Long userId) {
        String sql = "SELECT id, user_id, team_id, team_name, team_logo, league_name, created_at FROM t_user_favorite_team WHERE user_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapTeam(rs), userId);
    }

    public void addTeam(Long userId, String teamId, String teamName) {
        String sql = "INSERT INTO t_user_favorite_team (user_id, team_id, team_name, created_at) VALUES (?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE team_name = VALUES(team_name)";
        jdbcTemplate.update(sql, userId, teamId, teamName == null ? "" : teamName);
    }

    public void removeTeam(Long userId, String teamId) {
        jdbcTemplate.update("DELETE FROM t_user_favorite_team WHERE user_id = ? AND team_id = ?", userId, teamId);
    }

    public boolean existsTeam(Long userId, String teamId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_user_favorite_team WHERE user_id = ? AND team_id = ?", Integer.class, userId, teamId);
        return count != null && count > 0;
    }

    public List<Map<String, Object>> listMatches(Long userId) {
        String sql = "SELECT id, user_id, fixture_id, home_team_name, away_team_name, league_name, match_time, created_at FROM t_user_favorite_match WHERE user_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapMatch(rs), userId);
    }

    public void addMatch(Long userId, String fixtureId, String matchLabel) {
        String home = "";
        String away = "";
        if (StringUtils.hasText(matchLabel) && matchLabel.contains(" vs ")) {
            String[] arr = matchLabel.split(" vs ", 2);
            home = arr[0];
            away = arr.length > 1 ? arr[1] : "";
        }
        String sql = "INSERT INTO t_user_favorite_match (user_id, fixture_id, home_team_name, away_team_name, created_at) VALUES (?, ?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE home_team_name = VALUES(home_team_name), away_team_name = VALUES(away_team_name)";
        jdbcTemplate.update(sql, userId, fixtureId, home, away);
    }

    public void removeMatch(Long userId, String fixtureId) {
        jdbcTemplate.update("DELETE FROM t_user_favorite_match WHERE user_id = ? AND fixture_id = ?", userId, fixtureId);
    }

    public boolean existsMatch(Long userId, String fixtureId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_user_favorite_match WHERE user_id = ? AND fixture_id = ?", Integer.class, userId, fixtureId);
        return count != null && count > 0;
    }

    private Map<String, Object> mapTeam(ResultSet rs) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getLong("id"));
        item.put("userId", rs.getLong("user_id"));
        item.put("teamId", rs.getString("team_id"));
        item.put("teamName", rs.getString("team_name"));
        item.put("teamLogo", rs.getString("team_logo"));
        item.put("leagueName", rs.getString("league_name"));
        item.put("createdAt", rs.getTimestamp("created_at"));
        return item;
    }

    private Map<String, Object> mapMatch(ResultSet rs) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getLong("id"));
        item.put("userId", rs.getLong("user_id"));
        item.put("fixtureId", rs.getString("fixture_id"));
        item.put("homeTeamName", rs.getString("home_team_name"));
        item.put("awayTeamName", rs.getString("away_team_name"));
        item.put("leagueName", rs.getString("league_name"));
        item.put("matchTime", rs.getTimestamp("match_time"));
        item.put("createdAt", rs.getTimestamp("created_at"));
        return item;
    }
}
