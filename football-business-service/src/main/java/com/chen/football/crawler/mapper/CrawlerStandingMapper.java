package com.chen.football.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chen.football.crawler.entity.CrawlerStanding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CrawlerStandingMapper extends BaseMapper<CrawlerStanding> {

    @Select("SELECT * FROM crawler_standings WHERE league_id = #{leagueId} AND season = #{season} ORDER BY `rank`")
    List<CrawlerStanding> findByLeagueAndSeason(@Param("leagueId") String leagueId, @Param("season") String season);

    @Select("SELECT * FROM crawler_standings WHERE league_id = #{leagueId} ORDER BY updated_at DESC LIMIT 1")
    CrawlerStanding findLatestByLeague(@Param("leagueId") String leagueId);

    @Select("SELECT * FROM crawler_standings WHERE league_name = #{leagueName} ORDER BY `rank` ASC")
    List<CrawlerStanding> findByLeagueName(@Param("leagueName") String leagueName);

    @Select("SELECT * FROM crawler_standings WHERE league_name = #{leagueName} AND season = #{season} ORDER BY `rank` ASC")
    List<CrawlerStanding> findByLeagueNameAndSeason(@Param("leagueName") String leagueName, @Param("season") String season);

    @Select("SELECT DISTINCT season FROM crawler_standings WHERE league_name = #{leagueName} AND season IS NOT NULL AND season <> '' ORDER BY season DESC")
    List<String> findSeasonsByLeagueName(@Param("leagueName") String leagueName);
}
