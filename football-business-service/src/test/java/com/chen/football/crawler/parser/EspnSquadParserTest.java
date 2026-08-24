package com.chen.football.crawler.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EspnSquadParserTest {

    private final EspnSquadParser parser = new EspnSquadParser(new ObjectMapper());

    @Test
    void findsTeamAndParsesEmbeddedSquadJson() {
        String directory = "<a href=\"/soccer/team/_/id/359/arsenal\"><h2>Arsenal</h2></a>";
        EspnSquadParser.TeamRef team = parser.findTeam(directory, "Arsenal");
        assertNotNull(team);
        assertEquals("359", team.id());

        String html = "<script>window.__DATA__={\"squad\":{\"metadata\":{},\"team\":{\"id\":\"359\",\"displayName\":\"Arsenal\"},\"groups\":[{\"name\":\"goalkeepers\",\"athletes\":[{\"name\":\"David Raya\",\"href\":\"https://www.espn.com/soccer/player/_/id/196176/david-raya\",\"positionName\":\"Goalkeeper\",\"jersey\":\"1\",\"age\":\"30\",\"ctz\":\"Spain\"}]}]}};</script>";
        EspnSquadParser.SquadResult result = parser.parseSquad(html);
        assertEquals("359", result.teamId());
        assertEquals(1, result.players().size());
        assertEquals("David Raya", result.players().get(0).get("name"));
        assertEquals("Goalkeeper", result.players().get(0).get("position"));
        assertEquals("1", result.players().get(0).get("number"));
    }
}
