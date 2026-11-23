package com.chen.football.crawler.service;

import com.chen.football.crawler.entity.CrawlerMatch;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5: {@link MatchCrawlerService#mergeNonBlank} must keep its
 * "blank/null never overwrites" rule while reporting whether anything
 * actually changed — the dirty check that lets
 * {@code saveOrUpdateMatch} skip {@code updateById} (and the
 * {@code updated_at} refresh that made prediction snapshots permanently
 * stale) when a round brings no new data.
 */
class MatchCrawlerMergeNonBlankTest {

    private CrawlerMatch stored() {
        CrawlerMatch m = new CrawlerMatch();
        m.setLeagueId("39");
        m.setLeagueName("英超");
        m.setHomeTeamId("h1");
        m.setHomeTeamName("Arsenal");
        m.setHomeTeamLogo("logo-h");
        m.setAwayTeamId("a1");
        m.setAwayTeamName("Chelsea");
        m.setAwayTeamLogo("logo-a");
        m.setHomeScore(1);
        m.setAwayScore(0);
        m.setStatus("NS");
        m.setMatchTime(LocalDateTime.of(2026, 11, 23, 20, 0));
        m.setVenue("Emirates");
        m.setRound("Round 12");
        m.setNote("note");
        return m;
    }

    private CrawlerMatch incoming() {
        return stored();
    }

    @Test
    void identicalIncomingReportsNoChange() {
        CrawlerMatch existing = stored();
        assertFalse(MatchCrawlerService.mergeNonBlank(existing, incoming()));
        assertEquals(1, existing.getHomeScore());
        assertEquals("NS", existing.getStatus());
    }

    @Test
    void changedScoreReportsChangeAndUpdates() {
        CrawlerMatch existing = stored();
        CrawlerMatch in = incoming();
        in.setHomeScore(2);
        in.setStatus("LIVE");
        assertTrue(MatchCrawlerService.mergeNonBlank(existing, in));
        assertEquals(2, existing.getHomeScore());
        assertEquals("LIVE", existing.getStatus());
    }

    @Test
    void blankAndNullIncomingNeverOverwrites() {
        CrawlerMatch existing = stored();
        CrawlerMatch in = new CrawlerMatch();
        in.setLeagueName("");
        in.setHomeTeamName("   ");
        in.setHomeTeamLogo(null);
        in.setHomeScore(null);
        in.setAwayScore(null);
        in.setStatus("");
        in.setNote(null);
        assertFalse(MatchCrawlerService.mergeNonBlank(existing, in));
        assertEquals("英超", existing.getLeagueName());
        assertEquals("Arsenal", existing.getHomeTeamName());
        assertEquals("logo-h", existing.getHomeTeamLogo());
        assertEquals(1, existing.getHomeScore());
        assertEquals(0, existing.getAwayScore());
        assertEquals("NS", existing.getStatus());
        assertEquals("note", existing.getNote());
    }

    @Test
    void newLogoOnlyReportsChange() {
        CrawlerMatch existing = stored();
        CrawlerMatch in = incoming();
        in.setAwayTeamLogo("logo-a-new");
        assertTrue(MatchCrawlerService.mergeNonBlank(existing, in));
        assertEquals("logo-a-new", existing.getAwayTeamLogo());
        assertEquals(1, existing.getHomeScore()); // no other field touched
    }
}
