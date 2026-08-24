package com.chen.football.common.dto;

import java.time.LocalDateTime;

public record NormalizedMatch(
        String source,
        String externalMatchId,
        Long fixtureId,
        String leagueId,
        String leagueName,
        String homeTeamId,
        String homeTeamName,
        String homeTeamLogo,
        String awayTeamId,
        String awayTeamName,
        String awayTeamLogo,
        Integer homeScore,
        Integer awayScore,
        String status,
        LocalDateTime matchTime,
        String venue,
        String round
) {
}
