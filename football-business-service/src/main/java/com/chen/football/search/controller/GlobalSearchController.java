package com.chen.football.search.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.news.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class GlobalSearchController {
    private final CrawlerMatchMapper matchMapper;
    private final NewsService newsService;

    @GetMapping
    public ApiResponse<Map<String, Object>> search(@RequestParam(name = "q") String keyword,
                                                   @RequestParam(name = "limit", defaultValue = "8") int limit) {
        String q = keyword == null ? "" : keyword.trim();
        int safe = Math.max(1, Math.min(limit, 20));
        if (q.isBlank()) return ApiResponse.ok(Map.of("matches", List.of(), "articles", List.of()));
        List<Map<String, Object>> matches = matchMapper.searchMatches(q).stream().limit(safe).map(this::match).toList();
        var articles = newsService.getFeedPage(1, safe, null, q, "latest").items();
        return ApiResponse.ok(Map.of("matches", matches, "articles", articles));
    }

    private Map<String, Object> match(CrawlerMatch m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fixtureId", m.getFixtureId()); row.put("homeTeamName", m.getHomeTeamName());
        row.put("awayTeamName", m.getAwayTeamName()); row.put("leagueName", m.getLeagueName());
        row.put("matchTime", m.getMatchTime());
        return row;
    }
}
