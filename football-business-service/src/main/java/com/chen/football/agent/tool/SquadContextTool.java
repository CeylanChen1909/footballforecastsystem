package com.chen.football.agent.tool;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.controller.CrawlerController;
import com.chen.football.crawler.entity.CrawlerTeam;
import com.chen.football.crawler.mapper.CrawlerTeamMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a club's registered squad through the existing database-first roster
 * endpoint.  The endpoint owns ESPN/API-Football fallback and persistence, so
 * Agent queries do not create a second roster-fetch pipeline.
 */
@Component
public class SquadContextTool implements AgentTool {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CrawlerController crawlerController;
    private final CrawlerTeamMapper teamMapper;
    private final CrawlerProperties crawlerProperties;

    public SquadContextTool(CrawlerController crawlerController,
                            CrawlerTeamMapper teamMapper,
                            CrawlerProperties crawlerProperties) {
        this.crawlerController = crawlerController;
        this.teamMapper = teamMapper;
        this.crawlerProperties = crawlerProperties;
    }

    @Override
    public String name() {
        return "squad_context";
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        String teamName = string(context.get("teamName"));
        if (teamName == null || teamName.isBlank()) teamName = string(context.get("homeTeamName"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("teamName", teamName);
        if (teamName == null || teamName.isBlank()) {
            result.put("status", "MISSING_INPUT");
            result.put("message", "缺少球队名称，暂不查询球队名单");
            result.put("players", List.of());
            result.put("playerCount", 0);
            return result;
        }

        String leagueName = string(context.get("leagueName"));
        if (leagueName == null || leagueName.isBlank()) {
            try {
                CrawlerTeam team = teamMapper.findPreferredByName(teamName);
                if (team != null) leagueName = team.getLeagueName();
            } catch (Exception ignored) { }
        }
        int season = LocalDate.now(BUSINESS_ZONE).getYear();
        Map<String, Object> response = crawlerController.getTeamSquad(teamName, leagueName, "", season, false);
        Object rawData = response == null ? null : response.get("data");
        Map<String, Object> data = rawData instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
        Object rawPlayers = data.get("response");
        List<Map<String, Object>> sourcePlayers = rawPlayers instanceof List<?> list
                ? list.stream().filter(item -> item instanceof Map<?, ?>)
                .map(item -> compactPlayer((Map<?, ?>) item)).toList()
                : List.of();
        // The model needs names/positions/numbers, not portrait URLs or source
        // internals.  Keeping the fact payload compact prevents prompt tail
        // truncation from dropping the first half of a normal 25-man squad.
        List<Map<String, Object>> players = sourcePlayers.stream().limit(60).toList();
        String status = String.valueOf(data.getOrDefault("status", players.isEmpty() ? "EMPTY" : "AVAILABLE"));
        result.put("teamName", teamName);
        result.put("leagueName", leagueName == null ? "" : leagueName);
        result.put("teamId", data.getOrDefault("teamId", ""));
        result.put("status", status);
        result.put("message", data.getOrDefault("message", "当前没有可核验的球队名单"));
        result.put("source", data.getOrDefault("source", crawlerProperties.getPrimarySource()));
        result.put("cacheState", data.getOrDefault("cacheState", "UNKNOWN"));
        result.put("lastSyncedAt", data.get("lastSyncedAt"));
        result.put("players", players);
        result.put("playerCount", sourcePlayers.size());
        result.put("returnedPlayerCount", players.size());
        result.put("truncated", sourcePlayers.size() > players.size());
        return result;
    }

    private Map<String, Object> compactPlayer(Map<?, ?> raw) {
        Map<String, Object> player = new LinkedHashMap<>();
        for (String key : List.of("id", "name", "position", "number")) {
            Object value = raw.get(key);
            if (value != null && !String.valueOf(value).isBlank()) player.put(key, value);
        }
        return player;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
