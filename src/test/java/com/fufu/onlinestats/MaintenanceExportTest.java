package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

/**
 * The exports build their files line by line. These call the real helpers rather than a
 * copy of them, so a file that would download fine but contain escaped newlines still
 * fails here.
 */
class MaintenanceExportTest {

    @Test
    void aCommaOrQuoteStaysInsideOneCell() {
        assertEquals("\"南京市, 江苏\"", MaintenanceEndpoint.csv("南京市, 江苏"));
        assertEquals("\"他说\"\"你好\"\"\"", MaintenanceEndpoint.csv("他说\"你好\""));
        assertEquals("", MaintenanceEndpoint.csv(null));
        assertEquals("\"7\"", MaintenanceEndpoint.csv(7));
    }

    @Test
    void aLineBreakDoesNotSplitTheRow() {
        assertEquals("\"第一行 第二行\"", MaintenanceEndpoint.csv("第一行\n第二行"));
    }

    @Test
    void timesAreWrittenReadably() {
        long stamp = 1_700_000_000_000L;
        String expected = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(stamp));

        assertEquals(expected, MaintenanceEndpoint.formatTime(stamp));
        assertEquals("", MaintenanceEndpoint.formatTime(0), "a missing time is left blank");
    }

    @Test
    void theTypeFilterSeparatesPeopleFromBots() {
        assertTrue(filter("all", "").accepts(true, "1.2.3.4"));
        assertTrue(filter("all", "").accepts(false, "1.2.3.4"));
        assertFalse(filter("human", "").accepts(true, "1.2.3.4"));
        assertTrue(filter("human", "").accepts(false, "1.2.3.4"));
        assertFalse(filter("bot", "").accepts(false, "1.2.3.4"));
        assertTrue(filter("bot", "").accepts(true, "1.2.3.4"));
    }

    @Test
    void theKeywordMatchesAnyFieldItIsGiven() {
        assertTrue(filter("all", "南京").accepts(false, "1.2.3.4", "中国 江苏 南京"));
        assertTrue(filter("all", "1.2.3").accepts(false, "1.2.3.4", null));
        assertFalse(filter("all", "北京").accepts(false, "1.2.3.4", "中国 江苏 南京"));
        assertTrue(filter("all", "NANJING").accepts(false, "1.2.3.4", "nanjing"));
    }

    @Test
    void sortingOrdersByTheFieldThatWasAskedFor() {
        var older = row(1_000L, 1L);
        var newer = row(9_000L, 5L);

        var byLast = filter("all", "").comparator();
        assertEquals(newer, byLast.compare(older, newer) < 0 ? older : newer);

        var byVisits = new MaintenanceEndpoint.Filter("all", "visitCount", "").comparator();
        assertTrue(byVisits.compare(newer, older) < 0, "more visits sorts first");
    }

    private static java.util.Map<String, Object> row(long lastSeen, long visits) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("lastSeenAt", lastSeen);
        map.put("visitCount", visits);
        return map;
    }

    private static MaintenanceEndpoint.Filter filter(String type, String keyword) {
        return new MaintenanceEndpoint.Filter(type, "lastSeenAt", keyword);
    }
}