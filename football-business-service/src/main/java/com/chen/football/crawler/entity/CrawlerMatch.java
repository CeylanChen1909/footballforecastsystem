package com.chen.football.crawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chen.football.common.i18n.LabelCatalog;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("crawler_matches")
public class CrawlerMatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fixtureId;
    private String externalMatchId;
    private String source;
    private String leagueId;
    private String leagueName;
    private String round;
    private String venue;
    private String homeTeamId;
    private String homeTeamName;
    private String homeTeamLogo;
    private String awayTeamId;
    private String awayTeamName;
    private String awayTeamLogo;
    private Integer homeScore;
    private Integer awayScore;
    private String status;
    private LocalDateTime matchTime;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /*
     * Additive display-only fields for API consumers. Persisted values such as
     * leagueName/homeTeamName stay canonical (Chinese when the source provides
     * Chinese), while the *DisplayName fields follow the request language.
     * The *NameEn/*NameZh fields are language-independent and are safe to use
     * as request filters or cache keys.
     */
    public String getLeagueDisplayName() { return LabelCatalog.leagueDisplay(leagueName); }

    public String getLeagueNameEn() { return LabelCatalog.leagueEnglish(leagueName); }

    public String getLeagueNameZh() { return LabelCatalog.leagueChinese(leagueName); }

    public String getHomeTeamDisplayName() { return LabelCatalog.teamDisplay(homeTeamName); }

    public String getHomeTeamNameEn() { return LabelCatalog.teamEnglish(homeTeamName); }

    public String getHomeTeamNameZh() { return LabelCatalog.teamChinese(homeTeamName); }

    public String getAwayTeamDisplayName() { return LabelCatalog.teamDisplay(awayTeamName); }

    public String getAwayTeamNameEn() { return LabelCatalog.teamEnglish(awayTeamName); }

    public String getAwayTeamNameZh() { return LabelCatalog.teamChinese(awayTeamName); }
}
