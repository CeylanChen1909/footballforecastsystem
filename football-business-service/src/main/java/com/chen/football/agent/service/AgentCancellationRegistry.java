package com.chen.football.agent.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;

/** Tracks active SSE requests so a client disconnect can stop the worker. */
@Component
public class AgentCancellationRegistry {
    private final ConcurrentHashMap<String, State> active = new ConcurrentHashMap<>();
    private final Set<String> cancelledBeforeRegister = ConcurrentHashMap.newKeySet();

    public void register(String streamId, Future<?> future) {
        if (streamId == null || future == null) return;
        if (cancelledBeforeRegister.remove(streamId)) {
            future.cancel(true);
            return;
        }
        active.put(streamId, new State(future));
    }

    public boolean isCancelled(String streamId) {
        State state = active.get(streamId);
        return state != null && state.cancelled.get();
    }

    public void cancel(String streamId) {
        State state = active.get(streamId);
        if (state == null) {
            if (streamId != null) cancelledBeforeRegister.add(streamId);
            return;
        }
        state.cancelled.set(true);
        state.future.cancel(true);
    }

    public void remove(String streamId) {
        if (streamId != null) {
            active.remove(streamId);
            cancelledBeforeRegister.remove(streamId);
        }
    }

    private static final class State {
        private final Future<?> future;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private State(Future<?> future) { this.future = future; }
    }
}
