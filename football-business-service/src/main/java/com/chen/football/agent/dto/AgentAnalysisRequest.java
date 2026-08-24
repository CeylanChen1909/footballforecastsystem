package com.chen.football.agent.dto;

public record AgentAnalysisRequest(
        Long fixtureId,
        String homeTeamId,
        String awayTeamId,
        String homeTeamName,
        String awayTeamName,
        String leagueName,
        Long teamId,
        String teamName,
        Long articleId,
        Integer limit,
        String predictionResultLabel,
        Double predictionHomeWinProb,
        Double predictionDrawProb,
        Double predictionAwayWinProb,
        String predictionModelVersion,
        String predictionExplanation,
        String prompt,
        String provider,
        String model
) {
}
