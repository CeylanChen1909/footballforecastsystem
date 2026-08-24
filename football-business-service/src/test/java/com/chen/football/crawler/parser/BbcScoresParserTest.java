package com.chen.football.crawler.parser;

import com.chen.football.common.dto.MatchStatus;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.entity.CrawlerStanding;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BbcScoresParserTest {

    private final BbcScoresParser parser = new BbcScoresParser();

    @Test
    void rejectsNonScoresPageBeforeTreatingItAsEmpty() {
        assertFalse(parser.isValidScoresPage("<html><title>Access denied</title><body>challenge</body></html>"));
        assertTrue(parser.isValidScoresPage("<html><head><title>Scores & Fixtures - Football - BBC Sport</title></head>"
                + "<body><main data-testid='main-content'><div data-testid='datepicker'></div></main></body></html>"));
    }

    @Test
    void detectsEventMarkersWhenParserReturnsNoRows() {
        assertTrue(parser.hasEventMarkers("<div data-event-id='event-1'></div>"));
        assertFalse(parser.hasEventMarkers("<main data-testid='main-content'></main>"));
    }

    @Test
    void parsesScheduledAndPostponedEventsUsingSemanticAttributes() {
        String html = """
                <div>
                  <h2><a href="/sport/football/premier-league/table">Premier League</a></h2>
                  <ul>
                    <li>
                      <div data-event-id="s-demo-1">
                        <span class="visually-hidden">Arsenal versus Chelsea kick off 15:30</span>
                        <div data-participant-id="home-1" class="TeamHome">
                          <div class="TeamNameWrapper"><span class="DesktopValue">Arsenal</span><span class="MobileValue">Arsenal</span></div>
                          <img data-testid="badge-img-arsenal" src="https://example.test/arsenal.svg"/>
                        </div>
                        <div data-participant-id="away-1" class="TeamAway">
                          <div class="TeamNameWrapper"><span class="DesktopValue">Chelsea</span><span class="MobileValue">Chelsea</span></div>
                        </div>
                      </div>
                    </li>
                    <li>
                      <div data-event-id="s-demo-2">
                        <div data-participant-id="home-2" class="TeamHome"><div class="TeamNameWrapper"><span class="DesktopValue">Liverpool</span></div></div>
                        <div data-participant-id="away-2" class="TeamAway"><div class="TeamNameWrapper"><span class="DesktopValue">Everton</span></div></div>
                        <div class="MatchStatus">Match Postponed</div>
                      </div>
                    </li>
                  </ul>
                </div>
                """;

        List<CrawlerMatch> matches = parser.parseMatchList(html, LocalDate.of(2026, 8, 22));

        assertEquals(2, matches.size());
        CrawlerMatch scheduled = matches.get(0);
        assertEquals("s-demo-1", scheduled.getExternalMatchId());
        assertEquals("英超", scheduled.getLeagueName());
        assertEquals("Arsenal", scheduled.getHomeTeamName());
        assertEquals("Chelsea", scheduled.getAwayTeamName());
        // BBC 使用英国夏令时，2026-08-22 15:30 London = 22:30 Shanghai。
        assertEquals(LocalDate.of(2026, 8, 22).atTime(22, 30), scheduled.getMatchTime());
        assertEquals(MatchStatus.SCHEDULED, scheduled.getStatus());

        CrawlerMatch postponed = matches.get(1);
        assertEquals(MatchStatus.POSTPONED, postponed.getStatus());
        assertNull(postponed.getHomeScore());
        assertNull(postponed.getAwayScore());
    }

    @Test
    void parsesFinishedScoreAndDeduplicatesEventNodes() {
        String html = """
                <h2>La Liga</h2>
                <ul>
                  <li><div data-event-id="s-demo-score">
                    <div data-participant-id="h" class="TeamHome"><div class="TeamNameWrapper"><span class="DesktopValue">Barcelona</span></div></div>
                    <div data-participant-id="a" class="TeamAway"><div class="TeamNameWrapper"><span class="DesktopValue">Real Madrid</span></div></div>
                    <div data-testid="score"><div>2</div><div>|</div><div>1</div></div>
                    <span>Full Time</span>
                  </div></li>
                  <div data-event-id="s-demo-score"></div>
                </ul>
                """;

        List<CrawlerMatch> matches = parser.parseMatchList(html, LocalDate.of(2026, 8, 20));

        assertEquals(1, matches.size());
        assertEquals(MatchStatus.FINISHED, matches.get(0).getStatus());
        assertEquals(2, matches.get(0).getHomeScore());
        assertEquals(1, matches.get(0).getAwayScore());
        assertEquals("西甲", matches.get(0).getLeagueName());
    }

    @Test
    void convertsLondonWinterTimeWithEightHourOffset() {
        String html = """
                <h2>Premier League</h2>
                <div data-event-id="s-winter">
                  <div data-participant-id="h" class="TeamHome"><div class="TeamNameWrapper"><span class="DesktopValue">Arsenal</span></div></div>
                  <div data-participant-id="a" class="TeamAway"><div class="TeamNameWrapper"><span class="DesktopValue">Chelsea</span></div></div>
                  <span class="visually-hidden">Arsenal versus Chelsea kick off 15:30</span>
                </div>
                """;

        List<CrawlerMatch> matches = parser.parseMatchList(html, LocalDate.of(2026, 12, 1));

        assertEquals(1, matches.size());
        assertEquals(LocalDate.of(2026, 12, 1).atTime(23, 30), matches.get(0).getMatchTime());
    }

    @Test
    void doesNotInventMidnightWhenKickoffIsMissing() {
        String html = """
                <h2>Premier League</h2>
                <div data-event-id="s-missing-time">
                  <div data-participant-id="h" class="TeamHome"><div class="TeamNameWrapper"><span class="DesktopValue">Arsenal</span></div></div>
                  <div data-participant-id="a" class="TeamAway"><div class="TeamNameWrapper"><span class="DesktopValue">Chelsea</span></div></div>
                </div>
                """;

        List<CrawlerMatch> matches = parser.parseMatchList(html, LocalDate.of(2026, 8, 22));

        assertEquals(1, matches.size());
        assertNull(matches.get(0).getMatchTime());
        assertTrue(matches.get(0).getNote().contains("KICKOFF_TIME_MISSING"));
    }

    @Test
    void futureKickoffCannotBePersistedAsLiveFromGenericPageText() {
        LocalDate future = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);
        String html = """
                <h2>Premier League</h2>
                <div data-event-id="s-future-live-marker">
                  <div data-participant-id="h" class="TeamHome"><div class="TeamNameWrapper"><span>Arsenal</span></div></div>
                  <div data-participant-id="a" class="TeamAway"><div class="TeamNameWrapper"><span>Chelsea</span></div></div>
                  <span>Live scoreboard</span><span class="visually-hidden">Arsenal versus Chelsea kick off 15:30</span>
                </div>
                """;

        List<CrawlerMatch> matches = parser.parseMatchList(html, future);

        assertEquals(1, matches.size());
        assertEquals(MatchStatus.SCHEDULED, matches.get(0).getStatus());
    }

    @Test
    void prefersSemanticIsoKickoffAttribute() {
        String html = """
                <h2>Premier League</h2>
                <div data-event-id="s-iso-time">
                  <time datetime="2026-08-22T14:30:00Z">Today</time>
                  <div data-participant-id="h" class="TeamHome"><div class="TeamNameWrapper"><span>Arsenal</span></div></div>
                  <div data-participant-id="a" class="TeamAway"><div class="TeamNameWrapper"><span>Chelsea</span></div></div>
                </div>
                """;

        List<CrawlerMatch> matches = parser.parseMatchList(html, LocalDate.of(2026, 8, 22));

        assertEquals(1, matches.size());
        assertEquals(LocalDate.of(2026, 8, 22).atTime(22, 30), matches.get(0).getMatchTime());
    }

    @Test
    void parsesBbcStandingTeamBadgeAndStats() {
        String html = """
                <table data-testid="football-table"><tbody>
                  <tr>
                    <td><span class="Rank">1</span><div><img data-testid="badge-img-arsenal" src="https://example.test/arsenal.svg"/><a href="/sport/football/teams/arsenal"><span aria-hidden="true">Arsenal</span></a></div></td>
                    <td aria-label="Played">38</td><td aria-label="Won">28</td><td aria-label="Drawn">6</td><td aria-label="Lost">4</td>
                    <td aria-label="Goals For">91</td><td aria-label="Goals Against">29</td><td aria-label="Goal Difference">62</td><td aria-label="Points"><span>90</span></td>
                  </tr>
                </tbody></table>
                """;

        List<CrawlerStanding> standings = parser.parseStandings(html, "英超", "PL", "2026/2027");

        assertEquals(1, standings.size());
        CrawlerStanding row = standings.get(0);
        assertEquals("Arsenal", row.getTeamName());
        assertEquals("arsenal", row.getTeamId());
        assertEquals("https://example.test/arsenal.svg", row.getTeamLogo());
        assertEquals(38, row.getPlayed());
        assertEquals(90, row.getPoints());
    }

    @Test
    void parsesStandingWhenBbcOmitsTableTestId() {
        String html = """
                <table><tbody>
                  <tr>
                    <td><span class="Rank">1</span><a href="/sport/football/teams/arsenal">Arsenal</a></td>
                    <td aria-label="Played">0</td><td aria-label="Won">0</td><td aria-label="Drawn">0</td><td aria-label="Lost">0</td>
                    <td aria-label="Goals For">0</td><td aria-label="Goals Against">0</td><td aria-label="Goal Difference">0</td><td aria-label="Points">0</td>
                  </tr>
                </tbody></table>
                """;

        List<CrawlerStanding> standings = parser.parseStandings(html, "英超", "PL", "2026/2027");

        assertEquals(1, standings.size());
        assertEquals("Arsenal", standings.get(0).getTeamName());
        assertEquals(0, standings.get(0).getPlayed());
    }
}
