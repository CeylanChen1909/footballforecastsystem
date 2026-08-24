package com.chen.football.agent.controller;

import com.chen.football.agent.dto.AgentAnalysisRequest;
import com.chen.football.agent.dto.AgentAnalysisResponse;
import com.chen.football.agent.dto.AgentChatRequest;
import com.chen.football.agent.dto.AgentChatResponse;
import com.chen.football.agent.service.AgentConversationStore;
import com.chen.football.agent.service.AgentModelRouter;
import com.chen.football.agent.service.AgentResponseCacheService;
import com.chen.football.agent.service.AgentStreamService;
import com.chen.football.agent.service.AgentToolCircuitBreaker;
import com.chen.football.agent.service.AgentRateLimitService;
import com.chen.football.agent.service.AgentCancellationRegistry;
import com.chen.football.agent.service.AgentMetricsService;
import com.chen.football.agent.service.AgentEventBroadcaster;
import com.chen.football.agent.service.AgentConcurrencyGuard;
import com.chen.football.agent.service.FootballAgentService;
import com.chen.football.agent.service.FootballChatAgentService;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.util.AdminGuard;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final FootballAgentService agentService;
    private final FootballChatAgentService chatAgentService;
    private final AgentConversationStore conversationStore;
    private final AgentResponseCacheService responseCacheService;
    private final AgentStreamService streamService;
    private final AgentToolCircuitBreaker circuitBreaker;
    private final AgentModelRouter modelRouter;
    private final AgentRateLimitService rateLimitService;
    private final AgentCancellationRegistry cancellationRegistry;
    private final AgentMetricsService metricsService;
    private final AgentEventBroadcaster broadcaster;
    private final AgentConcurrencyGuard concurrencyGuard;

    public AgentController(FootballAgentService agentService,
                          FootballChatAgentService chatAgentService,
                          AgentConversationStore conversationStore,
                          AgentResponseCacheService responseCacheService,
                          AgentStreamService streamService,
                          AgentToolCircuitBreaker circuitBreaker,
                          AgentModelRouter modelRouter,
                          AgentRateLimitService rateLimitService,
                          AgentCancellationRegistry cancellationRegistry,
                          AgentMetricsService metricsService,
                          AgentEventBroadcaster broadcaster,
                          AgentConcurrencyGuard concurrencyGuard) {
        this.agentService = agentService;
        this.chatAgentService = chatAgentService;
        this.conversationStore = conversationStore;
        this.responseCacheService = responseCacheService;
        this.streamService = streamService;
        this.circuitBreaker = circuitBreaker;
        this.modelRouter = modelRouter;
        this.rateLimitService = rateLimitService;
        this.cancellationRegistry = cancellationRegistry;
        this.metricsService = metricsService;
        this.broadcaster = broadcaster;
        this.concurrencyGuard = concurrencyGuard;
    }

    @PostMapping("/analyze")
    public ApiResponse<AgentAnalysisResponse> analyze(@RequestBody AgentAnalysisRequest request) {
        requireLoginAndRateLimit();
        requireTokenBudget(2048);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分析请求不能为空");
        if (request.prompt() != null && request.prompt().length() > 4000) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "分析问题不能超过 4000 个字符");
        }
        Long userId = UserContext.getUserId();
        if (!concurrencyGuard.tryAcquire(userId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 当前正在处理较多请求，请稍后重试");
        }
        try {
            return ApiResponse.ok(agentService.analyze(request));
        } finally {
            concurrencyGuard.release(userId);
        }
    }

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@RequestBody AgentChatRequest request) {
        request = normalizeChatRequest(request);
        validateChatRequest(request);
        requireSessionAccess(request == null ? null : request.sessionId());
        requireRateLimit();
        requireTokenBudget(request == null || request.maxTokens() == null ? 2048 : request.maxTokens());
        Long userId = UserContext.getUserId();
        if (!concurrencyGuard.tryAcquire(userId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 当前正在处理较多请求，请稍后重试");
        }
        try {
            return ApiResponse.ok(chatAgentService.chat(request, userId));
        } finally {
            concurrencyGuard.release(userId);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> chatStream(@RequestBody AgentChatRequest request) {
        request = normalizeChatRequest(request);
        validateChatRequest(request);
        requireSessionAccess(request == null ? null : request.sessionId());
        if (!rateLimitService.allow(UserContext.getUserId())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Agent 请求过于频繁，请一分钟后再试");
        }
        requireTokenBudget(request == null || request.maxTokens() == null ? 2048 : request.maxTokens());
        Long userId = UserContext.getUserId();
        if (!concurrencyGuard.tryAcquire(userId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 当前正在处理较多请求，请稍后重试");
        }
        return streamService.streamChat(request, userId)
                .doFinally(signal -> concurrencyGuard.release(userId));
    }

    private void validateChatRequest(AgentChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 Agent 问题");
        }
    }

    private AgentChatRequest normalizeChatRequest(AgentChatRequest request) {
        if (request == null) return null;
        String message = request.message() == null ? "" : request.message().trim();
        if (message.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Agent 问题不能超过 4000 个字符");
        }
        List<com.chen.football.agent.dto.AgentMessage> history = request.history() == null
                ? List.of()
                : request.history().stream()
                .filter(item -> item != null && ("user".equalsIgnoreCase(item.role()) || "assistant".equalsIgnoreCase(item.role())))
                .map(item -> new com.chen.football.agent.dto.AgentMessage(
                        item.role().toLowerCase(),
                        item.content() == null ? "" : item.content().substring(0, Math.min(item.content().length(), 4000))))
                .limit(20).toList();
        Double temperature = request.temperature() == null ? null : Math.max(0d, Math.min(1.2d, request.temperature()));
        Integer maxTokens = request.maxTokens() == null ? null : Math.max(128, Math.min(2048, request.maxTokens()));
        String sessionId = request.sessionId();
        if (sessionId != null && sessionId.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话 ID 无效");
        }
        Map<String, Object> context = request.context() == null ? Map.of() : boundedContext(request.context());
        return new AgentChatRequest(message, history, sessionId, request.model(), request.provider(),
                temperature, maxTokens, request.thinking(), context);
    }

    private Map<String, Object> boundedContext(Map<String, Object> input) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || result.size() >= 32) continue;
            String key = entry.getKey().trim();
            if (key.length() > 64) continue;
            String value = String.valueOf(entry.getValue());
            if (value.length() > 1000 || total + value.length() > 8000) continue;
            result.put(key, value);
            total += value.length();
        }
        return result;
    }

    @PostMapping("/chat/stream/{streamId}/cancel")
    public ApiResponse<Map<String, Object>> cancelStream(@PathVariable String streamId) {
        AdminGuard.requireLogin();
        if (!broadcaster.ownsStream(streamId, UserContext.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 流不存在或无权取消");
        }
        cancellationRegistry.cancel(streamId);
        return ApiResponse.ok(Map.of("ok", true, "streamId", streamId));
    }

    private void requireRateLimit() {
        if (!rateLimitService.allow(UserContext.getUserId())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Agent 请求过于频繁，请一分钟后再试");
        }
    }

    private void requireTokenBudget(int requestedTokens) {
        if (!rateLimitService.allowTokenBudget(UserContext.getUserId(), requestedTokens)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Agent 今日模型额度已用尽，请明日再试");
        }
    }

    private void requireLoginAndRateLimit() {
        AdminGuard.requireLogin();
        requireRateLimit();
    }

    private void requireSessionAccess(String sessionId) {
        AdminGuard.requireLogin();
        if (sessionId == null || sessionId.isBlank()) return;
        Long userId = UserContext.getUserId();
        if (conversationStore.hasConversation(sessionId) && !conversationStore.ownsSession(userId, sessionId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 Agent 会话");
        }
    }

    private void requireOwnedSession(String sessionId) {
        AdminGuard.requireLogin();
        if (!conversationStore.ownsSession(UserContext.getUserId(), sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 会话不存在");
        }
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        AdminGuard.requireLogin();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agent", "football-agent");
        Map<String, Object> modelHealth = modelRouter.healthSnapshot();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) modelHealth.getOrDefault("providers", List.of());
        boolean modelReady = providers.stream().anyMatch(item -> "正常".equals(item.get("status")));
        boolean pending = providers.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("configured")) && "未验证".equals(item.get("status")));
        result.put("status", modelReady ? "ok" : (pending ? "checking" : "degraded"));
        result.put("tools", agentService.toolNames());
        result.put("circuitBreaker", circuitBreaker.snapshot());
        result.put("models", publicModelHealth(modelHealth));
        return ApiResponse.ok(result);
    }

    @GetMapping("/tools")
    public ApiResponse<List<String>> tools() {
        AdminGuard.requireLogin();
        return ApiResponse.ok(agentService.toolNames());
    }

    @GetMapping("/capabilities")
    public ApiResponse<List<Map<String, Object>>> capabilities() {
        AdminGuard.requireLogin();
        return ApiResponse.ok(agentService.toolCapabilities());
    }

    @GetMapping("/session/{sessionId}")
    public ApiResponse<Map<String, Object>> session(@PathVariable String sessionId) {
        requireOwnedSession(sessionId);
        // 统一从持久化会话读取；旧的 Redis-only memory store 不再作为第二份事实。
        return ApiResponse.ok(conversationStore.snapshot(sessionId));
    }

    @GetMapping("/conversation/{sessionId}")
    public ApiResponse<Map<String, Object>> conversation(@PathVariable String sessionId) {
        requireOwnedSession(sessionId);
        return ApiResponse.ok(conversationStore.snapshot(sessionId));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AgentConversationStore.SessionMeta>> sessions() {
        AdminGuard.requireLogin();
        return ApiResponse.ok(conversationStore.listSessions(UserContext.getUserId()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> deleteSession(@PathVariable String sessionId) {
        requireOwnedSession(sessionId);
        conversationStore.deleteSession(UserContext.getUserId(), sessionId);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> renameSession(@PathVariable String sessionId,
                                                            @RequestBody Map<String, Object> payload) {
        requireOwnedSession(sessionId);
        String title = payload == null ? "" : String.valueOf(payload.getOrDefault("title", ""));
        if (!conversationStore.renameSession(UserContext.getUserId(), sessionId, title)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标题不能为空");
        }
        return ApiResponse.ok(Map.of("ok", true, "title", title.trim()));
    }

    @GetMapping("/cache/{key}")
    public ApiResponse<Map<String, Object>> cache(@PathVariable String key) {
        AdminGuard.requireSuperAdmin();
        return ApiResponse.ok(responseCacheService.snapshot(key));
    }

    @GetMapping("/circuit-breaker")
    public ApiResponse<Map<String, Object>> circuitBreaker() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(circuitBreaker.snapshot());
    }

    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> models() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(modelRouter.modelCatalog());
    }

    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> metrics(@RequestParam(defaultValue = "7") int days) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(metricsService.summary(days));
    }

    private Map<String, Object> publicModelHealth(Map<String, Object> snapshot) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) snapshot.getOrDefault("providers", List.of());
        result.put("providers", providers.stream().map(item -> {
            Map<String, Object> safe = new java.util.LinkedHashMap<>();
            safe.put("provider", item.get("provider"));
            safe.put("status", item.get("status"));
            safe.put("configured", item.get("configured"));
            safe.put("lastCheckedAt", item.get("lastCheckedAt"));
            safe.put("model", item.get("model"));
            return safe;
        }).toList());
        // 仅返回当前模型名称和非敏感策略；不返回完整 Base URL，避免暴露
        // 内部网络拓扑或把管理配置误当成用户可选模型目录。
        Map<String, Object> policy = new java.util.LinkedHashMap<>();
        Object rawPolicy = snapshot.get("configuredPolicy");
        if (rawPolicy instanceof Map<?, ?> configured) {
            for (String key : List.of("provider", "thinkingEnabled", "fallbackEnabled", "temperature", "maxTokens")) {
                if (configured.containsKey(key)) policy.put(key, configured.get(key));
            }
            for (String provider : List.of("deepseek", "openrouter", "scnet")) {
                Object item = configured.get(provider);
                if (item instanceof Map<?, ?> map && map.get("model") != null) {
                    policy.put(provider, Map.of("model", map.get("model")));
                }
            }
        }
        result.put("configuredPolicy", policy);
        return result;
    }
}
