package com.chen.football.crawler.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ESPN team directory and roster parser.
 *
 * ESPN renders the squad data into a JSON object embedded in the HTML.  We
 * parse that object instead of depending on the client-side table classes,
 * which keeps the crawler useful when ESPN changes its visual markup.
 */
@Slf4j
@Component
public class EspnSquadParser {

    private static final Pattern TEAM_HREF = Pattern.compile("/soccer/team/_/id/(\\d+)/([^/?#\\\"]+)");
    private static final Pattern PLAYER_ID = Pattern.compile("/id/(\\d+)/");
    private final ObjectMapper objectMapper;

    public EspnSquadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<TeamRef> parseTeamDirectory(String html) {
        List<TeamRef> teams = new ArrayList<>();
        if (html == null || html.isBlank()) return teams;
        Document document = Jsoup.parse(html);
        for (Element link : document.select("a[href*='/soccer/team/_/id/']")) {
            Matcher matcher = TEAM_HREF.matcher(link.attr("href"));
            if (!matcher.find()) continue;
            Element heading = link.selectFirst("h2");
            String name = clean(heading == null ? link.text() : heading.text());
            if (name.isBlank()) continue;
            TeamRef ref = new TeamRef(matcher.group(1), matcher.group(2), name);
            if (teams.stream().noneMatch(item -> item.id().equals(ref.id()))) teams.add(ref);
        }
        return teams;
    }

    public TeamRef findTeam(String html, String requestedName) {
        List<TeamRef> teams = parseTeamDirectory(html);
        String requested = normalize(requestedName);
        String requestedSlug = slug(requestedName);
        TeamRef exact = teams.stream()
                .filter(item -> normalize(item.name()).equals(requested)
                        || slug(item.slug()).equals(requestedSlug)
                        || slug(item.name()).equals(requestedSlug))
                .findFirst()
                .orElse(null);
        if (exact != null) return exact;
        // BBC 有时使用短名（例如 Ajax），而 ESPN 目录使用官方展示名
        //（Ajax Amsterdam）。只有完整词边界前缀/后缀才接受，避免把
        // “United” 这类短词误配到另一支球队。
        return teams.stream().filter(item -> {
            String candidate = normalize(item.name());
            return (!requested.isBlank() && candidate.startsWith(requested + " "))
                    || (!candidate.isBlank() && requested.startsWith(candidate + " "));
        }).findFirst().orElse(null);
    }

    public SquadResult parseSquad(String html) {
        if (html == null || html.isBlank()) return new SquadResult("", "", List.of());
        try {
            // 页面导航也包含一个纯文本的 "squad"，必须定位到嵌入数据中
            // 带 metadata 的 squad 节点，避免截取导航 HTML。
            int key = html.indexOf("\"squad\":{\"metadata\"");
            if (key < 0) return new SquadResult("", "", List.of());
            int objectStart = html.indexOf('{', key);
            String objectJson = balancedObject(html, objectStart);
            JsonNode squad = objectMapper.readTree(objectJson);
            String teamId = squad.path("team").path("id").asText("");
            String teamName = squad.path("team").path("displayName").asText("");
            List<java.util.Map<String, Object>> players = new ArrayList<>();
            JsonNode groups = squad.path("groups");
            if (groups.isArray()) {
                for (JsonNode group : groups) {
                    String groupName = group.path("name").asText("");
                    JsonNode athletes = group.path("athletes");
                    if (!athletes.isArray()) continue;
                    for (JsonNode athlete : athletes) {
                        String name = athlete.path("name").asText("").trim();
                        if (name.isBlank()) continue;
                        java.util.Map<String, Object> player = new LinkedHashMap<>();
                        String href = athlete.path("href").asText("");
                        Matcher idMatcher = PLAYER_ID.matcher(href);
                        String id = idMatcher.find() ? idMatcher.group(1) : athlete.path("uid").asText("");
                        player.put("id", id);
                        player.put("name", name);
                        player.put("position", firstNonBlank(athlete.path("positionName").asText(""), positionLabel(groupName), athlete.path("position").asText("")));
                        player.put("number", athlete.path("jersey").asText(""));
                        player.put("age", athlete.path("age").asText(""));
                        player.put("nationality", athlete.path("ctz").asText(""));
                        // ESPN 当前 squad 页面只提供球员 ID，不再提供可用的
                        // headshot URL；头像由 Transfermarkt 补充服务按姓名匹配，
                        // 匹配不到时前端使用稳定的首字母头像，不生成 404 图片。
                        player.put("photo", "");
                        player.put("source", "espn-squad");
                        players.add(player);
                    }
                }
            }
            return new SquadResult(teamId, teamName, players);
        } catch (Exception ex) {
            log.warn("[ESPN] 阵容 JSON 解析失败: {}", ex.getMessage());
            return new SquadResult("", "", List.of());
        }
    }

    private String balancedObject(String value, int start) {
        if (start < 0 || start >= value.length()) return "{}";
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return value.substring(start, i + 1);
        }
        return "{}";
    }

    private String positionLabel(String group) {
        String value = group.toLowerCase(Locale.ROOT);
        if (value.contains("goal")) return "Goalkeeper";
        if (value.contains("def")) return "Defender";
        if (value.contains("mid")) return "Midfielder";
        if (value.contains("forward") || value.contains("attack")) return "Forward";
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("\\b(fc|cf|afc|sc|ac)\\b", " ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.replace(" and ", " ");
    }

    private String slug(String value) {
        return normalize(value).replace(' ', '-');
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public record TeamRef(String id, String slug, String name) {}
    public record SquadResult(String teamId, String teamName, List<java.util.Map<String, Object>> players) {}
}
