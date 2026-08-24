package com.chen.football.agent.service;

import com.chen.football.agent.dto.AgentChatRequest;
import com.chen.football.agent.dto.AgentChatResponse;
import com.chen.football.agent.dto.AgentMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import jakarta.annotation.PreDestroy;

/** SSE transport for the unified Agent runtime. */
@Service
public class AgentStreamService {

    private final FootballChatAgentService chatAgentService;
    private final AgentEventBroadcaster broadcaster;
    private final AgentCancellationRegistry cancellationRegistry;
    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            r -> {
                Thread thread = new Thread(r, "agent-stream-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread thread = new Thread(r, "agent-stream-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public AgentStreamService(FootballChatAgentService chatAgentService,
                              AgentEventBroadcaster broadcaster,
                              AgentCancellationRegistry cancellationRegistry) {
        this.chatAgentService = chatAgentService;
        this.broadcaster = broadcaster;
        this.cancellationRegistry = cancellationRegistry;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }

    public Flux<Map<String, Object>> streamChat(AgentChatRequest request) {
        return streamChat(request, null);
    }

    public Flux<Map<String, Object>> streamChat(AgentChatRequest request, Long userId) {
        String streamId = broadcaster.startStream(request, userId);
        Instant start = Instant.now();

        return Flux.create(sink -> {
            sink.next(metaEvent("stream_start", Map.of("streamId", streamId, "mode", "provider-sse-or-buffered")));
            sink.onCancel(() -> cancellationRegistry.cancel(streamId));
            sink.onDispose(() -> cancellationRegistry.cancel(streamId));
            ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                    () -> { if (!sink.isCancelled()) sink.next(metaEvent("heartbeat", Map.of("streamId", streamId))); },
                    10, 10, TimeUnit.SECONDS);

            Future<?> future;
            try {
                future = executor.submit(() -> {
                try {
                    if (cancellationRegistry.isCancelled(streamId)) return;
                    AgentChatResponse response = chatAgentService.chat(request, userId, event -> {
                        if (!cancellationRegistry.isCancelled(streamId)) sink.next(event);
                    });
                    if (cancellationRegistry.isCancelled(streamId)) return;
                    String answer = response.answer() == null ? "" : response.answer();

                    sink.next(metaEvent("intent", Map.of(
                            "intent", response.metadata().getOrDefault("intent", "general"),
                            "confidence", response.metadata().getOrDefault("intentConfidence", 0.0)
                    )));

                    Object reasoning = response.metadata().get("reasoning");
                    if (reasoning != null && !String.valueOf(reasoning).isBlank()) {
                        sink.next(metaEvent("reasoning", Map.of(
                                "content", String.valueOf(reasoning),
                                "label", "模型推理摘要"
                        )));
                    }

                    if (!Boolean.TRUE.equals(response.metadata().get("streamed"))) {
                        int chunkSize = Math.max(10, Math.min(Math.max(1, answer.length() / 20), 80));
                        for (int i = 0; i < answer.length(); i += chunkSize) {
                            if (cancellationRegistry.isCancelled(streamId)) return;
                            int end = Math.min(i + chunkSize, answer.length());
                            String chunk = answer.substring(i, end);
                            broadcaster.emit(streamId, AgentMessage.assistant(chunk));
                            sink.next(metaEvent("chunk", Map.of("content", chunk, "offset", i)));
                        }
                    }

                    Map<String, Object> end = new LinkedHashMap<>();
                    end.put("streamId", streamId);
                    end.put("streamMode", Boolean.TRUE.equals(response.metadata().get("streamed"))
                            ? "provider-sse" : "buffered-with-progress");
                    end.put("latencyMs", Duration.between(start, Instant.now()).toMillis());
                    end.put("requestId", response.requestId());
                    end.put("status", response.status());
                    end.put("totalChars", answer.length());
                    end.put("provider", response.metadata().getOrDefault("provider", "unknown"));
                    end.put("model", response.metadata().getOrDefault("model", "unknown"));
                    end.put("usage", response.metadata().getOrDefault("usage", Map.of()));
                    end.put("fallbackFrom", response.metadata().getOrDefault("fallbackFrom", ""));
                    end.put("evidenceSources", response.metadata().getOrDefault("evidenceSources", List.of()));
                    end.put("evidence", response.metadata().getOrDefault("evidence", List.of()));
                    end.put("facts", response.metadata().getOrDefault("facts", List.of()));
                    end.put("unknowns", response.metadata().getOrDefault("unknowns", List.of()));
                    end.put("answerValidation", response.metadata().getOrDefault("answerValidation", Map.of()));
                    end.put("dataQuality", response.metadata().getOrDefault("dataQuality", Map.of()));
                    end.put("artifacts", response.metadata().getOrDefault("artifacts", List.of()));
                    end.put("actions", response.metadata().getOrDefault("actions", List.of()));
                    end.put("toolSteps", response.metadata().getOrDefault("toolSteps", List.of()));
                    end.put("toolLatencies", response.metadata().getOrDefault("toolLatencies", Map.of()));
                    end.put("dataFreshness", response.metadata().getOrDefault("dataFreshness", Instant.now().toString()));
                    sink.next(metaEvent("stream_end", end));
                } catch (Exception e) {
                    if (!cancellationRegistry.isCancelled(streamId)) {
                        sink.next(metaEvent("error", Map.of("message", e.getMessage() == null ? "Agent 处理失败" : e.getMessage(), "type", e.getClass().getSimpleName())));
                    }
                } finally {
                    heartbeat.cancel(true);
                    broadcaster.drain(streamId);
                    cancellationRegistry.remove(streamId);
                    if (!sink.isCancelled()) sink.complete();
                }
                });
            } catch (RejectedExecutionException ex) {
                heartbeat.cancel(true);
                sink.next(metaEvent("error", Map.of("message", "Agent 当前请求较多，请稍后重试", "type", "BUSY")));
                broadcaster.drain(streamId);
                sink.complete();
                return;
            }
            cancellationRegistry.register(streamId, future);
        });
    }

    private Map<String, Object> metaEvent(String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("timestamp", Instant.now().toString());
        if (payload != null) event.putAll(payload);
        return event;
    }
}
