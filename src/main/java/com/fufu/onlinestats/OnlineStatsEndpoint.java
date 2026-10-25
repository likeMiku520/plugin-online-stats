package com.fufu.onlinestats;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * Public and console APIs for the online-stats plugin.
 */
@Component
public class OnlineStatsEndpoint implements CustomEndpoint {

    public static final String GROUP_VERSION = "online-stats.halo.run/v1alpha1";

    private static final int RATE_LIMIT_PER_MINUTE = 60;
    private static final int CHAT_RATE_LIMIT_PER_MINUTE = 60;
    private static final long RATE_WINDOW_MS = 60_000L;
    private static final int MAX_RATE_BUCKETS = 10_000;

    private static final int MAX_VISITOR_ID = 64;
    private static final int MAX_URL = 500;
    private static final int MAX_TITLE = 200;
    private static final int MAX_SHORT = 120;
    private static final int MAX_CHAT_TEXT = ChatMessageService.MAX_TEXT_LENGTH;
    private static final int CHAT_POLL_LIMIT = 50;
    private static final int CHAT_POLL_SECONDS = 5;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OnlineVisitorRegistry registry;
    private final VisitorRecordService recordService;
    private final IpGeoService ipGeoService;
    private final StatsAggregationService statsService;
    private final StatisticsQueryService queryService;
    private final ChatMessageService chatService;
    private final ReactiveSettingFetcher settingFetcher;

    private static final Logger log = LoggerFactory.getLogger(OnlineStatsEndpoint.class);

    private final Map<String, long[]> rateBuckets = new ConcurrentHashMap<>();
    private final Map<String, long[]> chatRateBuckets = new ConcurrentHashMap<>();

    public OnlineStatsEndpoint(OnlineVisitorRegistry registry,
                               VisitorRecordService recordService,
                               IpGeoService ipGeoService,
                               StatsAggregationService statsService,
                               StatisticsQueryService queryService,
                               ChatMessageService chatService,
                               ReactiveSettingFetcher settingFetcher) {
        this.registry = registry;
        this.recordService = recordService;
        this.ipGeoService = ipGeoService;
        this.statsService = statsService;
        this.queryService = queryService;
        this.chatService = chatService;
        this.settingFetcher = settingFetcher;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(GROUP_VERSION);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/online-count", this::getOnlineCount)
            .GET("/online-pages", this::getOnlinePages)
            .POST("/heartbeat", this::postHeartbeat)
            .GET("/online-stats", this::getScript)
            .GET("/visitors", this::listVisitors)
            .GET("/records", this::listRecords)
            .GET("/records/{name}", this::getRecord)
            .GET("/location", this::getLocation)
            .GET("/statistics/overview", this::getOverview)
            .GET("/statistics/trend", this::getTrend)
            .GET("/statistics/articles", this::getArticles)
            .GET("/statistics/articles/{postName}", this::getArticleDetail)
            .GET("/statistics/sources", this::getSources)
            .GET("/statistics/sources/detail", this::getSourceDetail)
            .GET("/statistics/visitors/{visitorKey}", this::getVisitorDetail)
            .GET("/chat-inbox", this::getChatInbox)
            .POST("/chat-reply", this::postChatReply)
            .GET("/chat-conversations", this::getChatConversations)
            .GET("/chat-messages", this::getChatMessages)
            .POST("/chat-send", this::postChatSend)
            .build();
    }

    private Mono<ServerResponse> getOnlineCount(ServerRequest request) {
        return fetchSettings()
            .flatMap(settings -> ServerResponse.ok()
                .bodyValue(Map.of("count", registry.count(settings.onlineTimeoutMillis()))));
    }

