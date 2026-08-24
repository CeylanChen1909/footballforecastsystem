package com.chen.football.crawler.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceHealthTrackerTest {
    @Test
    void classifiesQuotaAndPlanErrors() {
        assertTrue(DataSourceHealthTracker.isQuotaOrPlanError("You have reached the request limit for the day"));
        assertTrue(DataSourceHealthTracker.isQuotaOrPlanError("Free plans do not have access to this date"));
        assertFalse(DataSourceHealthTracker.isQuotaOrPlanError("connection reset by peer"));
    }
}
