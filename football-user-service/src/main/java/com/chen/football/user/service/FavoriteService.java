package com.chen.football.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.common.context.UserContext;
import com.chen.football.user.entity.FavoriteEntity;
import com.chen.football.user.entity.MatchFavoriteEntity;
import com.chen.football.user.mapper.FavoriteMapper;
import com.chen.football.user.mapper.MatchFavoriteMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final MatchFavoriteMapper matchFavoriteMapper;

    public FavoriteService(FavoriteMapper favoriteMapper, MatchFavoriteMapper matchFavoriteMapper) {
        this.favoriteMapper = favoriteMapper;
        this.matchFavoriteMapper = matchFavoriteMapper;
    }

    public boolean addFavorite(Long userId, Long teamId, String teamName) {
        if (userId == null) return false;

        // Check duplicate
        FavoriteEntity existing = favoriteMapper.selectOne(
                new LambdaQueryWrapper<FavoriteEntity>()
                        .eq(FavoriteEntity::getUserId, userId)
                        .eq(FavoriteEntity::getTeamId, teamId));
        if (existing != null) return true; // already favorited

        FavoriteEntity fav = new FavoriteEntity();
        fav.setUserId(userId);
        fav.setTeamId(teamId);
        fav.setTeamName(teamName);
        fav.setCreatedAt(LocalDateTime.now());
        return favoriteMapper.insert(fav) > 0;
    }

    public boolean removeFavorite(Long userId, Long teamId) {
        if (userId == null) return false;
        return favoriteMapper.delete(
                new LambdaQueryWrapper<FavoriteEntity>()
                        .eq(FavoriteEntity::getUserId, userId)
                        .eq(FavoriteEntity::getTeamId, teamId)) > 0;
    }

    public List<FavoriteEntity> listFavorites() {
        Long userId = UserContext.getUserId();
        if (userId == null) return List.of();
        return favoriteMapper.selectList(
                new LambdaQueryWrapper<FavoriteEntity>()
                        .eq(FavoriteEntity::getUserId, userId)
                        .orderByDesc(FavoriteEntity::getCreatedAt));
    }

    public boolean addFavoriteMatch(Long userId, Long fixtureId, String matchLabel) {
        if (userId == null) return false;

        String home = "未知主队";
        String away = "未知客队";
        if (matchLabel != null && matchLabel.contains(" vs ")) {
            String[] arr = matchLabel.split(" vs ", 2);
            if (arr.length > 0 && arr[0] != null && !arr[0].isBlank()) {
                home = arr[0].trim();
            }
            if (arr.length > 1 && arr[1] != null && !arr[1].isBlank()) {
                away = arr[1].trim();
            }
        }

        MatchFavoriteEntity existing = matchFavoriteMapper.selectOne(
                new LambdaQueryWrapper<MatchFavoriteEntity>()
                        .eq(MatchFavoriteEntity::getUserId, userId)
                        .eq(MatchFavoriteEntity::getFixtureId, fixtureId));
        if (existing != null) {
            existing.setHomeTeamName(home);
            existing.setAwayTeamName(away);
            return matchFavoriteMapper.updateById(existing) > 0;
        }

        MatchFavoriteEntity fav = new MatchFavoriteEntity();
        fav.setUserId(userId);
        fav.setFixtureId(fixtureId);
        fav.setHomeTeamName(home);
        fav.setAwayTeamName(away);
        fav.setCreatedAt(LocalDateTime.now());
        return matchFavoriteMapper.insert(fav) > 0;
    }

    public boolean removeFavoriteMatch(Long userId, Long fixtureId) {
        if (userId == null) return false;
        return matchFavoriteMapper.delete(
                new LambdaQueryWrapper<MatchFavoriteEntity>()
                        .eq(MatchFavoriteEntity::getUserId, userId)
                        .eq(MatchFavoriteEntity::getFixtureId, fixtureId)) > 0;
    }

    public List<MatchFavoriteEntity> listFavoriteMatches() {
        Long userId = UserContext.getUserId();
        if (userId == null) return List.of();
        return matchFavoriteMapper.selectList(
                new LambdaQueryWrapper<MatchFavoriteEntity>()
                        .eq(MatchFavoriteEntity::getUserId, userId)
                        .orderByDesc(MatchFavoriteEntity::getCreatedAt));
    }
}
