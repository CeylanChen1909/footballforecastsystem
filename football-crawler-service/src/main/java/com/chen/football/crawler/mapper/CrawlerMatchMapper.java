package com.chen.football.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.crawler.entity.CrawlerMatch;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Mapper
public interface CrawlerMatchMapper extends BaseMapper<CrawlerMatch> {

    default List<CrawlerMatch> findUpcomingMatches() {
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .orderByAsc(CrawlerMatch::getFixtureId)
                .last("LIMIT 50"));
    }

    default List<CrawlerMatch> findLiveMatches() {
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .in(CrawlerMatch::getStatus, List.of("LIVE", "1H", "2H", "HT"))
                .orderByAsc(CrawlerMatch::getFixtureId)
                .last("LIMIT 50"));
    }

    default CrawlerMatch findByExternalId(String externalMatchId, String source) {
        return selectOne(Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(CrawlerMatch::getExternalMatchId, externalMatchId)
                .eq(source != null && !source.isBlank(), CrawlerMatch::getSource, source)
                .last("LIMIT 1"));
    }

    default List<CrawlerMatch> findByTimeRange(Date start, Date end) {
        LocalDateTime startTime = start == null ? null : start.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime endTime = end == null ? null : end.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .ge(startTime != null, CrawlerMatch::getMatchTime, startTime)
                .le(endTime != null, CrawlerMatch::getMatchTime, endTime)
                .orderByAsc(CrawlerMatch::getMatchTime));
    }

    default List<CrawlerMatch> findByDate(Date date) {
        return findByTimeRange(date, date);
    }

    default List<CrawlerMatch> findByLeagueName(String leagueName) {
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(CrawlerMatch::getLeagueName, leagueName)
                .orderByDesc(CrawlerMatch::getMatchTime));
    }

    default CrawlerMatch findLatestByExternalId(String externalMatchId) {
        return selectOne(Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(CrawlerMatch::getExternalMatchId, externalMatchId)
                .orderByDesc(CrawlerMatch::getUpdatedAt)
                .last("LIMIT 1"));
    }

    default List<CrawlerMatch> searchMatches(String keyword) {
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .and(w -> w.like(CrawlerMatch::getLeagueName, keyword)
                        .or().like(CrawlerMatch::getHomeTeamName, keyword)
                        .or().like(CrawlerMatch::getAwayTeamName, keyword))
                .orderByDesc(CrawlerMatch::getMatchTime));
    }

    default List<CrawlerMatch> findRecentByTeamName(String teamName, int limit) {
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .and(w -> w.eq(CrawlerMatch::getHomeTeamName, teamName).or().eq(CrawlerMatch::getAwayTeamName, teamName))
                .orderByDesc(CrawlerMatch::getMatchTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 20))));
    }

    default List<CrawlerMatch> findHeadToHead(String homeTeam, String awayTeam, int limit) {
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .and(w -> w.eq(CrawlerMatch::getHomeTeamName, homeTeam).eq(CrawlerMatch::getAwayTeamName, awayTeam)
                        .or().eq(CrawlerMatch::getHomeTeamName, awayTeam).eq(CrawlerMatch::getAwayTeamName, homeTeam))
                .orderByDesc(CrawlerMatch::getMatchTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 20))));
    }

    default Integer countByDate(Date date) {
        return Math.toIntExact(selectCount(Wrappers.<CrawlerMatch>lambdaQuery()
                .ge(CrawlerMatch::getMatchTime, date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toLocalDate().atStartOfDay())
                .lt(CrawlerMatch::getMatchTime, date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toLocalDate().plusDays(1).atStartOfDay())));
    }
}
