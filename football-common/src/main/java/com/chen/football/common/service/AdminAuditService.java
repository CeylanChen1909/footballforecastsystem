package com.chen.football.common.service;

import com.chen.football.common.context.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final JdbcTemplate jdbcTemplate;

    public AdminAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String module, String action, String targetType, String targetId, String content, String result) {
        Long operatorId = UserContext.getUserId();
        String operatorName = UserContext.getUsername();
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_admin_audit_log (operator_id, operator_name, module, action, target_type, target_id, content, result, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    operatorId, operatorName, module, action, targetType, targetId, content, result, LocalDateTime.now()
            );
        } catch (Exception e) {
            log.warn("审计日志写入失败: module={} action={} target={} err={}", module, action, targetId, e.getMessage());
        }
    }
}
