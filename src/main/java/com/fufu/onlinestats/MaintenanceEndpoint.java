package com.fufu.onlinestats;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.PluginContext;

/**
 * Backing endpoints for the data management page: counts, one conversation at a time,
 * exporting, and clearing.
 *
 * <p>Clearing one visitor removes that device's record and its conversation together, and
 * a batch clear does the same for every key it is given, so the two lists never disagree.
 * Settings are read through {@link LiveSettings}, never through Halo's cached fetcher.</p>
 */
@Component
public class MaintenanceEndpoint implements CustomEndpoint {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceEndpoint.class);
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter READABLE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final VisitorRecordService recordService;
    private final ChatMessageService chatService;
    private final LiveSettings liveSettings;
    private final OnlineVisitorRegistry registry;
    private final StatsAggregationService statsService;
    private final String configMapName;

    public MaintenanceEndpoint(VisitorRecordService recordService,
                               ChatMessageService chatService,
                               LiveSettings liveSettings,
                               OnlineVisitorRegistry registry,
                               StatsAggregationService statsService,
                               PluginContext pluginContext) {
        this.recordService = recordService;
        this.chatService = chatService;
        this.liveSettings = liveSettings;
        this.registry = registry;
        this.statsService = statsService;
        this.configMapName = pluginContext == null ? null : pluginContext.getConfigMapName();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(OnlineStatsEndpoint.GROUP_VERSION);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/maintenance/summary", accept(MediaType.APPLICATION_JSON), this::summary)
            .GET("/maintenance/visitors", accept(MediaType.APPLICATION_JSON), this::listVisitors)
            .GET("/maintenance/conversations", accept(MediaType.APPLICATION_JSON),
                this::listConversations)
            .GET("/maintenance/thread", accept(MediaType.APPLICATION_JSON), this::thread)
            .GET("/maintenance/visitors/export", this::exportVisitors)
            .GET("/maintenance/chat/export", this::exportChat)
            .POST("/maintenance/clear", this::clear)
            .build();
    }

    /** The counts shown above the table, and while deciding what to clear. */
    private Mono<ServerResponse> summary(ServerRequest request) {
        int humans = 0;
        int bots = 0;
        for (VisitorRecord record : recordService.list()) {
            VisitorRecord.Spec spec = record.getSpec();
            if (spec == null) {
                continue;
            }
            if (StatisticsQueryService.isBot(spec)) {
                bots++;
            } else {
                humans++;
            }
        }
        int messages = 0;
        for (ChatMessageService.Conversation conversation : chatService.allConversations()) {
            messages += chatService.thread(conversation.key()).size();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("visitors", humans + bots);
        payload.put("humans", humans);
        payload.put("bots", bots);
        payload.put("conversations", chatService.allConversations().size());
        payload.put("messages", messages);
        // Shown on the page so the running build can be identified at a glance.
        payload.put("version", pluginVersion());
        return ServerResponse.ok().bodyValue(payload);
    }

    /** The version Halo loaded, read from this plugin's own manifest. */
    private String pluginVersion() {
        try {
            var resource = getClass().getClassLoader().getResource("plugin.yaml");
            if (resource == null) {
                return "";
            }
            try (var in = resource.openStream()) {
                for (String line : new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("version:")) {
                        return trimmed.substring("version:".length()).replace("\"", "").trim();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not read the plugin version", e);
        }
        return "";
    }

    /** One page of visitors, filtered and sorted the way the page asks. */
    private Mono<ServerResponse> listVisitors(ServerRequest request) {
        Filter filter = Filter.of(request);
        List<Map<String, Object>> rows = filteredVisitors(filter);
        return paged(request, rows);
    }

    /** One page of conversations. */
    private Mono<ServerResponse> listConversations(ServerRequest request) {
        Filter filter = Filter.of(request);
        List<Map<String, Object>> all = filteredConversations(filter);
        return paged(request, all);
    }

    private Mono<ServerResponse> paged(ServerRequest request, List<Map<String, Object>> rows) {
        int size = Math.max(1, intParam(request, "size", 20));
        int page = Math.max(1, intParam(request, "page", 1));
        int totalPages = Math.max(1, (rows.size() + size - 1) / size);
        int from = Math.min((page - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", rows.subList(from, to));
        payload.put("total", rows.size());
        payload.put("page", page);
        payload.put("size", size);
        payload.put("totalPages", totalPages);
        return ServerResponse.ok().bodyValue(payload);
    }

    /** Every message of one conversation, for the panel that shows the whole thread. */
    private Mono<ServerResponse> thread(ServerRequest request) {
        String key = request.queryParam("key").orElse("").trim();
        if (key.isBlank()) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "缺少访客"));
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessageService.ChatEntry entry : chatService.thread(key)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("seq", entry.seq());
            view.put("direction", entry.direction());
            view.put("text", entry.text());
            view.put("sentAt", entry.sentAt());
            view.put("authorName", entry.authorName());
            messages.add(view);
        }
        VisitorRecord record = recordService.get(key);
        VisitorRecord.Spec spec = record == null ? null : record.getSpec();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("visitorKey", key);
        payload.put("ip", spec == null ? null : spec.getIp());
        payload.put("location", spec == null ? null : spec.getLocation());
        payload.put("items", messages);
        return ServerResponse.ok().bodyValue(payload);
    }

    private List<Map<String, Object>> filteredVisitors(Filter filter) {
        Map<String, Boolean> online = onlineLookup();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VisitorRecord record : recordService.list()) {
            VisitorRecord.Spec spec = record.getSpec();
            if (spec == null) {
                continue;
            }
            boolean bot = StatisticsQueryService.isBot(spec);
            if (!filter.picks(record.getMetadata().getName())) {
                continue;
            }
            if (!filter.accepts(bot, spec.getIp(), spec.getLocation(),
                spec.getLastPageTitle(), spec.getUserAgent(), spec.getCity(), spec.getIsp())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("visitorKey", record.getMetadata().getName());
            row.put("ip", spec.getIp());
            row.put("location", spec.getLocation());
            row.put("pageTitle", spec.getLastPageTitle());
            row.put("userAgent", spec.getUserAgent());
            row.put("bot", bot);
            // The light means "online", the same thing it means in the visitor list.
            row.put("online", Boolean.TRUE.equals(online.get(record.getMetadata().getName())));
            row.put("firstSeenAt", spec.getFirstSeenAt());
            row.put("lastSeenAt", spec.getLastSeenAt());
            row.put("visitCount", spec.getVisitCount());
            row.put("pageViewCount", spec.getPageViewCount());
            row.put("pageUrl", spec.getLastPageUrl());
            row.put("country", spec.getCountry());
            row.put("province", spec.getRegion());
            row.put("botReason", spec.getBotReason());
            row.put("city", spec.getCity());
            row.put("isp", spec.getIsp());
            row.put("net", spec.getNet());
            row.put("firstReferer", spec.getFirstReferer());
            row.put("lastReferer", spec.getLastReferer());
            row.put("screen", spec.getScreen());
            row.put("viewport", spec.getViewport());
            row.put("language", spec.getLanguage());
            row.put("timezone", spec.getTimezone());
            row.put("platform", spec.getPlatform());
            row.put("totalOnlineMs", spec.getTotalOnlineMs());
            rows.add(row);
        }
        rows.sort(filter.comparator());
        return rows;
    }

    private List<Map<String, Object>> filteredConversations(Filter filter) {
        Map<String, Boolean> online = onlineLookup();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatMessageService.Conversation conversation : chatService.allConversations()) {
            String key = conversation.key();
            ChatMessageService.ChatEntry last = conversation.last();
            VisitorRecord record = recordService.get(key);
            VisitorRecord.Spec spec = record == null ? null : record.getSpec();
            boolean bot = spec != null && StatisticsQueryService.isBot(spec);
            String ip = last == null ? null : last.ip();
            if (!filter.picks(key)) {
                continue;
            }
            if (!filter.accepts(bot, ip,
                spec == null ? null : spec.getLocation(),
                spec == null ? null : spec.getLastPageTitle(),
                spec == null ? null : spec.getUserAgent(),
                last == null ? null : last.text())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("visitorKey", key);
            row.put("ip", ip);
            row.put("location", spec == null ? null : spec.getLocation());
            row.put("bot", bot);
            row.put("online", Boolean.TRUE.equals(online.get(key)));
            // Carried over from the visitor record, so the row can show the device.
            row.put("userAgent", spec == null ? null : spec.getUserAgent());
            row.put("pageTitle", spec == null ? null : spec.getLastPageTitle());
            row.put("pageUrl", spec == null ? null : spec.getLastPageUrl());
            row.put("idc", spec == null ? null : spec.getIdc());
            row.put("total", chatService.thread(key).size());
            row.put("lastText", last == null ? "" : last.text());
            row.put("lastAt", last == null ? 0L : last.sentAt());
            row.put("firstSeenAt", spec == null ? 0L : spec.getFirstSeenAt());
            row.put("lastSeenAt", last == null ? 0L : last.sentAt());
            row.put("visitCount", spec == null ? 0L : spec.getVisitCount());
            rows.add(row);
        }
        rows.sort(filter.comparator());
        return rows;
    }

    /** The visitor table as a file Excel and WPS open without extra steps. */
    private Mono<ServerResponse> exportVisitors(ServerRequest request) {
        Filter filter = Filter.of(request);
        List<Map<String, Object>> visitors = new ArrayList<>();
        for (Map<String, Object> row : filteredVisitors(filter)) {
            String key = String.valueOf(row.get("visitorKey"));
            visitors.add(visitorJson(row, key));
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("exportedAt", READABLE.format(Instant.now()));
        document.put("scope", filter.type);
        document.put("total", visitors.size());
        document.put("visitors", visitors);
        return download(toJson(document), "访客数据-" + STAMP.format(Instant.now()) + ".json",
            "application/json; charset=utf-8");
    }

    /** One conversation, several, or every conversation, as readable plain text. */
    private Mono<ServerResponse> exportChat(ServerRequest request) {
        String key = request.queryParam("key").orElse("").trim();
        Filter filter = Filter.of(request);
        StringBuilder out = new StringBuilder();
        List<ChatMessageService.Conversation> chosen = new ArrayList<>();
        for (ChatMessageService.Conversation conversation : chatService.allConversations()) {
            boolean byKey = key.isEmpty() || key.equals(conversation.key());
            if (byKey && filter.picks(conversation.key())) {
                chosen.add(conversation);
            }
        }
        List<Map<String, Object>> conversations = new ArrayList<>();
        for (ChatMessageService.Conversation conversation : chosen) {
            ChatMessageService.ChatEntry last = conversation.last();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("visitorKey", conversation.key());
            item.put("ip", last == null ? null : last.ip());
            List<Map<String, Object>> messages = new ArrayList<>();
            for (ChatMessageService.ChatEntry entry : chatService.thread(conversation.key())) {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("sentAt", formatTime(entry.sentAt()));
                message.put("sentAtMillis", entry.sentAt());
                message.put("direction", entry.direction());
                message.put("author", "admin".equals(entry.direction())
                    ? (entry.authorName() == null ? "管理员" : entry.authorName())
                    : "访客");
                message.put("text", entry.text());
                message.put("pageTitle", entry.pageTitle());
                message.put("pageUrl", entry.pageUrl());
                messages.add(message);
            }
            item.put("messages", messages);
            conversations.add(item);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("exportedAt", READABLE.format(Instant.now()));
        document.put("total", conversations.size());
        document.put("conversations", conversations);
        String name = (key.isEmpty() ? "聊天记录-" : "聊天记录-" + shortKey(key) + "-")
            + STAMP.format(Instant.now()) + ".json";
        return download(toJson(document), name, "application/json; charset=utf-8");
    }

    private Mono<ServerResponse> download(CharSequence body, String filename, String contentType) {
        return ServerResponse.ok()
            .header("Content-Disposition", "attachment; filename*=UTF-8''"
                + encodeFilename(filename))
            .contentType(MediaType.parseMediaType(contentType))
            .bodyValue(body.toString());
    }

    private static String encodeFilename(String name) {
        return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    /**
     * Removes what the page asks for.
     *
     * <p>{@code what} is {@code visitors}, {@code chat} or {@code both}. A {@code keys}
     * array clears exactly those devices; {@code all: true} clears everything. One key
     * removes the record and the conversation together, so nothing is left half deleted.</p>
     */
    private Mono<ServerResponse> clear(ServerRequest request) {
        return request.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
            })
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(Map.of())
            .flatMap(body -> Mono.fromCallable(() -> applyClear(body))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
            .flatMap(payload -> payload.containsKey("error")
                ? ServerResponse.badRequest().bodyValue(payload)
                : ServerResponse.ok().bodyValue(payload));
    }

    /** Deletes what was asked for and reports how much went. Blocks, so call on a worker. */
    private Map<String, Object> applyClear(Map<String, Object> body) {
                String what = String.valueOf(body.getOrDefault("what", "both"));
                boolean all = Boolean.TRUE.equals(body.get("all"));
                List<String> keys = new ArrayList<>();
                Object raw = body.get("keys");
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        if (item != null && !String.valueOf(item).isBlank()) {
                            keys.add(String.valueOf(item));
                        }
                    }
                }
                Object single = body.get("key");
                if (keys.isEmpty() && single != null && !String.valueOf(single).isBlank()) {
                    keys.add(String.valueOf(single));
                }
                if (!all && keys.isEmpty()) {
                    Map<String, Object> refused = new LinkedHashMap<>();
                    refused.put("error", "没有选中访客");
                    return refused;
                }

                int visitors = 0;
                int messages = 0;
                int statistics = 0;
                boolean wantsVisitors = "visitors".equals(what) || "both".equals(what);
                // Clearing a visitor means everything about them, so their conversation
                // goes too: leaving half of a person behind is what makes the two lists
                // disagree later.
                boolean wantsChat = "chat".equals(what) || "both".equals(what)
                    || wantsVisitors;
                List<String> removed = new ArrayList<>();
                if (all) {
                    if (wantsVisitors) {
                        removed.addAll(recordService.list().stream()
                            .map(record -> record.getMetadata() == null ? null
                                : record.getMetadata().getName())
                            .filter(name -> name != null)
                            .toList());
                        visitors = recordService.deleteAll();
                    }
                    if (wantsChat) {
                        messages = chatService.clearAllConversations();
                    }
                } else {
                    for (String key : keys) {
                        if (wantsVisitors && recordService.deleteByKey(key)) {
                            visitors++;
                            removed.add(key);
                        }
                        if (wantsChat) {
                            messages += chatService.clearConversation(key);
                        }
                        if (wantsVisitors && !removed.contains(key)) {
                            removed.add(key);
                        }
                    }
                }
                // The statistics page is built from its own counters, so a cleared device
                // has to be taken out of those as well. Clearing everything goes further
                // and throws the whole history away, which is what "start over" means.
                if (all && wantsVisitors) {
                    statistics += statsService.reset();
                } else {
                    for (String key : removed) {
                        statistics += statsService.forgetVisitor(key);
                    }
                }
                log.info("Cleared {} visitor records, {} messages and {} statistics ({})",
                    visitors, messages, statistics,
                    all ? "everything" : keys.size() + " selected");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", true);
                payload.put("visitors", visitors);
                payload.put("messages", messages);
                return payload;
    }
    /** Whether that device is currently reporting a heartbeat. */
    private boolean isOnline(String key) {
        if (key == null) {
            return false;
        }
        try {
            return registry.get(key) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The set of devices that are actually online right now, worked out the same way the
     * visitor list does it: a stored record counts while its last heartbeat is inside the
     * online window, and anything currently in the registry counts too. Asking the
     * registry alone made every device that had ever loaded the tracker look online.
     */
    private Map<String, Boolean> onlineLookup() {
        long timeout = 60_000L;
        try {
            var settings = liveSettings.effectiveChatSettings(configMapName);
            if (settings.isPresent()) {
                timeout = settings.get().onlineTimeoutMillis();
            }
        } catch (Exception e) {
            log.debug("Could not read the online timeout, using a minute", e);
        }
        long cutoff = System.currentTimeMillis() - timeout;
        Map<String, Boolean> online = new LinkedHashMap<>();
        for (VisitorRecord record : recordService.list()) {
            String name = record.getMetadata() == null ? null : record.getMetadata().getName();
            if (name == null || record.getSpec() == null) {
                continue;
            }
            Long lastSeen = record.getSpec().getLastSeenAt();
            online.put(name, lastSeen != null && lastSeen >= cutoff);
        }
        // The registry keeps a visitor until something asks it to expire entries, so it
        // is asked for the current window before its contents are trusted.
        for (var visitor : registry.list(timeout)) {
            online.put(visitor.getKey(), true);
        }
        return online;
    }

    private static int intParam(ServerRequest request, String name, int fallback) {
        try {
            return Integer.parseInt(request.queryParam(name).orElse(""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    static String formatTime(long millis) {
        return millis <= 0 ? "" : READABLE.format(Instant.ofEpochMilli(millis));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_WRITER =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    /** Pretty printed, because the point of exporting is to be read. */
    static String toJson(Object value) {
        try {
            return JSON_WRITER.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not write the export", e);
            return "{}";
        }
    }

    /** One visitor as a readable object, including the conversation. */
    private Map<String, Object> visitorJson(Map<String, Object> row, String key) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("visitorKey", key);
        item.put("ip", row.get("ip"));
        item.put("location", row.get("location"));
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("country", row.get("country"));
        geo.put("province", row.get("province"));
        geo.put("city", row.get("city"));
        geo.put("isp", row.get("isp"));
        geo.put("net", row.get("net"));
        item.put("geo", geo);
        item.put("robot", Boolean.TRUE.equals(row.get("bot")));
        item.put("robotReason", row.get("botReason"));
        item.put("online", Boolean.TRUE.equals(row.get("online")));
        item.put("firstSeenAt", formatTime(asLong(row.get("firstSeenAt"))));
        item.put("firstSeenAtMillis", asLong(row.get("firstSeenAt")));
        item.put("lastSeenAt", formatTime(asLong(row.get("lastSeenAt"))));
        item.put("lastSeenAtMillis", asLong(row.get("lastSeenAt")));
        item.put("visitCount", asLong(row.get("visitCount")));
        item.put("pageViewCount", asLong(row.get("pageViewCount")));
        item.put("timeOnSite", readableDuration(asLong(row.get("totalOnlineMs"))));
        item.put("timeOnSiteMillis", asLong(row.get("totalOnlineMs")));
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("title", row.get("pageTitle"));
        page.put("url", row.get("pageUrl"));
        item.put("lastPage", page);
        Map<String, Object> referer = new LinkedHashMap<>();
        referer.put("first", row.get("firstReferer"));
        referer.put("last", row.get("lastReferer"));
        item.put("referer", referer);
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("screen", row.get("screen"));
        device.put("viewport", row.get("viewport"));
        device.put("platform", row.get("platform"));
        device.put("language", row.get("language"));
        device.put("timezone", row.get("timezone"));
        device.put("userAgent", row.get("userAgent"));
        item.put("device", device);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessageService.ChatEntry entry : chatService.thread(key)) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("sentAt", formatTime(entry.sentAt()));
            message.put("sentAtMillis", entry.sentAt());
            message.put("direction", entry.direction());
            message.put("author", "admin".equals(entry.direction())
                ? (entry.authorName() == null ? "管理员" : entry.authorName())
                : "访客");
            message.put("text", entry.text());
            messages.add(message);
        }
        item.put("messages", messages);
        return item;
    }

    /** The whole conversation on one line, so the row keeps its meaning in a spreadsheet. */
    static String transcript(List<ChatMessageService.ChatEntry> thread) {
        if (thread == null || thread.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChatMessageService.ChatEntry entry : thread) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append("admin".equals(entry.direction()) ? "管理员: " : "访客: ")
                .append(entry.text() == null ? "" : entry.text());
        }
        return text.toString();
    }

    /** Time on site in a form a reader can compare, instead of raw milliseconds. */
    static String readableDuration(long millis) {
        if (millis <= 0) {
            return "";
        }
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) {
            return hours + " 小时 " + (minutes % 60) + " 分";
        }
        if (minutes > 0) {
            return minutes + " 分 " + (seconds % 60) + " 秒";
        }
        return seconds + " 秒";
    }

    static String csv(Object value) {
        if (value == null) {
            return "";
        }
        return "\"" + String.valueOf(value).replace("\"", "\"\"").replace("\n", " ") + "\"";
    }

    private static String shortKey(String key) {
        return key.length() <= 8 ? key : key.substring(key.length() - 8);
    }

    /** The filters the page and the export share. */
    static final class Filter {
        private final String type;
        private final String sort;
        private final String keyword;
        /** When set, only these devices are included; empty means every one of them. */
        private final java.util.Set<String> keys;

        Filter(String type, String sort, String keyword) {
            this(type, sort, keyword, java.util.Set.of());
        }

        Filter(String type, String sort, String keyword, java.util.Set<String> keys) {
            this.type = type;
            this.sort = sort;
            // Normalised here so a caller cannot forget it; matching ignores case.
            this.keyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            this.keys = keys == null ? java.util.Set.of() : keys;
        }

        static Filter of(ServerRequest request) {
            String raw = request.queryParam("keys").orElse("").trim();
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            if (!raw.isEmpty()) {
                for (String part : raw.split(",")) {
                    if (!part.isBlank()) {
                        keys.add(part.trim());
                    }
                }
            }
            return new Filter(
                request.queryParam("type").orElse("all"),
                request.queryParam("sort").orElse("lastSeenAt"),
                request.queryParam("keyword").orElse(""),
                keys);
        }

        /** Whether a device is inside the chosen selection. */
        boolean picks(String key) {
            return keys.isEmpty() || (key != null && keys.contains(key));
        }

        boolean accepts(boolean bot, String... fields) {
            if ("human".equalsIgnoreCase(type) && bot) {
                return false;
            }
            if ("bot".equalsIgnoreCase(type) && !bot) {
                return false;
            }
            if (keyword.isEmpty()) {
                return true;
            }
            for (String field : fields) {
                if (field != null && field.toLowerCase(Locale.ROOT).contains(keyword)) {
                    return true;
                }
            }
            return false;
        }

        Comparator<Map<String, Object>> comparator() {
            Comparator<Map<String, Object>> by;
            switch (sort) {
                case "firstSeenAt" -> by = Comparator.comparingLong(r -> asLong(r.get("firstSeenAt")));
                case "visitCount" -> by = Comparator.comparingLong(r -> asLong(r.get("visitCount")));
                case "lastAt" -> by = Comparator.comparingLong(r -> asLong(r.get("lastAt")));
                default -> by = Comparator.comparingLong(r -> asLong(r.get("lastSeenAt")));
            }
            return by.reversed();
        }
    }
}