package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Clearing a visitor has to take them out of the stored totals too, otherwise the list
 * says one thing and the statistics page another.
 */
class VisitorRemovalTest {

    private static DailyStat dailyWith(String key, long views) {
        DailyStat day = new DailyStat();
        DailyStat.Spec spec = day.getSpec();
        spec.setDate("2026-11-01");
        spec.getVisitorViews().put(key, views);
        spec.getVisitorViews().put("other", 5L);
        spec.getVisitorIps().put(key, "10.0.0.1");
        spec.setViews(views + 5);
        spec.setVisitors(2L);
        return day;
    }

    @Test
    void aDayLosesExactlyThatDevicesViews() {
        DailyStat day = dailyWith("v-1", 4);

        assertTrue(StatsAggregationService.subtractVisitor(day.getSpec(), "v-1"));

        assertEquals(5L, day.getSpec().getViews(), "4 views belong to the cleared device");
        assertEquals(1L, day.getSpec().getVisitors(), "one device is left");
        assertFalse(day.getSpec().getVisitorViews().containsKey("v-1"));
        assertFalse(day.getSpec().getVisitorIps().containsKey("v-1"));
    }

    @Test
    void aDayThatNeverSawTheDeviceIsLeftAlone() {
        DailyStat day = dailyWith("v-1", 4);

        assertFalse(StatsAggregationService.subtractVisitor(day.getSpec(), "v-9"));
        assertEquals(9L, day.getSpec().getViews());
        assertEquals(2L, day.getSpec().getVisitors());
    }

    @Test
    void anArticleLosesViewsAndTimeAndTheDeviceEntry() {
        ArticleStat article = new ArticleStat();
        ArticleStat.Spec spec = article.getSpec();
        spec.setPostName("post-1");
        spec.getVisitorViews().put("v-1", 3L);
        spec.getVisitorViews().put("other", 2L);
        spec.getVisitorDurations().put("v-1", 60_000L);
        spec.getVisitorDurations().put("other", 30_000L);
        spec.getVisitorLastSeen().put("v-1", 123L);
        spec.setViews(5L);
        spec.setTotalOnlineMs(90_000L);
        spec.setVisitors(2L);

        assertTrue(StatsAggregationService.subtractVisitor(spec, "v-1"));

        assertEquals(2L, spec.getViews());
        assertEquals(30_000L, spec.getTotalOnlineMs());
        assertEquals(1L, spec.getVisitors());
        assertFalse(spec.getVisitorDurations().containsKey("v-1"));
        assertFalse(spec.getVisitorLastSeen().containsKey("v-1"));
    }

    @Test
    void theTotalsNeverGoBelowZero() {
        DailyStat day = dailyWith("v-1", 4);
        day.getSpec().setViews(2L);

        assertTrue(StatsAggregationService.subtractVisitor(day.getSpec(), "v-1"));

        assertEquals(0L, day.getSpec().getViews());
    }
}