package com.chen.football.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chen.football.common.util.JwtUtil;
import com.chen.football.user.entity.UserEntity;
import com.chen.football.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    public AuthService(UserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
    }

    public Map<String, Object> register(String username, String password) {
        UserEntity existing = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (existing != null) {
            return Map.of("ok", false, "message", "用户名已存在");
        }
        UserEntity u = new UserEntity();
        u.setUsername(username);
        u.setPasswordHash(sha256(password));
        u.setRole("USER");
        u.setStatus("ACTIVE");
        userMapper.insert(u);
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("userId", u.getId());
        resp.put("role", u.getRole());
        resp.put("username", u.getUsername());
        return resp;
    }

    public Map<String, Object> login(String username, String password) {
        UserEntity u = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (u == null || !u.getPasswordHash().equals(sha256(password))) {
            return Map.of("ok", false, "message", "用户名或密码错误");
        }
        String role = u.getRole() == null ? "USER" : u.getRole();
        String token = jwtUtil.generateToken(u.getId(), u.getUsername(), role);
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("token", token);
        resp.put("userId", u.getId());
        resp.put("username", u.getUsername());
        resp.put("role", role);
        return resp;
    }

    public Map<String, Object> updateRole(Long userId, String role) {
        UserEntity u = userMapper.selectById(userId);
        if (u == null) return Map.of("ok", false, "message", "用户不存在");
        u.setRole(role);
        userMapper.updateById(u);
        return Map.of("ok", true, "userId", userId, "role", role);
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
