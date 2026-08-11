package com.chen.football.news.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.news.entity.NewsArticleAuditLog;
import com.chen.football.news.entity.SystemConfig;
import com.chen.football.news.mapper.NewsArticleAuditLogMapper;
import com.chen.football.news.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {
    private final SystemConfigMapper systemConfigMapper;
    private final NewsArticleAuditLogMapper auditLogMapper;

    @GetMapping
    public ApiResponse<Map<String, String>> getAll() {
        try {
            List<SystemConfig> rows = systemConfigMapper.selectList(null);
            Map<String, String> result = new java.util.HashMap<>();
            for (SystemConfig row : rows) {
                if (row == null || row.getConfigKey() == null) continue;
                result.put(row.getConfigKey(), row.getConfigValue() == null ? "" : row.getConfigValue());
            }
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return new ApiResponse<>(false, "配置加载失败: " + e.getMessage(), Collections.emptyMap());
        }
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, String> body) {
        body.forEach((k, v) -> {
            try {
                SystemConfig cfg = systemConfigMapper.selectOne(Wrappers.<SystemConfig>lambdaQuery().eq(SystemConfig::getConfigKey, k));
                if (cfg == null) {
                    cfg = new SystemConfig();
                    cfg.setConfigKey(k);
                    cfg.setConfigValue(v);
                    cfg.setUpdatedBy(UserContext.getUserId());
                    systemConfigMapper.insert(cfg);
                } else {
                    cfg.setConfigValue(v);
                    cfg.setUpdatedBy(UserContext.getUserId());
                    systemConfigMapper.updateById(cfg);
                }
            } catch (Exception ignored) {
            }
        });
        persistAudit("CONFIG", "UPDATE", "t_system_config", "bulk", body.toString(), "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true));
    }

    @GetMapping("/logs")
    public ApiResponse<List<NewsArticleAuditLog>> logs() {
        try {
            return ApiResponse.ok(auditLogMapper.selectList(Wrappers.<NewsArticleAuditLog>lambdaQuery().orderByDesc(NewsArticleAuditLog::getCreatedAt).last("LIMIT 200")));
        } catch (Exception e) {
            return new ApiResponse<>(false, "日志加载失败: " + e.getMessage(), Collections.emptyList());
        }
    }

    private void persistAudit(String module, String action, String targetType, String targetId, String content, String result) {
        try {
            NewsArticleAuditLog log = new NewsArticleAuditLog();
            log.setOperatorId(UserContext.getUserId());
            log.setOperatorName(UserContext.getUsername());
            log.setModule(module);
            log.setAction(action);
            log.setTargetType(targetType);
            log.setTargetId(targetId);
            log.setContent(content);
            log.setResult(result);
            log.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(log);
        } catch (Exception ignored) {
        }
    }
}
