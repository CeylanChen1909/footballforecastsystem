package com.chen.football.agent.service;

import com.chen.football.news.entity.SystemConfig;
import com.chen.football.news.mapper.SystemConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime Agent model policy. Values are editable by administrators through
 * t_system_config; secrets remain environment-only and are never exposed.
 */
@Service
public class AgentModelConfigService {
    public static final String PROVIDER = "agent.model.provider";
    public static final String DEEPSEEK_BASE_URL = "agent.model.deepseek.base-url";
    public static final String DEEPSEEK_MODEL = "agent.model.deepseek.model";
    public static final String DEEPSEEK_MODELS = "agent.model.deepseek.models";
    public static final String OPENROUTER_BASE_URL = "agent.model.openrouter.base-url";
    public static final String OPENROUTER_MODEL = "agent.model.openrouter.model";
    public static final String OPENROUTER_MODELS = "agent.model.openrouter.models";
    public static final String SCNET_BASE_URL = "agent.model.scnet.base-url";
    public static final String SCNET_MODEL = "agent.model.scnet.model";
    public static final String SCNET_MODELS = "agent.model.scnet.models";
    public static final String THINKING_ENABLED = "agent.model.thinking.enabled";
    public static final String FALLBACK_ENABLED = "agent.model.fallback.enabled";
    public static final String TEMPERATURE = "agent.model.temperature";
    public static final String MAX_TOKENS = "agent.model.max-tokens";

    private final SystemConfigMapper mapper;
    private final AtomicReference<Map<String, String>> values = new AtomicReference<>(Map.of());

    public AgentModelConfigService(SystemConfigMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void refresh() {
        try {
            List<SystemConfig> rows = mapper.selectList(Wrappers.<SystemConfig>lambdaQuery().likeRight(SystemConfig::getConfigKey, "agent.model."));
            Map<String, String> next = new LinkedHashMap<>();
            for (SystemConfig row : rows) {
                if (row != null && row.getConfigKey() != null && row.getConfigValue() != null) next.put(row.getConfigKey(), row.getConfigValue());
            }
            values.set(Map.copyOf(next));
        } catch (Exception ignored) {
            // Environment defaults remain active if the config table is not ready.
        }
    }

    public String get(String key, String fallback) {
        String value = values.get().get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public boolean bool(String key, boolean fallback) {
        String value = values.get().get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    public double decimal(String key, double fallback) {
        try { return Double.parseDouble(get(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    public int integer(String key, int fallback) {
        try { return Integer.parseInt(get(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    public Map<String, Object> publicSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", get(PROVIDER, "auto"));
        result.put("thinkingEnabled", bool(THINKING_ENABLED, true));
        result.put("fallbackEnabled", bool(FALLBACK_ENABLED, true));
        result.put("temperature", decimal(TEMPERATURE, 0.4));
        result.put("maxTokens", integer(MAX_TOKENS, 1200));
        result.put("deepseek", Map.of(
                "baseUrl", get(DEEPSEEK_BASE_URL, ""),
                "model", get(DEEPSEEK_MODEL, ""),
                "models", get(DEEPSEEK_MODELS, "")
        ));
        result.put("openrouter", Map.of(
                "baseUrl", get(OPENROUTER_BASE_URL, ""),
                "model", get(OPENROUTER_MODEL, ""),
                "models", get(OPENROUTER_MODELS, "")
        ));
        result.put("scnet", Map.of(
                "baseUrl", get(SCNET_BASE_URL, "https://api.scnet.cn/api/llm/v1"),
                "model", get(SCNET_MODEL, "GLM-5-Base"),
                "models", get(SCNET_MODELS, "GLM-5-Base")
        ));
        return result;
    }
}
