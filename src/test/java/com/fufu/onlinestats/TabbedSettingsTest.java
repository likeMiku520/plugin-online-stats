package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The settings live in several tabs, each stored as its own config group. Reading one
 * group only — which is what an earlier version did — silently leaves every other tab
 * at its default, and that is exactly the shape of the bug where a switch appeared to
 * be off no matter what was saved.
 */
class TabbedSettingsTest {

    private static Map<String, String> tab(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static OnlineStatsSettings merge(Map<String, String> basic, Map<String, String> chat,
                                             Map<String, String> announce,
                                             Map<String, String> data) {
        return OnlineStatsSettings.fromTabs(
            OnlineStatsSettings.tab(basic), OnlineStatsSettings.tab(chat),
            OnlineStatsSettings.tab(announce), OnlineStatsSettings.tab(data));
    }

    @Test
    void everyTabIsReadFromItsOwnGroup() {
        OnlineStatsSettings settings = merge(
            tab("heartbeatInterval", "20", "siteHomepage", "blog.fufu520.cn"),
            tab("chatEnabled", "true", "chatAvatar", "https://cdn.example.com/a.png"),
            tab("chatAnnounceEnabled", "true", "chatAnnounceText", "欢迎来玩"),
            tab("geoEndpoint", "https://example.com/geo?ip=%s", "maxRecords", "800"));

        assertEquals(20, settings.heartbeatIntervalOrDefault());
        assertEquals("blog.fufu520.cn", settings.siteHomepageOrDefault());
        assertTrue(settings.chatEnabledOrDefault());
        assertEquals("https://cdn.example.com/a.png", settings.chatAvatarOverride());
        assertTrue(settings.chatAnnounceActive());
        assertEquals("欢迎来玩", settings.announceText());
        assertEquals("https://example.com/geo?ip=%s", settings.geoEndpointOrDefault());
        assertEquals(800, settings.maxRecordsOrDefault());
    }

    @Test
    void aTabThatWasNeverSavedFallsBackToDefaults() {
        // This is the upgrade case: the config only has the original tab.
        OnlineStatsSettings settings = merge(tab("heartbeatInterval", "20"), null, null, null);

        assertEquals(20, settings.heartbeatIntervalOrDefault());
        assertFalse(settings.chatAnnounceActive(), "nothing saved means nothing on");
        assertNull(settings.announceText());
        assertEquals("访客消息", settings.announceName());
        assertEquals(IpGeoService.DEFAULT_ENDPOINT, settings.geoEndpointOrDefault());
    }

    @Test
    void allTabsMissingStillYieldsUsableSettings() {
        OnlineStatsSettings settings = merge(null, null, null, null);

        assertEquals(15, settings.heartbeatIntervalOrDefault());
        assertEquals(90, settings.historyRetentionDaysOrDefault());
        assertNull(settings.announceAvatar());
    }

    @Test
    void theGreetingNeedsTheConversationSwitchedOn() {
        // The greeting rides on the conversation, so it stays quiet until that is on.
        OnlineStatsSettings off = merge(null, tab("chatEnabled", "false"),
            tab("chatAnnounceEnabled", "true", "chatAnnounceText", "公告"), null);
        assertFalse(off.chatAnnounceActive());
        assertFalse(off.chatChannelEnabled());

        OnlineStatsSettings on = merge(null, tab("chatEnabled", "true"),
            tab("chatAnnounceEnabled", "true", "chatAnnounceText", "公告"), null);
        assertTrue(on.chatAnnounceActive());
        assertTrue(on.chatChannelEnabled());
    }

    @Test
    void theConversationStillRunsOnItsOwn() {
        OnlineStatsSettings settings = merge(null, tab("chatEnabled", "true"), null, null);

        assertTrue(settings.chatChannelEnabled());
        assertFalse(settings.chatAnnounceActive());
    }

    @Test
    void theTextIsCleanedUp() {
        OnlineStatsSettings settings = merge(null, null,
            tab("chatAnnounceEnabled", "true", "chatAnnounceText", "  第一行\r\n第二行  "), null);

        assertEquals("第一行\n第二行", settings.announceText());
        assertNull(merge(null, null,
            tab("chatAnnounceEnabled", "true", "chatAnnounceText", "  "), null).announceText());
        assertEquals(1000, merge(null, null,
            tab("chatAnnounceEnabled", "true", "chatAnnounceText", "字".repeat(1500)), null)
            .announceText().length());
    }

    @Test
    void theNameFallsBackToTheDefault() {
        assertEquals("访客消息", merge(null, null,
            tab("chatAnnounceName", "  "), null).announceName());
        assertEquals("站长通知", merge(null, null,
            tab("chatAnnounceName", " 站长通知 "), null).announceName());
        assertEquals(40, merge(null, null,
            tab("chatAnnounceName", "名".repeat(80)), null).announceName().length());
    }
}