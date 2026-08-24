package com.chen.football.crawler.service;

import com.chen.football.common.service.DistributedLockService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, resumable historical match backfill.
 *
 * The primary crawler remains the only writer. This service merely walks a
 * date range through the same idempotent crawl path, records a checkpoint, and
 * can therefore be restarted after a provider timeout without duplicating rows.
 */
@Slf4j
@Service
public class HistoricalBackfillService {
    private static final String TASK = "historical-backfill";
    private static final String LOCK = "crawler:historical-backfill";
    private static final Duration LOCK_TTL = Duration.ofHours(12);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbcTemplate;
    private final MatchCrawlerService matchCrawlerService;
    private final DistributedLockService lockService;
    private final CrawlerTaskStatusService taskStatusService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "historical-backfill");
        thread.setDaemon(true);
        return thread;
    });

    public HistoricalBackfillService(JdbcTemplate jdbcTemplate,
                                     MatchCrawlerService matchCrawlerService,
                                     DistributedLockService lockService,
                                     CrawlerTaskStatusService taskStatusService) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchCrawlerService = matchCrawlerService;
        this.lockService = lockService;
        this.taskStatusService = taskStatusService;
    }

    @PostConstruct
    void ensureTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_crawler_backfill_job (" +
                "job_name VARCHAR(64) NOT NULL PRIMARY KEY, from_date DATE NOT NULL, to_date DATE NOT NULL, " +
                "next_date DATE NOT NULL, status VARCHAR(16) NOT NULL, processed_days INT NOT NULL DEFAULT 0, " +
                "processed_matches INT NOT NULL DEFAULT 0, last_error VARCHAR(512), updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP " +
                "ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public Map<String, Object> start(LocalDate from, LocalDate to, int maxDays, boolean resume) {
        if (from == null || to == null || to.isBefore(from)) {
            return Map.of("accepted", false, "status", "INVALID", "message", "日期范围无效");
        }
        int safeDays = Math.max(1, Math.min(maxDays, 365));
        if (from.plusDays(safeDays - 1L).isBefore(to)) {
            to = from.plusDays(safeDays - 1L);
        }
        if (!taskStatusService.tryStart(TASK)) {
            return Map.of("accepted", false, "status", "RUNNING", "message", "历史回填任务正在运行");
        }
        LocalDate start = from;
        LocalDate end = to;
        String token = lockService.tryLock(LOCK, LOCK_TTL);
        if (token == null) {
            taskStatusService.failure(TASK, 0, new IllegalStateException("历史回填互斥锁不可用"));
            return Map.of("accepted", false, "status", "LOCKED", "message", "无法获取历史回填互斥锁，请确认 Redis 正常");
        }
        try {
            LocalDate next = resume ? loadNextDate(start, end) : start;
            saveJob(start, end, next, "RUNNING", 0, 0, null);
            String lockToken = token;
            executor.submit(() -> run(start, end, next, lockToken));
            return Map.of("accepted", true, "status", "QUEUED", "from", start, "to", end, "nextDate", next,
                    "message", "历史回填已加入队列");
        } catch (RuntimeException ex) {
            lockService.unlock(LOCK, token);
            taskStatusService.failure(TASK, 0, ex);
            throw ex;
        }
    }

    public Map<String, Object> status() {
        try {
            var rows = jdbcTemplate.queryForList("SELECT job_name,from_date,to_date,next_date,status,processed_days,processed_matches,last_error,updated_at FROM t_crawler_backfill_job WHERE job_name=?", TASK);
            if (rows.isEmpty()) return Map.of("task", TASK, "status", "IDLE");
            Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
            result.put("task", TASK);
            return result;
        } catch (Exception ex) {
            return Map.of("task", TASK, "status", "UNKNOWN", "error", ex.getMessage());
        }
    }

    private void run(LocalDate from, LocalDate to, LocalDate next, String token) {
        long started = System.currentTimeMillis();
        int days = 0, matches = 0;
        LocalDate current = next;
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "historical-backfill-lock-renewer");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledFuture<?> renewal = renewer.scheduleAtFixedRate(() -> {
            if (!lockService.renew(LOCK, token, LOCK_TTL)) {
                log.warn("历史回填锁续租失败，任务继续执行但禁止启动新的回填任务");
            }
        }, 1, 1, TimeUnit.HOURS);
        try {
            for (LocalDate date = next; !date.isAfter(to); date = date.plusDays(1)) {
                current = date;
                Date day = Date.from(date.atStartOfDay(BUSINESS_ZONE).toInstant());
                int count = matchCrawlerService.crawlMatchesByDate(day).size();
                matches += count;
                days++;
                saveJob(from, to, date.plusDays(1), date.plusDays(1).isAfter(to) ? "SUCCESS" : "RUNNING", days, matches, null);
            }
            taskStatusService.success(TASK, System.currentTimeMillis() - started, matches);
        } catch (Exception ex) {
            saveJob(from, to, current, "FAILED", days, matches, truncate(ex.getMessage()));
            taskStatusService.failure(TASK, System.currentTimeMillis() - started, ex);
            log.warn("历史比赛回填失败: {}", ex.getMessage());
        } finally {
            renewal.cancel(false);
            renewer.shutdownNow();
            lockService.unlock(LOCK, token);
        }
    }

    private LocalDate loadNextDate(LocalDate fallback, LocalDate end) {
        try {
            var rows = jdbcTemplate.queryForList("SELECT next_date,status FROM t_crawler_backfill_job WHERE job_name=?", TASK);
            if (!rows.isEmpty() && "FAILED".equals(String.valueOf(rows.get(0).get("status")))) {
                Object value = rows.get(0).get("next_date");
                if (value instanceof java.sql.Date date) return date.toLocalDate().isAfter(end) ? fallback : date.toLocalDate();
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private void saveJob(LocalDate from, LocalDate to, LocalDate next, String status, int days, int matches, String error) {
        jdbcTemplate.update("INSERT INTO t_crawler_backfill_job(job_name,from_date,to_date,next_date,status,processed_days,processed_matches,last_error) VALUES (?,?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE from_date=VALUES(from_date),to_date=VALUES(to_date),next_date=VALUES(next_date),status=VALUES(status),processed_days=VALUES(processed_days),processed_matches=VALUES(processed_matches),last_error=VALUES(last_error),updated_at=CURRENT_TIMESTAMP",
                TASK, from, to, next, status, days, matches, error);
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
