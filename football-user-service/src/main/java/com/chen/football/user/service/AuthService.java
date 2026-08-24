package com.chen.football.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.common.exception.BusinessException;
import com.chen.football.common.exception.UnauthorizedException;
import com.chen.football.common.service.AdminAuditService;
import com.chen.football.common.service.UserSessionStateService;
import com.chen.football.common.util.JwtUtil;
import com.chen.football.user.entity.UserEntity;
import com.chen.football.user.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final List<String> ALLOWED_ROLES = List.of("USER", "ADMIN", "SUPER_ADMIN");
    private static final List<String> ALLOWED_STATUSES = List.of("ACTIVE", "DISABLED", "LOCKED");
    private static final int MAX_PASSWORD_LENGTH = 64;
    private static final int MAX_AVATAR_BYTES = 512 * 1024;
    private static final DateTimeFormatter NICKNAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 密码哈希：BCrypt（新注册一律 BCrypt，兼容旧 SHA-256 登录并自动升级） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final AdminAuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final JdbcTemplate jdbcTemplate;
    private final UserSessionStateService sessionStateService;

    /** 仅在本地演示环境显式开启；默认拒绝历史 SQL 中可能残留的公开演示账号。 */
    @Value("${security.demo-accounts-enabled:false}")
    private boolean demoAccountsEnabled;

    @Value("${security.nickname-banned-words:admin,administrator,管理员,官方,系统,客服,色情,赌博,博彩,fuck,shit}")
    private String nicknameBannedWords;

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil, AdminAuditService auditService, RefreshTokenService refreshTokenService, JdbcTemplate jdbcTemplate, UserSessionStateService sessionStateService) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.auditService = auditService;
        this.refreshTokenService = refreshTokenService;
        this.jdbcTemplate = jdbcTemplate;
        this.sessionStateService = sessionStateService;
    }

    @PostConstruct
    void ensureUserIdentityColumns() {
        if (!com.chen.football.common.service.RuntimeSchemaPolicy.runtimeDdlEnabled()) return;
        // 兼容已有开发数据库；全新部署则由 sql 脚本直接创建这些字段。
        try { jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN email VARCHAR(254) NULL"); } catch (Exception ignored) { }
        try { jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN nickname VARCHAR(64) NULL"); } catch (Exception ignored) { }
        try { jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN avatar_data MEDIUMTEXT NULL"); } catch (Exception ignored) { }
        try { jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN nickname_updated_at DATETIME NULL"); } catch (Exception ignored) { }
        try { jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 0"); } catch (Exception ignored) { }
        try { jdbcTemplate.execute("CREATE UNIQUE INDEX uk_email ON t_user(email)"); } catch (Exception ignored) { }
        try { jdbcTemplate.update("UPDATE t_user SET nickname = username WHERE nickname IS NULL OR nickname = ''"); } catch (Exception ignored) { }
    }

    public Map<String, Object> register(String username, String password) {
        if (!StringUtils.hasText(username) || username.length() > 32) {
            return fail("用户名不能为空且不能超过32个字符");
        }
        if (!StringUtils.hasText(password) || password.length() < 6 || password.length() > 64) {
            return fail("密码长度需在6-64位之间");
        }
        UserEntity existing = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (existing != null) {
            return fail("用户名已存在");
        }
        UserEntity u = new UserEntity();
        u.setUsername(username.trim());
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRole("USER");
        u.setStatus("ACTIVE");
        userMapper.insert(u);
        sessionStateService.markActive(u.getId(), u.getRole());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("userId", u.getId());
        resp.put("role", u.getRole());
        resp.put("username", u.getUsername());
        auditService.record("USER", "REGISTER", "t_user", String.valueOf(u.getId()), "username=" + u.getUsername(), "SUCCESS");
        return resp;
    }

    public Map<String, Object> registerEmail(String rawEmail, String nickname, String password) {
        String email = EmailVerificationService.normalizeEmail(rawEmail);
        String displayName = nickname == null ? "" : nickname.trim();
        if (!EmailVerificationService.isValidEmail(email)) return fail("请输入有效邮箱");
        String nicknameError = validateNickname(displayName);
        if (nicknameError != null) return fail(nicknameError);
        if (!StringUtils.hasText(password) || password.length() < 8 || password.length() > MAX_PASSWORD_LENGTH) return fail("密码长度需在8-64位之间");
        UserEntity existing = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email));
        if (existing != null) return fail("该邮箱已注册，请直接登录");
        if (findNicknameOwner(displayName, null) != null) return fail("该昵称已被使用，请换一个昵称");
        UserEntity u = new UserEntity();
        // username 保留为历史兼容字段，真正的登录账号是 email。
        u.setUsername(email);
        u.setEmail(email);
        u.setNickname(displayName);
        u.setEmailVerified(true);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRole("USER");
        u.setStatus("ACTIVE");
        userMapper.insert(u);
        sessionStateService.markActive(u.getId(), u.getRole());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true); resp.put("userId", u.getId()); resp.put("role", u.getRole());
        resp.put("username", displayName); resp.put("nickname", displayName); resp.put("email", email);
        auditService.record("USER", "REGISTER", "t_user", String.valueOf(u.getId()), "email=" + email, "SUCCESS");
        return resp;
    }

    public Map<String, Object> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return fail("用户名或密码不能为空");
        }
        String account = username.trim();
        UserEntity u = account.contains("@")
                ? userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, EmailVerificationService.normalizeEmail(account)))
                : userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, account));
        if (u == null) {
            return fail("用户名或密码错误");
        }
        if (!demoAccountsEnabled && ("test".equalsIgnoreCase(u.getUsername()) || "admin".equalsIgnoreCase(u.getUsername()))) {
            return fail("该演示账号已停用，请使用受控账号登录");
        }
        if (!"ACTIVE".equalsIgnoreCase(u.getStatus())) {
            return fail("账号已被禁用或锁定");
        }
        if (u.getEmail() != null && !u.getEmail().isBlank() && !Boolean.TRUE.equals(u.getEmailVerified())) {
            return fail("邮箱尚未验证，请先完成邮箱验证");
        }
        String storedHash = u.getPasswordHash();
        boolean matched = storedHash != null && storedHash.startsWith("$2")
                ? passwordEncoder.matches(password, storedHash)
                : sha256(password).equals(storedHash);
        if (!matched) {
            return fail("用户名或密码错误");
        }
        // 旧版无盐 SHA-256 哈希：登录成功后自动升级为 BCrypt
        if (storedHash != null && !storedHash.startsWith("$2")) {
            u.setPasswordHash(passwordEncoder.encode(password));
            userMapper.updateById(u);
        }
        String role = normalizeRole(u.getRole());
        sessionStateService.markActive(u.getId(), role);
        String displayName = StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername();
        String token = jwtUtil.generateToken(u.getId(), displayName, role);
        String refreshToken = refreshTokenService.issue(u.getId(), displayName, role);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("token", token);
        resp.put("refreshToken", refreshToken);
        resp.put("userId", u.getId());
        resp.put("username", displayName);
        resp.put("nickname", displayName);
        resp.put("email", u.getEmail());
        resp.put("role", role);
        auditService.record("AUTH", "LOGIN", "t_user", String.valueOf(u.getId()), "account=" + account, "SUCCESS");
        return resp;
    }

    /**
     * 用 refresh token 换新 access token（轮换：旧 refresh token 作废）。
     * 返回结构与 login 一致；无效/过期返回 null。
     */
    public Map<String, Object> refresh(String refreshToken) {
        String payload = refreshTokenService.validate(refreshToken);
        if (payload == null) {
            return null;
        }
        String[] parts = payload.split(":", 3);
        if (parts.length < 3) {
            return null;
        }
        try {
            Long userId = Long.parseLong(parts[0]);
            UserEntity current = userMapper.selectById(userId);
            if (current == null || !"ACTIVE".equalsIgnoreCase(current.getStatus())) {
                return null;
            }
            String username = StringUtils.hasText(current.getNickname()) ? current.getNickname() : current.getUsername();
            String role = normalizeRole(current.getRole());
            sessionStateService.markActive(userId, role);
            // 轮换：旧 refresh token 作废，签发新的一对
            refreshTokenService.revoke(refreshToken);
            String token = jwtUtil.generateToken(userId, username, role);
            String newRefreshToken = refreshTokenService.issue(userId, username, role);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("token", token);
            resp.put("refreshToken", newRefreshToken);
            resp.put("userId", userId);
            resp.put("username", username);
            resp.put("role", role);
            return resp;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 退出登录：作废 refresh token */
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    public int logoutAll(Long userId, String keepToken) {
        return refreshTokenService.revokeAllForUser(userId, keepToken);
    }

    public List<Map<String, Object>> listSessions(Long userId, String currentToken) {
        return refreshTokenService.listForUser(userId, currentToken);
    }

    public Map<String, Object> changePassword(Long userId, String currentPassword, String newPassword) {
        if (userId == null || !StringUtils.hasText(currentPassword) || !StringUtils.hasText(newPassword)) return fail("密码不能为空");
        if (newPassword.length() < 8 || newPassword.length() > 64) return fail("新密码长度需在8-64位之间");
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) return fail("账号不可用");
        String stored = user.getPasswordHash();
        boolean matched = stored != null && (stored.startsWith("$2") ? passwordEncoder.matches(currentPassword, stored) : sha256(currentPassword).equals(stored));
        if (!matched) return fail("当前密码错误");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        auditService.record("AUTH", "PASSWORD_CHANGE", "t_user", String.valueOf(userId), "password changed", "SUCCESS");
        return Map.of("ok", true, "message", "密码已更新");
    }

    /** 更新可公开展示的个人资料。登录邮箱是账号标识，修改邮箱必须走单独的验证流程。 */
    public Map<String, Object> updateProfile(Long userId, String nickname) {
        if (userId == null) return fail("账号不存在");
        String normalized = nickname == null ? "" : nickname.trim();
        String nicknameError = validateNickname(normalized);
        if (nicknameError != null) return fail(nicknameError);
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) return fail("账号不可用");
        if (normalized.equals(user.getNickname())) {
            return profileResult(user, "昵称未改变");
        }
        if (findNicknameOwner(normalized, userId) != null) return fail("该昵称已被使用，请换一个昵称");
        LocalDateTime now = LocalDateTime.now();
        if (user.getNicknameUpdatedAt() != null) {
            LocalDateTime nextAllowed = user.getNicknameUpdatedAt().plusMonths(1);
            if (nextAllowed.isAfter(now)) {
                return fail("昵称每月只能修改一次，下次可在 " + nextAllowed.format(NICKNAME_DATE_FORMAT) + " 后修改");
            }
        }
        user.setNickname(normalized);
        user.setNicknameUpdatedAt(now);
        userMapper.updateById(user);
        auditService.record("USER", "PROFILE_UPDATE", "t_user", String.valueOf(userId), "nickname updated", "SUCCESS");
        return profileResult(user, "个人资料已更新");
    }

    /** 保存或清除头像 data URL，避免将未经校验的 SVG/脚本内容直接交给浏览器。 */
    public Map<String, Object> updateAvatar(Long userId, String avatarData) {
        if (userId == null) return fail("账号不存在");
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) return fail("账号不可用");
        String normalized = avatarData == null ? "" : avatarData.trim();
        if (!normalized.isEmpty()) {
            String error = validateAvatarData(normalized);
            if (error != null) return fail(error);
        }
        // MyBatis-Plus 默认不会把 null 写回数据库，清除头像时使用空串确保旧头像被覆盖。
        user.setAvatarData(normalized.isEmpty() ? "" : normalized);
        userMapper.updateById(user);
        auditService.record("USER", "AVATAR_UPDATE", "t_user", String.valueOf(userId), normalized.isEmpty() ? "avatar cleared" : "avatar updated", "SUCCESS");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("avatarData", normalized);
        result.put("message", normalized.isEmpty() ? "头像已移除" : "头像已更新");
        return result;
    }

    private Map<String, Object> profileResult(UserEntity user, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("nickname", user.getNickname());
        result.put("nicknameUpdatedAt", user.getNicknameUpdatedAt());
        result.put("message", message);
        return result;
    }

    private String validateNickname(String nickname) {
        if (!StringUtils.hasText(nickname)) return "昵称不能为空";
        if (nickname.length() > 32) return "昵称不能超过32个字符";
        if (nickname.codePoints().anyMatch(Character::isISOControl)) return "昵称不能包含控制字符";
        String lower = nickname.toLowerCase(Locale.ROOT);
        if (nicknameBannedWords != null) {
            for (String word : nicknameBannedWords.split(",")) {
                String banned = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
                if (!banned.isEmpty() && lower.contains(banned)) return "昵称包含不允许使用的内容";
            }
        }
        return null;
    }

    private UserEntity findNicknameOwner(String nickname, Long excludedUserId) {
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .and(wrapper -> wrapper.eq(UserEntity::getNickname, nickname).or().eq(UserEntity::getUsername, nickname));
        if (excludedUserId != null) query.ne(UserEntity::getId, excludedUserId);
        return userMapper.selectOne(query);
    }

    private String validateAvatarData(String avatarData) {
        int comma = avatarData.indexOf(',');
        if (comma <= 0 || comma == avatarData.length() - 1) return "头像格式不正确";
        String header = avatarData.substring(0, comma).toLowerCase(Locale.ROOT);
        if (!("data:image/png;base64".equals(header) || "data:image/jpeg;base64".equals(header)
                || "data:image/jpg;base64".equals(header) || "data:image/webp;base64".equals(header))) {
            return "仅支持 PNG、JPG、JPEG 或 WebP 图片";
        }
        String payload = avatarData.substring(comma + 1);
        if (payload.length() > 720_000 || !payload.matches("[A-Za-z0-9+/=]+")) return "头像文件过大或格式不正确";
        try {
            if (Base64.getDecoder().decode(payload).length > MAX_AVATAR_BYTES) return "头像不能超过 512KB";
        } catch (IllegalArgumentException e) {
            return "头像格式不正确";
        }
        return null;
    }

    public Map<String, Object> resetPassword(String rawEmail, String newPassword) {
        String email = EmailVerificationService.normalizeEmail(rawEmail);
        if (!EmailVerificationService.isValidEmail(email)) return fail("请输入有效邮箱");
        if (!StringUtils.hasText(newPassword) || newPassword.length() < 8 || newPassword.length() > MAX_PASSWORD_LENGTH) return fail("密码长度需在8-64位之间");
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email));
        if (user == null) return fail("邮箱或验证码不正确");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        auditService.record("AUTH", "PASSWORD_RESET", "t_user", String.valueOf(user.getId()), "email reset", "SUCCESS");
        return Map.of("ok", true, "message", "密码已重置，请使用新密码登录");
    }

    public Map<String, Object> disableAccount(Long userId) {
        if (userId == null) return fail("用户不存在");
        UserEntity user = userMapper.selectById(userId);
        if (user == null) return fail("用户不存在");
        List<String> purgeErrors = purgeUserData(userId);
        if (!purgeErrors.isEmpty()) {
            auditService.record("AUTH", "ACCOUNT_DISABLE", "t_user", String.valueOf(userId),
                    "purge failed: " + String.join(", ", purgeErrors), "FAILED");
            return fail("账号数据清理未完成，请稍后重试");
        }
        user.setStatus("DISABLED");
        userMapper.updateById(user);
        sessionStateService.markDisabled(userId);
        auditService.record("AUTH", "ACCOUNT_DISABLE", "t_user", String.valueOf(userId), "self service", "SUCCESS");
        return Map.of("ok", true, "message", "账号已注销");
    }

    /**
     * Remove all user-owned data before disabling the account.  The user
     * service shares the platform database, so this is intentionally a small
     * idempotent purge coordinator rather than a best-effort card-only cleanup.
     * Missing optional tables are ignored (older local schemas), while actual
     * delete failures block account disable and are auditable.
     */
    private List<String> purgeUserData(Long userId) {
        List<String> errors = new ArrayList<>();
        List<String> statements = List.of(
                "DELETE s FROM fc_user_lineup_slots s JOIN fc_user_lineups l ON l.id = s.lineup_id WHERE l.user_id = ?",
                "DELETE FROM fc_user_lineups WHERE user_id = ?",
                "DELETE FROM fc_lineup_share WHERE owner_user_id = ?",
                "DELETE FROM fc_user_card_tag WHERE user_id = ?",
                "DELETE FROM fc_card_public_like WHERE user_id = ?",
                "DELETE FROM fc_card_report WHERE reporter_user_id = ?",
                "DELETE FROM fc_persona_card_version WHERE owner_user_id = ?",
                "DELETE FROM fc_persona_inventory WHERE user_id = ?",
                "DELETE FROM fc_user_points_ledger WHERE user_id = ?",
                "DELETE FROM fc_user_points_wallet WHERE user_id = ?",
                "DELETE FROM fc_card_rogue_choice WHERE run_id IN (SELECT id FROM fc_card_rogue_run WHERE user_id = ?)",
                "DELETE FROM fc_card_rogue_run WHERE user_id = ?",
                "DELETE FROM fc_player_cards WHERE owner_user_id = ?",
                "DELETE FROM t_agent_conversation WHERE user_id = ?",
                "DELETE FROM t_prediction_history WHERE user_id = ?",
                "DELETE FROM t_prediction WHERE user_id = ?",
                "DELETE FROM t_user_favorite_match WHERE user_id = ?",
                "DELETE FROM t_user_favorite_team WHERE user_id = ?",
                "DELETE FROM t_user_notification WHERE user_id = ?",
                "DELETE FROM t_analytics_event WHERE user_id = ?",
                "DELETE FROM t_user_legal_consent WHERE user_id = ?",
                "DELETE FROM t_news_article_favorite WHERE user_id = ?",
                "DELETE FROM t_news_article_like WHERE user_id = ?",
                "DELETE FROM t_news_article_comment WHERE user_id = ?"
        );
        for (String statement : statements) {
            String table = statement.replaceFirst("(?is).*?FROM\\s+([a-zA-Z0-9_]+).*", "$1");
            try {
                if (!tableExists(table)) continue;
                jdbcTemplate.update(statement, userId);
            } catch (Exception ex) {
                errors.add(table);
                log.error("Account data purge failed userId={}, table={}: {}", userId, table, ex.getMessage());
            }
        }
        return errors.stream().distinct().toList();
    }

    private boolean tableExists(String table) {
        if (table == null || !table.matches("[A-Za-z0-9_]+")) return false;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, table);
            return count != null && count > 0;
        } catch (Exception ex) {
            throw new IllegalStateException("无法确认账号数据表是否存在: " + table, ex);
        }
    }

    public Long requireUserId() {
        Long userId = com.chen.football.common.context.UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        return userId;
    }

    public Map<String, Object> updateRole(Long userId, String role) {
        if (userId == null || userId <= 0) {
            return fail("userId 不合法");
        }
        String normalizedRole = normalizeRole(role);
        if (!ALLOWED_ROLES.contains(normalizedRole)) {
            return fail("role 不合法");
        }
        UserEntity u = userMapper.selectById(userId);
        if (u == null) return fail("用户不存在");
        String before = normalizeRole(u.getRole());
        u.setRole(normalizedRole);
        userMapper.updateById(u);
        if ("ACTIVE".equalsIgnoreCase(u.getStatus())) sessionStateService.markActive(userId, normalizedRole);
        auditService.record("USER", "ROLE", "t_user", String.valueOf(userId), "before=" + before + ", after=" + normalizedRole, "SUCCESS");
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("userId", userId);
        resp.put("role", normalizedRole);
        return resp;
    }

    public Map<String, Object> updateStatus(Long userId, String status) {
        if (userId == null || userId <= 0) {
            return fail("userId 不合法");
        }
        String normalizedStatus = normalizeStatus(status);
        if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
            return fail("status 不合法");
        }
        UserEntity u = userMapper.selectById(userId);
        if (u == null) return fail("用户不存在");
        String before = u.getStatus();
        u.setStatus(normalizedStatus);
        userMapper.updateById(u);
        if ("ACTIVE".equalsIgnoreCase(normalizedStatus)) {
            sessionStateService.markActive(userId, normalizeRole(u.getRole()));
        } else {
            sessionStateService.markDisabled(userId);
        }
        auditService.record("USER", "STATUS", "t_user", String.valueOf(userId), "before=" + before + ", after=" + normalizedStatus, "SUCCESS");
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("userId", userId);
        resp.put("status", normalizedStatus);
        return resp;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", false);
        resp.put("message", message);
        return resp;
    }

    private String normalizeRole(String role) {
        String value = role == null ? "USER" : role.trim().toUpperCase();
        return ALLOWED_ROLES.contains(value) ? value : "USER";
    }

    private String normalizeStatus(String status) {
        String value = status == null ? "ACTIVE" : status.trim().toUpperCase();
        return ALLOWED_STATUSES.contains(value) ? value : "ACTIVE";
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
