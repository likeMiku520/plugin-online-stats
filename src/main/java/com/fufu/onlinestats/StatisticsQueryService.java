package com.fufu.onlinestats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import run.halo.app.core.extension.content.Post;

/**
 * Read model for the console. Everything is computed from the pre-aggregated article
 * and daily statistics, so a dashboard request never scans raw heartbeats.
 */
@Service
public class StatisticsQueryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StatsAggregationService stats;

    public StatisticsQueryService(StatsAggregationService stats) {
        this.stats = stats;
    }

    public Map<String, Object> overview(OnlineStatsSettings settings, long onlineNow) {
        List<ArticleStat> articles = stats.articles();
        List<DailyStat> days = stats.daily();
        String today = LocalDate.now(ZoneId.systemDefault()).format(DAY);

        long views = 0;
        long visitors = 0;
        long officialViews = 0;
        long duration = 0;
        long todayViews = 0;
        long todayVisitors = 0;
        for (ArticleStat article : articles) {
            ArticleStat.Spec spec = article.getSpec();
            views += nvl(spec.getViews());
            visitors += nvl(spec.getVisitors());
            officialViews += nvl(spec.getOfficialViews());
            duration += nvl(spec.getTotalOnlineMs());
            todayViews += nvl(spec.getTodayViews());
            todayVisitors += nvl(spec.getTodayVisitors());
        }
        // The headline view number is Halo's own total. A configured correction is written
        // into Halo's counter data by the aggregation service, so it is not added again
        // here; these fields only report what was applied.
        long adjustment = settings.officialViewsAdjustmentOrDefault();
        long officialTotal = stats.officialViewsTotal();

        long totalVisitors = uniqueVisitors(days);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("views", views);
        result.put("visitors", visitors);
        result.put("uniqueVisitors", totalVisitors);
        result.put("officialViews", officialTotal);
        result.put("officialViewsRaw", officialTotal - stats.appliedAdjustment());
        result.put("officialViewsAdjustment", adjustment);
        result.put("adjustmentApplied", stats.appliedAdjustment());
        result.put("adjustmentError", stats.adjustmentError());
        result.put("totalOnlineMs", duration);
        result.put("todayViews", todayViews);
        result.put("todayVisitors", todayVisitors);
        result.put("online", onlineNow);
        result.put("articles", stats.officialByPost().size());
        result.put("trackedArticles", articles.size());
        result.put("days", days.size());
        result.put("today", today);
        result.put("loaded", stats.isLoaded());
        result.putAll(stats.diagnostics());
        return result;
    }

    /** Daily series for the trend chart, oldest first, gaps filled with zero. */
    public List<Map<String, Object>> trend(OnlineStatsSettings settings, int days) {
        LocalDate todayDate = LocalDate.now(ZoneId.systemDefault());
        LocalDate from = todayDate.minusDays(Math.max(days, 1) - 1L);
        Map<String, DailyStat> byDate = new LinkedHashMap<>();
        for (DailyStat day : stats.daily()) {
            byDate.put(day.getSpec().getDate(), day);
        }
        List<Map<String, Object>> series = new ArrayList<>();
        LocalDate current = from;
        while (!current.isAfter(todayDate)) {
            String key = current.format(DAY);
            DailyStat day = byDate.get(key);
            long views = day == null ? 0L : nvl(day.getSpec().getViews());
            long visitors = day == null ? 0L : nvl(day.getSpec().getVisitors());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", key);
            point.put("views", views);
            point.put("visitors", visitors);
            series.add(point);
            current = current.plusDays(1);
        }
        return series;
    }

    public List<Map<String, Object>> cumulativeTrend(OnlineStatsSettings settings, int days) {
        List<Map<String, Object>> series = trend(settings, days);
        List<Map<String, Object>> result = new ArrayList<>();
        long running = 0;
        for (Map<String, Object> point : series) {
            running += ((Number) point.get("views")).longValue();
            Map<String, Object> item = new LinkedHashMap<>(point);
            item.put("cumulative", running);
            result.add(item);
        }
        return result;
    }

    /** Browser, operating system and referrer totals across the selected window. */
    public Map<String, Object> sources(OnlineStatsSettings settings, int days,
                                       String dimension, String from, String to) {
        List<DailyStat> window = window(days, from, to);
        Map<String, Long> views = new LinkedHashMap<>();
        Map<String, List<String>> visitors = new LinkedHashMap<>();
        for (DailyStat day : window) {
            Map<String, Long> map = switch (dimension) {
                case "system" -> day.getSpec().getSystems();
                case "referer" -> day.getSpec().getReferrers();
                case "country" -> day.getSpec().getCountries();
                default -> day.getSpec().getBrowsers();
            };
            map.forEach((key, value) -> {
                if ("referer".equals(dimension) && isSelfReferer(key, settings)) {
                    return;
                }
                views.merge(key, value, Long::sum);
                List<String> keys = visitors.computeIfAbsent(key, k -> new ArrayList<>());
                List<String> tracked = day.getSpec().getSourceVisitors()
                    .get(dimensionKey(dimension, key));
                if (tracked != null) {
                    for (String visitorKey : tracked) {
                        if (!keys.contains(visitorKey)) {
                            keys.add(visitorKey);
                        }
                    }
                }
            });
        }
        List<Map<String, Object>> items = new ArrayList<>();
        views.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(entry -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getKey());
                item.put("views", entry.getValue());
                item.put("visitors", (long) visitors.getOrDefault(entry.getKey(), List.of()).size());
                items.add(item);
            });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("items", items);
        result.put("totalViews", items.stream()
            .mapToLong(item -> ((Number) item.get("views")).longValue()).sum());
        return result;
    }

    /**
     * Article ranking built from every published post joined with Halo's own counters,
     * so articles the plugin never saw still appear with their official view count.
     */
    public List<Map<String, Object>> articleList(OnlineStatsSettings settings, String sort,
                                                 int limit, String from, String to) {
        Map<String, Long> official = stats.officialByPost();
        Map<String, ArticleStat> tracked = new LinkedHashMap<>();
        for (ArticleStat article : stats.articles()) {
            tracked.put(article.getMetadata().getName(), article);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Post post : stats.posts()) {
            String name = post.getMetadata().getName();
            seen.add(name);
            items.add(articleView(post, tracked.get(name), official.get(name), settings));
        }
        // Articles the plugin recorded but whose post is no longer published.
        for (ArticleStat article : tracked.values()) {
            String name = article.getMetadata().getName();
            if (!seen.contains(name)) {
                items.add(articleView(null, article, official.get(name), settings));
            }
        }

        Comparator<Map<String, Object>> comparator = switch (sort == null ? "" : sort) {
            case "visitors" -> Comparator.comparingLong(
                row -> number(row.get("visitors")));
            case "today" -> Comparator.comparingLong(
                row -> number(row.get("todayViews")));
            case "duration" -> Comparator.comparingLong(
                row -> number(row.get("totalOnlineMs")));
            default -> Comparator.comparingLong(
                row -> number(row.get("officialViews")));
        };
        items.sort(comparator.reversed());
        return items.size() > (limit <= 0 ? 50 : limit)
            ? new ArrayList<>(items.subList(0, limit <= 0 ? 50 : limit)) : items;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * One ranking row. Either the post or the stat may be missing: a post with official
     * views but no plugin data still shows up, and so does a stat whose post was removed.
     */
    public Map<String, Object> articleView(Post post, ArticleStat article,
                                           Long officialViews, OnlineStatsSettings settings) {
        ArticleStat.Spec spec = article == null ? null : article.getSpec();
        String name = post != null ? post.getMetadata().getName()
            : article.getMetadata().getName();
        long visitors = spec == null ? 0L : nvl(spec.getVisitors());
        long duration = spec == null ? 0L : nvl(spec.getTotalOnlineMs());
        long official = officialViews != null ? officialViews
            : (spec == null ? 0L : nvl(spec.getOfficialViews()));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("postName", name);
        item.put("title", post != null && post.getSpec() != null
            ? post.getSpec().getTitle() : spec == null ? null : spec.getTitle());
        item.put("slug", post != null && post.getSpec() != null
            ? post.getSpec().getSlug() : spec == null ? null : spec.getSlug());
        item.put("permalink", post != null && post.getStatus() != null
            ? post.getStatus().getPermalink() : spec == null ? null : spec.getPermalink());
        item.put("views", spec == null ? 0L : nvl(spec.getViews()));
        item.put("officialViews", official);
        item.put("visitors", visitors);
        item.put("todayViews", spec == null ? 0L : nvl(spec.getTodayViews()));
        item.put("todayVisitors", spec == null ? 0L : nvl(spec.getTodayVisitors()));
        item.put("totalOnlineMs", duration);
        item.put("avgOnlineMs", visitors == 0 ? 0L : duration / visitors);
        item.put("tracked", article != null);
        item.put("firstSeenAt", spec == null ? null : spec.getFirstSeenAt());
        item.put("lastSeenAt", spec == null ? null : spec.getLastSeenAt());
        item.put("browsers", spec == null ? List.of() : topEntries(spec.getBrowsers(), 8));
        item.put("systems", spec == null ? List.of() : topEntries(spec.getSystems(), 8));
        item.put("referrers", spec == null ? List.of() : topEntries(spec.getReferrers(), 8));
        return item;
    }

    /**
     * Detail rows for one article: who read it, how often and for how long. Visitor
     * keys are opaque hashes; the IP is only attached when the setting allows it.
     */
    public Map<String, Object> articleDetail(String postName, OnlineStatsSettings settings,
                                             int limit) {
        Post post = stats.post(postName);
        ArticleStat article = stats.article(postName);
        if (post == null && article == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("article", articleView(post, article, stats.officialByPost().get(postName),
            settings));

        List<Map<String, Object>> visitors = new ArrayList<>();
        if (article != null) {
            ArticleStat.Spec spec = article.getSpec();
            spec.getVisitorViews().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit <= 0 ? 100 : limit)
                .forEach(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("visitorKey", entry.getKey());
                    row.put("views", entry.getValue());
                    row.put("durationMs",
                        spec.getVisitorDurations().getOrDefault(entry.getKey(), 0L));
                    row.put("lastSeenAt", spec.getVisitorLastSeen().get(entry.getKey()));
                    row.put("ip", settings.showIpInConsoleOrDefault()
                        ? spec.getVisitorIps().get(entry.getKey()) : null);
                    visitors.add(row);
                });
        }
        result.put("visitors", visitors);
        return result;
    }

    /** Drill-down for one browser / system / referrer value. */
    public Map<String, Object> sourceDetail(OnlineStatsSettings settings, int days,
                                            String dimension, String value) {
        List<DailyStat> window = window(days, null, null);
        long views = 0;
        Map<String, Long> perVisitor = new LinkedHashMap<>();
        Map<String, String> visitorIps = new LinkedHashMap<>();
        for (DailyStat day : window) {
            Map<String, Long> map = switch (dimension) {
                case "system" -> day.getSpec().getSystems();
                case "referer" -> day.getSpec().getReferrers();
                default -> day.getSpec().getBrowsers();
            };
            Long count = map.get(value);
            if (count == null) {
                continue;
            }
            views += count;
            List<String> tracked = day.getSpec().getSourceVisitors()
                .get(dimensionKey(dimension, value));
            if (tracked != null) {
                for (String visitorKey : tracked) {
                    perVisitor.merge(visitorKey,
                        day.getSpec().getVisitorViews().getOrDefault(visitorKey, 1L), Long::sum);
                }
            }
            visitorIps.putAll(day.getSpec().getVisitorIps());
        }
        List<Map<String, Object>> visitors = new ArrayList<>();
        perVisitor.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(100)
            .forEach(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("visitorKey", entry.getKey());
                row.put("views", entry.getValue());
                row.put("ip", settings.showIpInConsoleOrDefault()
                    ? visitorIps.get(entry.getKey()) : null);
                visitors.add(row);
            });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("value", value);
        result.put("views", views);
        result.put("visitors", (long) perVisitor.size());
        result.put("items", visitors);
        return result;
    }

    /** Drill-down for one IP or visitor key: everything this visitor did. */
    public Map<String, Object> visitorDetail(String visitorKey, OnlineStatsSettings settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visitorKey", visitorKey);
        List<Map<String, Object>> articles = new ArrayList<>();
        String ip = null;
        long totalViews = 0;
        long totalDuration = 0;
        for (ArticleStat article : stats.articles()) {
            ArticleStat.Spec spec = article.getSpec();
            Long views = spec.getVisitorViews().get(visitorKey);
            if (views == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("postName", article.getMetadata().getName());
            row.put("title", spec.getTitle());
            row.put("permalink", spec.getPermalink());
            row.put("views", views);
            row.put("durationMs", spec.getVisitorDurations().getOrDefault(visitorKey, 0L));
            row.put("lastSeenAt", spec.getVisitorLastSeen().get(visitorKey));
            articles.add(row);
            totalViews += views;
            totalDuration += spec.getVisitorDurations().getOrDefault(visitorKey, 0L);
            if (ip == null) {
                ip = spec.getVisitorIps().get(visitorKey);
            }
        }
        articles.sort(Comparator.comparingLong(
            (Map<String, Object> row) -> ((Number) row.get("views")).longValue()).reversed());
        result.put("ip", settings.showIpInConsoleOrDefault() ? ip : null);
        result.put("totalViews", totalViews);
        result.put("totalDurationMs", totalDuration);
        result.put("articles", articles);
        return result;
    }

    private static String dimensionKey(String dimension, String value) {
        return dimension + "|" + value;
    }

    /**
     * Internal navigation (clicking a post from the home page) is not a real referrer,
     * so it is hidden from the source list when the domain is known.
     */
    private static boolean isSelfReferer(String value, OnlineStatsSettings settings) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String configured = settings.siteHomepageOrDefault();
        if (configured.isBlank()) {
            return false;
        }
        String host = configured;
        try {
            if (host.contains("://")) {
                host = java.net.URI.create(host).getHost();
            }
        } catch (Exception ignored) {
            // fall back to comparing the raw setting
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.startsWith("www.") ? host.substring(4) : host;
        String target = value.startsWith("www.") ? value.substring(4) : value;
        return target.equalsIgnoreCase(normalized);
    }

    private List<DailyStat> window(int days, String from, String to) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate start = today.minusDays(Math.max(days, 1) - 1L);
        LocalDate end = today;
        try {
            if (from != null && !from.isBlank()) {
                start = LocalDate.parse(from, DAY);
            }
            if (to != null && !to.isBlank()) {
                end = LocalDate.parse(to, DAY);
            }
        } catch (Exception ignored) {
            // keep the day based window when the dates are unusable
        }
        List<DailyStat> result = new ArrayList<>();
        for (DailyStat day : stats.daily()) {
            try {
                LocalDate date = LocalDate.parse(day.getSpec().getDate(), DAY);
                if (!date.isBefore(start) && !date.isAfter(end)) {
                    result.add(day);
                }
            } catch (Exception ignored) {
                // skip malformed rows
            }
        }
        result.sort(Comparator.comparing(day -> day.getSpec().getDate()));
        return result;
    }

    private long uniqueVisitors(List<DailyStat> days) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (DailyStat day : days) {
            keys.addAll(day.getSpec().getVisitorViews().keySet());
        }
        return keys.size();
    }

    private static List<Map<String, Object>> topEntries(Map<String, Long> map, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        map.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .forEach(entry -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getKey());
                item.put("views", entry.getValue());
                items.add(item);
            });
        return items;
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    public static long daysBetween(String from, String to) {
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(from, DAY), LocalDate.parse(to, DAY)) + 1;
        } catch (Exception e) {
            return 0L;
        }
    }

    public static String today() {
        return LocalDate.now(ZoneId.systemDefault()).format(DAY);
    }

    public static String instantToDay(Long timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
            .toLocalDate().format(DAY);
    }
}