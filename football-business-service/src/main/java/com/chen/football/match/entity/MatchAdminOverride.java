package com.chen.football.match.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("crawler_matches")
public class MatchAdminOverride {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fixtureId;
    private String source;
    private String leagueId;
    private String leagueName;
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
    private Long updatedBy;
}
