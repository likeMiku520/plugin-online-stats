package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A write that lands while the plugin is being swapped out fails with "Scheme not found".
 * That is not a reason to raise the "statistics could not be saved" banner: the counters
 * are still in memory and the next write carries them.
 */
class SchemeMissingErrorTest {

    @Test
    void theSwapMessageIsRecognised() {
        assertTrue(StatsAggregationService.schemeMissing(
            new IllegalStateException("500 INTERNAL_SERVER_ERROR "
                + "\"Scheme not found for online-stats.halo.run/v1alpha1/ArticleStat\"")));
    }

    @Test
    void itIsRecognisedThroughAWrappedCause() {
        RuntimeException wrapped = new RuntimeException("save failed",
            new IllegalStateException("Scheme not found for online-stats.halo.run/v1alpha1/DailyStat"));
        assertTrue(StatsAggregationService.schemeMissing(wrapped));
    }

    @Test
    void otherFailuresStayFailures() {
        assertFalse(StatsAggregationService.schemeMissing(
            new IllegalStateException("Timeout on blocking read for 10000000000 NANOSECONDS")));
        assertFalse(StatsAggregationService.schemeMissing(new IllegalStateException("boom")));
    }
}