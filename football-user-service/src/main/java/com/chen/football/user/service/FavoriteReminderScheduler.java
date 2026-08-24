package com.chen.football.user.service;

import com.chen.football.common.service.DistributedLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Generates in-app reminders for matches followed by the current user. */
@Component
@RequiredArgsConstructor
@Slf4j
public class FavoriteReminderScheduler {
    private final JdbcTemplate jdbcTemplate;
    private final DistributedLockService distributedLockService;
    private volatile boolean tableReady;

    @PostConstruct
    void initialize() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        try { ensureNotificationTable(); }
        catch (Exception e) { log.warn("初始化站内通知表失败，提醒任务将在下一轮重试: {}", e.getMessage()); }
    }

    @Scheduled(initialDelayString = "${notifications.reminder-initial-delay-ms:45000}", fixedDelayString = "${notifications.reminder-interval-ms:300000}")
    public void generateReminders() {
        String lockToken;
        try {
            lockToken = distributedLockService.tryLock("notifications:favorite-reminders", Duration.ofMinutes(4));
        } catch (Exception ex) {
            // A scheduler must fail closed when Redis is unavailable; never
            // run duplicate reminder jobs without the distributed lease.
            log.warn("获取关注提醒互斥锁失败，本轮跳过: {}", ex.getMessage());
            return;
        }
        if (lockToken == null) {
            log.debug("已有其它实例执行关注提醒，本轮跳过");
            return;
        }
        try {
            ensureNotificationTable();
            createKickoffReminders();
            createPredictionReadyReminders();
        } catch (Exception e) {
            log.warn("生成关注提醒失败: {}", e.getMessage(), e);
        } finally {
            try { distributedLockService.unlock("notifications:favorite-reminders", lockToken); }
            catch (Exception ex) { log.warn("释放关注提醒互斥锁失败: {}", ex.getMessage()); }
        }
    }

    private void createKickoffReminders() {
        String sql = "SELECT f.user_id, f.fixture_id, COALESCE(NULLIF(f.home_team_name,''), m.home_team_name) home_name, "
                + "COALESCE(NULLIF(f.away_team_name,''), m.away_team_name) away_name, m.league_name, m.match_time "
                + "FROM t_user_favorite_match f JOIN crawler_matches m ON (m.id=f.fixture_id OR (m.fixture_id IS NOT NULL AND m.fixture_id=f.fixture_id AND NOT EXISTS (SELECT 1 FROM crawler_matches local_match WHERE local_match.id=f.fixture_id))) "
                + "WHERE m.match_time > NOW() AND m.match_time <= DATE_ADD(NOW(), INTERVAL 24 HOUR) "
                + "GROUP BY f.user_id, f.fixture_id, home_name, away_name, m.league_name, m.match_time";
        List<Map<String, Object>> rows;
        try { rows = jdbcTemplate.queryForList(sql); } catch (Exception ex) { log.warn("读取开赛提醒候选失败: {}", ex.getMessage()); return; }
        for (Map<String, Object> row : rows) {
            Long userId = number(row.get("user_id"));
            String fixtureId = text(row.get("fixture_id"));
            if (userId == null || fixtureId == null) continue;
            if (!remindersEnabled(userId)) continue;
            String link = "/prediction/" + fixtureId;
            if (alreadyNotified(userId, "MATCH_KICKOFF", link, 2)) continue;
            String home = valueOr(row.get("home_name"), "主队");
            String away = valueOr(row.get("away_name"), "客队");
            String league = valueOr(row.get("league_name"), "关注比赛");
            insertNotification(userId, "MATCH_KICKOFF", "关注比赛即将开赛", home + " vs " + away + " · " + league + " · " + formatTime(row.get("match_time")), link);
        }
    }

    private void createPredictionReadyReminders() {
        String sql = "SELECT f.user_id, f.fixture_id, COALESCE(NULLIF(f.home_team_name,''), m.home_team_name) home_name, "
                + "COALESCE(NULLIF(f.away_team_name,''), m.away_team_name) away_name "
                + "FROM t_user_favorite_match f JOIN crawler_matches m ON (m.id=f.fixture_id OR (m.fixture_id IS NOT NULL AND m.fixture_id=f.fixture_id AND NOT EXISTS (SELECT 1 FROM crawler_matches local_match WHERE local_match.id=f.fixture_id))) "
                + "JOIN t_match_prediction p ON p.fixture_id=(CASE WHEN m.fixture_id IS NULL THEN m.id ELSE m.fixture_id END) AND p.status='READY' "
                + "WHERE m.match_time > NOW() AND m.match_time <= DATE_ADD(NOW(), INTERVAL 7 DAY) "
                + "GROUP BY f.user_id, f.fixture_id, home_name, away_name";
        List<Map<String, Object>> rows;
        try { rows = jdbcTemplate.queryForList(sql); } catch (Exception ex) { log.warn("读取预测就绪提醒候选失败: {}", ex.getMessage()); return; }
        for (Map<String, Object> row : rows) {
            Long userId = number(row.get("user_id"));
            String fixtureId = text(row.get("fixture_id"));
            if (userId == null || fixtureId == null) continue;
            if (!remindersEnabled(userId)) continue;
            String link = "/prediction/" + fixtureId;
            if (alreadyNotified(userId, "PREDICTION_READY", link, 2)) continue;
            String home = valueOr(row.get("home_name"), "主队");
            String away = valueOr(row.get("away_name"), "客队");
            insertNotification(userId, "PREDICTION_READY", "关注比赛已有预测", home + " vs " + away + " 的统一预测已生成，可以查看证据与风险提示。", link);
        }
    }

    private boolean alreadyNotified(Long userId, String type, String link, int days) {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_user_notification WHERE user_id=? AND type=? AND link=? AND created_at >= DATE_SUB(NOW(), INTERVAL " + days + " DAY)", Integer.class, userId, type, link);
            return count != null && count > 0;
        } catch (Exception ex) {
            log.warn("检查提醒幂等键失败 userId={}, type={}: {}", userId, type, ex.getMessage());
            return true;
        }
    }

    private void insertNotification(Long userId, String type, String title, String body, String link) {
        // Keep the read-before-write check as a fast path, then repeat it in
        // the INSERT to close the common multi-instance race window.
        jdbcTemplate.update("INSERT INTO t_user_notification(user_id,type,title,body,link,created_at) "
                        + "SELECT ?,?,?,?,?,NOW() WHERE NOT EXISTS (SELECT 1 FROM t_user_notification WHERE user_id=? AND type=? AND link=? AND created_at >= DATE_SUB(NOW(), INTERVAL 2 DAY))",
                userId, type, truncate(title, 160), truncate(body, 1000), truncate(link, 255), userId, type, truncate(link, 255));
    }

    private boolean remindersEnabled(Long userId) {
        try {
            String preferences = jdbcTemplate.queryForObject("SELECT preferences_json FROM t_user_preference WHERE user_id=?", String.class, userId);
            return preferences == null || !preferences.replace(" ", "").contains("\"matchRemindersEnabled\":false");
            } catch (Exception ex) {
                // Existing accounts may not have a preference row; reminders are
                // enabled by default so the feature works immediately after login.
                return true;
        }
    }

    private void ensureNotificationTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        if (tableReady) return;
        synchronized (this) {
            if (tableReady) return;
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_user_notification (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL, title VARCHAR(160) NOT NULL, body VARCHAR(1000), link VARCHAR(255), read_at DATETIME NULL, created_at DATETIME NOT NULL, INDEX idx_user_notification_user_time (user_id, created_at), INDEX idx_user_notification_unread (user_id, read_at))");
            tableReady = true;
        }
    }

    private String formatTime(Object value) { return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime().toString().replace('T', ' ') : value == null ? "时间待定" : String.valueOf(value); }
    private String valueOr(Object value, String fallback) { String text = text(value); return text == null ? fallback : text; }
    private String text(Object value) { if (value == null) return null; String text = String.valueOf(value).trim(); return text.isBlank() ? null : text; }
    private Long number(Object value) { try { return value == null ? null : Long.valueOf(String.valueOf(value)); } catch (Exception ignored) { return null; } }
    private String truncate(String value, int limit) { return value == null ? null : value.substring(0, Math.min(limit, value.length())); }
}
