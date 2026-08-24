package com.chen.football.common.filter;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.service.UserSessionStateService;
import com.chen.football.common.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    private final JwtUtil jwtUtil;
    private final UserSessionStateService sessionStateService;

    public JwtFilter(JwtUtil jwtUtil, UserSessionStateService sessionStateService) {
        this.jwtUtil = jwtUtil;
        this.sessionStateService = sessionStateService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Long userId = jwtUtil.extractUserId(token);
                if (sessionStateService.isAllowed(userId)) {
                    String role = sessionStateService.roleOverride(userId);
                    UserContext.set(userId, jwtUtil.extractUsername(token), role == null ? jwtUtil.extractRole(token) : role);
                }
            } catch (Exception e) {
                log.debug("JWT 解析失败: {}", e.getMessage());
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
