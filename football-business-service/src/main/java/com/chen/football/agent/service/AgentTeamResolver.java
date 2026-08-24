package com.chen.football.agent.service;

import com.chen.football.crawler.entity.CrawlerTeam;
import com.chen.football.crawler.mapper.CrawlerTeamMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Resolves team mentions in a natural-language Agent question to the names
 * stored by the primary crawler. BBC uses English display names while users
 * commonly ask with Chinese aliases, so exact SQL matching alone is not
 * sufficient for Agent conversations.
 */
@Component
public class AgentTeamResolver {

    private static final Map<String, String> CHINESE_ALIASES = aliases();
    private static final Map<String, List<String>> AMBIGUOUS_ALIASES = Map.of(
            "米兰", List.of("AC Milan", "Inter Milan"),
            "曼彻斯特", List.of("Manchester City", "Manchester United")
    );
    private final CrawlerTeamMapper teamMapper;
    private final AtomicReference<KnownTeams> knownTeams = new AtomicReference<>(new KnownTeams(List.of(), 0L));

    public AgentTeamResolver(CrawlerTeamMapper teamMapper) {
        this.teamMapper = teamMapper;
    }

    /** Add canonical team fields without overwriting explicit UI context. */
    public List<String> enrich(Map<String, Object> context, String message) {
        List<String> ambiguous = ambiguousCandidates(message);
        if (!ambiguous.isEmpty()) {
            context.put("teamAmbiguity", true);
            context.put("teamCandidates", ambiguous);
            context.put("teamAmbiguityMessage", "检测到多个可能的球队，请先选择具体球队");
            return List.of();
        }
        List<String> resolved = resolve(message);
        if (resolved.isEmpty()) return resolved;
        if (!hasValue(context, "teamName") && !hasValue(context, "teamId")) {
            context.put("teamName", resolved.get(0));
        }
        if (resolved.size() >= 2 && !hasValue(context, "comparisonTeamName")) {
            context.put("comparisonTeamName", resolved.get(1));
            context.put("comparisonTeamNames", resolved);
        }
        try {
            CrawlerTeam primary = teamMapper.findPreferredByName(resolved.get(0));
            if (primary != null && primary.getId() != null && !hasValue(context, "teamId")) {
                context.put("teamId", primary.getId());
            }
            if (resolved.size() >= 2) {
                CrawlerTeam comparison = teamMapper.findPreferredByName(resolved.get(1));
                if (comparison != null && comparison.getId() != null) context.put("comparisonTeamId", comparison.getId());
            }
        } catch (Exception ignored) {
            // 球队 ID 补充失败不应阻断对话，工具会返回明确的缺失状态。
        }
        context.put("teamEntities", resolved);
        return resolved;
    }

    public List<String> resolve(String message) {
        if (message == null || message.isBlank()) return List.of();
        String text = message.trim();
        if (!ambiguousCandidates(text).isEmpty()) return List.of();
        Set<String> result = new LinkedHashSet<>();

        CHINESE_ALIASES.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey(Comparator.comparingInt(String::length)).reversed())
                .filter(entry -> text.toLowerCase(Locale.ROOT).contains(entry.getKey().toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getValue)
                .map(this::resolveStoredName)
                .forEach(result::add);

        for (String known : loadKnownTeams()) {
            if (containsTeamName(text, known)) result.add(known);
        }
        return new ArrayList<>(result);
    }

    private List<String> ambiguousCandidates(String message) {
        if (message == null || message.isBlank()) return List.of();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("ac米兰") || lower.contains("ac milan") || lower.contains("国际米兰")
                || lower.contains("inter milan") || lower.contains("曼彻斯特城") || lower.contains("曼彻斯特联")) {
            return List.of();
        }
        return AMBIGUOUS_ALIASES.entrySet().stream()
                .filter(entry -> lower.contains(entry.getKey().toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getValue)
                .findFirst().orElse(List.of());
    }

    private String resolveStoredName(String canonical) {
        try {
            CrawlerTeam exact = teamMapper.findPreferredByName(canonical);
            if (exact != null && exact.getName() != null && !exact.getName().isBlank()) return exact.getName();
            List<CrawlerTeam> matches = teamMapper.searchByName(canonical);
            if (matches != null && !matches.isEmpty() && matches.get(0).getName() != null) return matches.get(0).getName();
        } catch (Exception ignored) {
            // Alias resolution must never block a chat when the database is unavailable.
        }
        return canonical;
    }

    private List<String> loadKnownTeams() {
        KnownTeams cached = knownTeams.get();
        if (System.currentTimeMillis() - cached.loadedAt() < 5 * 60 * 1000L) return cached.names();
        try {
            List<CrawlerTeam> rows = teamMapper.selectList(null);
            List<String> names = rows == null ? List.of() : rows.stream()
                    .map(CrawlerTeam::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
            knownTeams.set(new KnownTeams(names, System.currentTimeMillis()));
            return names;
        } catch (Exception ignored) {
            return cached.names();
        }
    }

    private boolean containsTeamName(String text, String teamName) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerName = teamName.toLowerCase(Locale.ROOT);
        if (lowerName.isBlank()) return false;
        if (lowerName.chars().anyMatch(ch -> ch > 127)) return lowerText.contains(lowerName);
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(lowerName) + "(?![a-z0-9])")
                .matcher(lowerText).find();
    }

    private boolean hasValue(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value != null && !String.valueOf(value).isBlank();
    }

    private static Map<String, String> aliases() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("阿森纳", "Arsenal");
        map.put("枪手", "Arsenal");
        map.put("巴萨", "Barcelona");
        map.put("巴塞罗那", "Barcelona");
        map.put("皇家贝蒂斯", "Real Betis");
        map.put("贝蒂斯", "Real Betis");
        map.put("皇家社会", "Real Sociedad");
        map.put("曼城", "Manchester City");
        map.put("曼彻斯特城", "Manchester City");
        map.put("曼联", "Manchester United");
        map.put("曼彻斯特联", "Manchester United");
        map.put("利物浦", "Liverpool");
        map.put("切尔西", "Chelsea");
        map.put("热刺", "Tottenham");
        map.put("托特纳姆热刺", "Tottenham");
        map.put("皇马", "Real Madrid");
        map.put("皇家马德里", "Real Madrid");
        map.put("拜仁", "Bayern Munich");
        map.put("拜仁慕尼黑", "Bayern Munich");
        map.put("巴黎圣日耳曼", "Paris Saint-Germain");
        map.put("大巴黎", "Paris Saint-Germain");
        map.put("尤文", "Juventus");
        map.put("尤文图斯", "Juventus");
        map.put("国际米兰", "Inter Milan");
        map.put("国米", "Inter Milan");
        map.put("ac米兰", "AC Milan");
        // “米兰”同时可能指 AC Milan 或 Inter Milan，不做单向猜测。
        map.put("多特蒙德", "Borussia Dortmund");
        map.put("多特", "Borussia Dortmund");
        map.put("马竞", "Atletico Madrid");
        map.put("马德里竞技", "Atletico Madrid");
        map.put("那不勒斯", "Napoli");
        map.put("里昂", "Lyon");
        map.put("本菲卡", "Benfica");
        map.put("波尔图", "Porto");
        map.put("阿贾克斯", "Ajax");
        map.put("费耶诺德", "Feyenoord");
        map.put("埃因霍温", "PSV Eindhoven");
        return Map.copyOf(map);
    }

    private record KnownTeams(List<String> names, long loadedAt) { }
}
