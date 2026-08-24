package com.chen.football.agent.service;

import com.chen.football.agent.dto.AgentAnalysisRequest;
import com.chen.football.agent.dto.AgentAnalysisResponse;
import com.chen.football.agent.tool.AgentTool;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.UUID;

@Service
public class FootballAgentService {

    private static final int MAX_TOOL_STEPS = 6;
    private static final long TOOL_TIMEOUT_MS = 10_000;

    private final AgentModelRouter modelRouter;
    private final Map<String, AgentTool> toolsByName;
    private final AgentIntentClassifier intentClassifier;
    private final AgentResponseCacheService responseCacheService;
    private final AgentToolCircuitBreaker circuitBreaker;
    private final AgentResultParser resultParser;
    private final AgentTeamResolver teamResolver;
    private final AgentAnswerValidator answerValidator;
    private final ExecutorService toolExecutor = new ThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            r -> {
                Thread thread = new Thread(r, "agent-tool-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public FootballAgentService(AgentModelRouter modelRouter,
                                List<AgentTool> tools,
                                AgentIntentClassifier intentClassifier,
                                AgentResponseCacheService responseCacheService,
                                AgentToolCircuitBreaker circuitBreaker,
                                AgentResultParser resultParser,
                                AgentTeamResolver teamResolver,
                                AgentAnswerValidator answerValidator) {
        this.modelRouter = modelRouter;
        this.intentClassifier = intentClassifier;
        this.responseCacheService = responseCacheService;
        this.circuitBreaker = circuitBreaker;
        this.resultParser = resultParser;
        this.teamResolver = teamResolver;
        this.answerValidator = answerValidator;
        this.toolsByName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            this.toolsByName.put(tool.name(), tool);
        }
    }

