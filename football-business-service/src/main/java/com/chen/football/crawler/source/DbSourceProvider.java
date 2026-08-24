package com.chen.football.crawler.source;

import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.common.dto.FetchResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DbSourceProvider implements MatchSourceProvider {

    private final CrawlerMatchMapper mapper;

    public DbSourceProvider(CrawlerMatchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() { return "database"; }

    @Override
    public int priority() { return 5; }

    @Override
    public boolean isAvailable() { return mapper != null; }

    @Override
    public FetchResult fetchMatches(String date) { return FetchResult.success(name(), List.of(), 0); }

    @Override
    public FetchResult fetchMatchesByLeague(int leagueId, int season) { return FetchResult.success(name(), List.of(), 0); }
}
