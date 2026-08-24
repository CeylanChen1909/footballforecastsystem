package com.chen.football.common.dto;

import java.util.List;
import java.util.Map;

public record MatchPredictionResponse(
        Long fixtureId,
        String resultLabel,
        double homeWinProb,
        double drawProb,
        double awayWinProb,
        String modelVersion,
        String explanation,
        List<String> topFeatures,
        boolean featureComplete,
        String featureStatus,
        String fallbackReason,
        Map<String, Object> featureMeta,
        boolean predictionAvailable
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
        this(fixtureId, resultLabel, homeWinProb, drawProb, awayWinProb, modelVersion, explanation, List.of(), true, "COMPLETE", null, Map.of(), true);
    }

    public MatchPredictionResponse(
            Long fixtureId,
            String resultLabel,
            double homeWinProb,
            double drawProb,
            double awayWinProb,
            String modelVersion,
            String explanation,
            List<String> topFeatures
    ) {
        this(fixtureId, resultLabel, homeWinProb, drawProb, awayWinProb, modelVersion, explanation, topFeatures, true, "COMPLETE", null, Map.of(), true);
    }
}
