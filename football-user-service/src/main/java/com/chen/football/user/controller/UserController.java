package com.chen.football.user.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.dto.ApiError;
import com.chen.football.common.exception.UnauthorizedException;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.common.util.JwtUtil;
import com.chen.football.user.dto.ActionResponse;
import com.chen.football.user.dto.AuthResponse;
import com.chen.football.user.dto.FavoriteListResponse;
import com.chen.football.user.dto.MeResponse;
import com.chen.football.user.entity.FavoriteEntity;
import com.chen.football.user.entity.UserEntity;
import com.chen.football.user.mapper.UserMapper;
import com.chen.football.user.service.AuthService;
import com.chen.football.user.service.EmailVerificationService;
import com.chen.football.user.service.FavoriteService;
import com.chen.football.user.service.LoginCaptchaService;
import com.chen.football.user.service.RegistrationCaptchaService;
import com.chen.football.user.service.RateLimitService;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    public static final String LEGAL_CONSENT_VERSION = "20260824-v1";
    private static final int MAX_USERNAME_LENGTH = 32;
    private static final int MAX_PASSWORD_LENGTH = 64;

    private final AuthService authService;
    private final FavoriteService favoriteService;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final RateLimitService rateLimitService;
    private final EmailVerificationService emailVerificationService;
    private final LoginCaptchaService loginCaptchaService;
    private final RegistrationCaptchaService registrationCaptchaService;
    private final JdbcTemplate jdbcTemplate;
    private volatile boolean preferenceTableReady;
    private volatile boolean notificationTableReady;

    @Value("${security.refresh-token-cookie-only:false}")
    private boolean refreshTokenCookieOnly;

    /** Only trust forwarded client IPs when the service is behind our gateway. */
    @Value("${security.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    public UserController(AuthService authService, FavoriteService favoriteService, JwtUtil jwtUtil, UserMapper userMapper, RateLimitService rateLimitService, EmailVerificationService emailVerificationService, LoginCaptchaService loginCaptchaService, RegistrationCaptchaService registrationCaptchaService, JdbcTemplate jdbcTemplate) {
        this.authService = authService;
        this.favoriteService = favoriteService;
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.rateLimitService = rateLimitService;
        this.emailVerificationService = emailVerificationService;
        this.loginCaptchaService = loginCaptchaService;
        this.registrationCaptchaService = registrationCaptchaService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String ip = clientIp(request);
        if (!rateLimitService.isAllowed("register:" + ip, 10, 3600)) {
            return ApiResponse.ok(AuthResponse.failure("注册过于频繁，请稍后再试"));
        }
        String email = EmailVerificationService.normalizeEmail(body.get("email"));
        String nickname = normalize(body.get("nickname"));
        String password = body.getOrDefault("password", "");
        String verificationCode = normalize(body.get("verificationCode"));
        if (!EmailVerificationService.isValidEmail(email)) {
            return ApiResponse.ok(AuthResponse.failure("请输入有效邮箱"));
        }
        if (!StringUtils.hasText(nickname) || nickname.length() > MAX_USERNAME_LENGTH) {
            return ApiResponse.ok(AuthResponse.failure("昵称不能为空且不能超过32个字符"));
        }
        if (!StringUtils.hasText(password) || password.length() < 8 || password.length() > MAX_PASSWORD_LENGTH) {
            return ApiResponse.ok(AuthResponse.failure("密码长度需在8-64位之间"));
        }
        // 图形验证码在发送邮箱验证码时已经完成一次性校验；注册提交不再要求用户重复输入。
        // 邮箱验证码先只验证不消费，避免昵称重复等业务校验失败后被误判为“已过期”。
        if (!emailVerificationService.verify(email, "REGISTER", verificationCode)) {
            return ApiResponse.ok(AuthResponse.failure("邮箱验证码错误或已过期"));
        }
        String registrationError = authService.validateEmailRegistration(email, nickname, password);
        if (registrationError != null) return ApiResponse.ok(AuthResponse.failure(registrationError));
        Map<String, Object> result = authService.registerEmail(email, nickname, password);
        if (Boolean.TRUE.equals(result.get("ok"))) emailVerificationService.consume(email, "REGISTER");
        return ApiResponse.ok(toAuthResponse(result));
    }

    @PostMapping("/email/verification-code")
    public ApiResponse<Map<String, Object>> sendEmailVerificationCode(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String scene = body.get("scene");
        if ("REGISTER".equalsIgnoreCase(scene)
                && !registrationCaptchaService.verifyAndConsume(body.get("captchaId"), body.get("captchaAnswer"), clientIp(request))) {
            return ApiResponse.ok(Map.of("ok", false, "message", "请先完成图形验证"));
        }
        return ApiResponse.ok(emailVerificationService.sendCode(body.get("email"), body.get("scene"), clientIp(request)));
    }

    @GetMapping("/captcha")
    public ApiResponse<Map<String, Object>> captcha(HttpServletRequest request) {
        return ApiResponse.ok(loginCaptchaService.issue(clientIp(request)));
    }

    @GetMapping("/register/captcha")
    public ApiResponse<Map<String, Object>> registrationCaptcha(HttpServletRequest request) {
        return ApiResponse.ok(registrationCaptchaService.issue(clientIp(request)));
    }

    @GetMapping("/legal-consent/status")
    public ApiResponse<Map<String, Object>> legalConsentStatus() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ApiResponse.ok(Map.of("required", true, "accepted", false, "version", LEGAL_CONSENT_VERSION));
        }
        try {
            String version = jdbcTemplate.query(
                    "SELECT consent_version FROM t_user_legal_consent WHERE user_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, userId);
            return ApiResponse.ok(Map.of("required", true, "accepted", LEGAL_CONSENT_VERSION.equals(version), "version", LEGAL_CONSENT_VERSION));
        } catch (Exception ex) {
            log.error("读取用户协议确认状态失败 userId={}: {}", userId, ex.getMessage());
            return ApiResponse.ok(Map.of(
                    "required", true,
                    "accepted", false,
                    "version", LEGAL_CONSENT_VERSION,
                    "message", "协议记录尚未初始化，请先执行数据库迁移 V2026082404__user_legal_consent.sql"
            ));
        }
    }

    @PostMapping("/legal-consent/accept")
    public ApiResponse<Map<String, Object>> acceptLegalConsent(HttpServletRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("请先登录");
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 512) userAgent = userAgent.substring(0, 512);
        try {
            jdbcTemplate.update("INSERT INTO t_user_legal_consent(user_id,consent_version,agreed_at,ip_address,user_agent) VALUES(?,?,NOW(),?,?) "
                            + "ON DUPLICATE KEY UPDATE consent_version=VALUES(consent_version),agreed_at=NOW(),ip_address=VALUES(ip_address),user_agent=VALUES(user_agent)",
                    userId, LEGAL_CONSENT_VERSION, clientIp(request), userAgent);
            return ApiResponse.ok(Map.of("accepted", true, "version", LEGAL_CONSENT_VERSION));
        } catch (Exception ex) {
            log.error("保存用户协议确认失败 userId={}: {}", userId, ex.getMessage());
            return ApiResponse.ok(Map.of(
                    "accepted", false,
                    "message", "协议确认无法保存，请先执行数据库迁移 V2026082404__user_legal_consent.sql"
            ));
        }
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        // 登录限流：同一 IP 每分钟最多 20 次（防爆破）
        String ip = clientIp(request);
        if (!rateLimitService.isAllowed("login:" + ip, 20, 60)) {
            return ApiResponse.ok(AuthResponse.failure("登录尝试过于频繁，请稍后再试"));
        }
        String account = normalize(firstNonBlank(body.get("account"), body.get("email"), body.get("username")));
        String password = body.getOrDefault("password", "");
        if (!StringUtils.hasText(account) || !StringUtils.hasText(password)) {
            return ApiResponse.ok(AuthResponse.failure("邮箱和密码不能为空"));
        }
        if (loginCaptchaService.isRequired(ip, account)
                && !loginCaptchaService.verifyAndConsume(body.get("captchaId"), body.get("captchaAnswer"))) {
            Map<String, Object> challenge = loginCaptchaService.issue(ip);
            return ApiResponse.ok(AuthResponse.failureWithCaptcha("登录失败次数过多，请完成安全验证", value(challenge.get("captchaId")), value(challenge.get("question"))));
        }
        Map<String, Object> resp = authService.login(account, password);
        if (Boolean.TRUE.equals(resp.get("ok"))) {
            loginCaptchaService.clearFailures(ip, account);
            setRefreshCookie(request, response, value(resp.get("refreshToken")));
        } else if (loginCaptchaService.recordFailure(ip, account)) {
            Map<String, Object> challenge = loginCaptchaService.issue(ip);
            return ApiResponse.ok(AuthResponse.failureWithCaptcha(value(resp.get("message")), value(challenge.get("captchaId")), value(challenge.get("question"))));
        }
        return ApiResponse.ok(toAuthResponse(resp));
    }

    /**
     * 刷新令牌：POST /api/users/refresh  body: {refreshToken}
     * 成功返回新的 token + refreshToken（轮换，旧 refresh token 作废）
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@RequestBody(required = false) Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = normalize(body == null ? null : body.get("refreshToken"));
        if (!StringUtils.hasText(refreshToken)) refreshToken = cookieValue(request, "football_refresh_token");
        if (!StringUtils.hasText(refreshToken)) {
            return ApiResponse.ok(AuthResponse.failure("refreshToken 不能为空"));
        }
        Map<String, Object> resp = authService.refresh(refreshToken);
        if (resp == null) {
            return ApiResponse.ok(AuthResponse.failure("登录已过期，请重新登录"));
        }
        setRefreshCookie(request, response, value(resp.get("refreshToken")));
        return ApiResponse.ok(toAuthResponse(resp));
    }

    /** 退出登录：作废 refresh token */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(@RequestBody(required = false) Map<String, String> body, HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = normalize(body == null ? null : body.get("refreshToken"));
        if (!StringUtils.hasText(refreshToken)) refreshToken = cookieValue(request, "football_refresh_token");
        authService.logout(refreshToken);
        clearRefreshCookie(request, response);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/sessions/revoke-all")
    public ApiResponse<Map<String, Object>> revokeAllSessions(@RequestBody(required = false) Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        String currentRefreshToken = body == null ? null : body.get("refreshToken");
        return ApiResponse.ok(Map.of("revoked", authService.logoutAll(userId, currentRefreshToken), "message", "已退出其他设备"));
    }

    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> sessions(@RequestHeader(name = "X-Refresh-Token", required = false) String refreshToken) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        List<Map<String, Object>> items = authService.listSessions(userId, refreshToken);
        return ApiResponse.ok(Map.of("items", items, "total", items.size()));
    }

    @PutMapping("/password")
    public ApiResponse<Map<String, Object>> changePassword(@RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        return ApiResponse.ok(authService.changePassword(userId, body.get("currentPassword"), body.get("newPassword")));
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(@RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        return ApiResponse.ok(authService.updateProfile(userId, body == null ? null : body.get("nickname")));
    }

    @PutMapping("/avatar")
    public ApiResponse<Map<String, Object>> updateAvatar(@RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        return ApiResponse.ok(authService.updateAvatar(userId, body == null ? null : body.get("avatarData")));
    }

    @DeleteMapping("/avatar")
    public ApiResponse<Map<String, Object>> clearAvatar() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        return ApiResponse.ok(authService.updateAvatar(userId, null));
    }

    @PostMapping("/password/reset-request")
    public ApiResponse<Map<String, Object>> resetPasswordRequest(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String email = EmailVerificationService.normalizeEmail(body.get("email"));
        Map<String, Object> result = emailVerificationService.sendCode(email, "RESET", clientIp(request));
        // 不暴露邮箱是否存在，避免账号枚举。
        if (!Boolean.TRUE.equals(result.get("ok"))) return ApiResponse.ok(Map.of("ok", false, "message", result.get("message")));
        return ApiResponse.ok(Map.of("ok", true, "message", "如果该邮箱已注册，验证码已发送"));
    }

    @PostMapping("/password/reset")
    public ApiResponse<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body) {
        String email = EmailVerificationService.normalizeEmail(body.get("email"));
        if (!emailVerificationService.verifyAndConsume(email, "RESET", body.get("verificationCode"))) {
            return ApiResponse.ok(Map.of("ok", false, "message", "邮箱验证码错误或已过期"));
        }
        return ApiResponse.ok(authService.resetPassword(email, body.get("newPassword")));
    }

    @DeleteMapping("/account")
    public ApiResponse<Map<String, Object>> disableAccount() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        return ApiResponse.ok(authService.disableAccount(userId));
    }

    @GetMapping("/preferences")
    public ApiResponse<Map<String, Object>> preferences() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        try {
            ensurePreferenceTable();
            Map<String, Object> row = jdbcTemplate.query("SELECT preferences_json FROM t_user_preference WHERE user_id = ?", rs -> rs.next() ? Map.of("preferences", rs.getString(1)) : Map.of(), userId);
            return ApiResponse.ok(row);
        } catch (Exception ignored) { return ApiResponse.ok(Map.of("preferences", "{}")); }
    }

    @PutMapping("/preferences")
    public ApiResponse<Map<String, Object>> updatePreferences(@RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        if (body != null && body.size() > 20) {
            return ApiResponse.ok(Map.of("saved", false, "message", "偏好项过多"));
        }
        try {
            ensurePreferenceTable();
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body == null ? Map.of() : body);
            if (json.length() > 8192) return ApiResponse.ok(Map.of("saved", false, "message", "偏好内容过大"));
            jdbcTemplate.update("INSERT INTO t_user_preference(user_id,preferences_json,updated_at) VALUES (?,?,NOW()) ON DUPLICATE KEY UPDATE preferences_json=VALUES(preferences_json),updated_at=NOW()", userId, json);
            return ApiResponse.ok(Map.of("saved", true));
        } catch (Exception e) {
            log.warn("保存用户偏好失败, userId={}: {}", userId, e.getMessage());
            return ApiResponse.ok(Map.of("saved", false, "message", "偏好保存失败"));
        }
    }

    /** 服务端通知中心，供比赛提醒和数据异常提醒使用。 */
    @GetMapping("/notifications")
    public ApiResponse<?> notifications(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        try {
            ensureNotificationTable();
            int safeLimit = Math.max(1, Math.min(limit, 100));
            String sql = "SELECT id,type,title,body,link,read_at,created_at FROM t_user_notification "
                    + "WHERE user_id = ? " + (unreadOnly ? "AND read_at IS NULL " : "")
                    + "ORDER BY created_at DESC LIMIT " + safeLimit;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
            Long unread = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_user_notification WHERE user_id = ? AND read_at IS NULL", Long.class, userId);
            return ApiResponse.ok(Map.of("items", rows, "unread", unread == null ? 0L : unread));
        } catch (Exception ex) {
            log.error("读取用户通知失败 userId={}: {}", userId, ex.getMessage(), ex);
            return ApiResponse.error(ApiError.of("NOTIFICATION_QUERY_FAILED", "通知暂时不可用"));
        }
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<?> readNotification(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        try {
            ensureNotificationTable();
            int updated = jdbcTemplate.update("UPDATE t_user_notification SET read_at = NOW() WHERE id = ? AND user_id = ?", id, userId);
            return ApiResponse.ok(Map.of("updated", updated > 0));
        } catch (Exception ex) {
            log.error("标记通知已读失败 userId={}, id={}: {}", userId, id, ex.getMessage(), ex);
            return ApiResponse.error(ApiError.of("NOTIFICATION_UPDATE_FAILED", "通知状态更新失败"));
        }
    }

    @PostMapping("/notifications/read-all")
    public ApiResponse<?> readAllNotifications() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new UnauthorizedException("未登录或登录已过期");
        try {
            ensureNotificationTable();
            int updated = jdbcTemplate.update("UPDATE t_user_notification SET read_at = NOW() WHERE user_id = ? AND read_at IS NULL", userId);
            return ApiResponse.ok(Map.of("updated", updated));
        } catch (Exception ex) {
            log.error("批量标记通知已读失败 userId={}: {}", userId, ex.getMessage(), ex);
            return ApiResponse.error(ApiError.of("NOTIFICATION_UPDATE_FAILED", "通知状态更新失败"));
        }
    }

    private void ensureNotificationTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        if (notificationTableReady) return;
        synchronized (this) {
            if (notificationTableReady) return;
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_user_notification ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL,"
                    + "title VARCHAR(160) NOT NULL, body VARCHAR(1000), link VARCHAR(255), read_at DATETIME NULL,"
                    + "created_at DATETIME NOT NULL, INDEX idx_user_notification_user_time (user_id, created_at),"
                    + "INDEX idx_user_notification_unread (user_id, read_at))");
            notificationTableReady = true;
        }
    }

    private void ensurePreferenceTable() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        if (preferenceTableReady) return;
        synchronized (this) {
            if (preferenceTableReady) return;
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_user_preference (user_id BIGINT PRIMARY KEY, preferences_json JSON NOT NULL, updated_at DATETIME NOT NULL)");
            preferenceTableReady = true;
        }
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        if (info.userId == null) return ApiResponse.ok(MeResponse.anonymous());
        UserEntity user = userMapper.selectById(info.userId);
        if (user == null) return ApiResponse.ok(MeResponse.anonymous());
        String displayName = StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
        return ApiResponse.ok(MeResponse.authenticated(
                user.getId(),
                user.getUsername() != null ? user.getUsername() : "",
                displayName != null ? displayName : "",
                user.getEmail(),
                user.getEmailVerified(),
                user.getAvatarData(),
                user.getNicknameUpdatedAt(),
                user.getRole() != null ? user.getRole() : "USER",
                user.getCreatedAt()));
    }

    /**
     * 批量查询用户名（供其他微服务内部调用，避免跨服务直连数据库）。
     * 示例：GET /api/users/batch?ids=1,2,3
     */
    @GetMapping("/batch")
    public ApiResponse<Map<String, String>> batchUsernames(@RequestParam("ids") String ids) {
        AdminGuard.requireAdmin();
        Map<String, String> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(ids)) {
            return ApiResponse.ok(result);
        }
        try {
            List<Long> idList = new ArrayList<>();
            for (String part : ids.split(",")) {
                String trimmed = part.trim();
                if (StringUtils.hasText(trimmed)) {
                    idList.add(Long.parseLong(trimmed));
                }
            }
            if (!idList.isEmpty()) {
                for (UserEntity u : userMapper.selectBatchIds(idList)) {
                    if (u != null && u.getId() != null) {
                        result.put(String.valueOf(u.getId()), u.getUsername() == null ? "" : u.getUsername());
                    }
                }
            }
        } catch (NumberFormatException e) {
            log.warn("GET /api/users/batch invalid ids: {}", ids);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/favorites")
    public ApiResponse<FavoriteListResponse<FavoriteEntity>> favorites(HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        if (info.userId == null) return ApiResponse.ok(FavoriteListResponse.of(List.of()));
        return ApiResponse.ok(FavoriteListResponse.of(favoriteService.listFavorites()));
    }

    @GetMapping("/favorites/matches")
    public ApiResponse<FavoriteListResponse<?>> favoriteMatches(HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        log.info("GET /api/users/favorites/matches -> userId={}, username={}, authPresent={}",
                info.userId, info.username, hasAuthHeader(request));
        if (info.userId == null) return ApiResponse.ok(FavoriteListResponse.of(List.of()));
        return ApiResponse.ok(FavoriteListResponse.of(favoriteService.listFavoriteMatches()));
    }

    @PostMapping("/favorites")
    public ApiResponse<ActionResponse> addFavorite(@RequestBody Map<String, String> body, HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        log.info("POST /api/users/favorites -> userId={}, username={}, authPresent={}, body={}",
                info.userId, info.username, hasAuthHeader(request), body);
        if (info.userId == null) throw new com.chen.football.common.exception.UnauthorizedException("未登录或登录已过期");
        String teamIdValue = normalize(body.get("teamId"));
        String teamName = normalize(body.get("teamName"));
        if (!StringUtils.hasText(teamIdValue) && !StringUtils.hasText(teamName)) {
            return ApiResponse.ok(ActionResponse.fail("teamId/teamName 不能为空"));
        }
        String teamId = teamIdValue;
        if (!StringUtils.hasText(teamId) && StringUtils.hasText(teamName)) {
            // 前端未传 teamId 时，用队名哈希生成稳定 ID
            teamId = String.valueOf((long) Math.abs(teamName.trim().hashCode()));
            log.info("POST /api/users/favorites generated numeric teamId={} from teamName={}", teamId, teamName);
        }
        if (!StringUtils.hasText(teamId)) {
            return ApiResponse.ok(ActionResponse.fail("invalid teamId"));
        }
        boolean ok = favoriteService.addFavorite(info.userId, teamId, teamName);
        log.info("POST /api/users/favorites result -> ok={}, userId={}, teamId={}", ok, info.userId, teamId);
        return ApiResponse.ok(ok ? ActionResponse.success() : ActionResponse.fail("保存失败"));
    }

    @PostMapping("/favorites/matches")
    public ApiResponse<ActionResponse> addFavoriteMatch(@RequestBody Map<String, String> body, HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        log.info("POST /api/users/favorites/matches -> userId={}, username={}, authPresent={}, body={}",
                info.userId, info.username, hasAuthHeader(request), body);
        if (info.userId == null) throw new com.chen.football.common.exception.UnauthorizedException("未登录或登录已过期");
        String fixtureIdValue = normalize(body.get("fixtureId"));
        if (!StringUtils.hasText(fixtureIdValue)) {
            return ApiResponse.ok(ActionResponse.fail("fixtureId 不能为空"));
        }
        Long fixtureId;
        try {
            fixtureId = Long.parseLong(fixtureIdValue);
        } catch (Exception e) {
            return ApiResponse.ok(ActionResponse.fail("fixtureId 格式错误"));
        }
        String matchLabel = normalize(body.get("matchLabel"));
        String leagueName = normalize(body.get("leagueName"));
        String matchTime = normalize(body.get("matchTime"));
        boolean ok = favoriteService.addFavoriteMatch(info.userId, fixtureId, matchLabel, leagueName, matchTime);
        log.info("POST /api/users/favorites/matches result -> ok={}, userId={}, fixtureId={}", ok, info.userId, fixtureId);
        return ApiResponse.ok(ok ? ActionResponse.success() : ActionResponse.fail("保存失败"));
    }

    @DeleteMapping("/favorites/{teamId}")
    public ApiResponse<ActionResponse> removeFavorite(@PathVariable(name = "teamId") String teamId, HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        if (info.userId == null) throw new com.chen.football.common.exception.UnauthorizedException("未登录或登录已过期");
        return ApiResponse.ok(favoriteService.removeFavorite(info.userId, teamId) ? ActionResponse.success() : ActionResponse.fail("删除失败"));
    }

    @DeleteMapping("/favorites/matches/{fixtureId}")
    public ApiResponse<ActionResponse> removeFavoriteMatch(@PathVariable(name = "fixtureId") Long fixtureId, HttpServletRequest request) {
        UserInfo info = resolveUser(request);
        if (info.userId == null) throw new com.chen.football.common.exception.UnauthorizedException("未登录或登录已过期");
        return ApiResponse.ok(favoriteService.removeFavoriteMatch(info.userId, fixtureId) ? ActionResponse.success() : ActionResponse.fail("删除失败"));
    }

    @PutMapping("/role")
    public ApiResponse<ActionResponse> updateRole(@RequestBody Map<String, String> body) {
        AdminGuard.requireSuperAdmin();
        Long userId;
        try {
            userId = Long.parseLong(body.getOrDefault("userId", "0"));
        } catch (Exception e) {
            return ApiResponse.ok(ActionResponse.fail("userId 格式错误"));
        }
        String role = normalize(body.getOrDefault("role", "USER")).toUpperCase();
        if (!List.of("USER", "ADMIN", "SUPER_ADMIN").contains(role)) {
            return ApiResponse.ok(ActionResponse.fail("role 不合法"));
        }
        Map<String, Object> resp = authService.updateRole(userId, role);
        return ApiResponse.ok(Boolean.TRUE.equals(resp.get("ok")) ? ActionResponse.success() : ActionResponse.fail(value(resp.get("message"))));
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
            userId = jwtUtil.extractUserId(token);
            UserEntity u = userMapper.selectById(userId);
            if (u == null || !"ACTIVE".equalsIgnoreCase(u.getStatus())) return new UserInfo(null, null, null);
            username = StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername();
            role = u.getRole();
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

    private String clientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String value(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private AuthResponse toAuthResponse(Map<String, Object> resp) {
        boolean ok = Boolean.TRUE.equals(resp.get("ok"));
        return new AuthResponse(
                ok,
                resp.get("message") == null ? null : String.valueOf(resp.get("message")),
                toLong(resp.get("userId")),
                value(resp.get("username")),
                value(resp.get("email")),
                value(resp.get("role")),
                value(resp.get("token")),
                refreshTokenCookieOnly ? null : value(resp.get("refreshToken")),
                false,
                null,
                null
        );
    }

    private void setRefreshCookie(HttpServletRequest request, HttpServletResponse response, String token) {
        if (!StringUtils.hasText(token)) return;
        response.addHeader("Set-Cookie", ResponseCookie.from("football_refresh_token", token)
                .httpOnly(true)
                .secure(request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")))
                .sameSite("Lax")
                .path("/api/users")
                .maxAge(Duration.ofDays(30))
                .build().toString());
    }

    private void clearRefreshCookie(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from("football_refresh_token", "")
                .httpOnly(true)
                .secure(request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")))
                .sameSite("Lax")
                .path("/api/users")
                .maxAge(Duration.ZERO)
                .build().toString());
    }

    private String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return "";
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return normalize(cookie.getValue());
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return "";
    }

    private record UserInfo(Long userId, String username, String role) {}
}
