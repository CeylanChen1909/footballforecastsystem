package com.chen.football.news.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.exception.BusinessException;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.agent.service.AgentModelConfigService;
import com.chen.football.news.entity.NewsArticleAuditLog;
import com.chen.football.news.entity.SystemConfig;
import com.chen.football.news.mapper.NewsArticleAuditLogMapper;
import com.chen.football.news.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import java.net.URI;

@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|passwd|secret|token|api[-_.]?key|credential|private[-_.]?key).*");
    private static final int MAX_VALUE_LENGTH = 512;
    private static final Set<String> EDITABLE_KEYS = Set.of(
            AgentModelConfigService.PROVIDER,
            AgentModelConfigService.DEEPSEEK_BASE_URL,
            AgentModelConfigService.DEEPSEEK_MODEL,
            AgentModelConfigService.DEEPSEEK_MODELS,
            AgentModelConfigService.OPENROUTER_BASE_URL,
            AgentModelConfigService.OPENROUTER_MODEL,
            AgentModelConfigService.OPENROUTER_MODELS,
            AgentModelConfigService.SCNET_BASE_URL,
            AgentModelConfigService.SCNET_MODEL,
            AgentModelConfigService.SCNET_MODELS,
            AgentModelConfigService.THINKING_ENABLED,
            AgentModelConfigService.FALLBACK_ENABLED,
            AgentModelConfigService.TEMPERATURE,
            AgentModelConfigService.MAX_TOKENS,
            "news.audit.mode",
            "news.default.status",
            "match.override.enabled",
            "admin.dashboard.refresh"
    );
    private static final Logger log = LoggerFactory.getLogger(AdminConfigController.class);
    private final SystemConfigMapper systemConfigMapper;
    private final NewsArticleAuditLogMapper auditLogMapper;
    private final AgentModelConfigService agentModelConfigService;

    @GetMapping
    public ApiResponse<Map<String, String>> getAll() {
        AdminGuard.requireAdmin();
        try {
            List<SystemConfig> rows = systemConfigMapper.selectList(null);
            Map<String, String> result = new java.util.HashMap<>();
            for (SystemConfig row : rows) {
                if (row == null || row.getConfigKey() == null) continue;
                if (SECRET_KEY.matcher(row.getConfigKey()).matches()) continue;
                result.put(row.getConfigKey(), row.getConfigValue() == null ? "" : row.getConfigValue());
            }
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return new ApiResponse<>(false, "配置加载失败: " + e.getMessage(), Collections.emptyMap());
        }
    }

    @PutMapping
    public ApiResponse<Map<String, String>> save(@RequestBody Map<String, String> body) {
        AdminGuard.requireAdmin();
        if (body == null || body.isEmpty()) {
            throw new BusinessException("配置内容不能为空");
        }
        for (Map.Entry<String, String> entry : body.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue();
            if (!StringUtils.hasText(key)) throw new BusinessException("配置键不能为空");
            if (!EDITABLE_KEYS.contains(key)) throw new BusinessException("不允许修改该配置项");
            if (SECRET_KEY.matcher(key).matches()) throw new BusinessException("密钥类配置必须通过环境变量注入，不允许写入后台配置表");
            if (value != null && value.length() > MAX_VALUE_LENGTH) throw new BusinessException("配置值不能超过" + MAX_VALUE_LENGTH + "个字符");
            validateAgentValue(key, value == null ? "" : value.trim());
        }
        List<String> failures = new java.util.ArrayList<>();
        Map<String, String> before = new LinkedHashMap<>();
        body.keySet().forEach(k -> {
            SystemConfig existing = systemConfigMapper.selectOne(Wrappers.<SystemConfig>lambdaQuery().eq(SystemConfig::getConfigKey, k.trim()));
            before.put(k, existing == null ? "" : String.valueOf(existing.getConfigValue()));
        });
        body.forEach((k, v) -> {
            try {
                if (!StringUtils.hasText(k)) {
                    return;
                }
                SystemConfig cfg = systemConfigMapper.selectOne(Wrappers.<SystemConfig>lambdaQuery().eq(SystemConfig::getConfigKey, k.trim()));
                if (cfg == null) {
                    cfg = new SystemConfig();
                    cfg.setConfigKey(k.trim());
                    cfg.setConfigValue(v);
                    cfg.setUpdatedBy(UserContext.getUserId());
                    systemConfigMapper.insert(cfg);
                } else {
                    cfg.setConfigValue(v);
                    cfg.setUpdatedBy(UserContext.getUserId());
                    systemConfigMapper.updateById(cfg);
                }
            } catch (Exception e) {
                log.warn("保存配置项 {} 失败: {}", k, e.getMessage());
                failures.add(k + ": " + (e.getMessage() == null ? "写入失败" : e.getMessage()));
            }
        });
        if (!failures.isEmpty()) {
            persistAudit("CONFIG", "UPDATE", "t_system_config", "bulk", "before=" + redact(before) + ", after=" + redact(body), "FAILED");
            throw new BusinessException("部分配置保存失败：" + String.join("；", failures));
        }
        // Agent 的模型策略在保存后立即热加载，无需重启业务服务。
        agentModelConfigService.refresh();
        persistAudit("CONFIG", "UPDATE", "t_system_config", "bulk", "before=" + redact(before) + ", after=" + redact(body), "SUCCESS");
        return ApiResponse.ok(body);
    }

    private void validateAgentValue(String key, String value) {
        if (key.endsWith("base-url")) {
            if (value.isBlank()) return;
            try {
                URI uri = URI.create(value);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null) {
                    throw new BusinessException("模型 Base URL 必须是无凭据的 http(s) 地址");
                }
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("模型 Base URL 格式无效");
            }
        } else if (AgentModelConfigService.PROVIDER.equals(key)
                && !value.isBlank()
                && !Set.of("auto", "deepseek", "openrouter", "scnet").contains(value.toLowerCase())) {
            throw new BusinessException("模型通道只能是 auto、deepseek、openrouter 或 scnet");
        } else if (AgentModelConfigService.TEMPERATURE.equals(key) && !value.isBlank()) {
            try {
                double number = Double.parseDouble(value);
                if (number < 0 || number > 1.2) throw new BusinessException("Temperature 必须在 0 到 1.2 之间");
            } catch (NumberFormatException ex) {
                throw new BusinessException("Temperature 必须是数字");
            }
        } else if (AgentModelConfigService.MAX_TOKENS.equals(key) && !value.isBlank()) {
            try {
                int number = Integer.parseInt(value);
                if (number < 128 || number > 2048) throw new BusinessException("最大 Tokens 必须在 128 到 2048 之间");
            } catch (NumberFormatException ex) {
                throw new BusinessException("最大 Tokens 必须是整数");
            }
        } else if ((key.endsWith("thinking.enabled") || key.endsWith("fallback.enabled"))
                && !value.isBlank() && !Set.of("true", "false").contains(value.toLowerCase())) {
            throw new BusinessException("开关配置只能是 true 或 false");
        } else if (key.endsWith("models") && value.contains("\n")) {
            throw new BusinessException("模型白名单不能包含换行符");
        }
    }

    private Map<String, String> redact(Map<String, String> values) {
        Map<String, String> safe = new LinkedHashMap<>();
        values.forEach((key, value) -> safe.put(key, SECRET_KEY.matcher(key == null ? "" : key).matches() ? "[REDACTED]" : value));
        return safe;
    }

    @GetMapping("/logs")
    public ApiResponse<List<NewsArticleAuditLog>> logs() {
        AdminGuard.requireAdmin();
        try {
            return ApiResponse.ok(auditLogMapper.selectList(Wrappers.<NewsArticleAuditLog>lambdaQuery().orderByDesc(NewsArticleAuditLog::getCreatedAt).last("LIMIT 200")));
        } catch (Exception e) {
            return new ApiResponse<>(false, "日志加载失败: " + e.getMessage(), Collections.emptyList());
        }
    }

    private void persistAudit(String module, String action, String targetType, String targetId, String content, String result) {
        try {
            NewsArticleAuditLog audit = new NewsArticleAuditLog();
            audit.setOperatorId(UserContext.getUserId());
            audit.setOperatorName(UserContext.getUsername());
            audit.setModule(module);
            audit.setAction(action);
            audit.setTargetType(targetType);
            audit.setTargetId(targetId);
            audit.setContent(content);
            audit.setResult(result);
            audit.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(audit);
        } catch (Exception e) {
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }
}
