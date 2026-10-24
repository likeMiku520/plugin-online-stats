package com.fufu.onlinestats;

/**
 * A live visitor kept in memory. The registry keys entries by a device fingerprint
 * (IP + User-Agent) so multiple tabs of the same browser collapse into one visitor.
 */
public class OnlineVisitor {

    private static final long MAX_GAP_MS = 5L * 60 * 1000;

    private final String key;
    private final String ip;
    private final String userAgent;
    private final long firstSeenAt;

    private volatile String visitorId;
    private volatile String postName;
    private volatile String pageUrl;
    private volatile String pageTitle;
    private volatile String referer;
    private volatile String firstReferer;
    private volatile String screen;
    private volatile String viewport;
    private volatile String timezone;
    private volatile String language;
    private volatile String platform;
    private volatile String cpuCores;
    private volatile String deviceMemory;
    private volatile String connection;

    private volatile long lastSeenAt;
    private volatile long totalOnlineMs;
    private volatile int pageViewCount = 1;
    private volatile int sessionCount = 1;
    private volatile int flushedPageViewCount;
    private volatile long flushedOnlineMs;
    private volatile int flushedSessionCount;
    private volatile boolean dirty = true;
    private volatile long lastFlushedAt;

    public OnlineVisitor(String key, String ip, String userAgent, long now) {
        this.key = key;
        this.ip = ip;
        this.userAgent = userAgent;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.lastFlushedAt = now;
    }

    /**
     * Applies one heartbeat payload. Fields that the client did not send keep their
     * previous value instead of being cleared.
     */
    public void touch(String visitorId, String postName, String url, String title, String ref,
                      String screen, String viewport, String timezone, String language,
                      String platform, String cores, String memory, String connection,
                      long now) {
        if (postName != null && !postName.isBlank()) {
            this.postName = postName;
        }
        long gap = now - lastSeenAt;
        if (gap > 0 && gap <= MAX_GAP_MS) {
            totalOnlineMs += gap;
        }
        if (url != null && !url.isBlank() && !url.equals(pageUrl)) {
            if (pageUrl != null) {
                pageViewCount++;
            }
            pageUrl = url;
        }
        if (title != null && !title.isBlank()) {
            pageTitle = title;
        }
        if (ref != null && !ref.isBlank() && !ref.equals(referer)) {
            referer = ref;
            if (firstReferer == null) {
                firstReferer = ref;
            }
        }
        if (visitorId != null && !visitorId.isBlank()) {
            this.visitorId = visitorId;
        }
        if (screen != null && !screen.isBlank()) {
            this.screen = screen;
        }
        if (viewport != null && !viewport.isBlank()) {
            this.viewport = viewport;
        }
        if (timezone != null && !timezone.isBlank()) {
            this.timezone = timezone;
        }
        if (language != null && !language.isBlank()) {
            this.language = language;
        }
        if (platform != null && !platform.isBlank()) {
            this.platform = platform;
        }
        if (cores != null && !cores.isBlank()) {
            this.cpuCores = cores;
        }
        if (memory != null && !memory.isBlank()) {
            this.deviceMemory = memory;
        }
        if (connection != null && !connection.isBlank()) {
            this.connection = connection;
        }
        lastSeenAt = now;
        dirty = true;
    }

    public void markResumed() {
        sessionCount++;
        dirty = true;
    }

    public void markFlushed(int pageViews, long onlineMs, int sessions, long now) {
        this.flushedPageViewCount = pageViews;
        this.flushedOnlineMs = onlineMs;
        this.flushedSessionCount = sessions;
        this.dirty = false;
        this.lastFlushedAt = now;
    }

    public int getFlushedPageViewCount() {
        return flushedPageViewCount;
    }

    public long getFlushedOnlineMs() {
        return flushedOnlineMs;
    }

    public int getFlushedSessionCount() {
        return flushedSessionCount;
    }

    public boolean isDirty() {
        return dirty;
    }

    public long getLastFlushedAt() {
        return lastFlushedAt;
    }

    public String getKey() {
        return key;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public long getFirstSeenAt() {
        return firstSeenAt;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public String getPostName() {
        return postName;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public String getPageTitle() {
        return pageTitle;
    }

    public String getReferer() {
        return referer;
    }

    public String getFirstReferer() {
        return firstReferer;
    }

    public String getScreen() {
        return screen;
    }

    public String getViewport() {
        return viewport;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getLanguage() {
        return language;
    }

    public String getPlatform() {
        return platform;
    }

    public String getCpuCores() {
        return cpuCores;
    }

    public String getDeviceMemory() {
        return deviceMemory;
    }

    public String getConnection() {
        return connection;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public long getTotalOnlineMs() {
        return totalOnlineMs;
    }

    public int getPageViewCount() {
        return pageViewCount;
    }

    public int getSessionCount() {
        return sessionCount;
    }
}
