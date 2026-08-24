package com.chen.football.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.crawler.entity.CrawlerMatch;
import com.chen.football.common.dto.MatchStatus;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Mapper
public interface CrawlerMatchMapper extends BaseMapper<CrawlerMatch> {

    java.time.ZoneId BUSINESS_ZONE = java.time.ZoneId.of("Asia/Shanghai");

    default List<CrawlerMatch> findUpcomingMatches() {
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                // Never use fixtureId as a proxy for chronology.  Providers can
                // backfill old fixtures with a larger id, which made the public
                // "upcoming" endpoint return stale matches.
                .ge(CrawlerMatch::getMatchTime, LocalDateTime.now(BUSINESS_ZONE))
                .notIn(CrawlerMatch::getStatus, List.of("FT", "AET", "PEN", "CANC", "CANCELED", "CANCELLED", "ABD", "AWD", "WO", MatchStatus.SOURCE_REMOVED))
                .orderByAsc(CrawlerMatch::getMatchTime)
                .orderByAsc(CrawlerMatch::getFixtureId)
                .last("LIMIT 50"));
    }

    /**
     * Agent 的“今天赛程”不能只查未来时间：已经踢完的今天比赛仍然是
     * 今天的赛程事实。查询窗口使用应用时区，并保留当天完赛记录。
     */
    default List<CrawlerMatch> findTodayAndUpcomingMatches() {
        return findTodayAndUpcomingMatches(null);
    }

    default List<CrawlerMatch> findTodayAndUpcomingMatches(String leagueName) {
        java.time.LocalDate today = java.time.LocalDate.now(BUSINESS_ZONE);
        java.time.LocalDateTime start = today.atStartOfDay();
        java.time.LocalDateTime end = today.plusDays(8).atStartOfDay();
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(leagueName != null && !leagueName.isBlank(), CrawlerMatch::getLeagueName, leagueName)
                .ge(CrawlerMatch::getMatchTime, start)
                .lt(CrawlerMatch::getMatchTime, end)
                // NULL means the source has not supplied a status yet; keep
                // that row so the Agent can report an explicit sync gap.
                .and(q -> q.isNull(CrawlerMatch::getStatus)
                        .or().notIn(CrawlerMatch::getStatus,
                                List.of("PST", "POSTPONED", "PPD", "CANC", "CANCELED", "CANCELLED", "ABD", "AWD", "WO", MatchStatus.SOURCE_REMOVED)))
                .orderByAsc(CrawlerMatch::getMatchTime)
                .orderByAsc(CrawlerMatch::getFixtureId)
                .last("LIMIT 200"));
    }

    default List<CrawlerMatch> findLiveMatches() {
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .in(CrawlerMatch::getStatus, List.of("LIVE", "1H", "2H", "HT"))
                .orderByAsc(CrawlerMatch::getFixtureId)
                .last("LIMIT 50"));
    }

    default CrawlerMatch findByExternalId(String externalMatchId, String source) {
        if (externalMatchId == null || externalMatchId.isBlank()) return null;
        return selectOne(Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(CrawlerMatch::getExternalMatchId, externalMatchId)
                .eq(source != null && !source.isBlank(), CrawlerMatch::getSource, source)
                .last("LIMIT 1"));
    }

    default CrawlerMatch findByFixtureId(Long fixtureId) {
        return selectOne(Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(fixtureId != null, CrawlerMatch::getFixtureId, fixtureId)
                .orderByDesc(CrawlerMatch::getUpdatedAt)
                .last("LIMIT 1"));
    }

    default CrawlerMatch findByFixtureIdAndSource(Long fixtureId, String source) {
        if (fixtureId == null || fixtureId <= 0 || source == null || source.isBlank()) return null;
        return selectOne(Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(CrawlerMatch::getFixtureId, fixtureId)
                .eq(CrawlerMatch::getSource, source)
                .orderByDesc(CrawlerMatch::getUpdatedAt)
                .last("LIMIT 1"));
    }

    /**
     * 当上游先使用临时组合键、后续再返回正式事件 ID 时，按同源同场次回溯旧记录。
     * 时间窗口限定在同一自然日，避免同队伍跨日期比赛被错误合并。
     */
    default CrawlerMatch findBySourceAndTeamsOnDate(String source,
                                                     String homeTeamId,
                                                     String awayTeamId,
                                                     String homeTeamName,
                                                     String awayTeamName,
                                                     LocalDateTime matchTime) {
        if (source == null || source.isBlank() || matchTime == null) return null;
        boolean hasTeamIds = homeTeamId != null && !homeTeamId.isBlank()
                && awayTeamId != null && !awayTeamId.isBlank();
        if (!hasTeamIds && (homeTeamName == null || homeTeamName.isBlank()
                || awayTeamName == null || awayTeamName.isBlank())) return null;
        LocalDateTime dayStart = matchTime.toLocalDate().atStartOfDay();
        var query = Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(CrawlerMatch::getSource, source)
                .ge(CrawlerMatch::getMatchTime, dayStart)
                .lt(CrawlerMatch::getMatchTime, dayStart.plusDays(1));
        // Team/date alone is not enough for a double-header or a rescheduled
        // fixture. Use a bounded kickoff window only as the legacy fallback;
        // provider IDs remain the authoritative identity.
        query.ge(CrawlerMatch::getMatchTime, matchTime.minusHours(2))
                .le(CrawlerMatch::getMatchTime, matchTime.plusHours(2));
        if (hasTeamIds) {
            query.eq(CrawlerMatch::getHomeTeamId, homeTeamId)
                    .eq(CrawlerMatch::getAwayTeamId, awayTeamId);
        } else {
            query.eq(CrawlerMatch::getHomeTeamName, homeTeamName)
                    .eq(CrawlerMatch::getAwayTeamName, awayTeamName);
        }
        return selectOne(query.orderByDesc(CrawlerMatch::getUpdatedAt).last("LIMIT 1"));
    }

    /**
     * All rows written by one provider on one business date.  This is used by
     * a successful primary-source snapshot to reconcile stale scheduled rows
     * without deleting historical data.
     */
    default List<CrawlerMatch> findBySourceAndDate(String source, java.time.LocalDate date) {
        if (source == null || source.isBlank() || date == null) return List.of();
        LocalDateTime start = date.atStartOfDay();
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(CrawlerMatch::getSource, source)
                .ge(CrawlerMatch::getMatchTime, start)
                .lt(CrawlerMatch::getMatchTime, date.plusDays(1).atStartOfDay())
                .orderByAsc(CrawlerMatch::getMatchTime));
    }

    /** Same natural match across providers. Used only as a fallback when a
     * provider-specific external/fixture ID cannot identify the event. */
    default CrawlerMatch findByTeamsOnDateAnySource(String homeTeamId,
                                                     String awayTeamId,
                                                     String homeTeamName,
                                                     String awayTeamName,
                                                     LocalDateTime matchTime) {
        if (matchTime == null) return null;
        LocalDateTime dayStart = matchTime.toLocalDate().atStartOfDay();
        boolean hasTeamIds = homeTeamId != null && !homeTeamId.isBlank()
                && awayTeamId != null && !awayTeamId.isBlank();
        var query = Wrappers.<CrawlerMatch>lambdaQuery()
                .ge(CrawlerMatch::getMatchTime, dayStart)
                .lt(CrawlerMatch::getMatchTime, dayStart.plusDays(1));
        query.ge(CrawlerMatch::getMatchTime, matchTime.minusHours(2))
                .le(CrawlerMatch::getMatchTime, matchTime.plusHours(2));
        if (hasTeamIds) {
            query.eq(CrawlerMatch::getHomeTeamId, homeTeamId)
                    .eq(CrawlerMatch::getAwayTeamId, awayTeamId);
        } else if (homeTeamName != null && !homeTeamName.isBlank()
                && awayTeamName != null && !awayTeamName.isBlank()) {
            query.eq(CrawlerMatch::getHomeTeamName, homeTeamName)
                    .eq(CrawlerMatch::getAwayTeamName, awayTeamName);
        } else {
            return null;
        }
        return selectOne(query.orderByDesc(CrawlerMatch::getUpdatedAt).last("LIMIT 1"));
    }

    /**
     * 解析前端使用的公开数字比赛 ID。
     *
     * fixture_id 是第三方提供的比赛 ID，不能在所有场景下回退到本地主键，
     * 否则 API-Football 的 fixture 123 可能误命中本地 id=123 的 BBC 记录。
     * 只有明确处于“公开路由/预测查询”链路时，才允许使用本地 id 兼容没有
     * 数字 fixture_id 的网页数据源。
     */
    default CrawlerMatch findByPublicId(Long publicId) {
        if (publicId == null || publicId <= 0) return null;
        CrawlerMatch match = findByFixtureId(publicId);
        return match != null ? match : selectById(publicId);
    }

    /** Resolve a concrete upcoming fixture when Agent receives two team names
     * but the UI did not pass a fixtureId (for example, a natural-language
     * question about tomorrow's match). */
    default CrawlerMatch findUpcomingByTeams(String homeTeamName,
                                             String awayTeamName,
                                             String leagueName) {
        if (homeTeamName == null || homeTeamName.isBlank() || awayTeamName == null || awayTeamName.isBlank()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        var query = Wrappers.<CrawlerMatch>lambdaQuery()
                .eq(CrawlerMatch::getHomeTeamName, homeTeamName)
                .eq(CrawlerMatch::getAwayTeamName, awayTeamName)
                .ge(CrawlerMatch::getMatchTime, now.minusMinutes(30))
                .lt(CrawlerMatch::getMatchTime, now.plusDays(8))
                .and(q -> q.isNull(CrawlerMatch::getStatus)
                        .or().notIn(CrawlerMatch::getStatus,
                                List.of("PST", "POSTPONED", "PPD", "FT", "AET", "PEN", "CANC", "CANCELED", "CANCELLED", "ABD", "AWD", "WO", MatchStatus.SOURCE_REMOVED)));
        if (leagueName != null && !leagueName.isBlank()) query.eq(CrawlerMatch::getLeagueName, leagueName);
        return selectOne(query.orderByAsc(CrawlerMatch::getMatchTime).orderByDesc(CrawlerMatch::getUpdatedAt).last("LIMIT 1"));
    }

    default List<CrawlerMatch> findByTimeRange(Date start, Date end) {
        LocalDateTime startTime = start == null ? null : start.toInstant().atZone(BUSINESS_ZONE).toLocalDateTime();
        LocalDateTime endTime = end == null ? null : end.toInstant().atZone(BUSINESS_ZONE).toLocalDateTime();
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .ge(startTime != null, CrawlerMatch::getMatchTime, startTime)
                // 时间范围采用 [start, end)，避免“截至次日零点”把下一天
                // 的比赛混入当天，也避免同一时刻被两个窗口重复统计。
                .lt(endTime != null, CrawlerMatch::getMatchTime, endTime)
                .orderByAsc(CrawlerMatch::getMatchTime));
    }

    default List<CrawlerMatch> findByDate(Date date) {
        if (date == null) return List.of();
        java.time.LocalDate day = date.toInstant().atZone(BUSINESS_ZONE).toLocalDate();
        Date start = Date.from(day.atStartOfDay(BUSINESS_ZONE).toInstant());
        Date end = Date.from(day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
        return findByTimeRange(start, end);
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

    /**
     * 已完赛的近期样本，供 Agent 的“近期状态”使用。
     * 赛程表同时包含未来比赛，不能把未开始场次计入球队近期战绩。
     */
    default List<CrawlerMatch> findCompletedRecentByTeamName(String teamName, int limit) {
        if (teamName == null || teamName.isBlank()) return List.of();
        return selectList(Wrappers.<CrawlerMatch>lambdaQuery()
                .and(w -> w.eq(CrawlerMatch::getHomeTeamName, teamName).or().eq(CrawlerMatch::getAwayTeamName, teamName))
                .lt(CrawlerMatch::getMatchTime, LocalDateTime.now(BUSINESS_ZONE))
                .isNotNull(CrawlerMatch::getHomeScore)
                .isNotNull(CrawlerMatch::getAwayScore)
                .notIn(CrawlerMatch::getStatus, List.of("NS", "TBD", "PST", "CANC", "CANCELED", "CANCELLED", "ABD", "AWD", "WO", MatchStatus.SOURCE_REMOVED))
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
                .ge(CrawlerMatch::getMatchTime, date.toInstant().atZone(BUSINESS_ZONE).toLocalDateTime().toLocalDate().atStartOfDay())
                .lt(CrawlerMatch::getMatchTime, date.toInstant().atZone(BUSINESS_ZONE).toLocalDateTime().toLocalDate().plusDays(1).atStartOfDay())));
    }
}