    public AgentAnalysisResponse analyze(AgentAnalysisRequest request) {
        String requestId = UUID.randomUUID().toString();
        Instant start = Instant.now();
        List<String> steps = new ArrayList<>();
        List<String> skippedTools = new ArrayList<>();
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> toolOutputs = new LinkedHashMap<>();
        Map<String, Long> toolLatencies = new LinkedHashMap<>();

        putIfNotNull(context, "fixtureId", request.fixtureId());
        putIfNotNull(context, "homeTeamId", request.homeTeamId());
        putIfNotNull(context, "awayTeamId", request.awayTeamId());
        putIfNotNull(context, "homeTeamName", request.homeTeamName());
        putIfNotNull(context, "awayTeamName", request.awayTeamName());
        putIfNotNull(context, "leagueName", request.leagueName());
        putIfNotNull(context, "teamId", request.teamId());
        putIfNotNull(context, "teamName", request.teamName());
        putIfNotNull(context, "articleId", request.articleId());
        putIfNotNull(context, "limit", request.limit());
        // 预测结论、概率和解释不再接受客户端直接注入。Agent 必须按 fixtureId
        // 从服务端预测服务重新读取，避免修改 URL/context 后伪造事实。
        putIfNotNull(context, "prompt", request.prompt());
        teamResolver.enrich(context, request.prompt());

        String intent = intentClassifier.classify(request.prompt() == null ? request.leagueName() : request.prompt()).get("intent").toString();
        String cacheKey = buildCacheKey(request, intent);
        AgentAnalysisResponse cached = responseCacheService.getAnalysis(cacheKey);
        if (cached != null) {
            Map<String, Object> cachedData = new LinkedHashMap<>(cached.data() == null ? Map.of() : cached.data());
            cachedData.put("cacheHit", true);
            cachedData.put("intent", intent);
            cachedData.put("requestId", requestId);
            return new AgentAnalysisResponse(
                    cached.agent(),
                    requestId,
                    cached.status(),
                    cached.summary(),
                    cached.steps(),
                    cachedData
            );
        }

        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                request.fixtureId() != null || intent.equals("match-analysis"), "match_context");
        applyCanonicalMatchContext(context, toolOutputs.get("match_context"));
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                request.fixtureId() != null || intent.equals("match-analysis"), "prematch_data_context");
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                request.fixtureId() != null || intent.equals("match-analysis"), "prediction");
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                intent.equals("team-analysis") || intent.equals("team-roster") || intent.equals("match-analysis"), "team_context");
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                intent.equals("team-roster") || intent.equals("team-analysis"), "squad_context");
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies, intent.equals("news-analysis"), "news_context");
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                intent.equals("general-analysis") || intent.equals("schedule") || request.leagueName() != null, "crawler_summary");
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies, true, "agent_summary");

        Map<String, Object> heuristic = buildHeuristicSummary(context, toolOutputs, steps, skippedTools, intent, toolLatencies);
        String userPrompt = buildPrompt(request, context, toolOutputs, steps, heuristic);
        Map<String, Object> ai = modelRouter.chatJson(userPrompt, request.provider(), request.model());
        AgentStructuredResult structured = resultParser.parse(ai);

        Map<String, Object> data = new LinkedHashMap<>();
        AgentToolRun toolRun = new AgentToolRun(intent, 0.7, context, toolOutputs, steps, skippedTools, toolLatencies,
                buildEvidence(context, toolOutputs, toolLatencies));
        AgentAnswerValidator.Validation validation = answerValidator.validate(structured.summary(), toolRun);
        data.put("intent", intent);
        data.put("context", context);
        data.put("toolOutputs", toolOutputs);
        data.put("toolLatencies", toolLatencies);
        data.put("heuristic", heuristic);
        data.put("ai", ai);
        data.put("structured", Map.of(
                "summary", validation.answer(),
                "confidence", structured.confidence(),
                "keyPoints", structured.keyPoints(),
                "risks", structured.risks(),
                "recommendation", structured.recommendation(),
                "followUpQuestions", structured.followUpQuestions(),
                "parsed", structured.parsed()
        ));
        data.put("steps", steps);
        data.put("skippedTools", skippedTools);
        data.put("facts", AgentEvidenceComposer.facts(toolRun));
        data.put("unknowns", AgentEvidenceComposer.unknowns(toolRun));
        data.put("answerValidation", validation.metadata());
        data.put("dataQuality", AgentEvidenceComposer.quality(toolRun));
        data.put("circuitBreaker", circuitBreaker.snapshot());
        // Never echo the legacy client-supplied prediction fields back into
        // the analysis payload. They are intentionally ignored as facts and
        // returning them only makes the response look authoritative.
        data.put("request", requestSnapshot(request));
        data.put("latencyMs", Duration.between(start, Instant.now()).toMillis());

        AgentAnalysisResponse response = new AgentAnalysisResponse(
                "football-agent",
                requestId,
                String.valueOf(ai.getOrDefault("status", "ok")),
                validation.answer(),
                steps,
                data
        );
        if (isCacheableAnalysis(ai, structured)) {
            responseCacheService.putAnalysis(cacheKey, response);
        }
        return response;
    }

    public List<String> toolNames() {
        return new ArrayList<>(toolsByName.keySet());
    }

    public List<Map<String, Object>> toolCapabilities() {
        Map<String, String> descriptions = Map.of(
                "agent_summary", "读取 Agent 与主数据源运行状态",
                "crawler_summary", "查询今天及未来 7 天赛程",
                "match_context", "读取比赛、近期赛果和交锋上下文",
                "prematch_data_context", "读取伤停、首发状态与历史 xG 赛前快照",
                "prediction", "读取服务端统一预测结果",
                "team_context", "读取球队资料和近期比赛",
                "squad_context", "读取球队注册阵容与球员位置",
                "news_context", "读取赛事资讯摘要"
        );
        return toolsByName.keySet().stream().map(name -> {
            Map<String, Object> capability = new LinkedHashMap<>();
            capability.put("name", name);
            capability.put("description", descriptions.getOrDefault(name, "足球业务数据工具"));
            capability.put("statusContract", List.of("AVAILABLE", "PARTIAL", "EMPTY", "STALE", "NOT_CONFIGURED", "QUOTA_LIMITED", "REQUEST_FAILED", "MISSING_INPUT"));
            return capability;
        }).toList();
    }

    /**
     * Runs the same guarded tool plan used by structured analysis for the
     * conversational Agent path.  The callback is deliberately a generic map
     * so the SSE layer can forward typed events without coupling tools to
     * transport concerns.
     */
    public AgentToolRun executeChatTools(com.chen.football.agent.dto.AgentChatRequest request,
                                         Long userId,
                                         Consumer<Map<String, Object>> eventSink) {
        String message = request == null || request.message() == null ? "" : request.message().trim();
        Map<String, Object> classified = intentClassifier.classify(message);
        String intent = String.valueOf(classified.getOrDefault("intent", "general"));
        double confidence = toDouble(classified.get("confidence"), 0.0);

        Map<String, Object> context = new LinkedHashMap<>();
        if (request != null && request.context() != null) {
            Set<String> allowedContextKeys = Set.of(
                    "fixtureId", "homeTeamId", "awayTeamId", "homeTeamName", "awayTeamName",
                    "homeName", "awayName",
                    "leagueName",
                    "teamId", "teamName", "comparisonTeamId", "comparisonTeamName", "articleId",
                    "limit", "date", "from", "to"
            );
            request.context().forEach((key, value) -> {
                if (key != null && value != null && allowedContextKeys.contains(String.valueOf(key))) {
                    context.put(String.valueOf(key), value);
                }
            });
        }
        if (!context.containsKey("homeTeamName") && context.get("homeName") != null) context.put("homeTeamName", context.get("homeName"));
        if (!context.containsKey("awayTeamName") && context.get("awayName") != null) context.put("awayTeamName", context.get("awayName"));
        context.remove("homeName");
        context.remove("awayName");
        // context 来自浏览器 URL/状态，只允许作为查询线索；任何预测结论、
        // 工具输出或证据字段都不会进入 Prompt 或 PredictionTool。
        context.put("message", message);
        if (userId != null) context.put("userId", userId);
        if (!context.containsKey("limit")) context.put("limit", 8);
        List<String> resolvedTeams = teamResolver.enrich(context, message);
        boolean randomSelection = isRandomScheduleRequest(message)
                && !hasAny(context, "fixtureId", "homeTeamName", "awayTeamName");
        context.put("randomSelection", randomSelection);

        // A team tool is only useful after a concrete team has been identified.
        // Do not call it for a generic question, otherwise the model can mistake
        // an empty query result for an empty database.
        boolean hasMatch = hasMatchContext(context);
        boolean hasTeam = hasAny(context, "teamId", "teamName") || !resolvedTeams.isEmpty();
        boolean hasArticle = hasAny(context, "articleId");
        boolean matchIntent = hasMatch || ("match-analysis".equals(intent) && hasMatch) || "prediction".equals(intent);
        boolean prematchDataIntent = isPrematchDataRequest(message);
        boolean rosterIntent = "team-roster".equals(intent) || isRosterRequest(message);
        boolean teamIntent = hasTeam && ("team-analysis".equals(intent) || rosterIntent || hasMatch || resolvedTeams.size() >= 2);
        boolean newsIntent = hasArticle || "news-analysis".equals(intent);
        boolean scheduleIntent = isScheduleRequest(message) || "schedule".equals(intent) || "match-analysis".equals(intent);

        Map<String, Object> toolOutputs = new LinkedHashMap<>();
        List<String> steps = new ArrayList<>();
        List<String> skippedTools = new ArrayList<>();
        Map<String, Long> toolLatencies = new LinkedHashMap<>();

        // Random requests must first select a real row from the crawler result;
        // the selected fixture is then fed back into all subsequent tools.
        if (randomSelection) {
            runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                    true, "crawler_summary", intent, eventSink);
            applySelectedMatchContext(context, toolOutputs.get("crawler_summary"));
            hasMatch = hasMatchContext(context);
            hasTeam = hasAny(context, "teamId", "teamName");
            matchIntent = hasMatch;
            teamIntent = hasTeam;
        }

        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                matchIntent, "match_context", intent, eventSink);
        applyCanonicalMatchContext(context, toolOutputs.get("match_context"));
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                hasMatch && (matchIntent || prematchDataIntent),
                "prematch_data_context", intent, eventSink);
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                hasAny(context, "fixtureId") && matchIntent,
                "prediction", intent, eventSink);
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                teamIntent, "team_context", intent, eventSink);
        // A fixture-based question such as “这场比赛的首发” only gets its
        // team names after match_context canonicalizes the fixture.  Use those
        // names as a safe fallback instead of silently skipping the roster.
        boolean rosterTeamReady = rosterIntent && (hasTeam || hasAny(context, "teamName", "homeTeamName"));
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                rosterTeamReady, "squad_context", intent, eventSink);
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                newsIntent, "news_context", intent, eventSink);
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                scheduleIntent && !hasMatch && !randomSelection, "crawler_summary", intent, eventSink);
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies,
                true, "agent_summary", intent, eventSink);

        List<Map<String, Object>> evidence = buildEvidence(context, toolOutputs, toolLatencies);
        return new AgentToolRun(intent, confidence, context, toolOutputs, steps, skippedTools, toolLatencies, evidence);
    }

    @PreDestroy
    void shutdownToolExecutor() {
        toolExecutor.shutdownNow();
    }

    private void runToolWithCircuitBreaker(Map<String, Object> context,
                                           Map<String, Object> toolOutputs,
                                           List<String> steps,
                                           List<String> skippedTools,
                                           Map<String, Long> toolLatencies,
                                           boolean enabled,
                                           String toolName) {
        runToolWithCircuitBreaker(context, toolOutputs, steps, skippedTools, toolLatencies, enabled, toolName, null, null);
    }

    private void runToolWithCircuitBreaker(Map<String, Object> context,
                                           Map<String, Object> toolOutputs,
                                           List<String> steps,
                                           List<String> skippedTools,
                                           Map<String, Long> toolLatencies,
                                           boolean enabled,
                                           String toolName,
                                           String intent,
                                           Consumer<Map<String, Object>> eventSink) {
        if (!enabled || steps.size() >= MAX_TOOL_STEPS) {
            return;
        }
        AgentTool tool = toolsByName.get(toolName);
        if (tool == null) {
            return;
        }
        emitToolEvent(eventSink, "tool_start", Map.of("tool", toolName, "intent", intent == null ? "general" : intent));
        if (!circuitBreaker.allow(toolName)) {
            skippedTools.add(toolName);
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("skipped", true);
            skipped.put("reason", "circuit-open");
            Map<String, Object> normalizedSkipped = AgentToolResult.normalize(toolName, skipped);
            toolOutputs.put(toolName, normalizedSkipped);
            context.put(toolName, normalizedSkipped);
            emitToolEvent(eventSink, "tool_result", Map.of("tool", toolName, "status", "skipped", "reason", "circuit-open"));
            return;
        }
        Instant toolStart = Instant.now();
        try {
            Map<String, Object> result = executeWithTimeout(tool, context);
            Map<String, Object> normalized = AgentToolResult.normalize(toolName, result);
            toolOutputs.put(toolName, normalized);
            context.put(toolName, normalized);
            circuitBreaker.recordSuccess(toolName);
        } catch (Exception e) {
            circuitBreaker.recordFailure(toolName);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getClass().getSimpleName());
            error.put("message", e.getMessage());
            Map<String, Object> normalizedError = AgentToolResult.normalize(toolName, error);
            toolOutputs.put(toolName, normalizedError);
            context.put(toolName, normalizedError);
        }
        long latency = Duration.between(toolStart, Instant.now()).toMillis();
        toolLatencies.put(toolName, latency);
        steps.add(toolName);
        Map<String, Object> result = toolOutputs.get(toolName) instanceof Map<?, ?> raw
                ? castMap(raw) : Map.of();
        boolean failed = result != null && result.containsKey("error");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tool", toolName);
        event.put("status", failed ? "error" : "ok");
        event.put("dataStatus", result.getOrDefault("status", failed ? "REQUEST_FAILED" : "AVAILABLE"));
        event.put("latencyMs", latency);
        event.put("summary", summarizeToolResult(result));
        if (failed) event.put("message", result.get("message"));
        emitToolEvent(eventSink, "tool_result", event);
    }

    private void emitToolEvent(Consumer<Map<String, Object>> eventSink, String type, Map<String, Object> payload) {
        if (eventSink == null) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("timestamp", Instant.now().toString());
        if (payload != null) event.putAll(payload);
        try { eventSink.accept(event); } catch (RuntimeException ignored) { }
    }

    private boolean hasMatchContext(Map<String, Object> context) {
        if (hasAny(context, "fixtureId")) return true;
        return hasAny(context, "homeTeamName") && hasAny(context, "awayTeamName");
    }

    private boolean isScheduleRequest(String message) {
        return message != null && message.matches("(?s).*(今天|明天|赛程|比赛|fixture|match).*" );
    }

    private boolean isRandomScheduleRequest(String message) {
        return message != null
                && message.matches("(?s).*(随机|随便|random).*" )
                && isScheduleRequest(message);
    }

    private boolean isRosterRequest(String message) {
        return message != null && message.matches("(?s).*(球员|队员|名单|阵容|首发|替补|门将|后卫|中场|前锋|squad|roster|lineup|players).*");
    }

    @SuppressWarnings("unchecked")
    private void applySelectedMatchContext(Map<String, Object> context, Object output) {
        if (!(output instanceof Map<?, ?> raw)) return;
        Object selectedValue = raw.get("selectedMatch");
        if (!(selectedValue instanceof Map<?, ?> selected)) return;
        selected.forEach((key, value) -> {
            if (key != null && value != null) context.put(String.valueOf(key), value);
        });
        putIfPresent(context, "fixtureId", selected.get("fixtureId"));
        putIfPresent(context, "homeTeamId", selected.get("homeTeamId"));
        putIfPresent(context, "homeTeamName", selected.get("homeTeamName"));
        putIfPresent(context, "homeTeamLogo", selected.get("homeTeamLogo"));
        putIfPresent(context, "awayTeamId", selected.get("awayTeamId"));
        putIfPresent(context, "awayTeamName", selected.get("awayTeamName"));
        putIfPresent(context, "awayTeamLogo", selected.get("awayTeamLogo"));
        putIfPresent(context, "leagueName", selected.get("leagueName"));
        putIfPresent(context, "matchTime", selected.get("matchTime"));
        if (!hasAny(context, "teamName")) context.put("teamName", selected.get("homeTeamName"));
        if (!hasAny(context, "comparisonTeamName")) context.put("comparisonTeamName", selected.get("awayTeamName"));
        context.put("randomSelectionApplied", true);
    }

    @SuppressWarnings("unchecked")
    private void applyCanonicalMatchContext(Map<String, Object> context, Object output) {
        if (!(output instanceof Map<?, ?> raw)) return;
        Object candidateValue = raw.get("canonicalMatch");
        if (!(candidateValue instanceof Map<?, ?> candidate)) return;
        candidate.forEach((key, value) -> {
            if (key != null && value != null) context.put(String.valueOf(key), value);
        });
        context.put("serverContextVerified", true);
    }

    private void putIfPresent(Map<String, Object> context, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) context.put(key, value);
    }

    private Map<String, Object> summarizeToolResult(Map<String, Object> result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (result == null) return summary;
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List<?> list) summary.put(entry.getKey() + "Count", list.size());
            else if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) summary.put(entry.getKey(), value);
        }
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private List<Map<String, Object>> buildEvidence(Map<String, Object> context,
                                                     Map<String, Object> toolOutputs,
                                                     Map<String, Long> toolLatencies) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        String observedAt = Instant.now().toString();
        Map<String, String> sourceLabels = Map.of(
                "match_context", "本地赛程数据库",
                "prediction", "赛前预测服务",
                "team_context", "球队资料数据库",
                "squad_context", "公开球队阵容源",
                "news_context", "赛事资讯数据库",
                "crawler_summary", "主爬虫赛程源",
                "agent_summary", "Agent 运行状态"
        );
        for (String tool : toolOutputs.keySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", tool);
            item.put("label", sourceLabels.getOrDefault(tool, tool));
            item.put("source", sourceLabels.getOrDefault(tool, tool));
            item.put("observedAt", observedAt);
            item.put("latencyMs", toolLatencies.getOrDefault(tool, 0L));
            item.put("recordId", context.getOrDefault("fixtureId", context.getOrDefault("teamId", context.get("articleId"))));
            Object sourceUpdatedAt = findUpdatedAt(toolOutputs.get(tool));
            if (sourceUpdatedAt != null) item.put("sourceUpdatedAt", String.valueOf(sourceUpdatedAt));
            item.put("status", evidenceStatus(toolOutputs.get(tool)));
            item.put("recordCount", recordCount(toolOutputs.get(tool)));
            evidence.add(item);
        }
        return evidence;
    }

    private String evidenceStatus(Object value) {
        if (!(value instanceof Map<?, ?> map)) return "ok";
        if (map.containsKey("error")) return "error";
        String status = map.get("status") == null ? "" : String.valueOf(map.get("status"));
        if ("MISSING_INPUT".equalsIgnoreCase(status)) return "missing-input";
        if ("EMPTY".equalsIgnoreCase(status)) return "empty";
        if ("CONFLICT".equalsIgnoreCase(status)) return "conflict";
        if ("STALE".equalsIgnoreCase(status)) return "stale";
        if ("PARTIAL".equalsIgnoreCase(status)) return "partial";
        if ("NOT_CONFIGURED".equalsIgnoreCase(status)) return "not-configured";
        if ("QUOTA_LIMITED".equalsIgnoreCase(status)) return "quota-limited";
        if ("REQUEST_FAILED".equalsIgnoreCase(status)) return "error";
        if ("INVALID".equalsIgnoreCase(status)) return "invalid";
        if ("SKIPPED".equalsIgnoreCase(status)) return "skipped";
        return "ok";
    }

    private int recordCount(Object value) {
        if (value instanceof Map<?, ?> map) {
            int count = 0;
            for (Object nested : map.values()) count += recordCount(nested);
            if (count > 0) return count;
            if (map.containsKey("article") && map.get("article") != null) return 1;
            if (map.containsKey("canonicalMatch") && map.get("canonicalMatch") != null) return 1;
            return 0;
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object ignored : iterable) count++;
            return count;
        }
        return 0;
    }

    private Object findUpdatedAt(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("updatedAt", "sourceUpdatedAt", "lastUpdatedAt")) {
                if (map.get(key) != null) return map.get(key);
            }
            for (Object nested : map.values()) {
                Object found = findUpdatedAt(nested);
                if (found != null) return found;
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) {
                Object found = findUpdatedAt(nested);
                if (found != null) return found;
            }
        } else {
            try {
                return value.getClass().getMethod("getUpdatedAt").invoke(value);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private boolean hasAny(Map<String, Object> context, String... keys) {
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return true;
        }
        return false;
    }

    private boolean isPrematchDataRequest(String message) {
        if (message == null) return false;
        return java.util.regex.Pattern.compile("(?i)伤停|伤病|缺阵|停赛|首发|预计阵容|预期进球|xg|expected\\s*goals|赛前数据")
                .matcher(message).find();
    }

    private double toDouble(Object value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private Map<String, Object> buildHeuristicSummary(Map<String, Object> context,
                                                      Map<String, Object> toolOutputs,
                                                      List<String> steps,
                                                      List<String> skippedTools,
                                                      String intent,
                                                      Map<String, Long> toolLatencies) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("intent", intent);
        summary.put("stepCount", steps.size());
        summary.put("skippedTools", skippedTools);
        summary.put("hasPrediction", toolOutputs.containsKey("prediction"));
        summary.put("hasMatchContext", toolOutputs.containsKey("match_context"));
        summary.put("hasPrematchData", toolOutputs.containsKey("prematch_data_context"));
        summary.put("hasTeamContext", toolOutputs.containsKey("team_context"));
        summary.put("hasNewsContext", toolOutputs.containsKey("news_context"));
        summary.put("hasCrawlerSummary", toolOutputs.containsKey("crawler_summary"));
        summary.put("hasAgentSummary", toolOutputs.containsKey("agent_summary"));
        summary.put("toolLatencies", toolLatencies);
        summary.put("contextKeys", new ArrayList<>(context.keySet()));
        return summary;
    }

    private String buildPrompt(AgentAnalysisRequest request,
                               Map<String, Object> context,
                               Map<String, Object> toolOutputs,
                               List<String> steps,
                               Map<String, Object> heuristic) {
        return AgentPromptFactory.buildAnalysisPrompt(request.prompt(), context, toolOutputs, steps, heuristic);
    }

    private Map<String, Object> requestSnapshot(AgentAnalysisRequest request) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (request == null) return safe;
        putIfNotNull(safe, "fixtureId", request.fixtureId());
        putIfNotNull(safe, "homeTeamId", request.homeTeamId());
        putIfNotNull(safe, "awayTeamId", request.awayTeamId());
        putIfNotNull(safe, "homeTeamName", request.homeTeamName());
        putIfNotNull(safe, "awayTeamName", request.awayTeamName());
        putIfNotNull(safe, "leagueName", request.leagueName());
        putIfNotNull(safe, "teamId", request.teamId());
        putIfNotNull(safe, "teamName", request.teamName());
        putIfNotNull(safe, "articleId", request.articleId());
        putIfNotNull(safe, "limit", request.limit());
        putIfNotNull(safe, "provider", request.provider());
        putIfNotNull(safe, "model", request.model());
        return safe;
    }

    private boolean isCacheableAnalysis(Map<String, Object> ai, AgentStructuredResult structured) {
        if (ai == null || structured == null) return false;
        if (!"ok".equalsIgnoreCase(String.valueOf(ai.getOrDefault("status", "")))) return false;
        return structured.summary() != null && !structured.summary().isBlank();
    }

    private String buildCacheKey(AgentAnalysisRequest request, String intent) {
        return String.join(":",
                "agent-analysis",
                intent == null ? "general" : intent,
                String.valueOf(request.fixtureId()),
                String.valueOf(request.homeTeamId()),
                String.valueOf(request.awayTeamId()),
                String.valueOf(request.teamId()),
                String.valueOf(request.articleId()),
                String.valueOf(request.limit()),
                String.valueOf(request.provider()),
                String.valueOf(request.model()),
                "policy",
                modelRouter.policyFingerprint(),
                String.valueOf(request.predictionModelVersion()),
                Integer.toHexString((request.prompt() == null ? "" : request.prompt()).hashCode())
        );
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private Map<String, Object> executeWithTimeout(AgentTool tool, Map<String, Object> context) {
        java.util.concurrent.Future<Map<String, Object>> future = toolExecutor.submit(() -> tool.execute(context));
        try {
            return future.get(TOOL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            Map<String, Object> timeout = new LinkedHashMap<>();
            timeout.put("error", "TimeoutException");
            timeout.put("message", "工具执行超时 (" + TOOL_TIMEOUT_MS + "ms)");
            return timeout;
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("工具执行被中断", e);
        }
    }
}
