package com.chen.football.common.dto;

import java.util.List;

public record LeagueListResponse(
        List<LeagueItem> response,
        int results
) {
}
