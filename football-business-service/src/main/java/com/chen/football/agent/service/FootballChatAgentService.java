package com.chen.football.agent.service;

import com.chen.football.agent.dto.AgentChatRequest;
import com.chen.football.agent.dto.AgentChatResponse;
import com.chen.football.agent.dto.AgentMessage;
import com.chen.football.common.context.UserContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.UUID;

@Service
public class FootballChatAgentService {

    private static final int MAX_HISTORY = 12;
    private static final int MAX_RECENT_RECALL = 10;

    private final AgentModelRouter modelRouter;
    private final AgentConversationStore conversationStore;
    private final AgentIntentClassifier intentClassifier;
    private final AgentResponseCacheService responseCacheService;
    private final ObjectMapper objectMapper;
    private final FootballAgentService toolAgentService;
    private final AgentAnswerValidator answerValidator;

    public FootballChatAgentService(AgentModelRouter modelRouter,
                                    AgentConversationStore conversationStore,
                                    AgentIntentClassifier intentClassifier,
                                    AgentResponseCacheService responseCacheService,
                                    ObjectMapper objectMapper,
                                    FootballAgentService toolAgentService,
                                    AgentAnswerValidator answerValidator) {
        this.modelRouter = modelRouter;
        this.conversationStore = conversationStore;
        this.intentClassifier = intentClassifier;
        this.responseCacheService = responseCacheService;
        this.objectMapper = objectMapper;
        this.toolAgentService = toolAgentService;
        this.answerValidator = answerValidator;
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        return chat(request, UserContext.getUserId());
    }

    public AgentChatResponse chat(AgentChatRequest request, Long userId) {
        return chat(request, userId, null);
    }

