package com.fufu.onlinestats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * One calendar day of aggregated traffic. Everything the charts need is pre-aggregated
 * here so a trend query never has to scan raw events.
 */
@GVK(group = "online-stats.halo.run", version = "v1alpha1",
    kind = "DailyStat", plural = "dailystats", singular = "dailystat")
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyStat extends AbstractExtension {

    private Spec spec = new Spec();

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {

        /** Date in yyyy-MM-dd form, also used as the resource name. */
        private String date;

        private Long views;

        private Long visitors;

        private Long totalOnlineMs;

        private Map<String, Long> browsers = new LinkedHashMap<>();

        private Map<String, Long> systems = new LinkedHashMap<>();

        private Map<String, Long> referrers = new LinkedHashMap<>();

        private Map<String, Long> countries = new LinkedHashMap<>();

        /** Visitor key seen that day, used to count unique visitors per dimension. */
        private Map<String, Long> visitorViews = new LinkedHashMap<>();

        /** Post name to view count for that day. */
        private Map<String, Long> posts = new LinkedHashMap<>();

        /** Visitor key to the IP that was seen that day. */
        private Map<String, String> visitorIps = new LinkedHashMap<>();

        /** Source value to the visitor keys that used it, e.g. browser name to visitors. */
        private Map<String, List<String>> sourceVisitors = new LinkedHashMap<>();
    }
}