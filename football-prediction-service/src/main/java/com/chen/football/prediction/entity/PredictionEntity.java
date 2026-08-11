package com.chen.football.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("t_prediction")
public class PredictionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long fixtureId;
    private Long homeTeamId;
    private Long awayTeamId;
    private String homeTeamName;
    private String awayTeamName;
    private String leagueName;
    private String modelVersion;
    private String resultLabel;
    private Double homeWinProb;
    private Double drawProb;
    private Double awayWinProb;
    private String explanation;
    private Integer isCorrect;
    private String actualResult;
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;

    // Getter and Setter methods
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public Long getFixtureId() { return fixtureId; }
    public void setFixtureId(Long fixtureId) { this.fixtureId = fixtureId; }
    
    public Long getHomeTeamId() { return homeTeamId; }
    public void setHomeTeamId(Long homeTeamId) { this.homeTeamId = homeTeamId; }
    
    public Long getAwayTeamId() { return awayTeamId; }
    public void setAwayTeamId(Long awayTeamId) { this.awayTeamId = awayTeamId; }
    
    public String getHomeTeamName() { return homeTeamName; }
    public void setHomeTeamName(String homeTeamName) { this.homeTeamName = homeTeamName; }
    
    public String getAwayTeamName() { return awayTeamName; }
    public void setAwayTeamName(String awayTeamName) { this.awayTeamName = awayTeamName; }
    
    public String getLeagueName() { return leagueName; }
    public void setLeagueName(String leagueName) { this.leagueName = leagueName; }
    
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    
    public String getResultLabel() { return resultLabel; }
    public void setResultLabel(String resultLabel) { this.resultLabel = resultLabel; }
    
    public Double getHomeWinProb() { return homeWinProb; }
    public void setHomeWinProb(Double homeWinProb) { this.homeWinProb = homeWinProb; }
    
    public Double getDrawProb() { return drawProb; }
    public void setDrawProb(Double drawProb) { this.drawProb = drawProb; }
    
    public Double getAwayWinProb() { return awayWinProb; }
    public void setAwayWinProb(Double awayWinProb) { this.awayWinProb = awayWinProb; }
    
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    
    public Integer getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Integer isCorrect) { this.isCorrect = isCorrect; }
    
    public String getActualResult() { return actualResult; }
    public void setActualResult(String actualResult) { this.actualResult = actualResult; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
}