    public AgentChatResponse chat(AgentChatRequest request, Long userId, Consumer<Map<String, Object>> eventSink) {
        String requestId = UUID.randomUUID().toString();
        Instant start = Instant.now();
        String sessionId = resolveSessionId(request.sessionId());
        String userMessage = request.message() == null ? "" : request.message().trim();
        request = inheritFollowUpContext(request, sessionId, userMessage);
        Map<String, Object> intent = intentClassifier.classify(userMessage);
        String intentName = String.valueOf(intent.getOrDefault("intent", "general"));

        AgentToolRun toolRun = toolAgentService.executeChatTools(request, userId, eventSink);
        Map<String, Object> toolContext = new LinkedHashMap<>(toolRun.context());
        toolContext.put("toolOutputs", toolRun.toolOutputs());
        toolContext.put("toolSteps", toolRun.steps());
        toolContext.put("toolLatencies", toolRun.toolLatencies());
        toolContext.put("evidence", toolRun.evidence());

        String cacheKey = buildCacheKey(sessionId, userMessage, request.history(), request.context(), request.provider(), request.model(), request.temperature(), request.maxTokens(), request.thinking(), intentName);
        // Tool-backed conversations must reflect current fixture/model data;
        // only cache context-free synchronous chat replies.
        AgentChatResponse cached = eventSink == null && cacheableToolRun(toolRun)
                ? responseCacheService.getChat(cacheKey)
                : null;
        if (cached != null) {
            Map<String, Object> metadata = new LinkedHashMap<>(cached.metadata() == null ? Map.of() : cached.metadata());
            metadata.put("cacheHit", true);
            metadata.put("intent", intentName);
            metadata.put("sessionId", sessionId);
            metadata.put("toolSteps", toolRun.steps());
            metadata.put("toolLatencies", toolRun.toolLatencies());
            metadata.put("evidence", toolRun.evidence());
            return new AgentChatResponse(
                    cached.agent(),
                    cached.requestId(),
                    cached.status(),
                    cached.answer(),
                    cached.messages(),
                    metadata
            );
        }

        List<AgentMessage> recalled = conversationStore.recentMessages(sessionId, MAX_RECENT_RECALL);
        List<AgentMessage> messages = new ArrayList<>();

        if (recalled != null && !recalled.isEmpty()) {
            messages.addAll(recalled);
        }
        // The server-side conversation is authoritative. Only use a client
        // transcript for a brand-new session; concatenating both copies used
        // to duplicate messages and inflate prompt cost.
        if ((recalled == null || recalled.isEmpty()) && request.history() != null && !request.history().isEmpty()) {
            request.history().stream().limit(MAX_HISTORY).forEach(messages::add);
        }
        if (!userMessage.isBlank()) {
            messages.add(AgentMessage.user(userMessage));
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sessionId", sessionId);
        context.put("recalledMessageCount", recalled == null ? 0 : recalled.size());
        context.put("intent", intentName);
        context.put("intentConfidence", intent.getOrDefault("confidence", 0.0));
        context.putAll(toolContext);

        String prompt = AgentPromptFactory.buildChatPrompt(request, messages, context, toolRun.toolOutputs(), toolRun.evidence());
        Map<String, Object> ai;
        if (eventSink != null) {
            ai = modelRouter.chatStreaming(
                    prompt,
                    request.provider(),
                    request.model(),
                    request.thinking(),
                    chunk -> emitModelEvent(eventSink, "chunk", Map.of("content", chunk)),
                    chunk -> emitModelEvent(eventSink, "reasoning_delta", Map.of("content", chunk))
            );
        } else {
            ai = modelRouter.chat(prompt, request.provider(), request.model(), request.thinking());
        }
        AgentAnswerValidator.Validation validation = answerValidator.validate(resolveAnswer(ai), toolRun);
        String answer = validation.answer();
        List<Map<String, Object>> facts = AgentEvidenceComposer.facts(toolRun);
        List<Map<String, Object>> unknowns = AgentEvidenceComposer.unknowns(toolRun);

        List<AgentMessage> newTurn = new ArrayList<>();
        if (!userMessage.isBlank()) {
            newTurn.add(AgentMessage.user(userMessage));
        }
        newTurn.add(AgentMessage.assistant(answer));
        Map<String, Object> conversationMetadata = new LinkedHashMap<>();
        conversationMetadata.put("requestId", requestId);
        conversationMetadata.put("lastMode", "chat");
        conversationMetadata.put("intent", intentName);
        conversationMetadata.put("provider", ai.getOrDefault("provider", request.provider()));
        conversationMetadata.put("model", ai.getOrDefault("model", request.model()));
        conversationMetadata.put("fallbackFrom", ai.getOrDefault("fallbackFrom", ""));
        conversationMetadata.put("streamed", Boolean.TRUE.equals(ai.get("streamed")));
        conversationMetadata.put("reasoning", summarizeReasoning(ai.getOrDefault("reasoning", extractRawReasoning(ai.get("raw")))));
        conversationMetadata.put("evidence", toolRun.evidence());
        conversationMetadata.put("facts", facts);
        conversationMetadata.put("unknowns", unknowns);
        conversationMetadata.put("answerValidation", validation.metadata());
        conversationMetadata.put("dataQuality", AgentEvidenceComposer.quality(toolRun));
        conversationMetadata.put("contextSnapshot", safeContextSnapshot(toolRun.context()));
        conversationMetadata.put("evidenceSources", toolRun.evidence().stream().map(item -> String.valueOf(item.getOrDefault("label", item.get("source")))).distinct().toList());
        conversationMetadata.put("toolSteps", toolRun.steps());
        conversationMetadata.put("toolLatencies", toolRun.toolLatencies());
        conversationMetadata.put("artifacts", buildArtifacts(toolRun));
        conversationMetadata.put("actions", buildActions(toolRun));
        conversationMetadata.put("dataFreshness", latestDataTimestamp(toolRun.evidence()));
        conversationStore.append(sessionId, newTurn, conversationMetadata, userId);
        conversationStore.registerSession(userId, sessionId, userMessage.isBlank() ? "新会话" : userMessage.substring(0, Math.min(24, userMessage.length())), userMessage);

        List<AgentMessage> transcript = new ArrayList<>(messages);
        transcript.add(AgentMessage.assistant(answer));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", sessionId);
        metadata.put("requestId", requestId);
        metadata.put("model", ai.getOrDefault("model", request.model()));
        metadata.put("requestedModel", request.model());
        metadata.put("temperature", request.temperature());
        metadata.put("maxTokens", request.maxTokens());
        metadata.put("latencyMs", Duration.between(start, Instant.now()).toMillis());
        metadata.put("historySize", request.history() == null ? 0 : request.history().size());
        metadata.put("recalledMessageCount", recalled == null ? 0 : recalled.size());
        metadata.put("aiStatus", ai.getOrDefault("status", "ok"));
        metadata.put("streamed", Boolean.TRUE.equals(ai.get("streamed")));
        metadata.put("aiSource", ai.getOrDefault("source", "unknown"));
        metadata.put("usage", ai.getOrDefault("usage", Map.of()));
        metadata.put("provider", ai.getOrDefault("provider", request.provider()));
        metadata.put("requestedProvider", request.provider());
        metadata.put("fallbackFrom", ai.getOrDefault("fallbackFrom", ""));
        metadata.put("thinkingEnabled", request.thinking() == null || request.thinking());
        metadata.put("reasoning", summarizeReasoning(ai.getOrDefault("reasoning", extractRawReasoning(ai.get("raw")))));
        metadata.put("cacheHit", false);
        metadata.put("intent", intentName);
        metadata.put("intentConfidence", intent.getOrDefault("confidence", 0.0));
        metadata.put("evidenceSources", toolRun.evidence().isEmpty()
                ? evidenceSources(intentName)
                : toolRun.evidence().stream().map(item -> String.valueOf(item.getOrDefault("label", item.get("source")))).distinct().toList());
        metadata.put("evidence", toolRun.evidence());
        metadata.put("facts", facts);
        metadata.put("unknowns", unknowns);
        metadata.put("answerValidation", validation.metadata());
        metadata.put("dataQuality", AgentEvidenceComposer.quality(toolRun));
        metadata.put("toolSteps", toolRun.steps());
        metadata.put("toolLatencies", toolRun.toolLatencies());
        metadata.put("skippedTools", toolRun.skippedTools());
        metadata.put("artifacts", buildArtifacts(toolRun));
        metadata.put("actions", buildActions(toolRun));
        metadata.put("dataFreshness", latestDataTimestamp(toolRun.evidence()));

        AgentChatResponse response = new AgentChatResponse(
                "football-chat-agent",
                requestId,
                String.valueOf(ai.getOrDefault("status", "ok")),
                answer,
                transcript,
                metadata
        );
        if (cacheableToolRun(toolRun) && isCacheableModel(ai, answer)) responseCacheService.putChat(cacheKey, response);
        return response;
    }

    private void emitModelEvent(Consumer<Map<String, Object>> eventSink,
                                String type,
                                Map<String, Object> payload) {
        if (eventSink == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("timestamp", Instant.now().toString());
        if (payload != null) event.putAll(payload);
        try { eventSink.accept(event); } catch (RuntimeException ignored) { }
    }

    /** Carry only canonical entities into an obvious follow-up question. */
    private AgentChatRequest inheritFollowUpContext(AgentChatRequest request, String sessionId, String message) {
        if (request == null || sessionId == null || !looksLikeFollowUp(message)) return request;
        Map<String, Object> previous = conversationStore.snapshot(sessionId).get("metadata") instanceof Map<?, ?> raw
                ? castObjectMap(raw) : Map.of();
        Map<String, Object> snapshot = previous.get("contextSnapshot") instanceof Map<?, ?> raw
                ? castObjectMap(raw) : Map.of();
        if (snapshot.isEmpty()) return request;
        Map<String, Object> merged = new LinkedHashMap<>(snapshot);
        if (request.context() != null) merged.putAll(request.context());
        return new AgentChatRequest(request.message(), request.history(), request.sessionId(), request.model(), request.provider(),
                request.temperature(), request.maxTokens(), request.thinking(), merged);
    }

    private boolean looksLikeFollowUp(String message) {
        if (message == null || message.isBlank() || message.length() > 48) return false;
        return message.matches("(?s).*(这场|这支|这两支|刚才|上一场|它们|他们|阵容|首发|门将|后卫|中场|前锋|球员|名单|预测|为什么|那名单|那场).*" );
    }

    private Map<String, Object> safeContextSnapshot(Map<String, Object> context) {
        if (context == null) return Map.of();
        Set<String> keys = Set.of("fixtureId", "homeTeamId", "awayTeamId", "homeTeamName", "awayTeamName",
                "leagueName", "matchTime", "teamId", "teamName", "comparisonTeamId", "comparisonTeamName");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null && !String.valueOf(value).isBlank()) result.put(key, value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObjectMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> { if (key != null) result.put(String.valueOf(key), value); });
        return result;
    }

    private String resolveAnswer(Map<String, Object> ai) {
        Object content = ai.get("content");
        if (content != null && !String.valueOf(content).isBlank()) {
            return normalizeAnswer(String.valueOf(content));
        }
        String rawContent = extractRawContent(ai.get("raw"));
        if (rawContent != null && !rawContent.isBlank()) {
            return normalizeAnswer(rawContent);
        }
        Object summary = ai.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            return normalizeAnswer(String.valueOf(summary));
        }
        return "暂时无法生成回复，请稍后重试。";
    }

