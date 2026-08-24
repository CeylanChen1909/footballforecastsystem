package com.chen.football.crawler.source;

import org.junit.jupiter.api.Test;
import com.chen.football.common.config.CrawlerProperties;

import com.chen.football.crawler.entity.CrawlerTeam;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeagueNameNormalizerTest {
    private final LeagueNameNormalizer normalizer = new LeagueNameNormalizer();

    @Test
    void mapsBbcProviderSlugsToProductionChineseNames() {
        assertEquals("英超", normalizer.normalize("Premier League", "bbc-premier-league", "bbc-scores"));
        assertEquals("葡超", normalizer.normalize("Portuguese Primeira Liga", "bbc-portuguese-primeira-liga", "bbc-scores"));
        assertEquals("英冠", normalizer.normalize("Championship", "bbc-championship", "bbc-scores"));
    }

    @Test
    void treatsBbcStandingsAsTrustedEnrichmentOfBbcScores() {
        CrawlerProperties properties = new CrawlerProperties();
        CrawlerTeam team = new CrawlerTeam();
        team.setLeagueName("英超");
        team.setSource("bbc-standings");
        org.junit.jupiter.api.Assertions.assertTrue(ProductionLeagueScope.isVisible(team, properties));
    }
}
