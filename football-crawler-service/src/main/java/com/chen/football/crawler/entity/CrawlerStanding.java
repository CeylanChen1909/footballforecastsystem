package com.chen.football.crawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 积分榜数据实体
 */
@Data
@TableName("crawler_standings")
public class CrawlerStanding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 联赛名称 */
    private String leagueName;

    /** 联赛ID */
    private String leagueId;

    /** 赛季 */
    private String season;

    /** 球队名称 */
    private String teamName;

    /** 球队ID */
    private String teamId;

    /** 球队Logo */
    private String teamLogo;

    /** 排名 */
    private Integer rank;

    /** 场次 */
    private Integer played;

    /** 胜 */
    private Integer wins;

    /** 平 */
    private Integer draws;

    /** 负 */
    private Integer losses;

    /** 进球 */
    private Integer goalsFor;

    /** 失球 */
    private Integer goalsAgainst;

    /** 净胜球 */
    private Integer goalDifference;

    /** 积分 */
    private Integer points;

    /** 数据来源 */
    private String source;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
