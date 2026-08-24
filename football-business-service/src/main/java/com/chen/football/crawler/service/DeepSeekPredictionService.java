package com.chen.football.crawler.service;

import com.chen.football.agent.service.AgentModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekPredictionService {

    private final WebClient.Builder webClientBuilder;
    private final AgentModelConfigService modelConfigService;

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-v4-pro}")
    private String model;

    @Value("${deepseek.models:}")
    private String configuredModels;

    private final AtomicReference<String> lastHealthStatus = new AtomicReference<>("未验证");
    private final AtomicReference<String> lastHealthMessage = new AtomicReference<>("");
    private final AtomicReference<String> lastCheckedAt = new AtomicReference<>("");

    public Map<String, Object> analyze(String prompt) {
        return analyze(prompt, null, true);
    }

    public Map<String, Object> analyze(String prompt, String modelOverride) {
        return analyze(prompt, modelOverride, true);
    }

    public Map<String, Object> analyze(String prompt, String modelOverride, boolean thinkingEnabled) {
        String requestId = UUID.randomUUID().toString();
        String useModel = resolveModel(modelOverride);
        String requestBaseUrl = configuredBaseUrl();
        boolean useThinking = modelConfigService.bool(AgentModelConfigService.THINKING_ENABLED, thinkingEnabled);
        log.info("[DeepSeek] enter analyze requestId={} baseUrl={} model={} thinking={}", requestId, requestBaseUrl, useModel, useThinking);
        log.info("[DeepSeek] requestId={} apiKeyPresent={} apiKeyLength={}", requestId, apiKey != null && !apiKey.isBlank(), apiKey == null ? 0 : apiKey.length());

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[DeepSeek] requestId={} missing apiKey -> fallback", requestId);
            recordHealth("未配置", "缺少 DEEPSEEK_API_KEY");
            return fallback(prompt, requestId, "missing-api-key", 0L, useModel);
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", useModel);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a helpful assistant. Answer in concise Chinese unless the user explicitly requests JSON."),
                    Map.of("role", "user", "content", prompt)
            ));
            body.put("stream", false);
            body.put("temperature", modelConfigService.decimal(AgentModelConfigService.TEMPERATURE, 0.4));
            body.put("max_tokens", modelConfigService.integer(AgentModelConfigService.MAX_TOKENS, 1200));
            if (useThinking) {
                body.put("thinking", Map.of("type", "enabled"));
                body.put("reasoning_effort", "high");
            }

            Map<?, ?> res = webClientBuilder.baseUrl(requestBaseUrl)
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(30));

            long latency = System.currentTimeMillis() - start;
            log.info("[DeepSeek] requestId={} response received latencyMs={} keys={}", requestId, latency, res == null ? "null" : res.keySet());
            if (res == null) {
                log.warn("[DeepSeek] requestId={} empty response -> fallback", requestId);
                recordHealth("请求失败", "empty-response");
                return fallback(prompt, requestId, "empty-response", latency, useModel);
            }
            recordHealth("正常", "");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", "deepseek");
            data.put("provider", "deepseek");
            data.put("requestId", requestId);
            data.put("model", useModel);
            data.put("status", "ok");
            data.put("latencyMs", latency);
            data.put("content", extractMessageField(res, "content"));
            data.put("usage", res.get("usage"));
            String reasoning = extractReasoning(res);
            data.put("reasoning", reasoning == null ? null : reasoning.substring(0, Math.min(2000, reasoning.length())));
            return data;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[DeepSeek] requestId={} failed latencyMs={} errType={} errMsg={}", requestId, latency, e.getClass().getSimpleName(), e.getMessage(), e);
            recordHealth(classifyHealth(e.getMessage()), e.getMessage());
            return fallback(prompt, requestId, e.getClass().getSimpleName(), latency, useModel);
        }
    }

    public Map<String, Object> analyzeJson(String prompt) {
        return analyzeJson(prompt, null, true);
    }

    public Map<String, Object> analyzeJson(String prompt, String modelOverride) {
        return analyzeJson(prompt, modelOverride, true);
    }

    public Map<String, Object> analyzeJson(String prompt, String modelOverride, boolean thinkingEnabled) {
        Map<String, Object> ai = analyze(prompt, modelOverride, thinkingEnabled);
        if (ai.get("content") == null) ai.put("content", extractMessageField(ai.get("raw"), "content"));
        if (ai.get("reasoning") == null) ai.put("reasoning", extractReasoning(ai.get("raw")));
        return ai;
    }

    public List<String> availableModels() {
        String configured = configuredModels();
        String configuredDefault = configuredModel();
        List<String> result = Arrays.stream(configured == null ? new String[0] : configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        if (result.isEmpty() || !result.contains(configuredDefault)) result.add(0, configuredDefault);
        return result;
    }

    public String defaultModel() {
        return configuredModel();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "deepseek");
        result.put("status", isConfigured() ? lastHealthStatus.get() : "未配置");
        result.put("configured", isConfigured());
        result.put("message", lastHealthMessage.get());
        result.put("lastCheckedAt", lastCheckedAt.get());
        result.put("model", configuredModel());
        result.put("baseUrl", configuredBaseUrl());
        return result;
    }

    private void recordHealth(String status, String message) {
        lastHealthStatus.set(status);
        lastHealthMessage.set(message == null ? "" : message.substring(0, Math.min(240, message.length())));
        lastCheckedAt.set(Instant.now().toString());
    }

    private String classifyHealth(String message) {
        String text = message == null ? "" : message.toLowerCase();
        return text.contains("quota") || text.contains("limit") || text.contains("余额")
                || text.contains("rate") || text.contains("429") || text.contains("too many requests")
                ? "额度受限" : "请求失败";
    }

    private String resolveModel(String modelOverride) {
        // 模型由管理员统一配置，忽略请求体中的 model，避免用户绕过成本策略。
        return configuredModel();
    }

    private String configuredBaseUrl() {
        return modelConfigService.get(AgentModelConfigService.DEEPSEEK_BASE_URL, baseUrl);
    }

    private String configuredModel() {
        return modelConfigService.get(AgentModelConfigService.DEEPSEEK_MODEL, model);
    }

    private String configuredModels() {
        return modelConfigService.get(AgentModelConfigService.DEEPSEEK_MODELS, configuredModels);
    }

    @SuppressWarnings("unchecked")
    private String extractMessageField(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> rawMap)) return null;
        Object choices = rawMap.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) return null;
        Object message = firstMap.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) return null;
        Object value = messageMap.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private String extractReasoning(Object raw) {
        if (raw instanceof Map<?, ?> rawMap) {
            Object topLevel = rawMap.get("reasoning");
            if (topLevel == null) topLevel = rawMap.get("reasoning_content");
            if (topLevel != null) return String.valueOf(topLevel);
        }
        String reasoning = extractMessageField(raw, "reasoning_content");
        return reasoning == null ? extractMessageField(raw, "reasoning") : reasoning;
    }

    private String preview(String prompt) {
        if (prompt == null) return "null";
        String compact = prompt.replaceAll("\\s+", " ").trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180) + "...";
    }

    private Map<String, Object> fallback(String prompt, String requestId, String reason, long latencyMs, String modelName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "fallback");
        data.put("provider", "deepseek");
        data.put("requestId", requestId);
        data.put("model", modelName == null ? "fallback-rules" : modelName);
        data.put("status", reason);
        data.put("latencyMs", latencyMs);
        data.put("summary", "当前模型通道不可用，已停止本次调用，请稍后重试。");
        data.put("streamed", false);
        return data;
    }
}
