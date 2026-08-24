package com.chen.football.common.util;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.exception.BusinessException;
import com.chen.football.common.exception.UnauthorizedException;

import java.util.Map;
import java.util.Set;

public final class AdminGuard {

    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "SUPER_ADMIN");
    private static final Set<String> SUPER_ROLES = Set.of("SUPER_ADMIN");

    private static final Map<String, Set<String>> PERMISSION_MATRIX = Map.of(
            "NEWS", Set.of("ADMIN", "SUPER_ADMIN"),
            "VIDEO", Set.of("ADMIN", "SUPER_ADMIN"),
            "CONFIG", Set.of("ADMIN", "SUPER_ADMIN"),
            "USER", Set.of("SUPER_ADMIN"),
            "CRAWLER", Set.of("ADMIN", "SUPER_ADMIN"),
            "DATASYNC", Set.of("ADMIN", "SUPER_ADMIN"),
            "MATCH", Set.of("ADMIN", "SUPER_ADMIN"),
            "PREDICTION", Set.of("ADMIN", "SUPER_ADMIN"),
            "TEAM", Set.of("ADMIN", "SUPER_ADMIN"),
            "SYSTEM", Set.of("SUPER_ADMIN")
    );

    private AdminGuard() {
    }

    public static void requireLogin() {
        if (UserContext.getUserId() == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
    }

    public static void requireAdmin() {
        requireLogin();
        String role = normalizeRole(UserContext.getRole());
        if (!ADMIN_ROLES.contains(role)) {
            throw new BusinessException("需要管理员权限");
        }
    }

    public static void requireSuperAdmin() {
        requireLogin();
        String role = normalizeRole(UserContext.getRole());
        if (!SUPER_ROLES.contains(role)) {
            throw new BusinessException("需要超级管理员权限");
        }
    }

    public static void requirePermission(String module) {
        requireLogin();
        String role = normalizeRole(UserContext.getRole());
        Set<String> allowed = PERMISSION_MATRIX.get(module == null ? "" : module.toUpperCase());
        if (allowed == null) {
            throw new BusinessException("未知模块: " + module);
        }
        if (!allowed.contains(role)) {
            throw new BusinessException("无权限操作模块: " + module);
        }
    }

    public static void requirePermission(String module, String action) {
        requirePermission(module);
    }

    public static boolean hasPermission(String module) {
        Long userId = UserContext.getUserId();
        if (userId == null) return false;
        String role = normalizeRole(UserContext.getRole());
        Set<String> allowed = PERMISSION_MATRIX.get(module == null ? "" : module.toUpperCase());
        return allowed != null && allowed.contains(role);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            throw new BusinessException("无法识别用户角色，拒绝访问");
        }
        return role.trim().toUpperCase();
    }
}
