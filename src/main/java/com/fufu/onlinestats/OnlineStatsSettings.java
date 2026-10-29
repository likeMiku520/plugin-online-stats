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
                                  String chatAvatar,
                                  Boolean chatRequireHttps) {

    public static final String GROUP = "basic";

    /** Settings object used when the stored configuration cannot be read. */
    public static OnlineStatsSettings empty() {
        return new OnlineStatsSettings(null, null, null, null, null, null, null, null,
            null, null, null);
    }

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
     * The picture the administrator explicitly chose, or null when the signed in
     * account's own avatar should be used instead. The value earlier versions wrote as
     * their default counts as "not chosen", so an upgrade switches to account avatars
     * without anyone having to edit the setting.
     */
    public String chatAvatarOverride() {
        if (chatAvatar == null || chatAvatar.isBlank()) {
            return null;
        }
        String value = chatAvatar.trim();
        if (DEFAULT_CHAT_AVATAR.equals(value)) {
            return null;
        }
        return value.startsWith("https://") || value.startsWith("http://") ? value : null;
    }

    /**
     * Avatar painted on every message the visitor's popup shows. Configured rather
     * than read from the account, so the picture visitors see stays the same one even
     * when the signed-in administrator changes.
     */
    public String chatAvatarOrDefault() {
        String override = chatAvatarOverride();
        return override != null ? override : DEFAULT_CHAT_AVATAR;
    }

    /**
     * Refuses the conversation endpoints over a plain connection, so chat text is never
     * carried in the clear. Sites whose reverse proxy does not report the original
     * scheme can turn it off.
     * <p>Off by default. The server cannot reliably tell whether the visitor's own
     * connection was encrypted: behind a proxy it only sees the internal hop, and the
     * forwarding header that would describe the original request may be absent or say
     * {@code http} even though every visitor is on HTTPS. Treating that as plaintext
     * would silently cut the conversation off, so this guard is opt-in for deployments
     * that know their proxy reports the scheme.</p>
     */
    public boolean chatRequireHttpsOrDefault() {
        return chatRequireHttps != null && chatRequireHttps;
    }

    public String geoEndpointOrDefault() {
        // A stored value is normalised: retired providers are replaced and the retired
        // path of a working provider is rewritten, so an upgrade needs no manual change.
        return IpGeoService.normalize(geoEndpoint);
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
