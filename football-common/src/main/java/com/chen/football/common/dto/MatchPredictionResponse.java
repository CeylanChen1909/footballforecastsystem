package com.chen.football.common.dto;

import java.util.List;

public record MatchPredictionResponse(
        Long fixtureId,
        String resultLabel,
        double homeWinProb,
        double drawProb,
        double awayWinProb,
        String modelVersion,
        String explanation,
        List<String> topFeatures
) {
    public MatchPredictionResponse(
            Long fixtureId,
            String resultLabel,
            double homeWinProb,
            double drawProb,
            double awayWinProb,
            String modelVersion,
            String explanation
    ) {
        this(fixtureId, resultLabel, homeWinProb, drawProb, awayWinProb, modelVersion, explanation, List.of());
    }
}
