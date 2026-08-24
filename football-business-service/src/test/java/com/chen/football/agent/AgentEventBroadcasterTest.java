package com.chen.football.agent;

import com.chen.football.agent.dto.AgentChatRequest;
import com.chen.football.agent.dto.AgentMessage;
import com.chen.football.agent.service.AgentEventBroadcaster;
import com.chen.football.agent.service.AgentCancellationRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.FutureTask;

class AgentEventBroadcasterTest {

    @Test
    void streamCanOnlyBeCancelledByItsOwner() {
        AgentEventBroadcaster broadcaster = new AgentEventBroadcaster();
        String streamId = broadcaster.startStream(new AgentChatRequest("hello"), 7L);

        assertTrue(broadcaster.ownsStream(streamId, 7L));
        assertFalse(broadcaster.ownsStream(streamId, 8L));
        broadcaster.emit(streamId, AgentMessage.assistant("ok"));
        assertEquals(1, broadcaster.drain(streamId).size());
        assertFalse(broadcaster.ownsStream(streamId, 7L));
    }

    @Test
    void cancellationRegistryCanWinRaceBeforeWorkerRegistration() {
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        registry.cancel("early");
        FutureTask<Void> task = new FutureTask<>(() -> null);
        registry.register("early", task);
        assertTrue(task.isCancelled());
    }
}
