package com.chen.football.agent.service;

import com.chen.football.crawler.service.DeepSeekPredictionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class AgentModelRouter {

    private final DeepSeekPredictionService deepSeekProvider;
    private final OpenRouterModelProvider openRouterProvider;
    private final ScnetModelProvider scnetProvider;
    private final AgentModelConfigService modelConfigService;

    public AgentModelRouter(DeepSeekPredictionService deepSeekProvider,
                           OpenRouterModelProvider openRouterProvider,
                           ScnetModelProvider scnetProvider,
                           AgentModelConfigService modelConfigService) {
        this.deepSeekProvider = deepSeekProvider;
        this.openRouterProvider = openRouterProvider;
        this.scnetProvider = scnetProvider;
        this.modelConfigService = modelConfigService;
    }

    public Map<String, Object> chat(String prompt) {
        return chat(prompt, null, null);
    }

    public Map<String, Object> chat(String prompt, String preferredProvider, String modelOverride) {
        return chat(prompt, preferredProvider, modelOverride, true);
    }

    public Map<String, Object> chat(String prompt, String preferredProvider, String modelOverride, Boolean thinking) {
        String provider = resolveProvider(preferredProvider);
        boolean thinkingEnabled = modelConfigService.bool(AgentModelConfigService.THINKING_ENABLED, true);
        boolean fallbackEnabled = modelConfigService.bool(AgentModelConfigService.FALLBACK_ENABLED, true);
        return switch (provider) {
            case "openrouter" -> {
                Map<String, Object> result = openRouterProvider.chat(prompt, modelOverride, thinkingEnabled);
                if (isSuccess(result)) {
                    yield result;
                }
                if (!fallbackEnabled) {
                    yield result;
                }
                log.info("[ModelRouter] openrouter failed (status={}), falling back to deepseek", result.get("status"));
                Map<String, Object> dsResult = deepSeekProvider.analyzeJson(prompt, null, thinkingEnabled);
                if (isSuccess(dsResult)) {
                    dsResult.put("fallbackFrom", "openrouter");
                    yield dsResult;
                }
                log.warn("[ModelRouter] both openrouter and deepseek failed");
                yield dsResult != null ? dsResult : result;
            }
            case "scnet" -> {
                Map<String, Object> result = scnetProvider.chat(prompt, modelOverride, thinkingEnabled);
                if (isSuccess(result)) {
                    yield result;
                }
                if (!fallbackEnabled) {
                    yield result;
                }
                Map<String, Object> fallbackResult = openRouterProvider.isAvailable()
                        ? openRouterProvider.chat(prompt, null, thinkingEnabled)
                        : deepSeekProvider.analyzeJson(prompt, null, thinkingEnabled);
                if (isSuccess(fallbackResult)) {
                    fallbackResult.put("fallbackFrom", "scnet");
                    yield fallbackResult;
                }
                log.warn("[ModelRouter] scnet and fallback provider failed");
                yield result;
            }
            default -> {
                Map<String, Object> result = deepSeekProvider.analyzeJson(prompt, modelOverride, thinkingEnabled);
                if (isSuccess(result)) {
                    yield result;
                }
                if (fallbackEnabled && openRouterProvider.isAvailable()) {
                    log.info("[ModelRouter] deepseek failed (status={}), falling back to openrouter", result.get("status"));
                    Map<String, Object> orResult = openRouterProvider.chat(prompt, null, thinkingEnabled);
                    if (isSuccess(orResult)) {
                        orResult.put("fallbackFrom", "deepseek");
                        yield orResult;
                    }
                }
                yield result;
            }
        };
    }

    public Map<String, Object> chatJson(String prompt) {
        return chatJson(prompt, null, null);
    }

    public Map<String, Object> chatJson(String prompt, String preferredProvider) {
        return chatJson(prompt, preferredProvider, null);
    }

    public Map<String, Object> chatJson(String prompt, String preferredProvider, String modelOverride) {
        Map<String, Object> result = chat(prompt, preferredProvider, modelOverride);
        if (result.get("content") == null) {
            result.put("content", extractContent(result));
        }
        return result;
    }

    public List<String> availableProviders() {
        List<String> available = new java.util.ArrayList<>();
        available.add("deepseek");
        if (openRouterProvider.isAvailable()) {
            available.add("openrouter");
        }
        if (scnetProvider.isAvailable()) {
            available.add("scnet");
        }
        available.add("auto");
        available.add("fallback");
        return available;
    }

    public Map<String, Object> modelCatalog() {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", availableProviders());
        catalog.put("selectionLocked", true);
        catalog.put("userSelectable", false);
        catalog.put("configuredPolicy", modelConfigService.publicSnapshot());
        catalog.put("deepseek", Map.of(
                "defaultModel", deepSeekProvider.defaultModel(),
                "availableModels", deepSeekProvider.availableModels(),
                "thinkingSupported", true,
                "note", "模型由 DEEPSEEK_MODEL / DEEPSEEK_MODELS 配置"
        ));
        catalog.put("openrouter", Map.of(
                "defaultModel", openRouterProvider.defaultModel(),
                "availableModels", openRouterProvider.supportedModels(),
                "available", openRouterProvider.isAvailable(),
                "note", "仅允许目录中的模型，未知模型会回退到默认模型"
        ));
        catalog.put("scnet", Map.of(
                "defaultModel", scnetProvider.defaultModel(),
                "availableModels", scnetProvider.supportedModels(),
                "available", scnetProvider.isAvailable(),
                "thinkingSupported", false,
                "note", "OpenAI 兼容接口；API Key 仅从 SCNET_API_KEY 环境变量读取"
        ));
        return catalog;
    }

    /**
     * Stream model output when the selected provider supports OpenAI SSE. The
     * buffered providers remain a safe fallback, so transport failures never
     * turn into a half-completed response without an explicit status.
     */
    public Map<String, Object> chatStreaming(String prompt,
                                             String preferredProvider,
                                             String modelOverride,
                                             Boolean thinking,
                                             Consumer<String> contentSink,
                                             Consumer<String> reasoningSink) {
        String provider = resolveProvider(preferredProvider);
        boolean thinkingEnabled = thinking == null
                ? modelConfigService.bool(AgentModelConfigService.THINKING_ENABLED, true)
                : thinking;
        boolean fallbackEnabled = modelConfigService.bool(AgentModelConfigService.FALLBACK_ENABLED, true);
        if ("openrouter".equals(provider)) {
            Map<String, Object> result = openRouterProvider.stream(prompt, modelOverride, thinkingEnabled, contentSink, reasoningSink);
            if (isSuccess(result) || !fallbackEnabled) return result;
            Map<String, Object> fallback = deepSeekProvider.analyzeJson(prompt, null, thinkingEnabled);
            if (isSuccess(fallback)) fallback.put("fallbackFrom", "openrouter");
            return fallback;
        }
        if ("scnet".equals(provider)) {
            Map<String, Object> result = scnetProvider.stream(prompt, modelOverride, thinkingEnabled, contentSink, reasoningSink);
            if (isSuccess(result) || !fallbackEnabled) return result;
            Map<String, Object> fallback = openRouterProvider.isAvailable()
                    ? openRouterProvider.stream(prompt, null, thinkingEnabled, contentSink, reasoningSink)
                    : deepSeekProvider.analyzeJson(prompt, null, thinkingEnabled);
            if (isSuccess(fallback)) fallback.put("fallbackFrom", "scnet");
            return fallback;
        }
        Map<String, Object> result = deepSeekProvider.analyzeJson(prompt, modelOverride, thinkingEnabled);
        if (isSuccess(result) || !fallbackEnabled || !openRouterProvider.isAvailable()) return result;
        Map<String, Object> fallback = openRouterProvider.stream(prompt, null, thinkingEnabled, contentSink, reasoningSink);
        if (isSuccess(fallback)) fallback.put("fallbackFrom", "deepseek");
        return fallback;
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providers", List.of(deepSeekProvider.healthSnapshot(), openRouterProvider.healthSnapshot(), scnetProvider.healthSnapshot()));
        result.put("openrouterConfigured", openRouterProvider.isAvailable());
        result.put("deepseekConfigured", deepSeekProvider.isConfigured());
        result.put("scnetConfigured", scnetProvider.isAvailable());
        result.put("configuredPolicy", modelConfigService.publicSnapshot());
        return result;
    }

    /** Stable cache namespace for the current administrator model policy. */
    public String policyFingerprint() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("configured", modelConfigService.publicSnapshot());
        policy.put("deepseek", deepSeekProvider.defaultModel());
        policy.put("openrouter", openRouterProvider.defaultModel());
        policy.put("scnet", scnetProvider.defaultModel());
        return Integer.toHexString(policy.toString().hashCode());
    }

    private String resolveProvider(String preferred) {
        // 仅管理员配置决定路由；preferred 来自用户请求，必须忽略。
        String configured = modelConfigService.get(AgentModelConfigService.PROVIDER, "auto");
        if ("openrouter".equalsIgnoreCase(configured)) {
            return routeOrFallback("openrouter");
        }
        if ("scnet".equalsIgnoreCase(configured)) {
            return routeOrFallback("scnet");
        }
        if ("deepseek".equalsIgnoreCase(configured)) {
            return "deepseek";
        }
        if (isRouteHealthy(openRouterProvider.healthSnapshot())) return "openrouter";
        if (isRouteHealthy(scnetProvider.healthSnapshot())) return "scnet";
        return "deepseek";
    }

    private String routeOrFallback(String requested) {
        if ("openrouter".equals(requested) && isRouteHealthy(openRouterProvider.healthSnapshot())) return requested;
        if ("scnet".equals(requested) && isRouteHealthy(scnetProvider.healthSnapshot())) return requested;
        if (isRouteHealthy(openRouterProvider.healthSnapshot())) return "openrouter";
        if (isRouteHealthy(scnetProvider.healthSnapshot())) return "scnet";
        return "deepseek";
    }

    private boolean isRouteHealthy(Map<String, Object> snapshot) {
        if (snapshot == null || !Boolean.TRUE.equals(snapshot.get("configured"))) return false;
        String status = String.valueOf(snapshot.getOrDefault("status", "未验证"));
        return !List.of("未配置", "额度受限", "请求失败").contains(status);
    }

    private boolean isSuccess(Map<String, Object> result) {
        if (result == null) return false;
        Object status = result.get("status");
        Object content = result.get("content");
        boolean statusOk = "ok".equals(status);
        boolean hasContent = content != null && !String.valueOf(content).isBlank();
        if (statusOk && hasContent) return true;
        Object raw = result.get("raw");
        return raw instanceof Map<?, ?> && content != null && hasContent;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> result) {
        Object raw = result.get("raw");
        if (raw instanceof Map<?, ?> rawMap) {
            Object choices = rawMap.get("choices");
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
        }
        return null;
    }
}
