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
        assertEquals("欧冠", normalizer.normalize("UEFA Champions League", "bbc-champions-league", "bbc-scores"));
        assertEquals("欧国联", normalizer.normalize("UEFA Nations League", "bbc-nations-league", "bbc-scores"));
    }

    @Test
    void treatsBbcStandingsAsTrustedEnrichmentOfBbcScores() {
        CrawlerProperties properties = new CrawlerProperties();
        CrawlerTeam team = new CrawlerTeam();
        team.setLeagueName("英超");
        team.setSource("bbc-standings");
        org.junit.jupiter.api.Assertions.assertTrue(ProductionLeagueScope.isVisible(team, properties));
    }

    @Test
    void productionScopeAcceptsUclAndUnlBbcIds() {
        org.junit.jupiter.api.Assertions.assertTrue(
                ProductionLeagueScope.isSupported("bbc-champions-league", "欧冠"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ProductionLeagueScope.isSupported("bbc-nations-league", "欧国联"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ProductionLeagueScope.isSupported("bbcchampionsleague", null));
        org.junit.jupiter.api.Assertions.assertFalse(
                ProductionLeagueScope.isSupported("bbc-concacaf-nations-league", "Concacaf Nations League"));
    }
}
