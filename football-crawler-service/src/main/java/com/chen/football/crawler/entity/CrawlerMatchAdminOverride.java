package com.chen.football.crawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
@TableName("t_match_admin_override")
public class CrawlerMatchAdminOverride {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fixtureId;
    private String leagueName;
    private String homeTeamName;
    private String awayTeamName;
    private Date matchTime;
    private String status;
    private Integer homeScore;
    private Integer awayScore;
    private String note;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