    private Mono<ServerResponse> getOnlinePages(ServerRequest request) {
        return fetchSettings()
            .flatMap(settings -> {
                long timeout = settings.onlineTimeoutMillis();
                var pages = registry.list(timeout).stream()
                    .map(OnlineVisitor::getPageTitle)
                    .filter(title -> title != null && !title.isBlank())
                    .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new,
                        Collectors.counting()))
                    .entrySet().stream()
                    .map(e -> new PageView(e.getKey(), e.getValue().intValue()))
                    .toList();
                return ServerResponse.ok()
                    .bodyValue(Map.of("total", registry.count(timeout), "pages", pages));
            });
    }

    private Mono<ServerResponse> postHeartbeat(ServerRequest request) {
        String ip = resolveClientIp(request);
        if (isRateLimited(ip)) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        String userAgent = request.headers().firstHeader(HttpHeaders.USER_AGENT);
        return request.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
            })
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(Map.of())
            .flatMap(body -> fetchSettings().flatMap(settings -> {
                long now = System.currentTimeMillis();
                OnlineVisitor visitor = registry.heartbeat(ip, userAgent, toHeartbeat(body),
                    now, settings.onlineTimeoutMillis());
                statsService.recordAsync(visitor, settings, now);
                return ServerResponse.ok().bodyValue(Map.of("ok", true));
            }));
    }

    private OnlineVisitorRegistry.Heartbeat toHeartbeat(Map<String, Object> body) {
        return new OnlineVisitorRegistry.Heartbeat(
            str(body, "visitorId", MAX_VISITOR_ID),
            str(body, "postName", MAX_SHORT),
            str(body, "url", MAX_URL),
            str(body, "title", MAX_TITLE),
            str(body, "referer", MAX_URL),
            str(body, "screen", MAX_SHORT),
            str(body, "viewport", MAX_SHORT),
            str(body, "timezone", MAX_SHORT),
            str(body, "language", MAX_SHORT),
            str(body, "platform", MAX_SHORT),
            str(body, "cores", MAX_SHORT),
            str(body, "memory", MAX_SHORT),
            str(body, "connection", MAX_SHORT));
    }

    private static String str(Map<String, Object> body, String key, int max) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private boolean isRateLimited(String ip) {
        return isRateLimited(rateBuckets, ip, RATE_LIMIT_PER_MINUTE);
    }

    private boolean isChatRateLimited(String ip) {
        return isRateLimited(chatRateBuckets, ip, CHAT_RATE_LIMIT_PER_MINUTE);
    }

    private static boolean isRateLimited(Map<String, long[]> buckets, String key, int limit) {
        long now = System.currentTimeMillis();
        if (buckets.size() > MAX_RATE_BUCKETS) {
            buckets.entrySet().removeIf(e -> now - e.getValue()[0] >= RATE_WINDOW_MS);
        }
        long[] bucket = buckets.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing[0] >= RATE_WINDOW_MS) {
                return new long[] {now, 1};
            }
            existing[1]++;
            return existing;
        });
        return bucket[1] > limit;
    }

    private Mono<ServerResponse> getScript(ServerRequest request) {
        return fetchSettings()
            .flatMap(settings -> {
                String config = "window.__ONLINE_STATS__ = { heartbeatInterval: "
                    + settings.heartbeatIntervalOrDefault()
                    + ", chatEnabled: " + settings.chatEnabledOrDefault()
                    + ", chatPollSeconds: " + CHAT_POLL_SECONDS + " };\n";
                String content = config + readScript();
                return ServerResponse.ok()
                    .contentType(
                        MediaType.parseMediaType("application/javascript; charset=utf-8"))
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0")
                    .bodyValue(content);
            });
    }

    private Mono<ServerResponse> listVisitors(ServerRequest request) {
        return fetchSettings()
            .flatMap(settings -> {
                long timeout = settings.onlineTimeoutMillis();
                boolean showIp = settings.showIpInConsoleOrDefault();
                var items = registry.list(timeout).stream()
                    .map(v -> toLiveView(v, showIp))
                    .toList();
                return ServerResponse.ok()
                    .bodyValue(Map.of("total", items.size(), "items", items));
            });
    }

    private Mono<ServerResponse> listRecords(ServerRequest request) {
        return fetchSettings().flatMap(settings -> {
            int page = positiveInt(request.queryParam("page").orElse(null), 1);
            int size = Math.min(positiveInt(request.queryParam("size").orElse(null),
                DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);
            String keyword = request.queryParam("keyword").orElse("").trim()
                .toLowerCase(Locale.ROOT);
            String status = request.queryParam("status").orElse("all");
            String type = request.queryParam("type").orElse("all");
            String sort = request.queryParam("sort").orElse("lastSeenAt");
            long timeout = settings.onlineTimeoutMillis();
            boolean showIp = settings.showIpInConsoleOrDefault();

            long onlineCutoff = System.currentTimeMillis() - timeout;
            List<VisitorRecord> all = recordService.list();
            List<OnlineVisitor> live = registry.list(timeout);
            Map<String, OnlineVisitor> liveByKey = new LinkedHashMap<>();
            for (OnlineVisitor visitor : live) {
                liveByKey.put(visitor.getKey(), visitor);
            }

            // Records are written every 20 seconds; merging the in-memory visitors in
            // means a brand new visitor shows up in the table immediately.
            Map<String, RecordView> merged = new LinkedHashMap<>();
            for (VisitorRecord record : all) {
                String name = record.getMetadata().getName();
                merged.put(name, toRecordView(record, liveByKey.get(name), onlineCutoff, showIp));
            }
            for (OnlineVisitor visitor : live) {
                if (!merged.containsKey(visitor.getKey())) {
                    merged.put(visitor.getKey(), toRecordView(visitor, showIp));
                }
            }

            List<RecordView> views = new ArrayList<>();
            for (RecordView view : merged.values()) {
                if ("online".equals(status) && !view.online()) {
                    continue;
                }
                if ("offline".equals(status) && view.online()) {
                    continue;
                }
                if ("human".equals(type) && view.bot()) {
                    continue;
                }
                if ("bot".equals(type) && !view.bot()) {
                    continue;
                }
                if (!keyword.isEmpty() && !matches(view, keyword)) {
                    continue;
                }
                views.add(view);
            }
            Comparator<RecordView> comparator = switch (sort) {
                case "firstSeenAt" -> Comparator.comparing(
                    (RecordView v) -> v.firstSeenAt() == null ? 0L : v.firstSeenAt());
                case "visits" -> Comparator.comparing(
                    (RecordView v) -> v.visitCount() == null ? 0 : v.visitCount());
                default -> Comparator.comparing(
                    (RecordView v) -> v.lastSeenAt() == null ? 0L : v.lastSeenAt());
            };
            views.sort(comparator.reversed());

            int total = views.size();
            int from = Math.min((page - 1) * size, total);
            int to = Math.min(from + size, total);
            List<RecordView> pageItems = views.subList(from, to);

            Map<String, Object> stats = new LinkedHashMap<>();
            int onlineHumans = 0;
            int onlineBots = 0;
            for (OnlineVisitor visitor : live) {
                if (isBotVisitor(visitor)) {
                    onlineBots++;
                } else {
                    onlineHumans++;
                }
            }
            int[] today = todayCounts(all);
            int bots = 0;
            for (RecordView view : merged.values()) {
                if (view.bot()) {
                    bots++;
                }
            }
            stats.put("online", live.size());
            stats.put("onlineHumans", onlineHumans);
            stats.put("onlineBots", onlineBots);
            stats.put("total", total);
            stats.put("historyTotal", merged.size());
            stats.put("humans", merged.size() - bots);
            stats.put("bots", bots);
            stats.put("todayNew", today[0] + today[1]);
            stats.put("todayHumans", today[0]);
            stats.put("todayBots", today[1]);
            stats.putAll(recordService.diagnostics());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("total", total);
            payload.put("page", page);
            payload.put("size", size);
            payload.put("stats", stats);
            payload.put("items", pageItems);
            return ServerResponse.ok().bodyValue(payload);
        });
    }

    private Mono<ServerResponse> getRecord(ServerRequest request) {
        String name = request.pathVariable("name");
        return fetchSettings().flatMap(settings -> {
            VisitorRecord record = recordService.get(name);
            if (record == null) {
                return ServerResponse.notFound().build();
            }
            return ServerResponse.ok()
                .bodyValue(toRecordView(record, null,
                    System.currentTimeMillis() - settings.onlineTimeoutMillis(),
                    settings.showIpInConsoleOrDefault()));
        });
    }

    /**
     * Resolves the location of one visitor on demand. Only invoked from the console
     * lookup button, so no third party request happens unless a user asks for it.
     */
    private Mono<ServerResponse> getLocation(ServerRequest request) {
        String recordName = request.queryParam("name").orElse("");
        String queryIp = request.queryParam("ip").orElse("");
        return fetchSettings().flatMap(settings -> {
            String usedEndpoint = settings.geoEndpointOrDefault();
            ipGeoService.configure(usedEndpoint);
            String ip = queryIp;
            if (ip.isBlank() && !recordName.isBlank()) {
                VisitorRecord record = recordService.get(recordName);
                if (record != null && record.getSpec().getIp() != null) {
                    ip = record.getSpec().getIp();
                }
            }
            if (ip.isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "missing ip"));
            }
            final String targetIp = ip;
            return ipGeoService.lookup(targetIp)
                .flatMap(location -> Mono.fromCallable(() -> {
                    // Persisting blocks, and this continuation can run on a Netty event
                    // loop thread, so the write is moved to a worker thread.
                    if (!recordName.isBlank() && location.resolved()) {
                        if (recordService.get(recordName) == null) {
                            recordService.flushVisitor(recordName);
                        }
                        recordService.applyLocation(recordName, location);
                    }
                    return toLocationView(targetIp, location, usedEndpoint);
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(view -> ServerResponse.ok().bodyValue(view));
        });
    }

    private LocationView toLocationView(String ip, IpLocation location, String endpoint) {
        return new LocationView(ip, location.dec(), location.country(),
            location.countryCode(), location.province(), location.city(),
            location.districts(), location.isp(), location.net(), location.idc(),
            location.zipcode(), location.areacode(), location.protocol(),
            location.location(), location.resolvedAt(), location.resolved(),
            location.error(), endpoint);
    }

    private static String botReason(VisitorRecord.Spec spec) {
        if (!isBotRecord(spec)) {
            return null;
        }
        if (spec.getBotReason() != null) {
            return spec.getBotReason();
        }
        if (UaParser.isBot(spec.getUserAgent())) {
            return UaParser.botReason(spec.getUserAgent());
        }
        return "未上报屏幕、时区等浏览器信息";
    }

    private static boolean isOnline(VisitorRecord record, long cutoff) {
        Long lastSeen = record.getSpec().getLastSeenAt();
        return lastSeen != null && lastSeen >= cutoff;
    }

    /**
     * Uses the stored classification when present, otherwise derives it from the UA and
     * the browser fingerprint so old records are classified too.
     */
    private static boolean isBotRecord(VisitorRecord.Spec spec) {
        if (spec.getBot() != null) {
            return spec.getBot();
        }
        if (UaParser.isBot(spec.getUserAgent())) {
            return true;
        }
        return spec.getScreen() == null && spec.getViewport() == null
            && spec.getTimezone() == null && spec.getLanguage() == null;
    }

    /**
     * A visitor counts as a bot when the User-Agent says so, or when the browser never
     * reported the screen, timezone and language a real browser always sends.
     */
    private static boolean isBotVisitor(OnlineVisitor visitor) {
        if (UaParser.isBot(visitor.getUserAgent())) {
            return true;
        }
        return visitor.getScreen() == null && visitor.getViewport() == null
            && visitor.getTimezone() == null && visitor.getLanguage() == null;
    }

    private static int[] todayCounts(List<VisitorRecord> records) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        int humans = 0;
        int bots = 0;
        for (VisitorRecord record : records) {
            Long firstSeen = record.getSpec().getFirstSeenAt();
            if (firstSeen == null) {
                continue;
            }
            LocalDate date = Instant.ofEpochMilli(firstSeen).atZone(zone).toLocalDate();
            if (!today.equals(date)) {
                continue;
            }
            if (isBotRecord(record.getSpec())) {
                bots++;
            } else {
                humans++;
            }
        }
        return new int[] {humans, bots};
    }

    private static boolean matches(RecordView view, String keyword) {
        return contains(view.ip(), keyword)
            || contains(view.pageTitle(), keyword)
            || contains(view.pageUrl(), keyword)
            || contains(view.userAgent(), keyword)
            || contains(view.location(), keyword)
            || contains(view.city(), keyword)
            || contains(view.region(), keyword)
            || contains(view.country(), keyword)
            || contains(view.isp(), keyword);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static int positiveInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Builds a console row. When a live visitor is supplied the realtime fields come
     * from memory, so a visit is visible before the first database flush.
     */
    private RecordView toRecordView(VisitorRecord record, OnlineVisitor live, long cutoff,
                                    boolean showIp) {
        VisitorRecord.Spec spec = record.getSpec();
        boolean online = live != null || isOnline(record, cutoff);

        Long firstSeen = spec.getFirstSeenAt();
        if (live != null && (firstSeen == null || live.getFirstSeenAt() < firstSeen)) {
            firstSeen = live.getFirstSeenAt();
        }
        Long lastSeen = live != null ? live.getLastSeenAt() : spec.getLastSeenAt();
        String pageUrl = live != null ? live.getPageUrl() : spec.getLastPageUrl();
        String pageTitle = live != null ? live.getPageTitle() : spec.getLastPageTitle();
        Integer visitCount = spec.getVisitCount() != null
            ? spec.getVisitCount() : (live != null ? live.getSessionCount() : null);
        Integer pageViewCount = live != null ? live.getPageViewCount() : spec.getPageViewCount();
        Long totalOnlineMs = live != null ? live.getTotalOnlineMs() : spec.getTotalOnlineMs();

        return new RecordView(
            record.getMetadata().getName(),
            showIp ? spec.getIp() : null,
            spec.getLocation(),
            spec.getCountry(),
            spec.getCountryCode(),
            spec.getRegion(),
            spec.getCity(),
            spec.getDistricts(),
            spec.getIsp(),
            spec.getNet(),
            spec.getIdc(),
            spec.getZipcode(),
            spec.getAreacode(),
            spec.getProtocol(),
            spec.getDec(),
            spec.getGeoResolvedAt(),
            spec.getUserAgent(),
            UaParser.parse(spec.getUserAgent()),
            isBotRecord(spec),
            botReason(spec),
            spec.getFirstPageUrl(), spec.getFirstPageTitle(),
            pageUrl, pageTitle != null ? pageTitle : "",
            spec.getLastReferer(), spec.getFirstReferer(),
            firstSeen, lastSeen,
            visitCount, pageViewCount, totalOnlineMs,
            spec.getScreen(), spec.getViewport(), spec.getTimezone(), spec.getLanguage(),
            spec.getPlatform(), spec.getCpuCores(), spec.getDeviceMemory(), spec.getConnection(),
            online);
    }

    /**
     * Builds a row straight from a live visitor that has not been persisted yet.
     */
    private RecordView toRecordView(OnlineVisitor visitor, boolean showIp) {
        boolean bot = isBotVisitor(visitor);
        String reason = null;
        if (bot) {
            reason = UaParser.isBot(visitor.getUserAgent())
                ? UaParser.botReason(visitor.getUserAgent())
                : "未上报屏幕、时区等浏览器信息";
        }
        return new RecordView(
            visitor.getKey(),
            showIp ? visitor.getIp() : null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            visitor.getUserAgent(),
            UaParser.parse(visitor.getUserAgent()),
            bot,
            reason,
            visitor.getPageUrl(), visitor.getPageTitle(),
            visitor.getPageUrl(), visitor.getPageTitle() != null ? visitor.getPageTitle() : "",
            visitor.getReferer(), visitor.getFirstReferer(),
            visitor.getFirstSeenAt(), visitor.getLastSeenAt(),
            visitor.getSessionCount(), visitor.getPageViewCount(), visitor.getTotalOnlineMs(),
            visitor.getScreen(), visitor.getViewport(), visitor.getTimezone(),
            visitor.getLanguage(), visitor.getPlatform(), visitor.getCpuCores(),
            visitor.getDeviceMemory(), visitor.getConnection(),
            true);
    }

    private LiveView toLiveView(OnlineVisitor visitor, boolean showIp) {
        return new LiveView(
            visitor.getVisitorId(),
            showIp ? visitor.getIp() : null,
            visitor.getUserAgent(),
            visitor.getPageUrl(),
            visitor.getPageTitle(),
            visitor.getFirstSeenAt(),
            visitor.getLastSeenAt());
    }

    private Mono<ServerResponse> getOverview(ServerRequest request) {
        return fetchSettings().flatMap(settings -> {
            // Applies a changed correction right away instead of waiting for the timer.
            statsService.ensureAdjustment(settings.officialViewsAdjustmentOrDefault());
            return ServerResponse.ok().bodyValue(
                queryService.overview(settings, registry.count(settings.onlineTimeoutMillis())));
        });
    }

    private Mono<ServerResponse> getTrend(ServerRequest request) {
        return fetchSettings().flatMap(settings -> {
            int days = positiveInt(request.queryParam("days").orElse(null), 30);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("days", days);
            payload.put("series", queryService.cumulativeTrend(settings, days));
            payload.put("daily", queryService.trend(settings, days));
            return ServerResponse.ok().bodyValue(payload);
        });
    }

    private Mono<ServerResponse> getArticles(ServerRequest request) {
        return fetchSettings().flatMap(settings -> {
            String sort = request.queryParam("sort").orElse("views");
            int limit = positiveInt(request.queryParam("limit").orElse(null), 50);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sort", sort);
            payload.put("items", queryService.articleList(settings, sort, limit, null, null));
            return ServerResponse.ok().bodyValue(payload);
        });
    }

    private Mono<ServerResponse> getArticleDetail(ServerRequest request) {
        String postName = request.pathVariable("postName");
        return fetchSettings().flatMap(settings -> {
            Map<String, Object> detail = queryService.articleDetail(postName, settings, 200);
            if (detail == null) {
                return ServerResponse.notFound().build();
            }
            return ServerResponse.ok().bodyValue(detail);
        });
    }

    private Mono<ServerResponse> getSources(ServerRequest request) {
        return fetchSettings().flatMap(settings -> {
            String dimension = request.queryParam("dimension").orElse("browser");
            int days = positiveInt(request.queryParam("days").orElse(null), 30);
            String from = request.queryParam("from").orElse(null);
            String to = request.queryParam("to").orElse(null);
            return ServerResponse.ok()
                .bodyValue(queryService.sources(settings, days, dimension, from, to));
        });
    }

    private Mono<ServerResponse> getSourceDetail(ServerRequest request) {
        return fetchSettings().flatMap(settings -> {
            String dimension = request.queryParam("dimension").orElse("browser");
            String value = request.queryParam("value").orElse("");
            int days = positiveInt(request.queryParam("days").orElse(null), 30);
            if (value.isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "missing value"));
            }
            return ServerResponse.ok()
                .bodyValue(queryService.sourceDetail(settings, days, dimension, value));
        });
    }

    private Mono<ServerResponse> getVisitorDetail(ServerRequest request) {
        String visitorKey = request.pathVariable("visitorKey");
        return fetchSettings().flatMap(settings -> ServerResponse.ok()
            .bodyValue(queryService.visitorDetail(visitorKey, settings)));
    }

    /**
     * Poll target of the tracker script. The address is taken from the request, never
     * from a parameter, so one visitor can only ever read their own conversation.
     */
    private Mono<ServerResponse> getChatInbox(ServerRequest request) {
        String ip = resolveClientIp(request);
        if (isChatRateLimited(ip)) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        long since = longValue(request.queryParam("since").orElse(null));
        return fetchSettings().flatMap(settings -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            boolean enabled = settings.chatEnabledOrDefault();
            payload.put("enabled", enabled);
            if (!enabled) {
                payload.put("messages", List.of());
                payload.put("latestSeq", 0L);
                return ServerResponse.ok().bodyValue(payload);
            }
            payload.put("messages", chatService.poll(ip, since, CHAT_POLL_LIMIT).stream()
                .map(OnlineStatsEndpoint::toChatView).toList());
            payload.put("latestSeq", chatService.latestSeq(ip));
            return ServerResponse.ok().bodyValue(payload);
        });
    }

    private Mono<ServerResponse> postChatReply(ServerRequest request) {
        String ip = resolveClientIp(request);
        if (isChatRateLimited(ip)) {
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        String userAgent = request.headers().firstHeader(HttpHeaders.USER_AGENT);
        return request.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
            })
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(Map.of())
            .flatMap(body -> fetchSettings().flatMap(settings -> {
                if (!settings.chatEnabledOrDefault()) {
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                        .bodyValue(Map.of("error", "chat disabled"));
                }
                String text = sanitizeChatText(str(body, "text", MAX_CHAT_TEXT));
                if (text == null) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "empty message"));
                }
                ChatMessageService.ChatEntry entry = chatService.append(ip,
                    OnlineVisitorRegistry.deviceKey(ip, userAgent), "visitor", text, null,
                    null, str(body, "url", MAX_URL), str(body, "title", MAX_TITLE));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", true);
                payload.put("seq", entry.seq());
                return ServerResponse.ok().bodyValue(payload);
            }));
    }

    private Mono<ServerResponse> getChatConversations(ServerRequest request) {
        return fetchSettings().flatMap(settings -> {
            boolean showIp = settings.showIpInConsoleOrDefault();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("loaded", chatService.isLoaded());
            payload.put("unreadTotal", chatService.unreadTotal());
            payload.put("items", chatService.conversations().stream()
                .map(conversation -> toConversationView(conversation, showIp)).toList());
            return ServerResponse.ok().bodyValue(payload);
        });
    }

    private Mono<ServerResponse> getChatMessages(ServerRequest request) {
        String ip = request.queryParam("ip").orElse("").trim();
        if (ip.isBlank()) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "missing ip"));
        }
        boolean markRead = !"false".equals(request.queryParam("markRead").orElse("true"));
        return fetchSettings().flatMap(settings -> {
            if (markRead) {
                chatService.markReadByAdmin(ip);
            }
            boolean showIp = settings.showIpInConsoleOrDefault();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ip", showIp ? ip : maskIp(ip));
            payload.put("messages", chatService.thread(ip).stream()
                .map(OnlineStatsEndpoint::toAdminChatView).toList());
            return ServerResponse.ok().bodyValue(payload);
        });
    }

    /**
     * Writes a console message to one visitor address. The author shown to the visitor
     * is resolved from the authenticated account, not from the request body.
     */
    private Mono<ServerResponse> postChatSend(ServerRequest request) {
        return request.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
            })
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(Map.of())
            .flatMap(body -> {
                String ip = str(body, "ip", MAX_SHORT);
                String text = sanitizeChatText(str(body, "text", MAX_CHAT_TEXT));
                if (ip == null || text == null) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "missing ip or text"));
                }
                return chatService.resolveAdmin().flatMap(admin -> {
                    ChatMessageService.ChatEntry entry = chatService.append(ip, null,
                        "admin", text, admin.name(), admin.avatar(), null, null);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("ok", true);
                    payload.put("seq", entry.seq());
                    return ServerResponse.ok().bodyValue(payload);
                });
            });
    }

    /**
     * Keeps a reply to plain text: control characters are dropped and the length is
     * capped, so nothing that could be interpreted as markup survives the trip.
     */
    private static String sanitizeChatText(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_CHAT_TEXT ? text.substring(0, MAX_CHAT_TEXT) : text;
    }

    private static long longValue(String raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return Math.max(value, 0L);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Map<String, Object> toChatView(ChatMessageService.ChatEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seq", entry.seq());
        view.put("direction", entry.direction());
        view.put("text", entry.text());
        view.put("sentAt", entry.sentAt());
        view.put("adminName", entry.adminName());
        view.put("adminAvatar", entry.adminAvatar());
        return view;
    }

    private static Map<String, Object> toAdminChatView(ChatMessageService.ChatEntry entry) {
        Map<String, Object> view = toChatView(entry);
        view.put("visitorKey", entry.visitorKey());
        view.put("pageUrl", entry.pageUrl());
        view.put("pageTitle", entry.pageTitle());
        view.put("readByAdmin", entry.readByAdmin());
        return view;
    }

    private static Map<String, Object> toConversationView(
        ChatMessageService.Conversation conversation, boolean showIp) {
        ChatMessageService.ChatEntry last = conversation.last();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ip", showIp ? conversation.ip() : maskIp(conversation.ip()));
        view.put("unread", conversation.unread());
        view.put("total", conversation.total());
        view.put("lastText", last.text());
        view.put("lastDirection", last.direction());
        view.put("lastAt", last.sentAt());
        return view;
    }

    /** Hides the host part of an address when the console is configured to hide IPs. */
    private static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "已隐藏";
        }
        int index = ip.lastIndexOf('.');
        if (index <= 0) {
            return ip.length() > 4 ? ip.substring(0, 4) + "***" : "已隐藏";
        }
        return ip.substring(0, index) + ".*";
    }

    private String readScript() {
        try (InputStream is =
                 OnlineStatsEndpoint.class.getResourceAsStream("/static/online-stats.js")) {
            if (is == null) {
                throw new IllegalStateException("Resource static/online-stats.js not found");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load online-stats.js", e);
        }
    }

    private Mono<OnlineStatsSettings> fetchSettings() {
        return settingFetcher.fetch(OnlineStatsSettings.GROUP, OnlineStatsSettings.class)
            .defaultIfEmpty(new OnlineStatsSettings(null, null, null, null, null, null, null,
                null, null));
    }

    private String resolveClientIp(ServerRequest request) {
        String forwarded = request.headers().firstHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.headers().firstHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.remoteAddress()
            .map(InetSocketAddress::getAddress)
            .map(InetAddress::getHostAddress)
            .orElse("unknown");
    }

    public record PageView(String title, int count) {
    }

    public record LiveView(String visitorId, String ip, String userAgent,
                           String pageUrl, String pageTitle,
                           long firstSeenAt, long lastSeenAt) {
    }

    public record LocationView(String ip,
                               String dec,
                               String country,
                               String countryCode,
                               String province,
                               String city,
                               String districts,
                               String isp,
                               String net,
                               String idc,
                               String zipcode,
                               String areacode,
                               String protocol,
                               String location,
                               long resolvedAt,
                               boolean resolved,
                               String error,
                               String endpoint) {
    }

    public record RecordView(String name,
                             String ip,
                             String location,
                             String country,
                             String countryCode,
                             String region,
                             String city,
                             String districts,
                             String isp,
                             String net,
                             String idc,
                             String zipcode,
                             String areacode,
                             String protocol,
                             String dec,
                             Long geoResolvedAt,
                             String userAgent,
                             Map<String, String> ua,
                             Boolean bot,
                             String botReason,
                             String firstPageUrl,
                             String firstPageTitle,
                             String pageUrl,
                             String pageTitle,
                             String referer,
                             String firstReferer,
                             Long firstSeenAt,
                             Long lastSeenAt,
                             Integer visitCount,
                             Integer pageViewCount,
                             Long totalOnlineMs,
                             String screen,
                             String viewport,
                             String timezone,
                             String language,
                             String platform,
                             String cpuCores,
                             String deviceMemory,
                             String connection,
                             boolean online) {
    }
}
