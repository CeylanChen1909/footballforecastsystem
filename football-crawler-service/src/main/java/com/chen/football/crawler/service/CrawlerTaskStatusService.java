package com.chen.football.crawler.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 爬虫任务状态服务
 */
@Service
public class CrawlerTaskStatusService {

    private final Map<String, TaskStatus> taskStatusMap = new ConcurrentHashMap<>();

    public boolean tryStart(String taskName) {
        TaskStatus current = taskStatusMap.compute(taskName, (key, status) -> {
            if (status != null && status.isRunning()) {
                return status;
            }
            TaskStatus next = status == null ? new TaskStatus() : status;
            next.setTaskName(taskName);
            next.setRunning(true);
            next.setStartedAt(LocalDateTime.now());
            next.setFinishedAt(null);
            next.setLastError(null);
            return next;
        });
        return current != null && current.isRunning() && current.getStartedAt() != null;
    }

    public void start(String taskName) {
        tryStart(taskName);
    }

    public void success(String taskName, long durationMs, int processedCount) {
        taskStatusMap.compute(taskName, (key, status) -> {
            TaskStatus current = status == null ? new TaskStatus() : status;
            current.setTaskName(taskName);
            current.setRunning(false);
            current.setFinishedAt(LocalDateTime.now());
            current.setDurationMs(durationMs);
            current.setProcessedCount(processedCount);
            current.setLastError(null);
            current.setLastSuccessAt(LocalDateTime.now());
            return current;
        });
    }

    public void failure(String taskName, long durationMs, Throwable error) {
        taskStatusMap.compute(taskName, (key, status) -> {
            TaskStatus current = status == null ? new TaskStatus() : status;
            current.setTaskName(taskName);
            current.setRunning(false);
            current.setFinishedAt(LocalDateTime.now());
            current.setDurationMs(durationMs);
            current.setLastError(error == null ? null : error.getMessage());
            current.setLastFailedAt(LocalDateTime.now());
            return current;
        });
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", buildSummary());
        result.put("tasks", taskStatusMap.values().stream().map(TaskStatus::toMap).toList());
        return result;
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
