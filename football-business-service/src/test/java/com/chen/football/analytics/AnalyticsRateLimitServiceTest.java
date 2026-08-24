package com.chen.football.analytics;

import com.chen.football.analytics.service.AnalyticsRateLimitService;
import com.chen.football.common.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsRateLimitServiceTest {
    private final AnalyticsRateLimitService service = new AnalyticsRateLimitService();

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void anonymousEventsAreBoundedPerIp() {
        for (int i = 0; i < 60; i++) {
            assertTrue(service.allow("127.0.0.1"));
        }
        assertFalse(service.allow("127.0.0.1"));
        assertTrue(service.allow("127.0.0.2"));
    }

    @Test
    void authenticatedEventsUseUserIdentity() {
        UserContext.set(42L, "tester", "USER");
        for (int i = 0; i < 120; i++) {
            assertTrue(service.allow("127.0.0.1"));
        }
        assertFalse(service.allow("127.0.0.1"));
        // A different account on the same IP gets an independent bucket.
        UserContext.set(43L, "tester2", "USER");
        assertTrue(service.allow("127.0.0.1"));
    }
}
