package com.chen.football.user.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.user.entity.UserEntity;
import com.chen.football.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
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

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(name = "keyword", required = false) String keyword) {
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
        Long userId = Long.parseLong(body.getOrDefault("userId", "0"));
        String status = body.getOrDefault("status", "ACTIVE");
        UserEntity user = userMapper.selectById(userId);
        if (user == null) return ApiResponse.ok(Map.of("ok", false, "message", "用户不存在"));
        user.setStatus(status);
        userMapper.updateById(user);
        return ApiResponse.ok(Map.of("ok", true));
    }
}
