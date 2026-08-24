package com.chen.football.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SCNet OpenAI-compatible endpoint. The API key is environment-only; the
 * administrator can change the endpoint/model policy without exposing it in
 * t_system_config or the public model catalog.
 */
@Slf4j
@Component
public class ScnetModelProvider {

    private final WebClient.Builder webClientBuilder = WebClient.builder();
    private final AgentModelConfigService modelConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScnetModelProvider(AgentModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @Value("${scnet.api-key:}")
    private String apiKey;

    @Value("${scnet.base-url:https://api.scnet.cn/api/llm/v1}")
    private String baseUrl;

    @Value("${scnet.model:GLM-5-Base}")
    private String defaultModel;

    @Value("${scnet.models:GLM-5-Base}")
    private String configuredModels;

    private final AtomicReference<String> lastHealthStatus = new AtomicReference<>("未验证");
    private final AtomicReference<String> lastHealthMessage = new AtomicReference<>("");
    private final AtomicReference<String> lastCheckedAt = new AtomicReference<>("");

    public String name() {
        return "scnet";
    }

    public String defaultModel() {
        return configuredModel();
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Object> chat(String prompt) {
        return chat(prompt, null, true);
    }

    public Map<String, Object> chat(String prompt, String modelOverride, boolean thinkingEnabled) {
        String requestId = UUID.randomUUID().toString();
        String useModel = configuredModel();
        boolean useThinking = modelConfigService.bool(AgentModelConfigService.THINKING_ENABLED, thinkingEnabled);
        if (!isAvailable()) {
            recordHealth("未配置", "缺少 SCNET_API_KEY");
            return fallback(prompt, requestId, "missing-api-key", 0L, useModel);
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", useModel);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a helpful assistant. Answer in concise Chinese unless the user explicitly requests JSON."),
                    Map.of("role", "user", "content", prompt == null ? "" : prompt)
            ));
            body.put("stream", false);
            body.put("temperature", modelConfigService.decimal(AgentModelConfigService.TEMPERATURE, 0.4));
            body.put("max_tokens", modelConfigService.integer(AgentModelConfigService.MAX_TOKENS, 1200));
            // GLM-5-Base is not assumed to support provider-specific reasoning
            // request fields; any returned reasoning content is still surfaced.
            if (useThinking && supportsReasoning(useModel)) {
                body.put("reasoning", Map.of("effort", "medium"));
            }

            String endpoint = configuredBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            Map<?, ?> response = webClientBuilder.build()
                    .post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(45));

