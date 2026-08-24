package com.chen.football.crawler.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.source.DataSourceManager;
import com.chen.football.news.service.AdminAuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController("crawlerMatchController")
@RequestMapping("/api/crawler/matches")
@RequiredArgsConstructor
public class MatchController {

    private final CrawlerMatchMapper crawlerMatchMapper;
    private final AdminAuditLogService auditLogService;
    private final DataSourceManager dataSourceManager;

    @GetMapping
    public ApiResponse<List<CrawlerMatch>> list(@RequestParam(name = "keyword", required = false) String keyword,
                                                @RequestParam(name = "status", required = false) String status,
                                                @RequestParam(name = "date", required = false) String date,
                                                @RequestParam(name = "limit", defaultValue = "100") Integer limit) {
        var query = Wrappers.<CrawlerMatch>lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(CrawlerMatch::getLeagueName, keyword)
                    .or().like(CrawlerMatch::getHomeTeamName, keyword)
                    .or().like(CrawlerMatch::getAwayTeamName, keyword));
        }
        if (status != null && !status.isBlank()) {
            query.eq(CrawlerMatch::getStatus, status);
        }
        applySourceFilter(query);
        applyDateFilter(query, date);
        int safeLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        query.orderByDesc(CrawlerMatch::getMatchTime).last("LIMIT " + safeLimit);
        return ApiResponse.ok(crawlerMatchMapper.selectList(query));
    }

    @GetMapping("/page")
    public ApiResponse<Map<String, Object>> page(@RequestParam(name = "page", defaultValue = "1") Integer page,
                                                  @RequestParam(name = "size", defaultValue = "20") Integer size,
                                                  @RequestParam(name = "keyword", required = false) String keyword,
                                                  @RequestParam(name = "status", required = false) String status,
                                                  @RequestParam(name = "date", required = false) String date) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        var query = Wrappers.<CrawlerMatch>lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(CrawlerMatch::getLeagueName, keyword)
                    .or().like(CrawlerMatch::getHomeTeamName, keyword)
                    .or().like(CrawlerMatch::getAwayTeamName, keyword));
        }
        if (status != null && !status.isBlank()) {
            query.eq(CrawlerMatch::getStatus, status);
        }
        applySourceFilter(query);
        applyDateFilter(query, date);
        long total = crawlerMatchMapper.selectCount(query);
        query.orderByDesc(CrawlerMatch::getMatchTime).last("LIMIT " + ((safePage - 1) * safeSize) + "," + safeSize);
        List<CrawlerMatch> records = crawlerMatchMapper.selectList(query);
        Map<String, Object> data = new HashMap<>();
        data.put("items", records);
        data.put("total", total);
        data.put("page", safePage);
        data.put("size", safeSize);
        return ApiResponse.ok(data);
    }

    private void applyDateFilter(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrawlerMatch> query, String date) {
        if (date == null || date.isBlank()) return;
        try {
            LocalDate day = LocalDate.parse(date.trim());
            query.ge(CrawlerMatch::getMatchTime, day.atStartOfDay())
                    .lt(CrawlerMatch::getMatchTime, day.plusDays(1).atStartOfDay());
        } catch (java.time.format.DateTimeParseException ignored) {
            // 非法日期按未筛选处理，避免后台列表直接 500。
        }
    }

    private void applySourceFilter(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrawlerMatch> query) {
        if (dataSourceManager.isPrimaryOnly()) {
            query.eq(CrawlerMatch::getSource, dataSourceManager.primarySource());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<CrawlerMatch> detail(@PathVariable(name = "id") Long id) {
        CrawlerMatch match = crawlerMatchMapper.selectById(id);
        if (match != null && dataSourceManager.isPrimaryOnly()
                && !dataSourceManager.primarySource().equalsIgnoreCase(match.getSource())) {
            match = null;
        }
        return ApiResponse.ok(match);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody CrawlerMatch match) {
        AdminGuard.requireAdmin();
        normalizeEditableMatch(match, LocalDateTime.now());
        crawlerMatchMapper.insert(match);
        auditLogService.write(UserContext.getUserId(), UserContext.getUsername(), "CRAWLER_MATCH", "CREATE", "crawler_matches", String.valueOf(match.getFixtureId()), match.getHomeTeamName() + " vs " + match.getAwayTeamName(), "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true, "id", match.getId()));
    }

    @PutMapping("/{id}/edit")
    public ApiResponse<Map<String, Object>> edit(@PathVariable(name = "id") Long id,
                                                 @RequestBody CrawlerMatch match) {
        AdminGuard.requireAdmin();
        CrawlerMatch existing = crawlerMatchMapper.selectById(id);
        match.setId(id);
        if (existing != null) {
            match.setCreatedAt(existing.getCreatedAt());
        }
        normalizeEditableMatch(match, LocalDateTime.now());
        crawlerMatchMapper.updateById(match);
        auditLogService.write(UserContext.getUserId(), UserContext.getUsername(), "CRAWLER_MATCH", "UPDATE", "crawler_matches", String.valueOf(id), match.getHomeTeamName() + " vs " + match.getAwayTeamName(), "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true, "id", id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable(name = "id") Long id) {
        AdminGuard.requireAdmin();
        crawlerMatchMapper.deleteById(id);
        auditLogService.write(UserContext.getUserId(), UserContext.getUsername(), "CRAWLER_MATCH", "DELETE", "crawler_matches", String.valueOf(id), null, "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true));
    }

    private void normalizeEditableMatch(CrawlerMatch match, LocalDateTime now) {
        if (match.getFixtureId() == null) {
            match.setFixtureId(System.currentTimeMillis());
        }
        if (match.getExternalMatchId() == null || match.getExternalMatchId().isBlank()) {
            match.setExternalMatchId(String.valueOf(match.getFixtureId()));
        }
        if (match.getSource() == null || match.getSource().isBlank()) {
            match.setSource("admin");
        }
        if (match.getStatus() == null || match.getStatus().isBlank()) {
            match.setStatus("NS");
        }
        if (match.getMatchTime() == null) {
            match.setMatchTime(now);
        }
        if (match.getCreatedAt() == null) {
            match.setCreatedAt(now);
        }
        match.setUpdatedAt(now);
    }
}
