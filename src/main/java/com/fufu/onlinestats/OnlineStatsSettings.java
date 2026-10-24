package com.fufu.onlinestats;

/**
 * Plugin settings, mapped from the "basic" form group of the Setting resource.
 */
public record OnlineStatsSettings(Integer heartbeatInterval,
                                  Integer onlineTimeout,
                                  Boolean showIpInConsole,
                                  String geoEndpoint,
                                  Integer historyRetentionDays,
                                  Integer maxRecords) {

    public static final String GROUP = "basic";

    public int heartbeatIntervalOrDefault() {
        return heartbeatInterval != null ? heartbeatInterval : 15;
    }

    public long onlineTimeoutMillis() {
        return (onlineTimeout != null ? onlineTimeout : 60) * 1000L;
    }

    public boolean showIpInConsoleOrDefault() {
        return showIpInConsole == null || showIpInConsole;
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
