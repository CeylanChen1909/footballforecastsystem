package com.chen.football.common.source;

import com.chen.football.common.dto.FetchResult;

public interface MatchSourceProvider {

    String name();

    int priority();

    boolean isAvailable();

    FetchResult fetchMatches(String date);

    FetchResult fetchMatchesByLeague(int leagueId, int season);
}
