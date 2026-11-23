package com.chen.football.common.client;

import com.chen.football.common.client.ApiFootballClient.BudgetBucket;
import com.chen.football.common.config.ApiFootballProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 budget split: scheduled enrichment (60/day) and on-demand reads
 * (30/day) draw on separate buckets under the shared 90/day total.
 * <p>
 * The budget logic is exercised directly through the package-private
 * {@link ApiFootballClient#tryAcquire(BudgetBucket)} instead of through a
 * stub HTTP server: this sandbox stalls loopback data from Java processes,
 * so a local stub can never answer here (verified with a raw
 * {@code java.net.Socket} — connect succeeds, the first data segment never
 * arrives). In a normal dev/CI environment the same assertions can be run
 * end-to-end by pointing the client at a stub and calling
 * {@code getFixturesByDate(...).block()}.
 */
class ApiFootballBudgetTest {

    private ApiFootballClient client(int max, int scheduled, int onDemand, long minIntervalMs) {
        ApiFootballProperties props = new ApiFootballProperties();
        props.setBaseUrl("http://127.0.0.1:9");
        props.setApiKey("test-key");
        props.setMaxDailyRequests(max);
        props.setScheduledDailyLimit(scheduled);
        props.setOnDemandDailyLimit(onDemand);
        props.setMinRequestIntervalMs(minIntervalMs);
        return new ApiFootballClient(props);
    }

    @Test
    void scheduledBucketCapsAndOnDemandReserveSurvives() {
        ApiFootballClient client = client(5, 3, 2, 0);
        // Scheduled share: 3 acquires succeed, the 4th is rejected.
        for (int i = 0; i < 3; i++) assertTrue(client.tryAcquire(BudgetBucket.SCHEDULED));
        assertFalse(client.tryAcquire(BudgetBucket.SCHEDULED));
        Map<String, Object> snapshot = client.budgetSnapshot();
        assertEquals(3, snapshot.get("scheduledUsed"));
        assertEquals(0, snapshot.get("scheduledRemaining"));
        assertEquals(3, snapshot.get("usedToday"));
        // The on-demand reserve is untouched by scheduled exhaustion.
        assertTrue(client.tryAcquire(BudgetBucket.ON_DEMAND));
        assertTrue(client.tryAcquire(BudgetBucket.ON_DEMAND));
        assertFalse(client.tryAcquire(BudgetBucket.ON_DEMAND));
        assertEquals(0, client.budgetSnapshot().get("onDemandRemaining"));
        assertEquals(0, client.budgetSnapshot().get("remaining"));
        assertEquals(5, client.budgetSnapshot().get("usedToday"));
    }

    @Test
    void rejectedAttemptsDoNotConsumeBudget() {
        ApiFootballClient client = client(5, 3, 2, 0);
        for (int i = 0; i < 3; i++) assertTrue(client.tryAcquire(BudgetBucket.SCHEDULED));
        // Three more rejected attempts must not push usedToday past the cap
        // (the old code incremented before checking, so it did).
        for (int i = 0; i < 3; i++) assertFalse(client.tryAcquire(BudgetBucket.SCHEDULED));
        assertEquals(3, client.budgetSnapshot().get("usedToday"));
        assertEquals(3, client.budgetSnapshot().get("scheduledUsed"));
    }

    @Test
    void misconfiguredSplitIsClampedAndOnDemandReserveProtected() {
        // 80 + 30 > 90 total: scheduled is clamped to 60, on-demand keeps 30.
        ApiFootballClient client = client(90, 80, 30, 0);
        Map<String, Object> snapshot = client.budgetSnapshot();
        assertEquals(60, snapshot.get("scheduledDailyLimit"));
        assertEquals(30, snapshot.get("onDemandDailyLimit"));
    }

    @Test
    void pacingEnforcesMinimumIntervalBetweenRequests() {
        ApiFootballClient client = client(10, 10, 0, 200);
        long start = System.currentTimeMillis();
        assertTrue(client.tryAcquire(BudgetBucket.SCHEDULED));
        assertTrue(client.tryAcquire(BudgetBucket.SCHEDULED));
        long elapsed = System.currentTimeMillis() - start;
        // 200 ms gap between the two acquires (20 ms tolerance for scheduling).
        assertTrue(elapsed >= 180, "expected >=180ms between paced acquires, was " + elapsed);
    }

    @Test
    void unlimitedModeDisablesAllCaps() {
        ApiFootballClient client = client(0, 60, 30, 0);
        for (int i = 0; i < 200; i++) assertTrue(client.tryAcquire(BudgetBucket.SCHEDULED));
        assertEquals(-1, client.budgetSnapshot().get("remaining"));
    }
}
