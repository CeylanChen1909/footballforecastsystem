package com.chen.football.crawler.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/crawler/matches")
@RequiredArgsConstructor
public class MatchController {

    private final CrawlerMatchMapper crawlerMatchMapper;

    @GetMapping
    public ApiResponse<List<CrawlerMatch>> list(@RequestParam(name = "keyword", required = false) String keyword,
                                                @RequestParam(name = "status", required = false) String status,
                                                @RequestParam(name = "limit", defaultValue = "100") Integer limit) {
        var query = Wrappers.<CrawlerMatch>lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            query.like(CrawlerMatch::getLeagueName, keyword)
                 .or().like(CrawlerMatch::getHomeTeamName, keyword)
                 .or().like(CrawlerMatch::getAwayTeamName, keyword);
        }
        if (status != null && !status.isBlank()) {
            query.eq(CrawlerMatch::getStatus, status);
        }
        query.orderByDesc(CrawlerMatch::getMatchTime).last("LIMIT " + Math.max(1, Math.min(limit, 500)));
        return ApiResponse.ok(crawlerMatchMapper.selectList(query));
    }

    @GetMapping("/page")
    public ApiResponse<Map<String, Object>> page(@RequestParam(name = "page", defaultValue = "1") Integer page,
                                                  @RequestParam(name = "size", defaultValue = "20") Integer size,
                                                  @RequestParam(name = "keyword", required = false) String keyword,
                                                  @RequestParam(name = "status", required = false) String status) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        var query = Wrappers.<CrawlerMatch>lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            query.like(CrawlerMatch::getLeagueName, keyword)
                 .or().like(CrawlerMatch::getHomeTeamName, keyword)
                 .or().like(CrawlerMatch::getAwayTeamName, keyword);
        }
        if (status != null && !status.isBlank()) {
            query.eq(CrawlerMatch::getStatus, status);
        }
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

    @GetMapping("/{id}")
    public ApiResponse<CrawlerMatch> detail(@PathVariable(name = "id") Long id) {
        return ApiResponse.ok(crawlerMatchMapper.selectById(id));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody CrawlerMatch match) {
        normalizeEditableMatch(match, LocalDateTime.now());
        crawlerMatchMapper.insert(match);
        return ApiResponse.ok(Map.of("ok", true, "id", match.getId()));
    }

    @PutMapping("/{id}/edit")
    public ApiResponse<Map<String, Object>> edit(@PathVariable(name = "id") Long id,
                                                 @RequestBody CrawlerMatch match) {
        CrawlerMatch existing = crawlerMatchMapper.selectById(id);
        match.setId(id);
        if (existing != null) {
            match.setCreatedAt(existing.getCreatedAt());
        }
        normalizeEditableMatch(match, LocalDateTime.now());
        crawlerMatchMapper.updateById(match);
        return ApiResponse.ok(Map.of("ok", true, "id", id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable(name = "id") Long id) {
        crawlerMatchMapper.deleteById(id);
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
