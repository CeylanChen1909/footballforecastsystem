package com.chen.football.common.dto;

public record MatchPredictionRequest(
    Long fixtureId,
    String homeTeamId,
    String awayTeamId,
    String homeTeamName,
    String awayTeamName,
    String leagueName,
    Integer leagueId,
    Long userId,
    Double homeWinProb,
    Double drawProb,
    Double awayWinProb,
    String resultLabel,
    String explanation,
    /** 预测时的比赛快照，避免历史记录依赖会过期的实时赛程接口。 */
    String matchTime,
    String homeTeamLogo,
    String awayTeamLogo
) {
    public MatchPredictionRequest(Long fixtureId, String homeTeamId, String awayTeamId) {
        this(fixtureId, homeTeamId, awayTeamId, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 保留旧调用方的构造签名，新增快照字段对现有 Agent/内部调用保持兼容。 */
    public MatchPredictionRequest(Long fixtureId, String homeTeamId, String awayTeamId,
                                  String homeTeamName, String awayTeamName, String leagueName,
                                  Integer leagueId, Long userId, Double homeWinProb,
                                  Double drawProb, Double awayWinProb, String resultLabel,
                                  String explanation) {
        this(fixtureId, homeTeamId, awayTeamId, homeTeamName, awayTeamName, leagueName,
                leagueId, userId, homeWinProb, drawProb, awayWinProb, resultLabel, explanation,
                null, null, null);
    }
}
