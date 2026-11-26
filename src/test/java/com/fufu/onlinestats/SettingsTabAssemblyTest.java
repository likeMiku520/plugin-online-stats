package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The two switches live in the data tab, so they only reach a caller that reads every
 * tab. Reading one group silently dropped them and the automatic lookup never ran.
 */
class SettingsTabAssemblyTest {

    @Test
    void theDataTabSwitchesSurviveAssembly() {
        OnlineStatsSettings settings = OnlineStatsSettings.fromTabs(
            OnlineStatsSettings.tab(java.util.Map.of("heartbeatInterval", "3")),
            OnlineStatsSettings.tab(java.util.Map.of("chatEnabled", "true")),
            OnlineStatsSettings.tab(java.util.Map.of()),
            OnlineStatsSettings.tab(java.util.Map.of(
                "autoGeoLookup", "true",
                "autoGeoSkipBots", "false")));

        assertTrue(settings.autoGeoLookupOrDefault(), "the data tab contributes the switch");
        assertFalse(settings.autoGeoSkipBotsOrDefault(), "a switched off skip stays off");
    }

    @Test
    void anAbsentDataTabLeavesTheDefaults() {
        OnlineStatsSettings settings = OnlineStatsSettings.fromTabs(
            OnlineStatsSettings.tab(java.util.Map.of()),
            OnlineStatsSettings.tab(java.util.Map.of()),
            OnlineStatsSettings.tab(java.util.Map.of()),
            OnlineStatsSettings.tab(java.util.Map.of()));

        assertFalse(settings.autoGeoLookupOrDefault(), "the lookup is opt in");
        assertTrue(settings.autoGeoSkipBotsOrDefault(), "robots are skipped by default");
    }
}