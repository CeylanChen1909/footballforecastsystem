package com.chen.football.user.filter;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.util.JwtUtil;
import com.chen.football.user.mapper.UserMapper;
import com.chen.football.user.entity.UserEntity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    public JwtAuthFilter(JwtUtil jwtUtil, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String token = auth.substring(7).trim();
                try {
                    Long userId = jwtUtil.extractUserId(token);
                    UserEntity u = userMapper.selectById(userId);
                    if (u == null || !"ACTIVE".equalsIgnoreCase(u.getStatus())) {
                        UserContext.clear();
                    } else {
                        String username = (u.getNickname() == null || u.getNickname().isBlank()) ? u.getUsername() : u.getNickname();
                        String role = u.getRole() == null ? "USER" : u.getRole().trim().toUpperCase();
                        UserContext.set(userId, username, role);
                    }
                } catch (Exception ignored) {
                    UserContext.clear();
                }
            } else {
                UserContext.clear();
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
