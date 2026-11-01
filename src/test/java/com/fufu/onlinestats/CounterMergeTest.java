package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * A day's numbers used to drop when a write came back slightly behind: the figure was
 * read from the day record and written out verbatim, so the ranking could show 90 views
 * and later 60 with nothing deleted in between.
 */
class CounterMergeTest {

    @Test
    void aStaleDayRecordCannotLowerTodaysFigure() {
        // The stored article already counted 90; the day record came back saying 60.
        long merged = StatsAggregationService.mergeToday(60L, 90L, "2026-11-01", "2026-11-01");

        assertEquals(90L, merged, "within one day the number only climbs");
    }

    @Test
    void aNewDayStartsFromTheDayRecord() {
        long merged = StatsAggregationService.mergeToday(7L, 90L, "2026-10-31", "2026-11-01");

        assertEquals(7L, merged, "yesterday's total must not carry into today");
    }

    @Test
    void theFreshValueWinsWhenItIsAhead() {
        long merged = StatsAggregationService.mergeToday(120L, 90L, "2026-11-01", "2026-11-01");

        assertEquals(120L, merged);
    }

    @Test
    void anUnstampedArticleIsTreatedAsANewDay() {
        long merged = StatsAggregationService.mergeToday(12L, 90L, null, "2026-11-01");

        assertEquals(12L, merged);
    }
}