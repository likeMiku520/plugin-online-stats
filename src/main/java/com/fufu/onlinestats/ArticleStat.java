package com.fufu.onlinestats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * Aggregated statistics for one published post. View counts prefer the official Halo
 * counter; the plugin adds visitor, duration and per-day detail that Halo does not keep.
 */
@GVK(group = "online-stats.halo.run", version = "v1alpha1",
    kind = "ArticleStat", plural = "articlestats", singular = "articlestat")
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArticleStat extends AbstractExtension {

    private Spec spec = new Spec();

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {

        private String postName;

        private String slug;

        private String title;

        private String permalink;

        /** Total page views counted by this plugin. */
        private Long views;

        /** Page views reported by Halo's own counter, for comparison. */
        private Long officialViews;

        private Long visitors;

        private Long todayViews;

        private Long todayVisitors;

        private Long totalOnlineMs;

        private Long firstSeenAt;

        private Long lastSeenAt;

        /** Browser name to view count. */
        private Map<String, Long> browsers = new LinkedHashMap<>();

        /** Operating system name to view count. */
        private Map<String, Long> systems = new LinkedHashMap<>();

        /** Referrer host to view count. */
        private Map<String, Long> referrers = new LinkedHashMap<>();

        /** Visitor key to visit count for this article. */
        private Map<String, Long> visitorViews = new LinkedHashMap<>();

        /** Visitor key to accumulated online milliseconds for this article. */
        private Map<String, Long> visitorDurations = new LinkedHashMap<>();

        /** Visitor key to last seen timestamp for this article. */
        private Map<String, Long> visitorLastSeen = new LinkedHashMap<>();

        /** Visitor key to client IP at the time of the visit. */
        private Map<String, String> visitorIps = new LinkedHashMap<>();
    }
}