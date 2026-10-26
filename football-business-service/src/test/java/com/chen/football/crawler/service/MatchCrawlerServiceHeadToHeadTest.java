package com.chen.football.crawler.service;

import com.chen.football.common.client.FootballDataClient;
import com.chen.football.common.client.JuheFootballClient;
import com.chen.football.common.service.DistributedLockService;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.crawler.mapper.CrawlerMatchMapper;
import com.chen.football.crawler.source.DataSourceManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchCrawlerServiceHeadToHeadTest {

    @Test
    void ChineseEnglishAndAliasesQueryTheSameCanonicalCandidateSet() {
        CrawlerMatchMapper mapper = mock(CrawlerMatchMapper.class);
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.isSourceEnabled("bbc-scores")).thenReturn(true);
        when(mapper.findHeadToHead(anyList(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<String> homes = invocation.getArgument(0);
            List<String> aways = invocation.getArgument(1);
            boolean sameFixture = homes.contains("Liverpool") && aways.contains("Villarreal");
            return sameFixture ? List.of(englishFixture()) : List.of();
        });

        MatchCrawlerService service = new MatchCrawlerService(
                mapper,
                mock(FootballDataClient.class),
                mock(JuheFootballClient.class),
                dataSourceManager,
                mock(DistributedLockService.class),
                mock(IdentityMappingService.class),
                mock(CrawlerIngestionAuditService.class));

        assertEquals(1, service.getHeadToHead("利物浦", "比利亚雷亚尔", 10).size());
        assertEquals(1, service.getHeadToHead("Liverpool", "Villarreal", 10).size());
        assertEquals(1, service.getHeadToHead("红军", "黄色潜水艇", 10).size());

        verify(mapper, times(3)).findHeadToHead(anyList(), anyList(), eq(10));
    }

    private CrawlerMatch englishFixture() {
        CrawlerMatch match = new CrawlerMatch();
        match.setId(77L);
        match.setExternalMatchId("bbc-77");
        match.setSource("bbc-scores");
        match.setLeagueId("39");
        match.setLeagueName("英超");
        match.setHomeTeamName("Liverpool");
        match.setAwayTeamName("Villarreal");
        match.setHomeScore(2);
        match.setAwayScore(1);
        match.setStatus("FT");
        match.setMatchTime(LocalDateTime.of(2026, 5, 1, 20, 0));
        return match;
    }
}
