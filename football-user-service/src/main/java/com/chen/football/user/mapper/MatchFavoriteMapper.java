package com.chen.football.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chen.football.user.entity.MatchFavoriteEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MatchFavoriteMapper extends BaseMapper<MatchFavoriteEntity> {
    @Select("SELECT f.id, f.user_id, COALESCE(local_match.id, provider_match.id, f.fixture_id) AS fixture_id, "
            + "COALESCE(NULLIF(f.home_team_name,''), local_match.home_team_name, provider_match.home_team_name) AS home_team_name, "
            + "COALESCE(NULLIF(f.away_team_name,''), local_match.away_team_name, provider_match.away_team_name) AS away_team_name, "
            + "COALESCE(NULLIF(f.league_name,''), local_match.league_name, provider_match.league_name) AS league_name, "
            + "COALESCE(f.match_time, local_match.match_time, provider_match.match_time) AS match_time, f.created_at "
            + "FROM t_user_favorite_match f "
            + "LEFT JOIN crawler_matches local_match ON local_match.id=f.fixture_id "
            + "LEFT JOIN crawler_matches provider_match ON local_match.id IS NULL AND provider_match.fixture_id=f.fixture_id "
            + "WHERE f.user_id=#{userId} ORDER BY f.created_at DESC")
    List<MatchFavoriteEntity> selectEnrichedByUserId(Long userId);

    @Select("SELECT * FROM t_user_favorite_match f WHERE f.user_id=#{userId} AND (f.fixture_id=#{matchId} OR f.fixture_id=(SELECT m.fixture_id FROM crawler_matches m WHERE m.id=#{matchId} LIMIT 1)) LIMIT 1")
    MatchFavoriteEntity findByPublicMatchId(Long userId, Long matchId);

    @Delete("DELETE FROM t_user_favorite_match WHERE user_id=#{userId} AND (fixture_id=#{matchId} OR fixture_id=(SELECT m.fixture_id FROM crawler_matches m WHERE m.id=#{matchId} LIMIT 1))")
    int deleteByPublicMatchId(Long userId, Long matchId);
}
