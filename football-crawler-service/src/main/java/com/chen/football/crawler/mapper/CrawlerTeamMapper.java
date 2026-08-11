package com.chen.football.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chen.football.crawler.entity.CrawlerTeam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CrawlerTeamMapper extends BaseMapper<CrawlerTeam> {

    @Select("SELECT * FROM crawler_teams WHERE league_name = #{leagueName}")
    List<CrawlerTeam> findByLeague(@Param("leagueName") String leagueName);

    @Select("SELECT * FROM crawler_teams WHERE name LIKE CONCAT('%', #{name}, '%')")
    List<CrawlerTeam> searchByName(@Param("name") String name);

    @Select("SELECT * FROM crawler_teams WHERE name = #{name} ORDER BY updated_at DESC LIMIT 1")
    CrawlerTeam findLatestByName(@Param("name") String name);

    @Select("SELECT * FROM crawler_teams WHERE league_name = #{leagueName} AND name LIKE CONCAT('%', #{keyword}, '%') ORDER BY name ASC")
    List<CrawlerTeam> searchByLeagueAndName(@Param("leagueName") String leagueName, @Param("keyword") String keyword);
}
