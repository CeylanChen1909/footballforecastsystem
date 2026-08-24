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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Component
public class OpenRouterModelProvider {

    private final WebClient.Builder webClientBuilder = WebClient.builder();
    private final AgentModelConfigService modelConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenRouterModelProvider(AgentModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @Value("${openrouter.api-key:}")
    private String apiKey;

    @Value("${openrouter.base-url:https://openrouter.ai/api/v1}")
    private String baseUrl;

    @Value("${openrouter.model:google/gemini-2.0-flash-exp:free}")
    private String defaultModel;

    private final AtomicReference<String> lastHealthStatus = new AtomicReference<>("未验证");
    private final AtomicReference<String> lastHealthMessage = new AtomicReference<>("");
    private final AtomicReference<String> lastCheckedAt = new AtomicReference<>("");

    public static final List<String> SUPPORTED_MODELS = List.of(
            "google/gemini-2.0-flash-exp:free",
            "google/gemini-flash-1.5",
            "openai/gpt-4o-mini",
            "openai/gpt-4o",
            "anthropic/claude-3.5-sonnet",
            "anthropic/claude-3-haiku",
            "meta-llama/llama-3.3-70b-instruct",
            "deepseek/deepseek-chat",
            "deepseek/deepseek-r1",
            "qwen/qwen-2.5-72b-instruct",
            "mistralai/mistral-7b-instruct:free"
    );

    public String name() {
        return "openrouter";
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

    public Map<String, Object> chat(String prompt, String modelOverride) {
        return chat(prompt, modelOverride, true);
    }

    public Map<String, Object> chat(String prompt, String modelOverride, boolean thinkingEnabled) {
        String requestId = UUID.randomUUID().toString();
        String useModel = resolveModel(modelOverride);
        boolean useThinking = modelConfigService.bool(AgentModelConfigService.THINKING_ENABLED, thinkingEnabled);
        if (!isAvailable()) {
            recordHealth("未配置", "缺少 OPENROUTER_API_KEY");
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
            if (useThinking && supportsReasoning(useModel)) {
                body.put("reasoning", Map.of("effort", "medium"));
            }

            Map<?, ?> res = webClientBuilder.baseUrl(configuredBaseUrl())
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://football-forecast.local")
                    .header("X-Title", "Football Forecast Agent")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(45));

            long latency = System.currentTimeMillis() - start;
            if (res == null) {
                recordHealth("请求失败", "empty-response");
                return fallback(prompt, requestId, "empty-response", latency, useModel);
            }
            recordHealth("正常", "");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", "openrouter");
            data.put("provider", name());
            data.put("requestId", requestId);
            data.put("model", useModel);
            data.put("status", "ok");
            data.put("latencyMs", latency);
            data.put("content", extractContent(res));
            data.put("usage", res.get("usage"));
            data.put("reasoning", limitReasoning(extractReasoning(res)));
            return data;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[OpenRouter] requestId={} model={} failed latencyMs={} errType={} errMsg={}", requestId, useModel, latency, e.getClass().getSimpleName(), e.getMessage());
            recordHealth(classifyHealth(e.getMessage()), e.getMessage());
            return fallback(prompt, requestId, e.getClass().getSimpleName(), latency, useModel);
        }
    }

    /** OpenAI-compatible SSE transport used by the Agent stream endpoint. */
    public Map<String, Object> stream(String prompt,
                                      String modelOverride,
                                      boolean thinkingEnabled,
                                      Consumer<String> contentSink,
                                      Consumer<String> reasoningSink) {
        String requestId = UUID.randomUUID().toString();
        String useModel = resolveModel(modelOverride);
        if (!isAvailable()) {
            recordHealth("未配置", "缺少 OPENROUTER_API_KEY");
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
            if (modelConfigService.bool(AgentModelConfigService.THINKING_ENABLED, thinkingEnabled) && supportsReasoning(useModel)) {
                body.put("reasoning", Map.of("effort", "medium"));
            }

            webClientBuilder.baseUrl(configuredBaseUrl()).build()
                    .post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://football-forecast.local")
                    .header("X-Title", "Football Forecast Agent")
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
            result.put("source", "openrouter");
            result.put("provider", name());
            result.put("requestId", requestId);
            result.put("model", useModel);
            result.put("status", "ok");
            result.put("streamed", true);
            result.put("latencyMs", latency);
            result.put("content", content.toString());
            result.put("reasoning", reasoning.isEmpty() ? null : limitReasoning(reasoning.toString()));
            return result;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            recordHealth(classifyHealth(e.getMessage()), e.getMessage());
            log.warn("[OpenRouter] streaming requestId={} model={} failed: {}", requestId, useModel, e.getMessage());
            // Some OpenAI-compatible gateways ignore stream=true and return a
            // normal JSON body. Retry once through the buffered contract so a
            // provider capability mismatch is not shown as an outage.
            Map<String, Object> buffered = chat(prompt, useModel, thinkingEnabled);
            if (isSuccessful(buffered)) {
                buffered.put("streamed", false);
                return buffered;
            }
            return fallback(prompt, requestId, e.getClass().getSimpleName(), latency, useModel);
        }
    }

    private boolean isSuccessful(Map<String, Object> result) {
        return result != null && "ok".equals(result.get("status"))
                && result.get("content") != null && !String.valueOf(result.get("content")).isBlank();
    }

    @SuppressWarnings("unchecked")
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
            } catch (Exception ignored) {
                // Providers occasionally split an SSE JSON object across
                // network chunks. The final buffered request still gives the
                // caller a complete response; malformed fragments are ignored.
            }
        }
    }

    public List<String> supportedModels() {
        String configured = modelConfigService.get(AgentModelConfigService.OPENROUTER_MODELS, "");
        if (configured.isBlank()) return SUPPORTED_MODELS;
        List<String> models = new java.util.ArrayList<>();
        for (String value : configured.split(",")) {
            if (value != null && !value.isBlank() && !models.contains(value.trim())) models.add(value.trim());
        }
        if (models.isEmpty()) return SUPPORTED_MODELS;
        String selected = configuredModel();
        if (!models.contains(selected)) models.add(0, selected);
        return models;
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "openrouter");
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
        return text.contains("quota") || text.contains("limit") || text.contains("余额") || text.contains("rate") ? "额度受限" : "请求失败";
    }

    private String resolveModel(String modelOverride) {
        // 模型由管理员统一配置，忽略请求体中的 model。
        return configuredModel();
    }

    private String configuredBaseUrl() {
        return modelConfigService.get(AgentModelConfigService.OPENROUTER_BASE_URL, baseUrl);
    }

    private String configuredModel() {
        return modelConfigService.get(AgentModelConfigService.OPENROUTER_MODEL, defaultModel);
    }

    private boolean supportsReasoning(String model) {
        String normalized = model == null ? "" : model.toLowerCase();
        return normalized.contains("reason") || normalized.contains("o1") || normalized.contains("o3") || normalized.contains("thinking");
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> res) {
        Object choices = res.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> firstMap) {
                Object message = firstMap.get("message");
                if (message instanceof Map<?, ?> msgMap) {
                    Object c = msgMap.get("content");
                    if (c != null) return String.valueOf(c);
                }
            }
        }
        return null;
    }

    private String extractReasoning(Map<?, ?> res) {
        Object topLevel = res.get("reasoning");
        if (topLevel == null) topLevel = res.get("reasoning_content");
        if (topLevel != null) return String.valueOf(topLevel);
        Object choices = res.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> firstMap) {
                Object message = firstMap.get("message");
                if (message instanceof Map<?, ?> msgMap) {
                    Object value = msgMap.get("reasoning_content");
                    if (value == null) value = msgMap.get("reasoning");
                    return value == null ? null : String.valueOf(value);
                }
            }
        }
        return null;
    }

    private Map<String, Object> fallback(String prompt, String requestId, String reason, long latencyMs, String model) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "openrouter-fallback");
        data.put("provider", name());
        data.put("requestId", requestId);
        data.put("model", model);
        data.put("status", reason);
        data.put("latencyMs", latencyMs);
        data.put("summary", "OpenRouter 当前不可用，已停止本次模型调用，稍后可重试。");
        data.put("content", null);
        data.put("streamed", false);
        return data;
    }

    private String limitReasoning(String reasoning) {
        if (reasoning == null) return null;
        return reasoning.substring(0, Math.min(2000, reasoning.length()));
    }
}
