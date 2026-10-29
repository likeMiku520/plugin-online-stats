package com.fufu.onlinestats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * Persisted history entry for one visitor device. The record is keyed by a hash of
 * the client IP and User-Agent, so repeated visits from the same device accumulate
 * into a single row instead of creating duplicates.
 */
@GVK(group = "online-stats.halo.run", version = "v1alpha1",
    kind = "VisitorRecord", plural = "visitorrecords", singular = "visitorrecord")
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class VisitorRecord extends AbstractExtension {

    private Spec spec = new Spec();

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {

        private String ip;

        private String userAgent;

        private Boolean bot;

        private String botReason;

        private String country;

        private String countryCode;

        private String region;

        private String city;

        private String districts;

        private String isp;

        private String net;

        private String idc;

        private String zipcode;

        private String areacode;

        private String protocol;

        private String location;

        private String dec;

        private Long geoResolvedAt;

        private String firstPageUrl;

        private String firstPageTitle;

        private String lastPageUrl;

        private String lastPageTitle;

        private String firstReferer;

        private String lastReferer;

        private Long firstSeenAt;

        private Long lastSeenAt;

        private Integer visitCount;

        private Integer pageViewCount;

        private Long totalOnlineMs;

        private String screen;

        private String viewport;

        private String timezone;

        private String language;

        private String platform;

        private String cpuCores;

        private String deviceMemory;

        private String connection;

        /**
         * Pages this visitor has been on, newest last. One entry per page change, so the
         * console can show where they went, when, and how long they stayed.
         */
        private List<PageVisit> pageVisits = new ArrayList<>();
    }

    /** One page a visitor opened, with the time spent on it. */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PageVisit {

        private String url;

        private String title;

        /** When the page was opened. */
        private Long enteredAt;

        /** Last heartbeat seen on the page; null while it is still open. */
        private Long leftAt;

        private Long durationMs;
    }
}
