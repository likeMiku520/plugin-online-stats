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
                                  String chatAnnounceAvatar,
                                  java.util.List<GeoField> geoFields,
                                  java.util.List<GeoParam> geoParams) {

    /** Tab groups of the Setting resource. */
    public static final String GROUP = "basic";
    public static final String GROUP_CHAT = "chat";
    public static final String GROUP_ANNOUNCE = "announce";
    public static final String GROUP_DATA = "data";

    /**
     * One value the lookup should show, read from the provider's JSON at {@code path}.
     * A dot walks into nested objects, so {@code data.city} works for the common shape.
     */
    public record GeoField(String label, String path) {
    }

    /** One extra query parameter appended to the request, like a token or a language. */
    public record GeoParam(String name, String value) {
    }

    /** Settings object used when the stored configuration cannot be read. */
    public static OnlineStatsSettings empty() {
        return new OnlineStatsSettings(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, java.util.List.of(), java.util.List.of());
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
            a.text("chatAnnounceAvatar"),
            geoFields(d.text("geoFields")),
            geoParams(d.text("geoParams")));
    }

    /**
     * The two lists are stored as a JSON array of objects, which is what the repeater
     * input writes. Line based {@code name=value} text is still read, because that is
     * what the previous version of this setting held.
     */
    private static java.util.List<GeoField> geoFields(String raw) {
        return readList(raw, "label", "path", GeoField::new);
    }

    private static java.util.List<GeoParam> geoParams(String raw) {
        return readList(raw, "name", "value", GeoParam::new);
    }

    private static <T> java.util.List<T> readList(
        String raw, String nameKey, String valueKey,
        java.util.function.BiFunction<String, String, T> factory) {
        if (raw == null || raw.isBlank()) {
            return java.util.List.of();
        }
        String text = raw.trim();
        if (text.startsWith("[")) {
            return readJsonArray(text, nameKey, valueKey, factory);
        }
        java.util.List<T> list = new java.util.ArrayList<>();
        for (String line : text.split("\n")) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int split = entry.indexOf('=');
            String name = (split < 0 ? entry : entry.substring(0, split)).trim();
            String value = split < 0 ? "" : entry.substring(split + 1).trim();
            if (!name.isEmpty()) {
                list.add(factory.apply(name, value));
            }
        }
        return list;
    }

    private static <T> java.util.List<T> readJsonArray(
        String raw, String nameKey, String valueKey,
        java.util.function.BiFunction<String, String, T> factory) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            if (!node.isArray()) {
                return java.util.List.of();
            }
            java.util.List<T> list = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                String name = item.path(nameKey).asText("").trim();
                String value = item.path(valueKey).asText("").trim();
                // A row the reader left half filled is ignored rather than stored broken.
                if (!name.isEmpty() && !value.isEmpty()) {
                    list.add(factory.apply(name, value));
                }
            }
            return list;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** Fields shown when nothing is configured: the labels the plugin has always used. */
    public static final java.util.List<GeoField> DEFAULT_GEO_FIELDS = java.util.List.of(
        new GeoField("国家", "country"),
        new GeoField("省份", "province"),
        new GeoField("城市", "city"),
        new GeoField("区县", "districts"),
        new GeoField("运营商", "isp"),
        new GeoField("网络", "net"),
        new GeoField("区号", "areacode"),
        new GeoField("邮编", "zipcode"),
        new GeoField("归属地", "location"));

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
     * Whether a first time visitor should receive the automatic greeting. It runs over
     * the conversation, so the conversation has to be on first.
     */
    public boolean chatAnnounceActive() {
        return chatEnabledOrDefault() && chatAnnounceEnabled != null
            && chatAnnounceEnabled && announceText() != null;
    }

    /** Whether the conversation channel runs at all. */
    public boolean chatChannelEnabled() {
        return chatEnabledOrDefault();
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

    /** Extra query parameters the owner configured for the lookup. */
    public java.util.List<GeoParam> geoParams() {
        return geoParams == null || geoParams.isEmpty() ? java.util.List.of() : geoParams;
    }

    /** Values to show, in the order they were configured; empty means the built-in set. */
    public java.util.List<GeoField> geoFields() {
        return geoFields == null ? java.util.List.of() : geoFields;
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
