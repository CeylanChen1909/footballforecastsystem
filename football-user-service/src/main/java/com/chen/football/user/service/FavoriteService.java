package com.chen.football.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.service.AdminAuditService;
import com.chen.football.user.entity.FavoriteEntity;
import com.chen.football.user.entity.MatchFavoriteEntity;
import com.chen.football.user.mapper.FavoriteMapper;
import com.chen.football.user.mapper.MatchFavoriteMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final MatchFavoriteMapper matchFavoriteMapper;
    private final AdminAuditService auditService;

    public FavoriteService(FavoriteMapper favoriteMapper, MatchFavoriteMapper matchFavoriteMapper, AdminAuditService auditService) {
        this.favoriteMapper = favoriteMapper;
        this.matchFavoriteMapper = matchFavoriteMapper;
        this.auditService = auditService;
    }

    public boolean addFavorite(Long userId, String teamId, String teamName) {
        if (userId == null || !StringUtils.hasText(teamId)) return false;
        String normalizedTeamName = normalize(teamName);

        FavoriteEntity existing = favoriteMapper.selectOne(
                new LambdaQueryWrapper<FavoriteEntity>()
                        .eq(FavoriteEntity::getUserId, userId)
                        .eq(FavoriteEntity::getTeamId, teamId));
        if (existing != null) return true;

        FavoriteEntity fav = new FavoriteEntity();
        fav.setUserId(userId);
        fav.setTeamId(teamId);
        fav.setTeamName(StringUtils.hasText(normalizedTeamName) ? normalizedTeamName : teamId);
        fav.setCreatedAt(LocalDateTime.now());
        boolean ok = favoriteMapper.insert(fav) > 0;
        if (ok) {
            auditService.record("USER", "FAVORITE_ADD", "t_favorite", teamId, "userId=" + userId + ", teamName=" + fav.getTeamName(), "SUCCESS");
        }
        return ok;
    }

    public boolean removeFavorite(Long userId, String teamId) {
        if (userId == null || !StringUtils.hasText(teamId)) return false;
        boolean ok = favoriteMapper.delete(
                new LambdaQueryWrapper<FavoriteEntity>()
                        .eq(FavoriteEntity::getUserId, userId)
                        .eq(FavoriteEntity::getTeamId, teamId)) > 0;
        if (ok) {
            auditService.record("USER", "FAVORITE_REMOVE", "t_favorite", teamId, "userId=" + userId, "SUCCESS");
        }
        return ok;
    }

    public List<FavoriteEntity> listFavorites() {
        Long userId = UserContext.getUserId();
        if (userId == null) return List.of();
        return favoriteMapper.selectList(
                new LambdaQueryWrapper<FavoriteEntity>()
                        .eq(FavoriteEntity::getUserId, userId)
                        .orderByDesc(FavoriteEntity::getCreatedAt));
    }

    public boolean addFavoriteMatch(Long userId, Long fixtureId, String matchLabel, String leagueName, String matchTimeText) {
        if (userId == null || fixtureId == null || fixtureId <= 0) return false;

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

        MatchFavoriteEntity existing = matchFavoriteMapper.findByPublicMatchId(userId, fixtureId);
        if (existing != null) {
            // Migrate a legacy provider fixture key to the canonical local id
            // whenever the current request carries the local match id.
            existing.setFixtureId(fixtureId);
            existing.setHomeTeamName(home);
            existing.setAwayTeamName(away);
            existing.setLeagueName(normalize(leagueName));
            existing.setMatchTime(parseMatchTime(matchTimeText));
            boolean ok = matchFavoriteMapper.updateById(existing) > 0;
            if (ok) {
                auditService.record("USER", "FAVORITE_MATCH_UPDATE", "t_match_favorite", String.valueOf(fixtureId), "userId=" + userId + ", label=" + home + " vs " + away, "SUCCESS");
            }
            return ok;
        }

        MatchFavoriteEntity fav = new MatchFavoriteEntity();
        fav.setUserId(userId);
        fav.setFixtureId(fixtureId);
        fav.setHomeTeamName(home);
        fav.setAwayTeamName(away);
        fav.setLeagueName(normalize(leagueName));
        fav.setMatchTime(parseMatchTime(matchTimeText));
        fav.setCreatedAt(LocalDateTime.now());
        boolean ok = matchFavoriteMapper.insert(fav) > 0;
        if (ok) {
            auditService.record("USER", "FAVORITE_MATCH_ADD", "t_match_favorite", String.valueOf(fixtureId), "userId=" + userId + ", label=" + home + " vs " + away, "SUCCESS");
        }
        return ok;
    }

    public boolean removeFavoriteMatch(Long userId, Long fixtureId) {
        if (userId == null || fixtureId == null || fixtureId <= 0) return false;
        boolean ok = matchFavoriteMapper.deleteByPublicMatchId(userId, fixtureId) > 0;
        if (ok) {
            auditService.record("USER", "FAVORITE_MATCH_REMOVE", "t_match_favorite", String.valueOf(fixtureId), "userId=" + userId, "SUCCESS");
        }
        return ok;
    }

    public List<MatchFavoriteEntity> listFavoriteMatches() {
        Long userId = UserContext.getUserId();
        if (userId == null) return List.of();
        return matchFavoriteMapper.selectEnrichedByUserId(userId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private LocalDateTime parseMatchTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try { return LocalDateTime.parse(text.replace('T', ' '), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
            catch (DateTimeParseException ignoredAgain) { return null; }
        }
    }
}
