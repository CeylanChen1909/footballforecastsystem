package com.chen.football.datasync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("t_team_form")
public class TeamFormEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long teamId;
    private Long fixtureId;
    private Boolean isHome;
    private Long opponentId;
    private Integer goals;
    private Integer conceded;
    private Integer yellowCards;
    private Integer redCards;
    private Integer result;
    private LocalDateTime matchTime;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getFixtureId() { return fixtureId; }
    public void setFixtureId(Long fixtureId) { this.fixtureId = fixtureId; }
    public Boolean getIsHome() { return isHome; }
    public void setIsHome(Boolean isHome) { this.isHome = isHome; }
    public Long getOpponentId() { return opponentId; }
    public void setOpponentId(Long opponentId) { this.opponentId = opponentId; }
    public Integer getGoals() { return goals; }
    public void setGoals(Integer goals) { this.goals = goals; }
    public Integer getConceded() { return conceded; }
    public void setConceded(Integer conceded) { this.conceded = conceded; }
    public Integer getYellowCards() { return yellowCards; }
    public void setYellowCards(Integer yellowCards) { this.yellowCards = yellowCards; }
    public Integer getRedCards() { return redCards; }
    public void setRedCards(Integer redCards) { this.redCards = redCards; }
    public Integer getResult() { return result; }
    public void setResult(Integer result) { this.result = result; }
    public LocalDateTime getMatchTime() { return matchTime; }
    public void setMatchTime(LocalDateTime matchTime) { this.matchTime = matchTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
