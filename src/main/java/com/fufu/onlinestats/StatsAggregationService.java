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

    /** Per-device maps keep the same ceiling as the record list, not the label ceiling. */
    private static final int MAX_DEVICE_ENTRIES = MAX_VISITOR_KEYS;
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

    private final java.util.concurrent.atomic.AtomicLong statSaveFailures =
        new java.util.concurrent.atomic.AtomicLong();

    private final java.util.concurrent.atomic.AtomicLong indexLoadFailures =
        new java.util.concurrent.atomic.AtomicLong();

    private volatile String lastIndexLoadError;

    private volatile String lastStatSaveError;

    private volatile int indexRefreshRuns;
    private volatile long officialViewsTotal;
    private volatile int counterCountTotal;
    private volatile long appliedAdjustment = -1L;
    private volatile String adjustmentError;
    private final Map<String, Long> officialByPost = new ConcurrentHashMap<>();

    private final java.util.concurrent.ExecutorService recordExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "online-stats-aggregate");
            thread.setDaemon(true);
            return thread;
        });
    private final java.util.concurrent.atomic.AtomicInteger pendingRecords =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_PENDING_RECORDS = 5000;

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

    /**
     * Takes one device out of the stored totals, so a cleared visitor stops being counted
     * on the statistics page as well as the list.
     *
     * <p>Both kinds of statistic keep a per-device breakdown, which is what makes this
     * exact: the device's own contribution is subtracted and the totals are taken from
     * what is left. Only days and articles the device actually contributed to are
     * touched.</p>
     *
     * @return how many stored statistics were adjusted
     */
    public int forgetVisitor(String visitorKey) {
        if (visitorKey == null || visitorKey.isBlank()) {
            return 0;
        }
        int touched = 0;
        for (DailyStat day : new ArrayList<>(dailyIndex.values())) {
            if (subtractVisitor(day.getSpec(), visitorKey) && updateQuietly(day)) {
                touched++;
            }
        }
        for (ArticleStat article : new ArrayList<>(articleIndex.values())) {
            if (subtractVisitor(article.getSpec(), visitorKey) && updateQuietly(article)) {
                touched++;
            }
        }
        if (touched > 0) {
            log.debug("Removed {} from {} stored statistics", visitorKey, touched);
        }
        return touched;
    }

    private boolean updateQuietly(run.halo.app.extension.Extension extension) {
        try {
            client.update(extension).block(java.time.Duration.ofSeconds(10));
            return true;
        } catch (Exception e) {
            log.warn("Failed to adjust a statistic after a visitor was cleared", e);
            return false;
        }
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * Removes one device from a daily total.
     *
     * @return true when this day actually counted the device
     */
    static boolean subtractVisitor(DailyStat.Spec spec, String visitorKey) {
        if (spec == null || spec.getVisitorViews() == null
            || !spec.getVisitorViews().containsKey(visitorKey)) {
            return false;
        }
        Long views = spec.getVisitorViews().remove(visitorKey);
        spec.getVisitorIps().remove(visitorKey);
        if (views != null) {
            spec.setViews(Math.max(0L, nvl(spec.getViews()) - views));
        }
        spec.setVisitors((long) spec.getVisitorViews().size());
        return true;
    }

    /**
     * Removes one device from an article total, which keeps its own per-device numbers.
     *
     * @return true when this article actually counted the device
     */
    static boolean subtractVisitor(ArticleStat.Spec spec, String visitorKey) {
        if (spec == null || spec.getVisitorViews() == null
            || !spec.getVisitorViews().containsKey(visitorKey)) {
            return false;
        }
        Long views = spec.getVisitorViews().remove(visitorKey);
        Long duration = spec.getVisitorDurations().remove(visitorKey);
        spec.getVisitorLastSeen().remove(visitorKey);
        if (views != null) {
            spec.setViews(Math.max(0L, nvl(spec.getViews()) - views));
        }
        if (duration != null) {
            spec.setTotalOnlineMs(Math.max(0L, nvl(spec.getTotalOnlineMs()) - duration));
        }
        spec.setVisitors((long) spec.getVisitorViews().size());
        return true;
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

    /**
     * Reads every stored document of one type.
     *
     * <p>A listing can fail simply because the plugin starts before the resource type is
     * ready. That used to be swallowed at debug level, leaving an empty index that showed
     * up as rows with no numbers — the data was in the store all along. The failure is
     * counted now, and the scheduled refresh reads it back.</p>
     */
    private <E extends run.halo.app.extension.Extension> Flux<E> listAll(Class<E> type) {
        return client.listAll(type, ListOptions.builder().build(), Sort.unsorted())
            .onErrorResume(error -> {
                indexLoadFailures.incrementAndGet();
                lastIndexLoadError = type.getSimpleName() + ": " + error.getMessage();
                log.warn("Listing {} failed", type.getSimpleName(), error);
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
    public synchronized void ensureAdjustment(long adjustment) {
        if (adjustment == appliedAdjustment) {
            return;
        }
        appliedAdjustment = adjustment;
        client.fetch(Counter.class, ADJUSTMENT_COUNTER)
            // Both branches have to emit a value: Mono#then() completes empty, and an
            // empty completion would make switchIfEmpty run the create branch even
            // though the counter had just been updated, which Halo rejects with 400
            // "Duplicate name detected.".
            .flatMap(counter -> {
                if (adjustment <= 0) {
                    return client.delete(counter).thenReturn(Boolean.TRUE);
                }
                counter.setVisit(toVisit(adjustment));
                return client.update(counter).thenReturn(Boolean.TRUE);
            })
            .switchIfEmpty(Mono.defer(() -> {
                if (adjustment <= 0) {
                    return Mono.just(Boolean.FALSE);
                }
                Counter counter = Counter.emptyCounter(ADJUSTMENT_COUNTER);
                counter.setVisit(toVisit(adjustment));
                return client.create(counter).thenReturn(Boolean.TRUE);
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
     * Queues a heartbeat for aggregation. Writing to the extension store blocks, and the
     * caller is a Netty event loop thread where blocking is forbidden, so the work is
     * handed to a single background thread. One thread also keeps heartbeats in order,
     * which matters for counting a page entry exactly once.
     */
    public void recordAsync(OnlineVisitor visitor, OnlineStatsSettings settings, long now) {
        if (pendingRecords.get() >= MAX_PENDING_RECORDS) {
            log.debug("Dropping heartbeat aggregation, queue is full");
            return;
        }
        pendingRecords.incrementAndGet();
        try {
            recordExecutor.execute(() -> {
                try {
                    record(visitor, settings, now);
                } catch (Exception e) {
                    log.debug("Failed to aggregate heartbeat: {}", e.toString());
                } finally {
                    pendingRecords.decrementAndGet();
                }
            });
        } catch (Exception e) {
            pendingRecords.decrementAndGet();
            log.debug("Failed to queue heartbeat aggregation: {}", e.toString());
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
            /* Not an article. The visit still happened, so it is counted as a page view;
             * only the per-article total has nothing to add to. Dropping these was why
             * the dashboard never matched what the site actually served. */
            unresolvedHeartbeats++;
        }
        String postName = post == null ? null : post.getMetadata().getName();
        String pageKey = visitor.getKey() + "|" + (postName == null ? "page" : postName)
            + "|" + visitor.getPageUrl();
        boolean newView = !pageKey.equals(lastCountedPage.get(visitor.getKey()));
        if (newView) {
            if (lastCountedPage.size() > MAX_VISITOR_KEYS) {
                lastCountedPage.clear();
            }
            lastCountedPage.put(visitor.getKey(), pageKey);
        }

        long durationDelta = 0L;
        String durationKey = visitor.getKey() + "|" + (postName == null ? "page" : postName);
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
        if (post != null) {
            // Read the day back so today's per-article numbers include this heartbeat
            // even when the day record was just created above.
            updateArticle(post, visitor, browser, system, referer, durationDelta, newView,
                day(date), settings);
        }
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
        String pageName = pageKeyOf(visitor);
        if (view) {
            spec.setViews(nvl(spec.getViews()) + 1);
            if (postName != null) {
                spec.getPosts().merge(postName, 1L, Long::sum);
            }
            spec.pagesOrEmpty().merge(pageName, 1L, Long::sum);
            spec.getVisitorViews().merge(visitor.getKey(), 1L, Long::sum);
            spec.getBrowsers().merge(browser, 1L, Long::sum);
            spec.getSystems().merge(system, 1L, Long::sum);
            spec.getReferrers().merge(referer, 1L, Long::sum);
            trackSourceVisitor(spec, "browser", browser, visitor.getKey());
            trackSourceVisitor(spec, "system", system, visitor.getKey());
            trackSourceVisitor(spec, "referer", referer, visitor.getKey());
            trackSourceVisitor(spec, "page", pageName, visitor.getKey());
        }
        if (durationDelta > 0) {
            spec.setTotalOnlineMs(nvl(spec.getTotalOnlineMs()) + durationDelta);
            spec.durationsOrEmpty().merge(visitor.getKey(), durationDelta, Long::sum);
        }
        /* The visitor count only ever grows within a day. Reading it back from a map is
         * fine while nothing trims that map, but a counted visitor must never disappear
         * because a write came back slightly behind. */
        spec.setVisitors(Math.max(nvl(spec.getVisitors()),
            (long) spec.getVisitorViews().size()));
        if (settings.showIpInConsoleOrDefault() && visitor.getIp() != null) {
            spec.getVisitorIps().put(visitor.getKey(), visitor.getIp());
        }
        trimLong(spec.getBrowsers());
        trimLong(spec.getSystems());
        trimLong(spec.getReferrers());
        trimLong(spec.getPosts());
        trimLong(spec.pagesOrEmpty());
        trimLong(spec.durationsOrEmpty(), MAX_DEVICE_ENTRIES);
        trimLong(spec.getVisitorViews(), MAX_DEVICE_ENTRIES);
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
        spec.setVisitors(Math.max(nvl(spec.getVisitors()),
            (long) spec.getVisitorViews().size()));
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
            String today = daily.getSpec().getDate();
            long todayViews = mergeToday(nvl(daily.getSpec().getPosts().get(name)),
                nvl(spec.getTodayViews()), spec.getTodayDate(), today);
            long todayVisitors = mergeToday((long) countTodayVisitors(spec, daily),
                nvl(spec.getTodayVisitors()), spec.getTodayDate(), today);
            spec.setTodayDate(today);
            spec.setTodayViews(todayViews);
            spec.setTodayVisitors(todayVisitors);
        }

        trimLong(spec.getBrowsers());
        trimLong(spec.getSystems());
        trimLong(spec.getReferrers());
        trimLong(spec.getVisitorViews(), MAX_DEVICE_ENTRIES);
        trimLong(spec.getVisitorDurations(), MAX_DEVICE_ENTRIES);
        trimLong(spec.getVisitorLastSeen(), MAX_DEVICE_ENTRIES);
        trimString(spec.getVisitorIps());
        saveArticle(stat);
    }

    /**
     * Today's figure for one article. Within a day it may only climb: a day record read
     * back after a conflict is sometimes a beat behind, and writing that value out gave
     * the ranking a way to fall from 90 to 60 while nothing had been deleted. A new day
     * starts from the day record.
     */
    static long mergeToday(long fromDaily, long stored, String storedDate, String today) {
        if (today != null && today.equals(storedDate)) {
            return Math.max(fromDaily, stored);
        }
        return fromDaily;
    }

    /**
     * How one page is named in the counters: its title, plus the route it lives at so
     * two pages sharing a title stay apart.
     */
    public static String pageKeyOf(OnlineVisitor visitor) {
        return pageKey(visitor.getPageTitle(), visitor.getPageUrl());
    }

    public static String pageKey(String title, String url) {
        String route = routeOf(url);
        String name = title == null || title.isBlank() ? route : title;
        if (name == null || name.isBlank()) {
            return "未知页面";
        }
        if (route == null || route.isBlank() || route.equals(name)) {
            return name;
        }
        return name + " (" + route + ")";
    }

    private static String routeOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String value = url;
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            int slash = value.indexOf('/', scheme + 3);
            value = slash < 0 ? "/" : value.substring(slash);
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        return value.isBlank() ? "/" : value;
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
        trimLong(map, MAX_MAP_ENTRIES);
    }

    private static void trimLong(Map<String, Long> map, int limit) {
        if (map.size() <= limit) {
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparingLong(
            (Map.Entry<String, Long> entry) -> entry.getValue()).reversed());
        Map<String, Long> kept = new LinkedHashMap<>();
        for (int i = 0; i < limit; i++) {
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
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ArticleStat result = isNew
                    ? client.create(stat).block(Duration.ofSeconds(10))
                    : client.update(stat).block(Duration.ofSeconds(10));
                articleIndex.put(name, result != null ? result : stat);
                return;
            } catch (Exception e) {
                noteStatSaveFailure(e);
                // Same rule as the daily record: the counters in memory must not be
                // dropped because somebody else wrote this article first.
                ArticleStat existing = fetchArticleQuietly(name);
                if (existing == null) {
                    if (isNew) {
                        log.debug("Failed to create article stat {}", name, e);
                        return;
                    }
                    articleIndex.remove(name);
                    isNew = true;
                    continue;
                }
                mergeInto(existing, stat);
                stat = existing;
                isNew = false;
            }
        }
        log.debug("Gave up saving article stat {} after three attempts", name);
    }

    private void noteStatSaveFailure(Exception error) {
        statSaveFailures.incrementAndGet();
        String message = error == null ? "unknown" : String.valueOf(error.getMessage());
        lastStatSaveError = message.length() > 300 ? message.substring(0, 300) : message;
        log.warn("A statistic could not be stored: {}", message, error);
    }

    /**
     * Re-reads both statistics from the store. Listing can fail once without anyone
     * noticing — the failure is only logged — and then the page shows empty rows for
     * data that is still there. Running this on a timer lets the page heal itself.
     */
    public void refreshIndexes() {
        indexRefreshRuns++;
        listAll(ArticleStat.class).subscribe(stat -> {
            String name = nameOf(stat.getMetadata());
            if (name != null) {
                articleIndex.put(name, stat);
            }
        }, error -> log.warn("Failed to refresh article stats", error));

        listAll(DailyStat.class).subscribe(stat -> {
            String name = nameOf(stat.getMetadata());
            if (name != null) {
                dailyIndex.put(name, stat);
            }
        }, error -> log.warn("Failed to refresh daily stats", error));
    }

    private ArticleStat fetchArticleQuietly(String name) {
        try {
            return client.fetch(ArticleStat.class, name).block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.debug("Could not read article stat {} back", name, e);
            return null;
        }
    }

    private static void mergeInto(ArticleStat target, ArticleStat mine) {
        ArticleStat.Spec into = target.getSpec();
        ArticleStat.Spec from = mine.getSpec();
        if (into == null || from == null) {
            return;
        }
        into.setViews(Math.max(nvl(into.getViews()), nvl(from.getViews())));
        into.setVisitors(Math.max(nvl(into.getVisitors()), nvl(from.getVisitors())));
        into.setTotalOnlineMs(Math.max(nvl(into.getTotalOnlineMs()),
            nvl(from.getTotalOnlineMs())));
        // Today's numbers may only go up within a day; a stale copy must not drag the
        // ranking back down, which is what made a page show 90 views and later 60.
        if (into.getTodayDate() != null && into.getTodayDate().equals(from.getTodayDate())) {
            into.setTodayViews(Math.max(nvl(into.getTodayViews()), nvl(from.getTodayViews())));
            into.setTodayVisitors(Math.max(nvl(into.getTodayVisitors()),
                nvl(from.getTodayVisitors())));
        } else {
            into.setTodayDate(from.getTodayDate());
            into.setTodayViews(nvl(from.getTodayViews()));
            into.setTodayVisitors(nvl(from.getTodayVisitors()));
        }
        mergeMax(into.getBrowsers(), from.getBrowsers());
        mergeMax(into.getSystems(), from.getSystems());
        mergeMax(into.getReferrers(), from.getReferrers());
        mergeMax(into.getVisitorViews(), from.getVisitorViews());
        mergeMax(into.getVisitorDurations(), from.getVisitorDurations());
        mergeMax(into.getVisitorLastSeen(), from.getVisitorLastSeen());
    }

    public synchronized void saveDaily(DailyStat stat) {
        String name = stat.getMetadata().getName();
        boolean isNew = !dailyIndex.containsKey(name);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                DailyStat result = isNew
                    ? client.create(stat).block(Duration.ofSeconds(10))
                    : client.update(stat).block(Duration.ofSeconds(10));
                dailyIndex.put(name, result != null ? result : stat);
                return;
            } catch (Exception e) {
                noteStatSaveFailure(e);
                /* The write carries counters accumulated in memory, so giving up loses
                 * them for good. A conflict means someone else wrote this day (another
                 * instance, or a request that finished first): take their version and
                 * put ours on top of it, then try again. */
                DailyStat existing = fetchQuietly(name);
                if (existing == null) {
                    if (isNew) {
                        log.debug("Failed to create daily stat {}", name, e);
                        return;
                    }
                    dailyIndex.remove(name);
                    isNew = true;
                    continue;
                }
                mergeInto(existing, stat);
                stat = existing;
                isNew = false;
            }
        }
        log.debug("Gave up saving daily stat {} after three attempts", name);
    }

    private DailyStat fetchQuietly(String name) {
        try {
            return client.fetch(DailyStat.class, name).block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.debug("Could not read daily stat {} back", name, e);
            return null;
        }
    }

    /**
     * Folds the numbers accumulated in memory into the version that came back from the
     * store, so a retry never writes less than what was already counted.
     */
    private static void mergeInto(DailyStat target, DailyStat mine) {
        DailyStat.Spec into = target.getSpec();
        DailyStat.Spec from = mine.getSpec();
        if (into == null || from == null) {
            return;
        }
        into.setViews(Math.max(nvl(into.getViews()), nvl(from.getViews())));
        into.setVisitors(Math.max(nvl(into.getVisitors()), nvl(from.getVisitors())));
        into.setTotalOnlineMs(Math.max(nvl(into.getTotalOnlineMs()),
            nvl(from.getTotalOnlineMs())));
        mergeMax(into.getPosts(), from.getPosts());
        mergeMax(into.getBrowsers(), from.getBrowsers());
        mergeMax(into.getSystems(), from.getSystems());
        mergeMax(into.getReferrers(), from.getReferrers());
        mergeMax(into.getCountries(), from.getCountries());
        mergeMax(into.getVisitorViews(), from.getVisitorViews());
        from.getVisitorIps().forEach(into.getVisitorIps()::putIfAbsent);
    }

    private static void mergeMax(Map<String, Long> into, Map<String, Long> from) {
        from.forEach((key, value) -> into.merge(key, value, Math::max));
    }

    public void clear() {
        articleIndex.clear();
        dailyIndex.clear();
        postIndex.clear();
        lastCountedPage.clear();
        lastHeartbeatAt.clear();
        officialByPost.clear();
        appliedAdjustment = -1L;
        adjustmentError = null;
        loaded = false;
    }

    /**
     * Throws away every stored statistic, so counting starts from zero.
     *
     * <p>Used by the "clear everything" action: its promise is that nothing from before
     * is still counted anywhere, which only holds if the day and article totals go too.</p>
     *
     * @return how many stored statistics were deleted
     */
    public synchronized int reset() {
        int removed = 0;
        for (DailyStat day : new ArrayList<>(dailyIndex.values())) {
            try {
                client.delete(day).block(Duration.ofSeconds(10));
                removed++;
            } catch (Exception e) {
                log.warn("Failed to delete a daily statistic", e);
            }
        }
        for (ArticleStat article : new ArrayList<>(articleIndex.values())) {
            try {
                client.delete(article).block(Duration.ofSeconds(10));
                removed++;
            } catch (Exception e) {
                log.warn("Failed to delete an article statistic", e);
            }
        }
        clear();
        try {
            ensureAdjustment(0L);
        } catch (Exception e) {
            log.debug("Could not reset the official view adjustment", e);
        }
        // The post list is metadata, not a statistic; read it back so the page is usable.
        reloadPosts();
        return removed;
    }

    /** Releases the aggregation thread when the plugin stops. */
    public void shutdown() {
        recordExecutor.shutdownNow();
        clear();
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
        info.put("statSaveFailures", statSaveFailures.get());
        info.put("lastStatSaveError", lastStatSaveError);
        info.put("indexRefreshes", indexRefreshRuns);
        info.put("indexLoadFailures", indexLoadFailures.get());
        info.put("lastIndexLoadError", lastIndexLoadError);
        return info;
    }
}