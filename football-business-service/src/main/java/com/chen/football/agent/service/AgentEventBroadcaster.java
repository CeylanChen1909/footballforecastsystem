package com.chen.football.agent.service;

import com.chen.football.agent.dto.AgentChatRequest;
import com.chen.football.agent.dto.AgentMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentEventBroadcaster {

    private final Map<String, StreamState> liveBuffers = new ConcurrentHashMap<>();

    public String startStream(AgentChatRequest request) {
        return startStream(request, null);
    }

    public String startStream(AgentChatRequest request, Long ownerId) {
        String streamId = UUID.randomUUID().toString();
        liveBuffers.put(streamId, new StreamState(ownerId, new ArrayList<>()));
        return streamId;
    }

    public boolean ownsStream(String streamId, Long ownerId) {
        StreamState state = streamId == null ? null : liveBuffers.get(streamId);
        return state != null && ownerId != null && ownerId.equals(state.ownerId());
    }

    public void emit(String streamId, AgentMessage message) {
        if (streamId == null || message == null) {
            return;
        }
        StreamState state = liveBuffers.get(streamId);
        if (state != null) {
            synchronized (state.messages()) {
                state.messages().add(message);
            }
        }
    }

    public List<AgentMessage> drain(String streamId) {
        StreamState state = liveBuffers.remove(streamId);
        if (state == null) return List.of();
        synchronized (state.messages()) {
            return new ArrayList<>(state.messages());
        }
    }

    public Map<String, Object> status(String streamId) {
        StreamState state = liveBuffers.get(streamId);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("streamId", streamId);
        status.put("active", state != null);
        int buffered = 0;
        if (state != null) {
            synchronized (state.messages()) { buffered = state.messages().size(); }
        }
        status.put("bufferedMessages", buffered);
        return status;
    }

    private record StreamState(Long ownerId, List<AgentMessage> messages) { }
}
