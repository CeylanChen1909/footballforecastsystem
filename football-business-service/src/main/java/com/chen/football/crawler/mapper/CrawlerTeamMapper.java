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

    /** 优先返回国内联赛档案，避免同一俱乐部在欧冠/联赛两条记录中随机命中欧战档案。 */
    @Select("SELECT * FROM crawler_teams WHERE name = #{name} "
            + "ORDER BY CASE WHEN league_name IN ('欧冠','欧联','欧洲冠军联赛','欧洲联赛','UEFA Champions League','UEFA Europa League') THEN 1 ELSE 0 END, "
            + "updated_at DESC LIMIT 1")
    CrawlerTeam findPreferredByName(@Param("name") String name);

    @Select("SELECT * FROM crawler_teams WHERE league_name = #{leagueName} AND name LIKE CONCAT('%', #{keyword}, '%') ORDER BY name ASC")
    List<CrawlerTeam> searchByLeagueAndName(@Param("leagueName") String leagueName, @Param("keyword") String keyword);
}
