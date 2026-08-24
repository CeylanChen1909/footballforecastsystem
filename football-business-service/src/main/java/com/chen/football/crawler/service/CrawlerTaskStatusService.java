package com.chen.football.crawler.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 爬虫任务状态服务
 */
@Service
@Slf4j
public class CrawlerTaskStatusService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final Map<String, TaskStatus> taskStatusMap = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    /** 最近任务历史；生产环境可由外部日志/数据库接管，避免只保留当前状态。 */
    private final Deque<Map<String, Object>> history = new ArrayDeque<>();

    public CrawlerTaskStatusService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void loadHistory() {
        try {
            if (com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) {
                jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_crawler_task_run (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_name VARCHAR(64) NOT NULL, result VARCHAR(16) NOT NULL, duration_ms BIGINT NOT NULL DEFAULT 0, processed_count INT NOT NULL DEFAULT 0, error_message VARCHAR(1000), finished_at DATETIME NOT NULL, INDEX idx_crawler_task_finished (task_name, finished_at))");
            }
            var rows = jdbcTemplate.queryForList("SELECT task_name,result,duration_ms,processed_count,error_message,finished_at FROM t_crawler_task_run ORDER BY finished_at DESC LIMIT 100");
            synchronized (history) {
                rows.forEach(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("taskName", row.get("task_name")); item.put("result", row.get("result"));
                    item.put("durationMs", row.get("duration_ms")); item.put("processedCount", row.get("processed_count"));
                    item.put("error", row.get("error_message")); item.put("finishedAt", row.get("finished_at"));
                    history.addLast(item);
                    hydrateCurrentStatus(row);
                });
            }
        } catch (Exception ex) {
            // Do not make a healthy crawler look healthy after a restart when
            // its durable history table is missing or unreadable.
            log.error("无法加载爬虫任务持久化历史，当前仅保留内存状态: {}", ex.getMessage());
        }
    }

    public boolean tryStart(String taskName) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        TaskStatus current = taskStatusMap.compute(taskName, (key, status) -> {
            if (status != null && status.isRunning()) {
                return status;
            }
            acquired.set(true);
            TaskStatus next = status == null ? new TaskStatus() : status;
            next.setTaskName(taskName);
            next.setRunning(true);
            next.setStartedAt(LocalDateTime.now(BUSINESS_ZONE));
            next.setFinishedAt(null);
            next.setLastError(null);
            return next;
        });
        return acquired.get() && current != null && current.isRunning() && current.getStartedAt() != null;
    }

    public void start(String taskName) {
        tryStart(taskName);
    }

    public void success(String taskName, long durationMs, int processedCount) {
        taskStatusMap.compute(taskName, (key, status) -> {
            TaskStatus current = status == null ? new TaskStatus() : status;
            current.setTaskName(taskName);
            current.setRunning(false);
            current.setFinishedAt(LocalDateTime.now(BUSINESS_ZONE));
            current.setDurationMs(durationMs);
            current.setProcessedCount(processedCount);
            current.setLastError(null);
            current.setLastSuccessAt(LocalDateTime.now(BUSINESS_ZONE));
            return current;
        });
        recordHistory(taskName, "SUCCESS", durationMs, processedCount, null);
    }

    public void failure(String taskName, long durationMs, Throwable error) {
        taskStatusMap.compute(taskName, (key, status) -> {
            TaskStatus current = status == null ? new TaskStatus() : status;
            current.setTaskName(taskName);
            current.setRunning(false);
            current.setFinishedAt(LocalDateTime.now(BUSINESS_ZONE));
            current.setDurationMs(durationMs);
            current.setLastError(error == null ? null : error.getMessage());
            current.setLastFailedAt(LocalDateTime.now(BUSINESS_ZONE));
            return current;
        });
        recordHistory(taskName, "FAILED", durationMs, 0, error == null ? null : error.getMessage());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", buildSummary());
        result.put("tasks", taskStatusMap.values().stream().map(TaskStatus::toMap).toList());
        synchronized (history) {
            result.put("history", new ArrayList<>(history));
        }
        return result;
    }

    private void recordHistory(String taskName, String result, long durationMs, int processedCount, String error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskName", taskName);
        item.put("result", result);
        item.put("finishedAt", LocalDateTime.now(BUSINESS_ZONE));
        item.put("durationMs", durationMs);
        item.put("processedCount", processedCount);
        item.put("error", error);
        synchronized (history) {
            history.addFirst(item);
            while (history.size() > 100) history.removeLast();
        }
        try {
            jdbcTemplate.update("INSERT INTO t_crawler_task_run(task_name,result,duration_ms,processed_count,error_message,finished_at) VALUES (?,?,?,?,?,?)",
                    taskName, result, durationMs, processedCount, error, LocalDateTime.now(BUSINESS_ZONE));
        } catch (Exception ex) {
            log.error("爬虫任务历史写入失败 task={}, result={}: {}", taskName, result, ex.getMessage());
        }
    }

    private void hydrateCurrentStatus(Map<String, Object> row) {
        String taskName = String.valueOf(row.get("task_name"));
        if (taskName == null || taskName.isBlank() || "null".equals(taskName)) return;
        taskStatusMap.computeIfAbsent(taskName, ignored -> {
            TaskStatus status = new TaskStatus();
            status.setTaskName(taskName);
            status.setRunning(false);
            status.setDurationMs(number(row.get("duration_ms")));
            status.setProcessedCount((int) number(row.get("processed_count")));
            status.setFinishedAt(toLocalDateTime(row.get("finished_at")));
            String result = String.valueOf(row.get("result"));
            if ("SUCCESS".equalsIgnoreCase(result)) status.setLastSuccessAt(status.getFinishedAt());
            else status.setLastFailedAt(status.getFinishedAt());
            status.setLastError(row.get("error_message") == null ? null : String.valueOf(row.get("error_message")));
            return status;
        });
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return 0L; }
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    public Map<String, Object> getTask(String taskName) {
        TaskStatus status = taskStatusMap.get(taskName);
        return status == null ? Map.of() : status.toMap();
    }

    private Map<String, Object> buildSummary() {
        long total = taskStatusMap.size();
        long running = taskStatusMap.values().stream().filter(TaskStatus::isRunning).count();
        long success = taskStatusMap.values().stream().filter(v -> v.getLastSuccessAt() != null).count();
        long failed = taskStatusMap.values().stream().filter(v -> v.getLastFailedAt() != null).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("running", running);
        summary.put("success", success);
        summary.put("failed", failed);
        return summary;
    }

    @Data
    public static class TaskStatus {
        private String taskName;
        private boolean running;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private LocalDateTime lastSuccessAt;
        private LocalDateTime lastFailedAt;
        private long durationMs;
        private int processedCount;
        private String lastError;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("taskName", taskName);
            map.put("running", running);
            map.put("startedAt", startedAt);
            map.put("finishedAt", finishedAt);
            map.put("lastSuccessAt", lastSuccessAt);
            map.put("lastFailedAt", lastFailedAt);
            map.put("durationMs", durationMs);
            map.put("processedCount", processedCount);
            map.put("lastError", lastError);
            return map;
        }
    }
}
