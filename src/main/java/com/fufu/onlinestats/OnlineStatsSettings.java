package com.fufu.onlinestats;

/**
 * Plugin settings, mapped from the "basic" form group of the Setting resource.
 */
public record OnlineStatsSettings(Integer heartbeatInterval,
                                  Integer onlineTimeout,
                                  Boolean showIpInConsole,
                                  String geoEndpoint,
                                  Integer historyRetentionDays,
                                  Integer maxRecords,
                                  Long officialViewsAdjustment,
                                  String siteHomepage,
                                  Boolean chatEnabled,
                                  String chatAvatar) {

    public static final String GROUP = "basic";

    /** Avatar every visitor sees on a console message when none is configured. */
    public static final String DEFAULT_CHAT_AVATAR =
        "https://blog.fufu520.cn/upload/fufu_touming.webp";

    /**
     * Manual correction added to the official view total. Useful when the blog was
     * migrated and Halo's own counters start from zero; usually left empty.
     */
    public long officialViewsAdjustmentOrDefault() {
        return officialViewsAdjustment == null ? 0L : officialViewsAdjustment;
    }

    /** The blog's own domain, used to filter self referrals out of the source list. */
    public String siteHomepageOrDefault() {
        return siteHomepage == null ? "" : siteHomepage.trim();
    }

    public int heartbeatIntervalOrDefault() {
        return heartbeatInterval != null ? heartbeatInterval : 15;
    }

    public long onlineTimeoutMillis() {
        return (onlineTimeout != null ? onlineTimeout : 60) * 1000L;
    }

    public boolean showIpInConsoleOrDefault() {
        return showIpInConsole == null || showIpInConsole;
    }

    /** Turns the visitor side message popup and its polling off entirely. */
    public boolean chatEnabledOrDefault() {
        return chatEnabled == null || chatEnabled;
    }

    /**
     * Avatar painted on every message the visitor's popup shows. Configured rather
     * than read from the account, so the picture visitors see stays the same one even
     * when the signed-in administrator changes.
     */
    public String chatAvatarOrDefault() {
        if (chatAvatar == null || chatAvatar.isBlank()) {
            return DEFAULT_CHAT_AVATAR;
        }
        String value = chatAvatar.trim();
        return value.startsWith("https://") || value.startsWith("http://")
            ? value : DEFAULT_CHAT_AVATAR;
    }

    public String geoEndpointOrDefault() {
        if (geoEndpoint == null || geoEndpoint.isBlank() || !geoEndpoint.contains("%s")) {
            return IpGeoService.DEFAULT_ENDPOINT;
        }
        // The default shipped by 1.1.x pointed at ip-api; 1.2 switched to mir6.
        // Ignore the stale value so an upgrade needs no manual settings change.
        if (geoEndpoint.contains("ip-api.com")) {
            return IpGeoService.DEFAULT_ENDPOINT;
        }
        return geoEndpoint;
    }

    public int historyRetentionDaysOrDefault() {
        if (historyRetentionDays == null) {
            return 90;
        }
        return Math.max(1, Math.min(historyRetentionDays, 3650));
    }

    public int maxRecordsOrDefault() {
        if (maxRecords == null) {
            return 5000;
        }
        return Math.max(100, Math.min(maxRecords, 100_000));
    }
}
