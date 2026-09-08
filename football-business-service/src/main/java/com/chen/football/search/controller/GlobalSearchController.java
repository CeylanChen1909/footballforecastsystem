package com.chen.football.search.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerTeam;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.mapper.CrawlerTeamMapper;
import com.chen.football.news.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class GlobalSearchController {
    private final CrawlerMatchMapper matchMapper;
    private final CrawlerTeamMapper teamMapper;
    private final NewsService newsService;

    @GetMapping
    public ApiResponse<Map<String, Object>> search(@RequestParam(name = "q") String keyword,
                                                   @RequestParam(name = "limit", defaultValue = "8") int limit) {
        String q = keyword == null ? "" : keyword.trim();
        int safe = Math.max(1, Math.min(limit, 20));
        if (q.isBlank()) {
            return ApiResponse.ok(Map.of(
                    "matches", List.of(),
                    "teams", List.of(),
                    "leagues", List.of(),
                    "articles", List.of()));
        }

        List<CrawlerMatch> matchRows = matchMapper.searchMatches(q);
        List<Map<String, Object>> matches = matchRows.stream().limit(safe).map(this::match).toList();

        List<Map<String, Object>> teams = teamMapper.searchByName(q).stream()
                .limit(safe)
                .map(this::team)
                .toList();

        // Also surface teams mentioned in match hits when dedicated team rows are thin.
        if (teams.isEmpty()) {
            Set<String> seen = new LinkedHashSet<>();
            List<Map<String, Object>> derived = new ArrayList<>();
            for (CrawlerMatch row : matchRows) {
                addDerivedTeam(derived, seen, row.getHomeTeamId(), row.getHomeTeamName(), row.getLeagueName(), safe);
                addDerivedTeam(derived, seen, row.getAwayTeamId(), row.getAwayTeamName(), row.getLeagueName(), safe);
                if (derived.size() >= safe) break;
            }
            teams = derived;
        }

        List<Map<String, Object>> leagues = distinctLeagues(matchRows, q, safe);
        var articles = newsService.getFeedPage(1, safe, null, q, "latest").items();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matches", matches);
        payload.put("teams", teams);
        payload.put("leagues", leagues);
        payload.put("articles", articles);
        return ApiResponse.ok(payload);
    }

    private void addDerivedTeam(List<Map<String, Object>> out, Set<String> seen,
                                String teamId, String teamName, String leagueName, int limit) {
        if (out.size() >= limit || teamName == null || teamName.isBlank()) return;
        String key = teamName.trim().toLowerCase(Locale.ROOT);
        if (!seen.add(key)) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", teamId == null || teamId.isBlank() ? 0L : teamId);
        row.put("name", teamName);
        row.put("league", leagueName == null ? "" : leagueName);
        row.put("logo", "");
        out.add(row);
    }

    private List<Map<String, Object>> distinctLeagues(List<CrawlerMatch> matches, String keyword, int limit) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> leagues = new ArrayList<>();
        for (CrawlerMatch match : matches) {
            String league = match.getLeagueName();
            if (league == null || league.isBlank()) continue;
            if (!league.toLowerCase(Locale.ROOT).contains(needle) && !needle.contains(league.toLowerCase(Locale.ROOT))) {
                // Keep leagues from match hits even when the query was a team name,
                // but prefer direct league-name hits first.
                if (!seen.isEmpty() && leagues.size() >= Math.min(3, limit)) continue;
            }
            if (!seen.add(league)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", league);
            row.put("matchCount", matches.stream().filter(m -> league.equals(m.getLeagueName())).count());
            leagues.add(row);
            if (leagues.size() >= limit) break;
        }
        // Prefer leagues whose name matches the query.
        leagues.sort((a, b) -> {
            boolean aHit = String.valueOf(a.get("name")).toLowerCase(Locale.ROOT).contains(needle);
            boolean bHit = String.valueOf(b.get("name")).toLowerCase(Locale.ROOT).contains(needle);
            if (aHit == bHit) return 0;
            return aHit ? -1 : 1;
        });
        return leagues.size() > limit ? leagues.subList(0, limit) : leagues;
    }

    private Map<String, Object> match(CrawlerMatch m) {
        Map<String, Object> row = new LinkedHashMap<>();
        Long publicId = m.getFixtureId() != null && m.getFixtureId() > 0 ? m.getFixtureId() : m.getId();
        row.put("fixtureId", publicId);
        row.put("matchId", publicId);
        row.put("id", publicId);
        row.put("homeTeamName", m.getHomeTeamName());
        row.put("awayTeamName", m.getAwayTeamName());
        row.put("leagueName", m.getLeagueName());
        row.put("matchTime", m.getMatchTime());
        return row;
    }

    private Map<String, Object> team(CrawlerTeam t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", t.getId() == null ? 0L : t.getId());
        row.put("name", t.getName() == null ? "" : t.getName());
        row.put("league", t.getLeagueName() == null ? "" : t.getLeagueName());
        row.put("logo", t.getLogo() == null ? "" : t.getLogo());
        row.put("country", t.getCountry() == null ? "" : t.getCountry());
        return row;
    }
}