            long latency = System.currentTimeMillis() - start;
            if (response == null) {
                recordHealth("请求失败", "empty-response");
                return fallback(prompt, requestId, "empty-response", latency, useModel);
            }
            String content = extractContent(response);
            if (content == null || content.isBlank()) {
                recordHealth("请求失败", "empty-content");
                return fallback(prompt, requestId, "empty-content", latency, useModel);
            }
            recordHealth("正常", "");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", "scnet");
            data.put("provider", name());
            data.put("requestId", requestId);
            data.put("model", useModel);
            data.put("status", "ok");
            data.put("latencyMs", latency);
            data.put("content", content);
            data.put("usage", response.get("usage"));
            data.put("reasoning", limitReasoning(extractReasoning(response)));
            return data;
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[SCNet] requestId={} model={} failed latencyMs={} errType={} errMsg={}",
                    requestId, useModel, latency, ex.getClass().getSimpleName(), ex.getMessage());
            recordHealth(classifyHealth(ex.getMessage()), ex.getMessage());
            return fallback(prompt, requestId, ex.getClass().getSimpleName(), latency, useModel);
        }
    }

    /** OpenAI-compatible SSE transport used by the Agent stream endpoint. */
    public Map<String, Object> stream(String prompt,
                                      String modelOverride,
                                      boolean thinkingEnabled,
                                      Consumer<String> contentSink,
                                      Consumer<String> reasoningSink) {
        String requestId = UUID.randomUUID().toString();
        String useModel = configuredModel();
        if (!isAvailable()) {
            recordHealth("未配置", "缺少 SCNET_API_KEY");
            return fallback(prompt, requestId, "missing-api-key", 0L, useModel);
        }
        long start = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", useModel);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a helpful assistant. Answer in concise Chinese unless the user explicitly requests JSON."),
                    Map.of("role", "user", "content", prompt == null ? "" : prompt)
            ));
            body.put("stream", true);
            body.put("temperature", modelConfigService.decimal(AgentModelConfigService.TEMPERATURE, 0.4));
            body.put("max_tokens", modelConfigService.integer(AgentModelConfigService.MAX_TOKENS, 1200));

            webClientBuilder.build().post()
                    .uri(configuredBaseUrl().replaceAll("/+$", "") + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { })
                    .timeout(Duration.ofSeconds(60))
                    .toStream()
                    .forEach(event -> consumeSse(event == null ? null : event.data(), content, reasoning, contentSink, reasoningSink));

            long latency = System.currentTimeMillis() - start;
            if (content.isEmpty()) {
                recordHealth("请求失败", "empty-stream");
                return fallback(prompt, requestId, "empty-stream", latency, useModel);
            }
            recordHealth("正常", "");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "scnet");
            result.put("provider", name());
            result.put("requestId", requestId);
            result.put("model", useModel);
            result.put("status", "ok");
            result.put("streamed", true);
            result.put("latencyMs", latency);
            result.put("content", content.toString());
            result.put("reasoning", reasoning.isEmpty() ? null : limitReasoning(reasoning.toString()));
            return result;
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            recordHealth(classifyHealth(ex.getMessage()), ex.getMessage());
            log.warn("[SCNet] streaming requestId={} model={} failed: {}", requestId, useModel, ex.getMessage());
            Map<String, Object> buffered = chat(prompt, useModel, thinkingEnabled);
            if (isSuccessful(buffered)) {
                buffered.put("streamed", false);
                return buffered;
            }
            return fallback(prompt, requestId, ex.getClass().getSimpleName(), latency, useModel);
        }
    }

    private boolean isSuccessful(Map<String, Object> result) {
        return result != null && "ok".equals(result.get("status"))
                && result.get("content") != null && !String.valueOf(result.get("content")).isBlank();
    }

    private void consumeSse(String raw,
                             StringBuilder content,
                             StringBuilder reasoning,
                             Consumer<String> contentSink,
                             Consumer<String> reasoningSink) {
        if (raw == null || raw.isBlank()) return;
        for (String line : raw.split("\\r?\\n")) {
            String payload = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
            if (payload.isBlank() || "[DONE]".equals(payload)) continue;
            try {
                Map<?, ?> json = objectMapper.readValue(payload, Map.class);
                Object choices = json.get("choices");
                if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> first)) continue;
                Object delta = first.get("delta");
                if (!(delta instanceof Map<?, ?> deltaMap)) delta = first.get("message");
                if (!(delta instanceof Map<?, ?> message)) continue;
                Object text = message.get("content");
                if (text != null && !String.valueOf(text).isEmpty()) {
                    String value = String.valueOf(text);
                    content.append(value);
                    if (contentSink != null) contentSink.accept(value);
                }
                Object think = message.get("reasoning_content");
                if (think == null) think = message.get("reasoning");
                if (think != null && !String.valueOf(think).isEmpty()) {
                    String value = String.valueOf(think);
                    reasoning.append(value);
                    if (reasoningSink != null) reasoningSink.accept(value);
                }
            } catch (Exception ignored) { }
        }
    }

    public List<String> supportedModels() {
        List<String> result = new ArrayList<>();
        String configured = configuredModels();
        if (configured != null) {
            Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(value -> { if (!result.contains(value)) result.add(value); });
        }
        String selected = configuredModel();
        if (!result.contains(selected)) result.add(0, selected);
        return result;
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", name());
        result.put("status", isAvailable() ? lastHealthStatus.get() : "未配置");
        result.put("configured", isAvailable());
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
        return text.contains("quota") || text.contains("limit") || text.contains("余额") || text.contains("rate")
                || text.contains("429") || text.contains("too many requests")
                ? "额度受限" : "请求失败";
    }

    private String configuredBaseUrl() {
        return modelConfigService.get(AgentModelConfigService.SCNET_BASE_URL, baseUrl);
    }

    private String configuredModel() {
        return modelConfigService.get(AgentModelConfigService.SCNET_MODEL, defaultModel);
    }

    private String configuredModels() {
        return modelConfigService.get(AgentModelConfigService.SCNET_MODELS, configuredModels);
    }

    private boolean supportsReasoning(String model) {
        String normalized = model == null ? "" : model.toLowerCase();
        return normalized.contains("reason") || normalized.contains("thinking") || normalized.contains("r1");
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> response) {
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) return null;
        Object message = firstMap.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) return null;
        Object content = messageMap.get("content");
        return content == null ? null : String.valueOf(content);
    }

    private String extractReasoning(Map<?, ?> response) {
        Object topLevel = response.get("reasoning");
        if (topLevel == null) topLevel = response.get("reasoning_content");
        if (topLevel != null) return String.valueOf(topLevel);
        Object choices = response.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object message = first.get("message");
            if (message instanceof Map<?, ?> messageMap) {
                Object value = messageMap.get("reasoning_content");
                if (value == null) value = messageMap.get("reasoning");
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    private Map<String, Object> fallback(String prompt, String requestId, String reason, long latencyMs, String model) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "scnet-fallback");
        data.put("provider", name());
        data.put("requestId", requestId);
        data.put("model", model);
        data.put("status", reason);
        data.put("latencyMs", latencyMs);
        data.put("summary", "SCNet 当前不可用，等待管理员配置的备用模型通道。");
        data.put("content", null);
        data.put("streamed", false);
        return data;
    }

    private String limitReasoning(String reasoning) {
        if (reasoning == null) return null;
        return reasoning.substring(0, Math.min(2000, reasoning.length()));
    }
}
