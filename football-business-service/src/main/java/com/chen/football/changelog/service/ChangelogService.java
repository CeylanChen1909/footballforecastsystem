package com.chen.football.changelog.service;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.exception.BusinessException;
import com.chen.football.common.service.AdminAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChangelogService {
    private static final int MAX_LIMIT = 50;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> STATUSES = List.of("DRAFT", "PUBLISHED", "HIDDEN", "ARCHIVED");
    private static final List<String> TONES = List.of("account", "match", "prediction", "system");

    private final JdbcTemplate jdbcTemplate;
    private final AdminAuditService adminAuditService;

    public List<Map<String, Object>> listPublished(int limit) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 10 : limit, MAX_LIMIT));
        String sql = "SELECT id, title, summary, details_text, tag, tone, version_label, publish_at, updated_at "
                + "FROM t_changelog WHERE status = 'PUBLISHED' AND (publish_at IS NULL OR publish_at <= NOW()) "
                + "ORDER BY COALESCE(publish_at, updated_at) DESC, id DESC LIMIT ?";
        return jdbcTemplate.queryForList(sql, safeLimit).stream().map(this::toPublicView).toList();
    }

    public Map<String, Object> listAdmin(String keyword, String status, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        StringBuilder where = new StringBuilder(" FROM t_changelog WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (title LIKE ? OR summary LIKE ? OR tag LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        if (StringUtils.hasText(status)) {
            String safeStatus = normalizeStatus(status);
            where.append(" AND status = ?");
            args.add(safeStatus);
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        int offset = (safePage - 1) * safeSize;
        String dataSql = "SELECT id, title, summary, details_text, tag, tone, version_label, status, publish_at, "
                + "created_by_name, updated_by_name, created_at, updated_at" + where
                + " ORDER BY COALESCE(publish_at, updated_at) DESC, id DESC LIMIT " + offset + "," + safeSize;
        List<Map<String, Object>> items = jdbcTemplate.queryForList(dataSql, args.toArray()).stream().map(this::toAdminView).toList();
        return Map.of("items", items, "total", total == null ? 0L : total, "page", safePage, "size", safeSize);
    }

    public Map<String, Object> create(Map<String, Object> payload) {
        ChangelogData data = validate(payload);
        Long operatorId = UserContext.getUserId();
        String operatorName = UserContext.getUsername();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_changelog (title, summary, details_text, tag, tone, version_label, status, publish_at, created_by, created_by_name, updated_by, updated_by_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            bind(ps, data, operatorId, operatorName);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        String id = key == null ? "" : String.valueOf(key.longValue());
        adminAuditService.record("CHANGELOG", "CREATE", "t_changelog", id, data.title(), "SUCCESS");
        return Map.of("ok", true, "id", id);
    }

    public Map<String, Object> update(long id, Map<String, Object> payload) {
        ChangelogData data = validate(payload);
        int updated = jdbcTemplate.update(
                "UPDATE t_changelog SET title=?, summary=?, details_text=?, tag=?, tone=?, version_label=?, status=?, publish_at=?, updated_by=?, updated_by_name=?, updated_at=NOW() WHERE id=?",
                data.title(), data.summary(), data.detailsText(), data.tag(), data.tone(), data.versionLabel(), data.status(), data.publishAt(), UserContext.getUserId(), UserContext.getUsername(), id);
        if (updated == 0) return Map.of("ok", false, "message", "更新日志不存在");
        adminAuditService.record("CHANGELOG", "UPDATE", "t_changelog", String.valueOf(id), data.title(), "SUCCESS");
        return Map.of("ok", true, "id", id);
    }

    public Map<String, Object> updateStatus(long id, String status) {
        String safeStatus = normalizeStatus(status);
        int updated = jdbcTemplate.update("UPDATE t_changelog SET status=?, publish_at=CASE WHEN ?='PUBLISHED' AND publish_at IS NULL THEN NOW() ELSE publish_at END, updated_by=?, updated_by_name=?, updated_at=NOW() WHERE id=?",
                safeStatus, safeStatus, UserContext.getUserId(), UserContext.getUsername(), id);
        if (updated == 0) return Map.of("ok", false, "message", "更新日志不存在");
        adminAuditService.record("CHANGELOG", "STATUS", "t_changelog", String.valueOf(id), safeStatus, "SUCCESS");
        return Map.of("ok", true, "id", id, "status", safeStatus);
    }

    public Map<String, Object> delete(long id) {
        int deleted = jdbcTemplate.update("DELETE FROM t_changelog WHERE id=?", id);
        adminAuditService.record("CHANGELOG", "DELETE", "t_changelog", String.valueOf(id), "删除更新日志", deleted > 0 ? "SUCCESS" : "FAILED");
        return Map.of("ok", deleted > 0);
    }

    private ChangelogData validate(Map<String, Object> payload) {
        if (payload == null) throw new BusinessException("更新日志内容不能为空");
        String title = text(payload.get("title"));
        String summary = text(payload.get("summary"));
        if (!StringUtils.hasText(title)) throw new BusinessException("标题不能为空");
        if (title.length() > 160) throw new BusinessException("标题不能超过160个字符");
        if (!StringUtils.hasText(summary)) throw new BusinessException("摘要不能为空");
        if (summary.length() > 500) throw new BusinessException("摘要不能超过500个字符");
        String details = text(payload.get("detailsText"));
        if (details.length() > 5000) throw new BusinessException("详细内容不能超过5000个字符");
        String tag = text(payload.get("tag"));
        if (tag.isBlank()) tag = "赛程";
        if (tag.length() > 32) throw new BusinessException("标签不能超过32个字符");
        String tone = text(payload.get("tone"));
        if (!TONES.contains(tone)) tone = "system";
        String versionLabel = text(payload.get("versionLabel"));
        if (versionLabel.length() > 64) throw new BusinessException("版本标识不能超过64个字符");
        String status = normalizeStatus(text(payload.get("status")));
        LocalDateTime publishAt = parseDateTime(payload.get("publishAt"));
        return new ChangelogData(title, summary, details, tag, tone, versionLabel, status, publishAt);
    }

    private String normalizeStatus(String value) {
        String status = text(value).toUpperCase(Locale.ROOT);
        if (status.isBlank()) return "DRAFT";
        if (!STATUSES.contains(status)) throw new BusinessException("状态不合法");
        return status;
    }

    private LocalDateTime parseDateTime(Object value) {
        String raw = text(value);
        if (raw.isBlank()) return null;
        raw = raw.replace('T', ' ');
        try {
            return LocalDateTime.parse(raw, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]"));
        } catch (DateTimeParseException ignored) {
            try { return LocalDateTime.parse(raw + " 00:00", DATE_TIME); }
            catch (DateTimeParseException error) { throw new BusinessException("发布时间格式应为 yyyy-MM-dd HH:mm"); }
        }
    }

    private void bind(PreparedStatement ps, ChangelogData data, Long operatorId, String operatorName) throws java.sql.SQLException {
        ps.setString(1, data.title());
        ps.setString(2, data.summary());
        ps.setString(3, data.detailsText());
        ps.setString(4, data.tag());
        ps.setString(5, data.tone());
        ps.setString(6, data.versionLabel());
        ps.setString(7, data.status());
        if (data.publishAt() == null) ps.setTimestamp(8, null); else ps.setTimestamp(8, Timestamp.valueOf(data.publishAt()));
        if (operatorId == null) ps.setObject(9, null); else ps.setLong(9, operatorId);
        ps.setString(10, operatorName);
        if (operatorId == null) ps.setObject(11, null); else ps.setLong(11, operatorId);
        ps.setString(12, operatorName);
    }

    private Map<String, Object> toPublicView(Map<String, Object> row) {
        Map<String, Object> out = toCommonView(row);
        out.put("date", formatDate(row.get("publish_at"), row.get("updated_at")));
        out.remove("status");
        out.remove("createdAt");
        out.remove("updatedAt");
        out.remove("publishedAt");
        return out;
    }

    private Map<String, Object> toAdminView(Map<String, Object> row) {
        Map<String, Object> out = toCommonView(row);
        out.put("status", row.get("status"));
        out.put("publishAt", formatDate(row.get("publish_at"), null));
        out.put("createdAt", formatDate(row.get("created_at"), null));
        out.put("updatedAt", formatDate(row.get("updated_at"), null));
        out.put("createdByName", row.get("created_by_name"));
        out.put("updatedByName", row.get("updated_by_name"));
        out.put("detailsText", text(row.get("details_text")));
        return out;
    }

    private Map<String, Object> toCommonView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("title", row.get("title"));
        out.put("summary", row.get("summary"));
        out.put("tag", row.get("tag"));
        out.put("tone", row.get("tone"));
        out.put("versionLabel", row.get("version_label"));
        out.put("details", details(text(row.get("details_text"))));
        out.put("publishedAt", formatDate(row.get("publish_at"), null));
        return out;
    }

    private List<String> details(String value) {
        if (!StringUtils.hasText(value)) return Collections.emptyList();
        return value.lines().map(String::trim).filter(StringUtils::hasText).toList();
    }

    private String formatDate(Object primary, Object fallback) {
        Object value = primary == null ? fallback : primary;
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().format(DATE_TIME);
        return String.valueOf(value).replace('T', ' ');
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private record ChangelogData(String title, String summary, String detailsText, String tag, String tone,
                                 String versionLabel, String status, LocalDateTime publishAt) {}
}
