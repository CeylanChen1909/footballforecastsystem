package com.chen.football.news.service;

import com.chen.football.news.entity.NewsArticleAuditLog;
import com.chen.football.news.mapper.AdminAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {
    private final AdminAuditLogMapper mapper;

    public void write(Long operatorId, String operatorName, String module, String action, String targetType, String targetId, String content, String result) {
        try {
            NewsArticleAuditLog log = new NewsArticleAuditLog();
            log.setOperatorId(operatorId);
            log.setOperatorName(operatorName);
            log.setModule(module);
            log.setAction(action);
            log.setTargetType(targetType);
            log.setTargetId(targetId);
            log.setContent(content);
            log.setResult(result);
            log.setCreatedAt(LocalDateTime.now());
            mapper.insert(log);
        } catch (Exception ignored) {
            // 忽略审计写入失败，避免影响主流程
        }
    }
}
