package com.chen.football.agent;

import com.chen.football.agent.service.AgentConcurrencyGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConcurrencyGuardTest {
    @Test
    void limitsOneUserToTwoConcurrentRequests() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard();
        assertTrue(guard.tryAcquire(1L));
        assertTrue(guard.tryAcquire(1L));
        assertFalse(guard.tryAcquire(1L));
        guard.release(1L);
        assertTrue(guard.tryAcquire(1L));
        guard.release(1L);
        guard.release(1L);
        guard.release(1L);
    }
}
