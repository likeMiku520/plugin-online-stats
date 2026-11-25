package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The automatic location lookup is opt in, and the second switch only exists to narrow
 * it: a visitor's address leaves the site when it runs, so the default has to be "no".
 */
class AutoGeoSettingTest {

    @Test
    void lookupIsOffUnlessTheOperatorAsks() {
        assertFalse(OnlineStatsSettings.empty().autoGeoLookupOrDefault());
    }

    @Test
    void robotsAreSkippedByDefault() {
        assertTrue(OnlineStatsSettings.empty().autoGeoSkipBotsOrDefault());
    }
}