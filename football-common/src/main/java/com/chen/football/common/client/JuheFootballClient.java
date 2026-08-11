package com.chen.football.common.client;

import com.chen.football.common.config.JuheFootballProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 聚合数据足球API客户端
 * 接口地址: http://apis.juhe.cn/fapig/football/query
 */
@Component
public class JuheFootballClient {

    private final JuheFootballProperties props;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    // 联赛类型映射 (前端ID -> 聚合数据type)
    private static final Map<Integer, String> LEAGUE_TYPE_MAP = new HashMap<>();
    static {
        LEAGUE_TYPE_MAP.put(39, "yingchao");   // 英超
        LEAGUE_TYPE_MAP.put(140, "xijia");    // 西甲
        LEAGUE_TYPE_MAP.put(135, "yijia");    // 意甲
        LEAGUE_TYPE_MAP.put(78, "dejia");     // 德甲
        LEAGUE_TYPE_MAP.put(61, "fajia");     // 法甲
        LEAGUE_TYPE_MAP.put(0, "yingchao");    // 默认英超
    }

    // 联赛ID映射 (名称 -> ID)
    private static final Map<String, Integer> LEAGUE_ID_MAP = new HashMap<>();
    static {
        LEAGUE_ID_MAP.put("英超", 39);
        LEAGUE_ID_MAP.put("西甲", 140);
        LEAGUE_ID_MAP.put("意甲", 135);
        LEAGUE_ID_MAP.put("德甲", 78);
        LEAGUE_ID_MAP.put("法甲", 61);
        LEAGUE_ID_MAP.put("中超", 1);
    }

