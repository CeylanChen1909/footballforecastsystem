package com.chen.football.crawler.controller;

import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.service.MatchCrawlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 爬虫统计接口
 */
@RestController
@RequestMapping("/api/crawler/stats")
@RequiredArgsConstructor
public class CrawlerStatsController {

    private final MatchCrawlerService matchCrawlerService;
    private final CrawlerMatchMapper crawlerMatchMapper;

    @GetMapping("/matches")
    public Map<String, Object> matches(@RequestParam(name = "date", required = false) String date) {
        Date targetDate = parseDateOrToday(date);
        List<CrawlerMatch> todayMatches = crawlerMatchMapper.findByDate(targetDate);
        List<CrawlerMatch> liveMatches = crawlerMatchMapper.findLiveMatches();
        List<CrawlerMatch> upcomingMatches = crawlerMatchMapper.findUpcomingMatches();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", new SimpleDateFormat("yyyy-MM-dd").format(targetDate));
        data.put("count", todayMatches.size());
        data.put("liveCount", liveMatches.size());
        data.put("upcomingCount", upcomingMatches.size());
        data.put("matches", todayMatches.stream().map(this::toBriefMap).toList());
        data.put("sourceCount", Map.of(
                "db", todayMatches.size(),
                "live", liveMatches.size(),
                "upcoming", upcomingMatches.size()
        ));
        return Map.of("success", true, "message", "获取成功", "data", data);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Date today = new Date();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", new SimpleDateFormat("yyyy-MM-dd").format(today));
        data.put("dbCount", crawlerMatchMapper.findByDate(today).size());
        data.put("upcomingCount", crawlerMatchMapper.findUpcomingMatches().size());
        data.put("liveCount", crawlerMatchMapper.findLiveMatches().size());
        data.put("crawledCount", matchCrawlerService.countMatchesByDate(today));
        return Map.of("success", true, "message", "获取成功", "data", data);
    }

    @GetMapping("/today")
    public Map<String, Object> todayStats() {
        Date today = new Date();
        int dbCount = crawlerMatchMapper.findByDate(today).size();
        int crawledCount = matchCrawlerService.countMatchesByDate(today);
        return Map.of(
                "success", true,
                "message", "获取成功",
                "data", Map.of(
                        "date", new SimpleDateFormat("yyyy-MM-dd").format(today),
                        "dbCount", dbCount,
                        "crawledCount", crawledCount
                )
        );
    }

    private Map<String, Object> toBriefMap(CrawlerMatch match) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", match.getId());
        item.put("leagueName", match.getLeagueName());
        item.put("homeTeamName", match.getHomeTeamName());
        item.put("awayTeamName", match.getAwayTeamName());
        item.put("status", match.getStatus());
        item.put("matchTime", match.getMatchTime());
        return item;
    }

    private Date parseDateOrToday(String date) {
        if (date == null || date.isBlank()) {
            return new Date();
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(date);
        } catch (Exception e) {
            return new Date();
        }
    }
}
