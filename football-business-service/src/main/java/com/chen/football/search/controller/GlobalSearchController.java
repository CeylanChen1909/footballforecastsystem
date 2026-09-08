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

        List<String> queries = expandAliases(q);
        List<CrawlerMatch> matchRows = new ArrayList<>();
        Set<Long> seenMatchIds = new LinkedHashSet<>();
        for (String query : queries) {
            for (CrawlerMatch row : matchMapper.searchMatches(query)) {
                Long key = row.getId() != null ? row.getId() : row.getFixtureId();
                if (key != null && !seenMatchIds.add(key)) continue;
                matchRows.add(row);
            }
        }
        List<Map<String, Object>> matches = matchRows.stream().limit(safe).map(this::match).toList();

        List<Map<String, Object>> teams = new ArrayList<>();
        Set<String> seenTeams = new LinkedHashSet<>();
        for (String query : queries) {
            for (CrawlerTeam row : teamMapper.searchByName(query)) {
                String key = (row.getId() == null ? "" : row.getId()) + ":" + String.valueOf(row.getName());
                if (!seenTeams.add(key)) continue;
                teams.add(team(row));
                if (teams.size() >= safe) break;
            }
            if (teams.size() >= safe) break;
        }

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

    private List<String> expandAliases(String keyword) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(keyword);
        String key = keyword.toLowerCase(Locale.ROOT).trim();
        Map<String, List<String>> aliases = Map.ofEntries(
                Map.entry("曼城", List.of("Manchester City")),
                Map.entry("曼联", List.of("Manchester United")),
                Map.entry("奈梅亨", List.of("NEC", "NEC Nijmegen")),
                Map.entry("阿森纳", List.of("Arsenal")),
                Map.entry("利物浦", List.of("Liverpool")),
                Map.entry("切尔西", List.of("Chelsea")),
                Map.entry("热刺", List.of("Tottenham")),
                Map.entry("皇马", List.of("Real Madrid")),
                Map.entry("巴萨", List.of("Barcelona")),
                Map.entry("拜仁", List.of("Bayern Munich")),
                Map.entry("多特", List.of("Borussia Dortmund")),
                Map.entry("国米", List.of("Inter Milan")),
                Map.entry("尤文", List.of("Juventus")),
                Map.entry("大巴黎", List.of("Paris Saint-Germain", "PSG")),
                Map.entry("英超", List.of("Premier League")),
                Map.entry("西甲", List.of("La Liga")),
                Map.entry("意甲", List.of("Serie A")),
                Map.entry("德甲", List.of("Bundesliga")),
                Map.entry("法甲", List.of("Ligue 1")),
                Map.entry("荷甲", List.of("Eredivisie")),
                Map.entry("葡超", List.of("Primeira Liga")),
                Map.entry("英冠", List.of("Championship"))
        );
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            String aliasKey = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.equals(aliasKey) || key.contains(aliasKey)) {
                queries.addAll(entry.getValue());
            }
            for (String alias : entry.getValue()) {
                String normalized = alias.toLowerCase(Locale.ROOT);
                if (key.equals(normalized) || key.contains(normalized)) {
                    queries.add(entry.getKey());
                    queries.addAll(entry.getValue());
                }
            }
        }
        // Extra English nicknames that should map to full club names.
        if (key.equals("man city") || key.equals("mancity")) {
            queries.add("Manchester City");
            queries.add("曼城");
        }
        if (key.equals("man united") || key.equals("man utd")) {
            queries.add("Manchester United");
            queries.add("曼联");
        }
        if (key.equals("nec")) {
            queries.add("NEC Nijmegen");
            queries.add("奈梅亨");
        }
        return List.copyOf(queries);
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
            if (!seen.add(league)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", league);
            row.put("matchCount", matches.stream().filter(m -> league.equals(m.getLeagueName())).count());
            leagues.add(row);
            if (leagues.size() >= limit) break;
        }
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