    public JuheFootballClient(JuheFootballProperties props) {
        this.props = props;
        this.objectMapper = new ObjectMapper();
        
        // 创建支持 GBK 编码的 RestTemplate
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 获取比赛数据
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getFixturesByDate(String date, int leagueId) {
        String leagueType = LEAGUE_TYPE_MAP.getOrDefault(leagueId, "yingchao");
        
        return Mono.fromCallable(() -> {
            StringBuilder url = new StringBuilder(props.getBaseUrl())
                    .append("?key=").append(props.getApiKey())
                    .append("&type=").append(leagueType);
            if (date != null && !date.isBlank()) {
                url.append("&date=").append(date);
            }
            // 使用 RestTemplate 获取字节数据
            byte[] bytes = restTemplate.getForObject(url.toString(), byte[].class);
            if (bytes == null) throw new RuntimeException("API返回为空");
            // 优先 UTF-8，若异常再尝试 GBK（聚合接口返回编码不稳定）
            String json = decodeResponse(bytes);
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            return convertToStandardFormat(data);
        }).timeout(Duration.ofSeconds(15));
    }

    /**
     * 获取积分榜
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getStandings(int leagueId) {
        String leagueType = LEAGUE_TYPE_MAP.getOrDefault(leagueId, "yingchao");
        
        return Mono.fromCallable(() -> {
            String url = props.getRankUrl() + "?key=" + props.getApiKey() + "&type=" + leagueType;
            byte[] bytes = restTemplate.getForObject(url, byte[].class);
            if (bytes == null) throw new RuntimeException("API返回为空");
            String json = decodeResponse(bytes);
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            return convertStandingsToStandardFormat(data);
        }).timeout(Duration.ofSeconds(15));
    }

    /**
     * 获取支持的联赛列表
     */
    public Mono<Map<String, Object>> getLeagues() {
        List<Map<String, Object>> leagues = List.of(
                Map.of("id", 39, "name", "英超", "country", "英格兰", "logo", ""),
                Map.of("id", 140, "name", "西甲", "country", "西班牙", "logo", ""),
                Map.of("id", 135, "name", "意甲", "country", "意大利", "logo", ""),
                Map.of("id", 78, "name", "德甲", "country", "德国", "logo", ""),
                Map.of("id", 61, "name", "法甲", "country", "法国", "logo", "")
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("response", leagues);
        result.put("results", leagues.size());
        return Mono.just(result);
    }

    /**
     * 将聚合数据格式转换为标准格式 (适配前端 MatchCard 组件)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToStandardFormat(Map<String, Object> juheResponse) {
        Map<String, Object> result = new HashMap<>();
        
        Integer errorCode = (Integer) juheResponse.get("error_code");
        if (errorCode == null || errorCode != 0) {
            result.put("response", Collections.emptyList());
            result.put("results", 0);
            result.put("error", juheResponse.get("reason"));
            return result;
        }
        
        Object resultObj = juheResponse.get("result");
        if (resultObj == null) {
            result.put("response", Collections.emptyList());
            result.put("results", 0);
            return result;
        }
        
        Map<String, Object> resultData = (Map<String, Object>) resultObj;
        Object matchsObj = resultData.get("matchs");
        
        List<Map<String, Object>> standardMatches = new ArrayList<>();
        
        if (matchsObj instanceof List) {
            List<Map<String, Object>> matchs = (List<Map<String, Object>>) matchsObj;
            for (Map<String, Object> match : matchs) {
                String leagueName = (String) match.get("title");
                int leagueId = getLeagueIdFromName(leagueName);
                String normalizedLeagueName = normalizeLeagueName(leagueName);
                
                // 联赛信息
                Map<String, Object> league = new HashMap<>();
                league.put("id", leagueId);
                league.put("name", normalizedLeagueName);
                league.put("logo", "");
                
                // 比赛场次列表
                Object listObj = match.get("list");
                if (listObj instanceof List) {
                    List<Map<String, Object>> gamesRaw = (List<Map<String, Object>>) listObj;
                    for (Map<String, Object> game : gamesRaw) {
                        String realMatchDate = extractRealMatchDate(game);
                        if (realMatchDate == null) {
                            continue;
                        }
                        Map<String, Object> standardMatch = new HashMap<>();
                        
                        // 联赛信息
                        standardMatch.put("league", league);
                        
                        // fixture
                        Map<String, Object> fixture = new HashMap<>();
                        fixture.put("id", Math.abs(game.hashCode()));
                        fixture.put("date", realMatchDate);
                        fixture.put("time", game.get("time_start"));
                        
                        // 状态
                        Map<String, Object> status = new HashMap<>();
                        String statusCode = String.valueOf(game.get("status"));
                        status.put("short", convertStatus(statusCode));
                        fixture.put("status", status);
                        standardMatch.put("fixture", fixture);
                        
                        // 主队
                        Map<String, Object> homeTeam = new HashMap<>();
                        homeTeam.put("id", Math.abs(game.hashCode()) / 2);
                        homeTeam.put("name", normalizeTeamName((String) game.get("team1")));
                        homeTeam.put("logo", game.get("team1_logo") != null ? game.get("team1_logo").toString() : "");
                        
                        // 客队
                        Map<String, Object> awayTeam = new HashMap<>();
                        awayTeam.put("id", Math.abs(game.hashCode()) / 3);
                        awayTeam.put("name", normalizeTeamName((String) game.get("team2")));
                        awayTeam.put("logo", game.get("team2_logo") != null ? game.get("team2_logo").toString() : "");
                        
                        Map<String, Object> teams = new HashMap<>();
                        teams.put("home", homeTeam);
                        teams.put("away", awayTeam);
                        standardMatch.put("teams", teams);
                        
                        // 比分
                        Map<String, Object> goals = new HashMap<>();
                        goals.put("home", safeParseInt(game.get("team1_score")));
                        goals.put("away", safeParseInt(game.get("team2_score")));
                        standardMatch.put("goals", goals);
                        
                        standardMatches.add(standardMatch);
                    }
                }
            }
        }
        
        result.put("response", standardMatches);
        result.put("results", standardMatches.size());
        
        return result;
    }

    /**
     * 修复编码并标准化联赛名称
     */
    private String normalizeLeagueName(String name) {
        if (name == null) return "联赛";
        // 从原始名称中提取中文联赛名
        for (Map.Entry<String, Integer> entry : LEAGUE_ID_MAP.entrySet()) {
            if (name.contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        return name;
    }

    /**
     * 修复球队名称编码
     */
    private String normalizeTeamName(String name) {
        if (name == null) return "未知球队";
        return name;
    }

    private String extractRealMatchDate(Map<String, Object> game) {
        if (game == null || game.isEmpty()) return null;
        String[] dateKeys = {"date", "match_date", "matchDate", "game_date", "gameDate", "start_date", "startDate"};
        for (String key : dateKeys) {
            String normalized = normalizeDateValue(game.get(key));
            if (normalized != null) return normalized;
        }
        String[] dateTimeKeys = {"datetime", "date_time", "match_time", "matchTime", "time", "time_start", "start_time", "startTime"};
        for (String key : dateTimeKeys) {
            String normalized = normalizeDateValue(game.get(key));
            if (normalized != null) return normalized;
        }
        return null;
    }

    private String normalizeDateValue(Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return null;
        raw = raw.replace('/', '-').replace('.', '-');
        if (raw.length() >= 10) {
            String candidate = raw.substring(0, 10);
            if (candidate.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                try {
                    LocalDate date = LocalDate.parse(candidate, DateTimeFormatter.ofPattern("yyyy-M-d"));
                    return date.toString();
                } catch (DateTimeParseException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 转换比赛状态
     */
    private String convertStatus(String status) {
        if (status == null) return "NS";
        return switch (status) {
            case "1" -> "NS";  // 未开赛
            case "2" -> "LIVE"; // 进行中
            case "3" -> "FT";   // 完赛
            case "4" -> "PST";  // 延期
            default -> "NS";
        };
    }

    /**
     * 根据联赛名称获取ID
     */
    private int getLeagueIdFromName(String title) {
        if (title == null) return 0;
        for (Map.Entry<String, Integer> entry : LEAGUE_ID_MAP.entrySet()) {
            if (title.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return 0;
    }

    private int safeParseInt(Object value) {
        if (value == null) return 0;
        try {
            String str = String.valueOf(value);
            if ("-".equals(str) || "".equals(str)) return 0;
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 智能解码响应内容（优先 UTF-8，异常时回退 GBK）
     */
    private String decodeResponse(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (looksLikeMojibake(utf8)) {
            return new String(bytes, Charset.forName("GBK"));
        }
        return utf8;
    }

    /**
     * 简单判断是否存在常见乱码特征
     */
    private boolean looksLikeMojibake(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("锟") || text.contains("�") || text.contains("甯") || text.contains("瀵");
    }

    /**
     * 将聚合数据积分榜格式转换为标准格式
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertStandingsToStandardFormat(Map<String, Object> juheResponse) {
        Map<String, Object> result = new HashMap<>();
        
        Integer errorCode = (Integer) juheResponse.get("error_code");
        if (errorCode == null || errorCode != 0) {
            result.put("response", Collections.emptyList());
            result.put("results", 0);
            result.put("error", juheResponse.get("reason"));
            return result;
        }
        
        Object resultObj = juheResponse.get("result");
        if (resultObj == null) {
            result.put("response", Collections.emptyList());
            result.put("results", 0);
            return result;
        }
        
        Map<String, Object> resultData = (Map<String, Object>) resultObj;
        String title = (String) resultData.get("title");
        String duration = (String) resultData.get("duration");
        Object rankingObj = resultData.get("ranking");
        
        List<Map<String, Object>> standardStandings = new ArrayList<>();
        
        if (rankingObj instanceof List) {
            List<Map<String, Object>> ranking = (List<Map<String, Object>>) rankingObj;
            for (Map<String, Object> row : ranking) {
                Map<String, Object> standardRow = new HashMap<>();
                standardRow.put("position", safeParseInt(row.get("rank_id")));
                standardRow.put("team", row.get("team"));
                standardRow.put("teamLogo", "");
                standardRow.put("played", safeParseInt(row.get("wins")) + safeParseInt(row.get("losses")) + safeParseInt(row.get("draw")));
                standardRow.put("won", safeParseInt(row.get("wins")));
                standardRow.put("drawn", safeParseInt(row.get("draw")));
                standardRow.put("lost", safeParseInt(row.get("losses")));
                standardRow.put("goalsFor", safeParseInt(row.get("goals")));
                standardRow.put("goalsAgainst", safeParseInt(row.get("losing_goals")));
                standardRow.put("goalDifference", row.get("goal_difference"));
                standardRow.put("points", safeParseInt(row.get("scores")));
                standardStandings.add(standardRow);
            }
        }
        
        result.put("response", standardStandings);
        result.put("results", standardStandings.size());
        result.put("leagueName", normalizeLeagueName(title));
        result.put("season", duration);
        
        return result;
    }
}
