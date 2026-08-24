package com.chen.football.crawler.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listTeams(Long userId) {
        String sql = "SELECT id, user_id, team_id, team_name, team_logo, league_name, created_at FROM t_user_favorite_team WHERE user_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapTeam(rs), userId);
    }

    public void addTeam(Long userId, String teamId, String teamName, String teamLogo, String leagueName) {
        String sql = "INSERT INTO t_user_favorite_team (user_id, team_id, team_name, team_logo, league_name, created_at) VALUES (?, ?, ?, ?, ?, NOW()) "
                + "ON DUPLICATE KEY UPDATE team_name = VALUES(team_name), team_logo = VALUES(team_logo), league_name = VALUES(league_name)";
        jdbcTemplate.update(sql, userId, teamId, teamName == null ? "" : teamName,
                teamLogo == null ? "" : teamLogo, leagueName == null ? "" : leagueName);
    }

    public void removeTeam(Long userId, String teamId) {
        jdbcTemplate.update("DELETE FROM t_user_favorite_team WHERE user_id = ? AND team_id = ?", userId, teamId);
    }

    public boolean existsTeam(Long userId, String teamId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_user_favorite_team WHERE user_id = ? AND team_id = ?", Integer.class, userId, teamId);
        return count != null && count > 0;
    }

    /** Returns follower counts keyed by both team id and stored team name. */
    public Map<String, Integer> followerCounts(Collection<String> teamIds, Collection<String> teamNames) {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        if (teamIds != null) teamIds.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).forEach(identities::add);
        if (teamNames != null) teamNames.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).forEach(identities::add);
        if (identities.isEmpty()) return Map.of();

        String placeholders = String.join(",", Collections.nCopies(identities.size(), "?"));
        String sql = "SELECT team_id, team_name, COUNT(*) AS follower_count FROM t_user_favorite_team "
                + "WHERE team_id IN (" + placeholders + ") OR team_name IN (" + placeholders + ") "
                + "GROUP BY team_id, team_name";
        List<Object> args = new ArrayList<>(identities);
        args.addAll(identities);
        Map<String, Integer> result = new HashMap<>();
        try {
            jdbcTemplate.query(sql, args.toArray(), rs -> {
                int count = rs.getInt("follower_count");
                putCount(result, count, rs.getString("team_id"), rs.getString("team_name"));
            });
        } catch (RuntimeException ignored) {
            // Popularity is optional; a temporary table/database issue must
            // never make the match-focus rail unavailable.
        }
        return result;
    }

    /** Reads the small aggregate table once for the rolling focus snapshot. */
    public Map<String, Integer> allFollowerCounts() {
        Map<String, Integer> result = new HashMap<>();
        try {
            jdbcTemplate.query("SELECT team_id, team_name, COUNT(*) AS follower_count FROM t_user_favorite_team GROUP BY team_id, team_name", rs -> {
                int count = rs.getInt("follower_count");
                putCount(result, count, rs.getString("team_id"), rs.getString("team_name"));
            });
        } catch (RuntimeException ignored) {
            // Optional ranking signal; return an empty snapshot on failure.
        }
        return result;
    }

    private void putCount(Map<String, Integer> result, int count, String... values) {
        Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).distinct()
                .forEach(key -> result.merge(key, count, Integer::sum));
    }

    public List<Map<String, Object>> listMatches(Long userId) {
        String sql = "SELECT id, user_id, fixture_id, home_team_name, away_team_name, league_name, match_time, created_at FROM t_user_favorite_match WHERE user_id = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapMatch(rs), userId);
    }

    public void addMatch(Long userId, String fixtureId, String matchLabel, String leagueName, String matchTimeText) {
        String home = "";
        String away = "";
        if (StringUtils.hasText(matchLabel) && matchLabel.contains(" vs ")) {
            String[] arr = matchLabel.split(" vs ", 2);
            home = arr[0];
            away = arr.length > 1 ? arr[1] : "";
        }
        String sql = "INSERT INTO t_user_favorite_match (user_id, fixture_id, home_team_name, away_team_name, league_name, match_time, created_at) VALUES (?, ?, ?, ?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE home_team_name = VALUES(home_team_name), away_team_name = VALUES(away_team_name), league_name = VALUES(league_name), match_time = VALUES(match_time)";
        jdbcTemplate.update(sql, userId, fixtureId, home, away, leagueName == null ? "" : leagueName, parseMatchTime(matchTimeText));
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

    private LocalDateTime parseMatchTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return OffsetDateTime.parse(value.trim()).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime(); }
        catch (DateTimeParseException ignored) {
            try { return LocalDateTime.parse(value.trim().replace('T', ' '), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
            catch (DateTimeParseException ignoredAgain) { return null; }
        }
    }
}