    private List<String> evidenceSources(String intent) {
        return switch (intent) {
            case "schedule" -> List.of("主爬虫赛程源", "本地赛程数据库");
            case "match-analysis" -> List.of("赛程与赛事上下文", "预测模型");
            case "team-analysis" -> List.of("球队资料", "近期赛程");
            case "team-roster" -> List.of("公开球队阵容源", "球队资料");
            case "news-analysis" -> List.of("赛事资讯 RSS 摘要", "来源原文链接");
            case "prediction" -> List.of("预测模型", "球队近期数据");
            default -> List.of("ChenFootball 当前数据集");
        };
    }

    private List<Map<String, Object>> buildArtifacts(AgentToolRun run) {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        Map<String, Object> context = run.context();
        if (run.toolOutputs().containsKey("match_context") || context.containsKey("fixtureId")) {
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("type", "match");
            match.put("fixtureId", context.get("fixtureId"));
            match.put("homeTeamName", context.getOrDefault("homeTeamName", "主队"));
            match.put("awayTeamName", context.getOrDefault("awayTeamName", "客队"));
            match.put("homeTeamLogo", context.get("homeTeamLogo"));
            match.put("awayTeamLogo", context.get("awayTeamLogo"));
            match.put("leagueName", context.get("leagueName"));
            match.put("matchTime", context.get("matchTime"));
            artifacts.add(match);
        }
        Object prediction = run.toolOutputs().get("prediction");
        if (prediction instanceof Map<?, ?> source) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "prediction");
            for (String key : List.of("fixtureId", "resultLabel", "homeWinProb", "drawProb", "awayWinProb", "modelVersion", "featureStatus", "predictionAvailable", "fallbackReason")) {
                if (source.containsKey(key)) card.put(key, source.get(key));
            }
            artifacts.add(card);
        }
        Object team = run.toolOutputs().get("team_context");
        if (team instanceof Map<?, ?> source) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "team");
            card.put("teamName", source.get("teamName"));
            card.put("teamInfo", source.get("teamInfo"));
            card.put("recentMatchCount", source.get("recentMatches") instanceof List<?> list ? list.size() : 0);
            card.put("comparisonTeamName", source.containsKey("comparisonTeamName")
                    ? source.get("comparisonTeamName") : source.get("awayTeamName"));
            card.put("comparisonTeamInfo", source.get("comparisonTeamInfo"));
            card.put("comparisonRecentMatchCount", source.get("awayRecentMatches") instanceof List<?> list ? list.size() : 0);
            card.put("status", source.get("status"));
            artifacts.add(card);
        }
        Object squad = run.toolOutputs().get("squad_context");
        if (squad instanceof Map<?, ?> source) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "squad");
            card.put("teamName", source.get("teamName"));
            card.put("leagueName", source.get("leagueName"));
            card.put("status", source.get("status"));
            card.put("message", source.get("message"));
            card.put("source", source.get("source"));
            card.put("playerCount", source.get("playerCount"));
            card.put("players", source.get("players"));
            card.put("lastSyncedAt", source.get("lastSyncedAt"));
            artifacts.add(card);
        }
        Object schedule = run.toolOutputs().get("crawler_summary");
        if (schedule instanceof Map<?, ?> source) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "schedule");
            card.put("leagueName", source.get("leagueName"));
            card.put("total", source.get("total"));
            card.put("returned", source.get("returned"));
            card.put("truncated", source.get("truncated"));
            card.put("windowType", source.get("windowType"));
            card.put("timeZone", source.get("timeZone"));
            card.put("windowStart", source.get("windowStart"));
            card.put("windowEnd", source.get("windowEnd"));
            card.put("predictionSummary", source.get("predictionSummary"));
            card.put("matches", source.get("matches"));
            card.put("selectionMode", source.get("selectionMode"));
            card.put("selectedMatch", source.get("selectedMatch"));
            artifacts.add(card);
        }
        return artifacts;
    }

    private List<Map<String, Object>> buildActions(AgentToolRun run) {
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> context = run.context();
        Object fixtureId = context.get("fixtureId");
        if (fixtureId != null && !String.valueOf(fixtureId).isBlank()) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "open-match");
            action.put("label", "打开比赛");
            action.put("fixtureId", fixtureId);
            action.put("homeTeamName", context.get("homeTeamName"));
            action.put("awayTeamName", context.get("awayTeamName"));
            action.put("leagueName", context.get("leagueName"));
            action.put("matchTime", context.get("matchTime"));
            actions.add(action);
        }
        Object teamId = context.get("teamId");
        if (teamId == null || String.valueOf(teamId).isBlank()) {
            Object teamOutput = run.toolOutputs().get("team_context");
            if (teamOutput instanceof Map<?, ?> source) teamId = source.get("teamId");
        }
        if (teamId != null && !String.valueOf(teamId).isBlank()) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "open-team");
            action.put("label", "查看球队阵容");
            action.put("teamId", teamId);
            actions.add(action);
        }
        Object candidates = context.get("teamCandidates");
        if (candidates instanceof List<?> list) {
            for (Object candidate : list.stream().limit(4).toList()) {
                if (candidate == null || String.valueOf(candidate).isBlank()) continue;
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("type", "select-team");
                action.put("label", "选择 " + candidate);
                action.put("teamName", candidate);
                action.put("prompt", "请查询 " + candidate + " 的相关资料");
                actions.add(action);
            }
        }
        return actions;
    }

    @SuppressWarnings("unchecked")
    private String normalizeAnswer(String text) {
        String trimmed = text == null ? "" : text.trim();
        String json = AgentJsonTools.extractJson(trimmed);
        if (!AgentJsonTools.looksLikeJson(json)) return trimmed;
        try {
            Object parsedValue = objectMapper.readValue(json, Object.class);
            if (parsedValue instanceof Map<?, ?> parsed) {
                for (String key : List.of("reply", "answer", "message", "content", "text", "summary")) {
                    Object value = parsed.get(key);
                    if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
                }
            }
        } catch (JsonProcessingException ignored) {
            // 保留原文，避免模型返回的非标准 JSON 被丢弃。
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private String extractRawContent(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) return null;
        Object choices = rawMap.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) return null;
        Object message = firstMap.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) return null;
        Object content = messageMap.get("content");
        return content == null ? null : String.valueOf(content);
    }

    private String extractRawReasoning(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) return null;
        Object choices = rawMap.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) return null;
        Object message = firstMap.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) return null;
        Object reasoning = messageMap.get("reasoning_content");
        if (reasoning == null) reasoning = messageMap.get("reasoning");
        return reasoning == null ? null : String.valueOf(reasoning);
    }

    /**
     * Keep the optional thinking UI useful without persisting an unbounded raw
     * chain of thought in conversation history.  This is intentionally framed
     * as a provider-generated summary, not as a source of facts.
     */
    private String summarizeReasoning(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isBlank()) return null;
        int max = 800;
        return text.length() <= max ? text : text.substring(0, max) + "\n[推理摘要已截断]";
    }

    private String resolveSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
    }

    private String latestDataTimestamp(List<Map<String, Object>> evidence) {
        if (evidence == null || evidence.isEmpty()) return null;
        String latest = null;
        Instant latestInstant = null;
        for (Map<String, Object> item : evidence) {
            Object value = item.get("sourceUpdatedAt");
            if (value == null || String.valueOf(value).isBlank()) continue;
            String candidate = String.valueOf(value);
            try {
                Instant parsed = Instant.parse(candidate);
                if (latestInstant == null || parsed.isAfter(latestInstant)) {
                    latestInstant = parsed;
                    latest = candidate;
                }
            } catch (Exception ignored) {
                if (latest == null || candidate.compareTo(latest) > 0) latest = candidate;
            }
        }
        return latest == null ? Instant.now().toString() : latest;
    }

    private boolean cacheableToolRun(AgentToolRun run) {
        if (run == null || run.evidence() == null || run.evidence().isEmpty()) return true;
        return run.evidence().stream().allMatch(item -> "agent_summary".equals(item.get("tool")));
    }

    private boolean isCacheableModel(Map<String, Object> ai, String answer) {
        if (ai == null || !"ok".equalsIgnoreCase(String.valueOf(ai.getOrDefault("status", "")))) return false;
        return answer != null && !answer.isBlank() && !answer.contains("暂时无法生成回复");
    }

    private String buildCacheKey(String sessionId, String userMessage, List<AgentMessage> history, Map<String, Object> context, String provider, String model, Double temperature, Integer maxTokens, Boolean thinking, String intent) {
        String historySig = Integer.toHexString(String.valueOf(history).hashCode());
        String contextSig = context == null ? "0" : Integer.toHexString(context.toString().hashCode());
        return String.join(":",
                "session-v3", sessionId,
                "policy", modelRouter.policyFingerprint(),
                "intent", intent == null ? "general" : intent,
                "thinking", String.valueOf(thinking == null || thinking),
                "history", historySig,
                "context", contextSig,
                "provider", String.valueOf(provider),
                "model", String.valueOf(model),
                "temperature", String.valueOf(temperature),
                "maxTokens", String.valueOf(maxTokens),
                "msg", Integer.toHexString(userMessage == null ? 0 : userMessage.hashCode())
        );
    }
}
