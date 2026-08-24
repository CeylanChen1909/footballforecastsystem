package com.chen.football.crawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 球队数据实体
 */
@Data
@TableName("crawler_teams")
public class CrawlerTeam {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 球队名称 */
    private String name;

    /** 球队Logo */
    private String logo;

    /** 所属联赛 */
    private String leagueName;

    /** 国家 */
    private String country;

    /** 数据来源 */
    private String source;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
