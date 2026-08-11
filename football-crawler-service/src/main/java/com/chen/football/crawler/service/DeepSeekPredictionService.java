package com.chen.football.crawler.service;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekPredictionService {

    private final WebClient.Builder webClientBuilder;

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-v4-pro}")
    private String model;

    public Map<String, Object> analyze(String prompt) {
        String requestId = UUID.randomUUID().toString();
        log.info("[DeepSeek] enter analyze requestId={} baseUrl={} model={}", requestId, baseUrl, model);
        log.info("[DeepSeek] requestId={} promptPreview={}", requestId, preview(prompt));
        log.info("[DeepSeek] requestId={} apiKeyPresent={} apiKeyLength={}", requestId, apiKey != null && !apiKey.isBlank(), apiKey == null ? 0 : apiKey.length());

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[DeepSeek] requestId={} missing apiKey -> fallback", requestId);
            return fallback(prompt, requestId, "missing-api-key", 0L);
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "You are a helpful assistant. Return concise JSON only."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "thinking", Map.of("type", "enabled"),
                    "reasoning_effort", "high",
                    "stream", false
            );
            log.debug("[DeepSeek] requestId={} requestBody={}", requestId, body);

            Map<?, ?> res = webClientBuilder.baseUrl(baseUrl)
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
            log.debug("[DeepSeek] requestId={} rawResponse={}", requestId, res);
            if (res == null) {
                log.warn("[DeepSeek] requestId={} empty response -> fallback", requestId);
                return fallback(prompt, requestId, "empty-response", latency);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", "deepseek-v4-pro");
            data.put("requestId", requestId);
            data.put("model", model);
            data.put("status", "ok");
            data.put("latencyMs", latency);
            data.put("raw", res);
            return data;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[DeepSeek] requestId={} failed latencyMs={} errType={} errMsg={}", requestId, latency, e.getClass().getSimpleName(), e.getMessage(), e);
            return fallback(prompt, requestId, e.getClass().getSimpleName(), latency);
        }
    }

    public Map<String, Object> analyzeJson(String prompt) {
        Map<String, Object> ai = analyze(prompt);
        Object raw = ai.get("raw");
        String content = null;
        if (raw instanceof Map<?, ?> rawMap) {
            Object choices = rawMap.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> firstMap) {
                    Object message = firstMap.get("message");
                    if (message instanceof Map<?, ?> msgMap) {
                        Object c = msgMap.get("content");
                        if (c != null) content = String.valueOf(c);
                    }
                }
            }
        }
        ai.put("content", content);
        return ai;
    }

    private String preview(String prompt) {
        if (prompt == null) return "null";
        String compact = prompt.replaceAll("\\s+", " ").trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180) + "...";
    }

    private Map<String, Object> fallback(String prompt, String requestId, String reason, long latencyMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "fallback");
        data.put("requestId", requestId);
        data.put("model", "fallback-rules");
        data.put("status", reason);
        data.put("latencyMs", latencyMs);
        data.put("summary", "当前使用规则化兜底分析，未能连接到 DeepSeek。\n" + prompt);
        return data;
    }
}
