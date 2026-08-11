package com.chen.football.user.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.util.JwtUtil;
import com.chen.football.user.entity.FavoriteEntity;
import io.jsonwebtoken.ExpiredJwtException;
import com.chen.football.user.entity.UserEntity;
import com.chen.football.user.mapper.UserMapper;
import com.chen.football.user.service.AuthService;
import com.chen.football.user.service.FavoriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private final AuthService authService;
    private final FavoriteService favoriteService;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    public UserController(AuthService authService, FavoriteService favoriteService, JwtUtil jwtUtil, UserMapper userMapper) {
        this.authService = authService;
        this.favoriteService = favoriteService;
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.register(body.getOrDefault("username", ""), body.getOrDefault("password", "")));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.login(body.getOrDefault("username", ""), body.getOrDefault("password", "")));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        if (info.userId == null) return ApiResponse.ok(Map.of("guest", true));
        return ApiResponse.ok(Map.of("userId", info.userId, "username", info.username != null ? info.username : "", "role", info.role != null ? info.role : "USER", "loggedIn", true));
    }

    @GetMapping("/favorites")
    public ApiResponse<List<FavoriteEntity>> favorites(HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        if (info.userId == null) return ApiResponse.ok(List.of());
        return ApiResponse.ok(favoriteService.listFavorites());
    }

    @GetMapping("/favorites/matches")
    public ApiResponse<?> favoriteMatches(HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        log.info("GET /api/users/favorites/matches -> userId={}, username={}, authPresent={}",
                info.userId, info.username, hasAuthHeader(request));
        if (info.userId == null) return ApiResponse.ok(List.of());
        return ApiResponse.ok(favoriteService.listFavoriteMatches());
    }

    @PostMapping("/favorites")
    public ApiResponse<Map<String, Object>> addFavorite(@RequestBody Map<String, String> body, HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        log.info("POST /api/users/favorites -> userId={}, username={}, authPresent={}, body={}",
                info.userId, info.username, hasAuthHeader(request), body);
        if (info.userId == null) return ApiResponse.ok(Map.of("ok", false, "message", "unauthorized"));
        String teamIdValue = body.getOrDefault("teamId", "");
        String teamName = body.getOrDefault("teamName", "");
        Long teamId;
        try {
            teamId = Long.parseLong(teamIdValue);
        } catch (Exception ex) {
            log.warn("POST /api/users/favorites invalid teamId, falling back to teamName. teamIdValue={}, teamName={}", teamIdValue, teamName);
            teamId = null;
        }
        if (teamId == null && StringUtils.hasText(teamName)) {
            teamId = (long) Math.abs(teamName.trim().hashCode());
            log.info("POST /api/users/favorites generated numeric teamId={} from teamName={}", teamId, teamName);
        }
        if (teamId == null) {
            return ApiResponse.ok(Map.of("ok", false, "message", "invalid teamId"));
        }
        boolean ok = favoriteService.addFavorite(info.userId, teamId, teamName);
        log.info("POST /api/users/favorites result -> ok={}, userId={}, teamId={}", ok, info.userId, teamId);
        return ApiResponse.ok(Map.of("ok", ok));
    }

    @PostMapping("/favorites/matches")
    public ApiResponse<Map<String, Object>> addFavoriteMatch(@RequestBody Map<String, String> body, HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        log.info("POST /api/users/favorites/matches -> userId={}, username={}, authPresent={}, body={}",
                info.userId, info.username, hasAuthHeader(request), body);
        if (info.userId == null) return ApiResponse.ok(Map.of("ok", false, "message", "unauthorized"));
        Long fixtureId = Long.parseLong(body.getOrDefault("fixtureId", "0"));
        String matchLabel = body.getOrDefault("matchLabel", "");
        boolean ok = favoriteService.addFavoriteMatch(info.userId, fixtureId, matchLabel);
        log.info("POST /api/users/favorites/matches result -> ok={}, userId={}, fixtureId={}", ok, info.userId, fixtureId);
        return ApiResponse.ok(Map.of("ok", ok));
    }

    @DeleteMapping("/favorites/{teamId}")
    public ApiResponse<Map<String, Object>> removeFavorite(@PathVariable(name = "teamId") Long teamId, HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        if (info.userId == null) return ApiResponse.ok(Map.of("ok", false, "message", "unauthorized"));
        return ApiResponse.ok(Map.of("ok", favoriteService.removeFavorite(info.userId, teamId)));
    }

    @DeleteMapping("/favorites/matches/{fixtureId}")
    public ApiResponse<Map<String, Object>> removeFavoriteMatch(@PathVariable(name = "fixtureId") Long fixtureId, HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        if (info.userId == null) return ApiResponse.ok(Map.of("ok", false, "message", "unauthorized"));
        return ApiResponse.ok(Map.of("ok", favoriteService.removeFavoriteMatch(info.userId, fixtureId)));
    }

    @PutMapping("/role")
    public ApiResponse<Map<String, Object>> updateRole(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.updateRole(Long.parseLong(body.getOrDefault("userId", "0")), body.getOrDefault("role", "USER")));
    }

    private UserInfo resolveUser(HttpServletRequest request) {
        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        String role = UserContext.getRole();
        if (userId != null) {
            return new UserInfo(userId, username, role);
        }
        String auth = request.getHeader("Authorization");
        if (!StringUtils.hasText(auth) || !auth.startsWith("Bearer ")) {
            log.warn("resolveUser: missing or invalid Authorization header, authPresent={}", hasAuthHeader(request));
            return new UserInfo(null, null, null);
        }
        try {
            String token = auth.substring(7).trim();
            log.info("resolveUser: tokenPrefix={}...", token.length() > 12 ? token.substring(0, 12) : token);
            userId = jwtUtil.extractUserId(token);
            username = jwtUtil.extractUsername(token);
            role = jwtUtil.extractRole(token);
            if (!StringUtils.hasText(username)) {
                UserEntity u = userMapper.selectById(userId);
                username = u != null ? u.getUsername() : null;
            }
            log.info("resolveUser: parsed userId={}, username={}, role={}", userId, username, role);
            return new UserInfo(userId, username, role);
        } catch (ExpiredJwtException e) {
            log.warn("resolveUser: token expired, will reject request and ask client to re-login", e);
            return new UserInfo(null, null, null);
        } catch (Exception e) {
            log.error("resolveUser: failed to parse token", e);
            return new UserInfo(null, null, null);
        }
    }

    private boolean hasAuthHeader(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return StringUtils.hasText(auth);
    }

    private record UserInfo(Long userId, String username, String role) {}
}
