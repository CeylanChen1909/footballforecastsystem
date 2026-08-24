package com.chen.football.crawler.source;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 联赛名称统一器：
 * 不同数据源（api-football / juhe / seed）对同一联赛的命名不同，
 * 这里按 league_id（各源官方联赛代码）映射成统一中文名。
 * 仅收录无歧义的知名联赛，避免把同名小联赛误标（如 api-football 的
 * "Premier League" 可能是乌克兰/马耳他联赛，只能靠 league_id 区分）。
 */
@Component
public class LeagueNameNormalizer {

    private static final Map<String, String> ID_MAP = Map.ofEntries(
            // ===== 欧洲顶级联赛 =====
            Map.entry("39", "英超"),
            Map.entry("40", "英冠"),
            Map.entry("41", "英甲"),
            Map.entry("42", "英乙"),
            Map.entry("140", "西甲"),
            Map.entry("141", "西乙"),
            Map.entry("135", "意甲"),
            Map.entry("136", "意乙"),
            Map.entry("78", "德甲"),
            Map.entry("79", "德乙"),
            Map.entry("61", "法甲"),
            Map.entry("62", "法乙"),
            Map.entry("94", "葡超"),
            Map.entry("95", "葡甲"),
            Map.entry("88", "荷甲"),
            Map.entry("144", "比甲"),
            Map.entry("179", "苏超"),
            Map.entry("203", "土超"),
            // ===== 洲际赛事 =====
            Map.entry("2", "欧冠"),
            Map.entry("3", "欧联"),
            Map.entry("848", "欧协联"),
            Map.entry("1", "世界杯"),
            Map.entry("4", "欧洲杯"),
            Map.entry("15", "世俱杯"),
            // ===== 美洲 =====
            Map.entry("71", "巴西甲"),
            Map.entry("72", "巴西乙"),
            Map.entry("253", "美职联"),
            Map.entry("262", "墨超"),
            Map.entry("128", "阿甲"),
            Map.entry("13", "解放者杯"),
            Map.entry("11", "南美杯"),
            // ===== 亚洲 =====
            Map.entry("169", "中超"),
            Map.entry("98", "日职联"),
            Map.entry("292", "韩K联"),
            // ===== 杯赛 =====
            Map.entry("137", "意大利杯"),
            Map.entry("46", "英锦赛"),
            Map.entry("45", "足总杯"),
            Map.entry("48", "英联杯"),
            // ===== juhe 联赛代码 =====
            Map.entry("PL", "英超"),
            Map.entry("PD", "西甲"),
            Map.entry("SA", "意甲"),
            // ===== BBC Scores provider slugs =====
            Map.entry("bbc-premier-league", "英超"),
            Map.entry("bbc-spanish-la-liga", "西甲"),
            Map.entry("bbc-italian-serie-a", "意甲"),
            Map.entry("bbc-german-bundesliga", "德甲"),
            Map.entry("bbc-french-ligue-one", "法甲"),
            Map.entry("bbc-dutch-eredivisie", "荷甲"),
            Map.entry("bbc-portuguese-primeira-liga", "葡超"),
            Map.entry("bbc-championship", "英冠"),
            // ===== seed 代码 =====
            Map.entry("WC", "世界杯"),
            Map.entry("FRIENDLY", "国际友谊赛")
    );

    /**
     * 把数据源的联赛名统一为规范名称；无法识别时返回原名。
     *
     * @param leagueName 原始联赛名
     * @param leagueId   数据源联赛代码（api-football 数字 id / juhe PL、PD、SA 等）
     * @param source     数据源标识
     * @return 统一后的联赛名
     */
    public String normalize(String leagueName, String leagueId, String source) {
        if (leagueId != null && !leagueId.isBlank()) {
            String mapped = ID_MAP.get(leagueId.trim());
            if (mapped != null) {
                return mapped;
            }
        }
        return leagueName == null || leagueName.isBlank() ? "" : leagueName;
    }
}
