package com.fufu.onlinestats;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Turns raw heartbeats into article and daily aggregates. A view is counted when a
 * visitor opens a new page rather than on every heartbeat, so staying on a page does
 * not inflate the numbers.
 */
@Service
public class StatsAggregationService {

    private static final Logger log = LoggerFactory.getLogger(StatsAggregationService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final int MAX_VISITOR_KEYS = 5000;
    private static final int MAX_MAP_ENTRIES = 400;
    private static final long MAX_DURATION_GAP_MS = 5L * 60 * 1000;
    private static final String COUNTER_PREFIX = "posts.content.halo.run/";

    /** Counter that carries the manual correction; it matches no real post. */
    private static final String ADJUSTMENT_COUNTER =
        COUNTER_PREFIX + "online-stats-adjustment";

    private final ReactiveExtensionClient client;

    private final Map<String, ArticleStat> articleIndex = new ConcurrentHashMap<>();
    private final Map<String, DailyStat> dailyIndex = new ConcurrentHashMap<>();
    private final Map<String, Post> postIndex = new ConcurrentHashMap<>();

    /** Visitor key plus page, used so one page entry counts as exactly one view. */
    private final Map<String, String> lastCountedPage = new ConcurrentHashMap<>();

    /** Visitor key plus post, used to accumulate reading time from heartbeat gaps. */
    private final Map<String, Long> lastHeartbeatAt = new ConcurrentHashMap<>();

    private volatile boolean loaded;
    private volatile long lastPostLoadAt;
    private volatile int postLoadCount;
    private volatile int unresolvedHeartbeats;
    private volatile String lastHeartbeatUrl;
    private volatile long officialViewsTotal;
    private volatile int counterCountTotal;
    private volatile long appliedAdjustment = -1L;
    private volatile String adjustmentError;
    private final Map<String, Long> officialByPost = new ConcurrentHashMap<>();

    public StatsAggregationService(ReactiveExtensionClient client) {
        this.client = client;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public List<ArticleStat> articles() {
        return new ArrayList<>(articleIndex.values());
    }

    public ArticleStat article(String postName) {
        return articleIndex.get(postName);
    }

    public List<DailyStat> daily() {
        return new ArrayList<>(dailyIndex.values());
    }

    public DailyStat day(String date) {
        return dailyIndex.get(date);
    }

    public List<Post> posts() {
        return new ArrayList<>(postIndex.values());
    }

    public Post post(String name) {
        return postIndex.get(name);
    }

    public void loadAll() {
        listAll(ArticleStat.class).subscribe(stat -> {
            String name = nameOf(stat.getMetadata());
            if (name != null) {
                articleIndex.put(name, stat);
            }
        }, error -> log.warn("Failed to load article stats", error));

        listAll(DailyStat.class).subscribe(stat -> {
            String name = nameOf(stat.getMetadata());
            if (name != null) {
                dailyIndex.put(name, stat);
            }
        }, error -> log.warn("Failed to load daily stats", error));

        listAll(Post.class).subscribe(post -> {
            String name = nameOf(post.getMetadata());
            if (name != null && Boolean.TRUE.equals(post.getSpec().getPublish())
                && !Boolean.TRUE.equals(post.getSpec().getDeleted())) {
                postIndex.put(name, post);
            }
        }, error -> log.warn("Failed to load posts", error), () -> loaded = true);
    }

    private <E extends run.halo.app.extension.Extension> Flux<E> listAll(Class<E> type) {
        return client.listAll(type, ListOptions.builder().build(), Sort.unsorted())
            .onErrorResume(error -> {
                log.debug("Listing {} failed: {}", type.getSimpleName(), error.toString());
                return Flux.empty();
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    private static String nameOf(run.halo.app.extension.MetadataOperator metadata) {
        return metadata == null ? null : metadata.getName();
    }

    /**
     * Refreshes official view counts. The site wide total sums every counter exactly the
     * way Halo's own siteStatsFinder does, because counters also exist for single pages
     * and other content; per-article numbers come from the post counters.
     */
    public void refreshOfficialCounts() {
        long[] total = {0L};
        Map<String, Long> perPost = new ConcurrentHashMap<>();
        int[] counterCount = {0};
        listAll(Counter.class).subscribe(counter -> {
            Integer visit = counter.getVisit();
            long value = visit == null ? 0L : visit;
            total[0] += value;
            counterCount[0]++;
            String raw = nameOf(counter.getMetadata());
            if (raw == null || !raw.startsWith(COUNTER_PREFIX)) {
                return;
            }
            String postName = raw.substring(COUNTER_PREFIX.length());
            perPost.put(postName, value);
            ArticleStat stat = articleIndex.get(postName);
            if (stat == null) {
                return;
            }
            if (Long.valueOf(value).equals(stat.getSpec().getOfficialViews())) {
                return;
            }
            stat.getSpec().setOfficialViews(value);
            saveArticle(stat);
        }, error -> log.debug("Failed to read official counters: {}", error.toString()),
            () -> {
                officialViewsTotal = total[0];
                counterCountTotal = counterCount[0];
                officialByPost.clear();
                officialByPost.putAll(perPost);
                log.debug("Official views: {} across {} counters", total[0], counterCount[0]);
            });
    }

    /** Sum of every Halo counter, matching the number Halo itself reports. */
    public long officialViewsTotal() {
        return officialViewsTotal;
    }

    /** Official visit count per post name, whether or not this plugin saw the visit. */
    public Map<String, Long> officialByPost() {
        return officialByPost;
    }

    public long appliedAdjustment() {
        return appliedAdjustment < 0 ? 0L : appliedAdjustment;
    }

    public String adjustmentError() {
        return adjustmentError;
    }

    /**
     * Writes the configured correction straight into Halo's counter data, so the
     * console dashboard and the theme both see the adjusted total. The value lives in a
     * dedicated counter that belongs to no post, which keeps real articles honest while
     * still being summed by Halo's own site statistics.
     */
    public void ensureAdjustment(long adjustment) {
        if (adjustment == appliedAdjustment) {
            return;
        }
        appliedAdjustment = adjustment;
        client.fetch(Counter.class, ADJUSTMENT_COUNTER)
            .flatMap(counter -> {
                if (adjustment <= 0) {
                    return client.delete(counter).then();
                }
                counter.setVisit(toVisit(adjustment));
                return client.update(counter).then();
            })
            .switchIfEmpty(Mono.defer(() -> {
                if (adjustment <= 0) {
                    return Mono.empty();
                }
                Counter counter = Counter.emptyCounter(ADJUSTMENT_COUNTER);
                counter.setVisit(toVisit(adjustment));
                return client.create(counter).then();
            }))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(unused -> {
                adjustmentError = null;
                log.debug("Applied official view adjustment {}", adjustment);
                refreshOfficialCounts();
            }, error -> {
                adjustmentError = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
                // Allow another attempt on the next cycle instead of giving up.
                appliedAdjustment = -1;
                log.warn("Failed to apply official view adjustment: {}", error.toString());
            });
    }

    private static int toVisit(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * Resolves the post for a heartbeat. Prefers the reported name, then falls back to
     * matching the permalink so a theme without the meta tag still gets attributed.
     */
    public Post resolvePost(String postName, String pageUrl) {
        if (postName != null && !postName.isBlank()) {
            Post post = postIndex.get(postName);
            if (post != null) {
                return post;
            }
            // The post may have been published after this plugin loaded its index.
            reloadPosts();
            post = postIndex.get(postName);
            if (post != null) {
                return post;
            }
        }
        if (pageUrl == null || pageUrl.isBlank()) {
            return null;
        }
        String path = pageUrl.split("\\?")[0];
        Post matched = matchByPath(path);
        if (matched != null) {
            return matched;
        }
        reloadPosts();
        return matchByPath(path);
    }

    private Post matchByPath(String path) {
        for (Post post : postIndex.values()) {
            String permalink = post.getStatus() == null ? null : post.getStatus().getPermalink();
            if (permalink != null && !permalink.isBlank()) {
                String normalized = permalink.endsWith("/") && permalink.length() > 1
                    ? permalink.substring(0, permalink.length() - 1) : permalink;
                if (path.equals(normalized)) {
                    return post;
                }
            }
            String slug = post.getSpec() == null ? null : post.getSpec().getSlug();
            if (slug != null && !slug.isBlank()
                && (path.equals("/archives/" + slug) || path.endsWith("/" + slug))) {
                return post;
            }
        }
        return null;
    }

    /** Re-reads the published posts, at most once every half minute. */
    public synchronized void reloadPosts() {
        long now = System.currentTimeMillis();
        if (now - lastPostLoadAt < 30_000L) {
            return;
        }
        lastPostLoadAt = now;
        try {
            List<Post> posts = client.listAll(Post.class, ListOptions.builder().build(),
                    Sort.unsorted()).collectList().block(Duration.ofSeconds(20));
            if (posts == null) {
                return;
            }
            Map<String, Post> refreshed = new ConcurrentHashMap<>();
            for (Post post : posts) {
                String name = nameOf(post.getMetadata());
                if (name != null && Boolean.TRUE.equals(post.getSpec().getPublish())
                    && !Boolean.TRUE.equals(post.getSpec().getDeleted())) {
                    refreshed.put(name, post);
                }
            }
            postIndex.clear();
            postIndex.putAll(refreshed);
            postLoadCount++;
            log.debug("Loaded {} published posts", refreshed.size());
        } catch (Exception e) {
            log.debug("Failed to reload posts: {}", e.toString());
        }
    }

    /**
     * Applies one heartbeat for one visitor. Heartbeats that cannot be tied to a post
     * are ignored, so only article pages are counted.
     */
    public void record(OnlineVisitor visitor, OnlineStatsSettings settings, long now) {
        lastHeartbeatUrl = visitor.getPageUrl();
        Post post = resolvePost(visitor.getPostName(), visitor.getPageUrl());
        if (post == null) {
            unresolvedHeartbeats++;
            return;
        }
        String postName = post.getMetadata().getName();
        String pageKey = visitor.getKey() + "|" + postName + "|" + visitor.getPageUrl();
        boolean newView = !pageKey.equals(lastCountedPage.get(visitor.getKey()));
        if (newView) {
            if (lastCountedPage.size() > MAX_VISITOR_KEYS) {
                lastCountedPage.clear();
            }
            lastCountedPage.put(visitor.getKey(), pageKey);
        }

        long durationDelta = 0L;
        String durationKey = visitor.getKey() + "|" + postName;
        Long previousBeat = lastHeartbeatAt.get(durationKey);
        if (previousBeat != null) {
            long delta = now - previousBeat;
            if (delta > 0 && delta <= MAX_DURATION_GAP_MS) {
                durationDelta = delta;
            }
        }
        if (lastHeartbeatAt.size() > MAX_VISITOR_KEYS) {
            lastHeartbeatAt.clear();
        }
        lastHeartbeatAt.put(durationKey, now);

        String browser = browserOf(visitor);
        String system = systemOf(visitor);
        String referer = refererHost(visitor.getReferer());
        String date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            .toLocalDate().format(DAY);

        DailyStat daily = day(date);
        boolean dayView = newView;

        updateDaily(daily, date, postName, visitor, browser, system, referer,
            durationDelta, dayView, settings);
        // Read the day back so today's per-article numbers include this heartbeat even
        // when the day record was just created above.
        updateArticle(post, visitor, browser, system, referer, durationDelta, newView,
            day(date), settings);
    }

    private void updateDaily(DailyStat existing, String date, String postName,
                             OnlineVisitor visitor, String browser, String system,
                             String referer, long durationDelta, boolean view,
                             OnlineStatsSettings settings) {
        DailyStat stat = existing;
        if (stat == null) {
            stat = new DailyStat();
            stat.setMetadata(new Metadata());
            stat.getMetadata().setName(date);
            stat.getSpec().setDate(date);
            stat.getSpec().setViews(0L);
            stat.getSpec().setVisitors(0L);
            stat.getSpec().setTotalOnlineMs(0L);
        }
        DailyStat.Spec spec = stat.getSpec();
        if (view) {
            spec.setViews(nvl(spec.getViews()) + 1);
            spec.getPosts().merge(postName, 1L, Long::sum);
            spec.getVisitorViews().merge(visitor.getKey(), 1L, Long::sum);
            spec.getBrowsers().merge(browser, 1L, Long::sum);
            spec.getSystems().merge(system, 1L, Long::sum);
            spec.getReferrers().merge(referer, 1L, Long::sum);
            trackSourceVisitor(spec, "browser", browser, visitor.getKey());
            trackSourceVisitor(spec, "system", system, visitor.getKey());
            trackSourceVisitor(spec, "referer", referer, visitor.getKey());
        }
        if (durationDelta > 0) {
            spec.setTotalOnlineMs(nvl(spec.getTotalOnlineMs()) + durationDelta);
        }
        spec.setVisitors((long) spec.getVisitorViews().size());
        if (settings.showIpInConsoleOrDefault() && visitor.getIp() != null) {
            spec.getVisitorIps().put(visitor.getKey(), visitor.getIp());
        }
        trimLong(spec.getBrowsers());
        trimLong(spec.getSystems());
        trimLong(spec.getReferrers());
        trimLong(spec.getPosts());
        trimLong(spec.getVisitorViews());
        trimString(spec.getVisitorIps());
        saveDaily(stat);
    }

    private void updateArticle(Post post, OnlineVisitor visitor, String browser, String system,
                               String referer, long durationDelta, boolean view,
                               DailyStat daily, OnlineStatsSettings settings) {
        String name = post.getMetadata().getName();
        ArticleStat stat = articleIndex.get(name);
        if (stat == null) {
            stat = new ArticleStat();
            stat.setMetadata(new Metadata());
            stat.getMetadata().setName(name);
            stat.getSpec().setPostName(name);
            stat.getSpec().setViews(0L);
            stat.getSpec().setVisitors(0L);
            stat.getSpec().setTotalOnlineMs(0L);
        }
        ArticleStat.Spec spec = stat.getSpec();
        spec.setSlug(post.getSpec() == null ? null : post.getSpec().getSlug());
        spec.setTitle(post.getSpec() == null ? null : post.getSpec().getTitle());
        spec.setPermalink(post.getStatus() == null ? null : post.getStatus().getPermalink());

        if (view) {
            spec.setViews(nvl(spec.getViews()) + 1);
            spec.getVisitorViews().merge(visitor.getKey(), 1L, Long::sum);
            spec.getBrowsers().merge(browser, 1L, Long::sum);
            spec.getSystems().merge(system, 1L, Long::sum);
            spec.getReferrers().merge(referer, 1L, Long::sum);
        }
        if (durationDelta > 0) {
            spec.setTotalOnlineMs(nvl(spec.getTotalOnlineMs()) + durationDelta);
            spec.getVisitorDurations().merge(visitor.getKey(), durationDelta, Long::sum);
        }
        spec.setVisitors((long) spec.getVisitorViews().size());
        spec.setFirstSeenAt(spec.getFirstSeenAt() == null
            ? visitor.getFirstSeenAt()
            : Math.min(spec.getFirstSeenAt(), visitor.getFirstSeenAt()));
        spec.setLastSeenAt(Math.max(nvl(spec.getLastSeenAt()), visitor.getLastSeenAt()));
        spec.getVisitorLastSeen().put(visitor.getKey(), visitor.getLastSeenAt());
        if (settings.showIpInConsoleOrDefault() && visitor.getIp() != null) {
            spec.getVisitorIps().put(visitor.getKey(), visitor.getIp());
        }

        // Today's numbers for one article come from the daily aggregate, which is the
        // only place that knows the calendar boundary.
        if (daily != null) {
            spec.setTodayViews(nvl(daily.getSpec().getPosts().get(name)));
            spec.setTodayVisitors((long) countTodayVisitors(spec, daily));
        }

        trimLong(spec.getBrowsers());
        trimLong(spec.getSystems());
        trimLong(spec.getReferrers());
        trimLong(spec.getVisitorViews());
        trimLong(spec.getVisitorDurations());
        trimLong(spec.getVisitorLastSeen());
        trimString(spec.getVisitorIps());
        saveArticle(stat);
    }

    private static int countTodayVisitors(ArticleStat.Spec spec, DailyStat daily) {
        int count = 0;
        for (String visitorKey : daily.getSpec().getVisitorViews().keySet()) {
            if (spec.getVisitorViews().containsKey(visitorKey)) {
                count++;
            }
        }
        return count;
    }

    /** Remembers which visitors used a browser / system / referrer value that day. */
    private static void trackSourceVisitor(DailyStat.Spec spec, String dimension,
                                           String value, String visitorKey) {
        String key = dimension + "|" + value;
        List<String> keys = spec.getSourceVisitors()
            .computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!keys.contains(visitorKey) && keys.size() < MAX_MAP_ENTRIES) {
            keys.add(visitorKey);
        }
    }

    private static String browserOf(OnlineVisitor visitor) {
        if (visitor.getUserAgent() == null || visitor.getUserAgent().isBlank()) {
            return "未知";
        }
        return UaParser.parse(visitor.getUserAgent()).getOrDefault("browser", "未知");
    }

    private static String systemOf(OnlineVisitor visitor) {
        if (visitor.getUserAgent() == null || visitor.getUserAgent().isBlank()) {
            return "未知";
        }
        return UaParser.parse(visitor.getUserAgent()).getOrDefault("os", "未知");
    }

    static String refererHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return "直接访问";
        }
        try {
            java.net.URI uri = java.net.URI.create(referer);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "直接访问";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "直接访问";
        }
    }

    private static void trimLong(Map<String, Long> map) {
        if (map.size() <= MAX_MAP_ENTRIES) {
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparingLong(
            (Map.Entry<String, Long> entry) -> entry.getValue()).reversed());
        Map<String, Long> kept = new LinkedHashMap<>();
        for (int i = 0; i < MAX_MAP_ENTRIES; i++) {
            kept.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        map.clear();
        map.putAll(kept);
    }

    private static void trimString(Map<String, String> map) {
        if (map.size() <= MAX_MAP_ENTRIES) {
            return;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (index++ >= MAX_MAP_ENTRIES) {
                break;
            }
            kept.put(entry.getKey(), entry.getValue());
        }
        map.clear();
        map.putAll(kept);
    }

    public synchronized void saveArticle(ArticleStat stat) {
        String name = stat.getMetadata().getName();
        boolean isNew = !articleIndex.containsKey(name);
        try {
            ArticleStat result = isNew
                ? client.create(stat).block(Duration.ofSeconds(10))
                : client.update(stat).block(Duration.ofSeconds(10));
            articleIndex.put(name, result != null ? result : stat);
        } catch (Exception e) {
            if (isNew) {
                try {
                    ArticleStat existing = client.fetch(ArticleStat.class, name)
                        .block(Duration.ofSeconds(5));
                    if (existing != null) {
                        articleIndex.put(name, existing);
                    }
                } catch (Exception ignored) {
                    log.debug("Failed to save article stat {}", name, e);
                }
            } else {
                log.debug("Failed to update article stat {}", name, e);
            }
        }
    }

    public synchronized void saveDaily(DailyStat stat) {
        String name = stat.getMetadata().getName();
        boolean isNew = !dailyIndex.containsKey(name);
        try {
            DailyStat result = isNew
                ? client.create(stat).block(Duration.ofSeconds(10))
                : client.update(stat).block(Duration.ofSeconds(10));
            dailyIndex.put(name, result != null ? result : stat);
        } catch (Exception e) {
            if (isNew) {
                try {
                    DailyStat existing = client.fetch(DailyStat.class, name)
                        .block(Duration.ofSeconds(5));
                    if (existing != null) {
                        dailyIndex.put(name, existing);
                    }
                } catch (Exception ignored) {
                    log.debug("Failed to save daily stat {}", name, e);
                }
            } else {
                log.debug("Failed to update daily stat {}", name, e);
            }
        }
    }

    public void clear() {
        articleIndex.clear();
        dailyIndex.clear();
        postIndex.clear();
        lastCountedPage.clear();
        lastHeartbeatAt.clear();
        loaded = false;
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * State of the aggregation pipeline, surfaced in the console so an empty dashboard
     * can be explained without reading server logs.
     */
    public Map<String, Object> diagnostics() {
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("loaded", loaded);
        info.put("posts", postIndex.size());
        info.put("postLoads", postLoadCount);
        info.put("articles", articleIndex.size());
        info.put("days", dailyIndex.size());
        info.put("unresolvedHeartbeats", unresolvedHeartbeats);
        info.put("lastHeartbeatUrl", lastHeartbeatUrl);
        info.put("officialViewsTotal", officialViewsTotal);
        info.put("counters", counterCountTotal);
        info.put("officialPosts", officialByPost.size());
        return info;
    }
}