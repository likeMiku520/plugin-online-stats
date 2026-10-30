package com.fufu.onlinestats;

import java.util.Map;
import java.util.function.Function;

/**
 * Plugin settings, flattened from the tabs of the Setting resource. Each tab is stored
 * as its own config group, so they are read one by one and merged into this record.
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
                                  Boolean chatRequireHttps,
                                  Boolean chatAnnounceEnabled,
                                  String chatAnnounceText,
                                  String chatAnnounceName,
                                  String chatAnnounceAvatar) {

    /** Tab groups of the Setting resource. */
    public static final String GROUP = "basic";
    public static final String GROUP_CHAT = "chat";
    public static final String GROUP_ANNOUNCE = "announce";
    public static final String GROUP_DATA = "data";

    /** Settings object used when the stored configuration cannot be read. */
    public static OnlineStatsSettings empty() {
        return new OnlineStatsSettings(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    /** Wraps a stored group, so the merge below can read any of them the same way. */
    public static final class Partial {
        private final Function<String, String> value;

        Partial(Function<String, String> value) {
            this.value = value;
        }

        Integer number(String key) {
            String raw = value.apply(key);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Integer.valueOf(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Long longNumber(String key) {
            String raw = value.apply(key);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Long.valueOf(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Boolean bool(String key) {
            String raw = value.apply(key);
            return raw == null || raw.isBlank() ? null : Boolean.valueOf(raw.trim());
        }

        String text(String key) {
            return value.apply(key);
        }
    }

    /**
     * Reads one stored tab. A tab nobody has saved yet is simply absent, so a missing
     * group must not be treated as an error.
     */
    public static Partial tab(Map<String, String> stored) {
        Map<String, String> data = stored == null ? Map.of() : stored;
        return new Partial(data::get);
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

    /**
     * Builds the whole configuration out of the stored tabs. A tab that was never saved
     * contributes nothing, which leaves its fields at their built-in defaults.
     */
    public static OnlineStatsSettings fromTabs(Partial basic, Partial chat,
                                               Partial announce, Partial data) {
        Partial b = basic == null ? tab(null) : basic;
        Partial c = chat == null ? tab(null) : chat;
        Partial a = announce == null ? tab(null) : announce;
        Partial d = data == null ? tab(null) : data;
        return new OnlineStatsSettings(
            b.number("heartbeatInterval"),
            b.number("onlineTimeout"),
            b.bool("showIpInConsole"),
            d.text("geoEndpoint"),
            d.number("historyRetentionDays"),
            d.number("maxRecords"),
            b.longNumber("officialViewsAdjustment"),
            b.text("siteHomepage"),
            c.bool("chatEnabled"),
            c.text("chatAvatar"),
            c.bool("chatRequireHttps"),
            a.bool("chatAnnounceEnabled"),
            a.text("chatAnnounceText"),
            a.text("chatAnnounceName"),
            a.text("chatAnnounceAvatar"));
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

    /** Name shown on the automatic greeting when none is configured. */
    public static final String DEFAULT_ANNOUNCE_NAME = "访客消息";

    /** Upper bound for the greeting, matching the chat message limit. */
    private static final int MAX_ANNOUNCE_LENGTH = 1000;

    /**
     * Whether a first time visitor should receive the automatic greeting. Turning it on
     * is enough on its own: the greeting travels over the conversation channel, so the
     * channel runs for as long as there is something to greet with.
     */
    public boolean chatAnnounceActive() {
        return chatAnnounceEnabled != null && chatAnnounceEnabled && announceText() != null;
    }

    /**
     * Whether the conversation channel runs at all: either the owner turned the
     * conversation on, or there is a greeting to deliver.
     */
    public boolean chatChannelEnabled() {
        return chatEnabledOrDefault() || chatAnnounceActive();
    }

    /** The greeting text, or null when there is nothing to send. */
    public String announceText() {
        if (chatAnnounceText == null || chatAnnounceText.isBlank()) {
            return null;
        }
        String text = chatAnnounceText.replace("\r\n", "\n").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_ANNOUNCE_LENGTH
            ? text.substring(0, MAX_ANNOUNCE_LENGTH) : text;
    }

    /** Who the greeting appears to come from. */
    public String announceName() {
        if (chatAnnounceName == null || chatAnnounceName.isBlank()) {
            return DEFAULT_ANNOUNCE_NAME;
        }
        String name = chatAnnounceName.trim();
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    /** Picture shown next to the greeting, empty when unset. */
    public String announceAvatar() {
        if (chatAnnounceAvatar == null || chatAnnounceAvatar.isBlank()) {
            return null;
        }
        return chatAnnounceAvatar.trim();
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
