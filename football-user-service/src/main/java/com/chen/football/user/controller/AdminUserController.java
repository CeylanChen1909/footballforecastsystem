package com.chen.football.user.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.service.AdminAuditService;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.user.entity.UserEntity;
import com.chen.football.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final UserMapper userMapper;
    private final AdminAuditService auditService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(name = "keyword", required = false) String keyword) {
        AdminGuard.requireAdmin();
        try {
            List<UserEntity> rows = userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                    .like(keyword != null && !keyword.isBlank(), UserEntity::getUsername, keyword)
                    .orderByDesc(UserEntity::getCreatedAt));
            List<Map<String, Object>> safe = new java.util.ArrayList<>();
            for (UserEntity u : rows) {
                if (u == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", u.getId() == null ? 0L : u.getId());
                item.put("username", u.getUsername() == null ? "" : u.getUsername());
                item.put("role", u.getRole() == null ? "USER" : u.getRole());
                item.put("status", u.getStatus() == null ? "ACTIVE" : u.getStatus());
                item.put("createdAt", u.getCreatedAt() == null ? "" : String.valueOf(u.getCreatedAt()));
                safe.add(item);
            }
            return ApiResponse.ok(safe);
        } catch (Exception e) {
            return new ApiResponse<>(false, "用户列表加载失败: " + e.getMessage(), Collections.emptyList());
        }
    }

    @PutMapping("/status")
    public ApiResponse<Map<String, Object>> updateStatus(@RequestBody Map<String, String> body) {
        AdminGuard.requireAdmin();
        Long userId;
        try {
            userId = Long.parseLong(body.getOrDefault("userId", "0"));
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("ok", false, "message", "userId 格式错误"));
        }
        String status = normalize(body.getOrDefault("status", "ACTIVE"));
        if (!List.of("ACTIVE", "DISABLED", "LOCKED").contains(status)) {
            return ApiResponse.ok(Map.of("ok", false, "message", "status 不合法"));
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null) return ApiResponse.ok(Map.of("ok", false, "message", "用户不存在"));
        String before = user.getStatus();
        user.setStatus(status);
        userMapper.updateById(user);
        auditService.record("USER", "STATUS", "t_user", String.valueOf(userId), "before=" + before + ", after=" + status, "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PutMapping("/role")
    public ApiResponse<Map<String, Object>> updateRole(@RequestBody Map<String, String> body) {
        AdminGuard.requireSuperAdmin();
        Long userId;
        try {
            userId = Long.parseLong(body.getOrDefault("userId", "0"));
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("ok", false, "message", "userId 格式错误"));
        }
        String role = normalize(body.getOrDefault("role", "USER")).toUpperCase();
        if (!List.of("USER", "ADMIN", "SUPER_ADMIN").contains(role)) {
            return ApiResponse.ok(Map.of("ok", false, "message", "role 不合法"));
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null) return ApiResponse.ok(Map.of("ok", false, "message", "用户不存在"));
        String before = user.getRole();
        user.setRole(role);
        userMapper.updateById(user);
        auditService.record("USER", "ROLE", "t_user", String.valueOf(userId), "before=" + before + ", after=" + role, "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
