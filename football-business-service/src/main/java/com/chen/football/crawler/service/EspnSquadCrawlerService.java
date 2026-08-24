package com.chen.football.crawler.service;

import com.chen.football.common.config.CrawlerProperties;
import com.chen.football.crawler.http.CrawlerHttpClient;
import com.chen.football.crawler.parser.EspnSquadParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads current registered squads from ESPN's public team pages.  ESPN is
 * used only for low-frequency roster data; it is not part of the match
 * write pipeline and therefore cannot create duplicate fixtures.
 */
@Slf4j
@Service
public class EspnSquadCrawlerService {

    private static final Map<String, String> LEAGUE_CODES = Map.of(
            "英超", "eng.1", "西甲", "esp.1", "意甲", "ita.1", "德甲", "ger.1",
            "法甲", "fra.1", "荷甲", "ned.1", "葡超", "por.1", "英冠", "eng.2",
            "欧冠", "uefa.champions"
    );

    private final CrawlerHttpClient httpClient;
    private final EspnSquadParser parser;
    private final TransfermarktPhotoService transfermarktPhotoService;
    private final CrawlerProperties properties;
    private final Map<String, DirectoryCache> directoryCache = new ConcurrentHashMap<>();

    public EspnSquadCrawlerService(CrawlerHttpClient httpClient,
                                   EspnSquadParser parser,
                                   CrawlerProperties properties,
                                   TransfermarktPhotoService transfermarktPhotoService) {
        this.httpClient = httpClient;
        this.parser = parser;
        this.properties = properties;
        this.transfermarktPhotoService = transfermarktPhotoService;
    }

    public Result fetch(String teamName, String leagueName, int season) {
        if (!properties.isEnabled() || properties.getEspn() == null || !properties.getEspn().isEnabled()) {
            return new Result("NOT_CONFIGURED", "ESPN 阵容爬虫未启用", "", List.of(), "espn-squad");
        }
        String leagueCode = LEAGUE_CODES.getOrDefault(leagueName == null ? "" : leagueName.trim(), "");
        if (leagueCode.isBlank()) {
            return new Result("NOT_CONFIGURED", "当前联赛暂未配置 ESPN 阵容映射", "", List.of(), "espn-squad");
        }
        try {
            CrawlerProperties.Espn espn = properties.getEspn();
            String base = espn.getBaseUrl() == null ? "https://www.espn.com" : espn.getBaseUrl().replaceAll("/$", "");
            DirectoryCache directory = getDirectory(leagueCode, base, espn);
            EspnSquadParser.TeamRef team = parser.findTeam(directory.html(), teamName);
            if (team == null) {
                return new Result("EMPTY", "ESPN 当前联赛名单中未找到该球队，暂不展示推测阵容", "", List.of(), "espn-squad");
            }
            String squadUrl = base + "/soccer/team/squad/_/id/" + team.id() + "/" + team.slug();
            String html = httpClient.getHtmlDirect(squadUrl, espn.getUserAgent(), Duration.ofMillis(Math.max(1000, espn.getTimeoutMs())));
            EspnSquadParser.SquadResult parsed = parser.parseSquad(html);
            if (parsed.players().isEmpty()) {
                return new Result("EMPTY", "ESPN 页面未返回可核验的注册名单", team.id(), List.of(), "espn-squad");
            }
            List<Map<String, Object>> enriched = transfermarktPhotoService.enrich(teamName, parsed.players());
            String source = enriched.stream().anyMatch(item -> "transfermarkt".equals(item.get("photoSource")))
                    ? "espn-squad+transfermarkt-photos" : "espn-squad";
            return new Result("AVAILABLE", "已从公开球队页面加载注册名单", parsed.teamId().isBlank() ? team.id() : parsed.teamId(), enriched, source);
        } catch (Exception ex) {
            log.warn("[ESPN] 获取球队阵容失败, team={}, league={}, error={}", teamName, leagueName, ex.getMessage());
            return new Result("REQUEST_FAILED", "ESPN 阵容页面请求失败，请稍后重试", "", List.of(), "espn-squad");
        }
    }

    private DirectoryCache getDirectory(String leagueCode, String base, CrawlerProperties.Espn espn) {
        String key = leagueCode.toLowerCase(Locale.ROOT);
        DirectoryCache current = directoryCache.get(key);
        long now = System.currentTimeMillis();
        if (current != null && now - current.loadedAt() < 12 * 60 * 60 * 1000L) return current;
        String url = base + "/soccer/teams/_/league/" + leagueCode;
        String html = httpClient.getHtmlDirect(url, espn.getUserAgent(), Duration.ofMillis(Math.max(1000, espn.getTimeoutMs())));
        DirectoryCache refreshed = new DirectoryCache(html, now);
        directoryCache.put(key, refreshed);
        return refreshed;
    }

    private record DirectoryCache(String html, long loadedAt) {}

    public record Result(String status, String message, String teamId,
                         List<Map<String, Object>> players, String source) {}
}
