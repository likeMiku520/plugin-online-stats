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
    private final String configMapName;

    public MaintenanceEndpoint(VisitorRecordService recordService,
                               ChatMessageService chatService,
                               LiveSettings liveSettings,
                               PluginContext pluginContext) {
        this.recordService = recordService;
        this.chatService = chatService;
        this.liveSettings = liveSettings;
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
            .GET("/maintenance/announce-enabled", accept(MediaType.APPLICATION_JSON),
                this::announceEnabled)
            .POST("/maintenance/announce-test", this::announceTest)
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
        return ServerResponse.ok().bodyValue(payload);
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
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VisitorRecord record : recordService.list()) {
            VisitorRecord.Spec spec = record.getSpec();
            if (spec == null) {
                continue;
            }
            boolean bot = StatisticsQueryService.isBot(spec);
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
            row.put("firstSeenAt", spec.getFirstSeenAt());
            row.put("lastSeenAt", spec.getLastSeenAt());
            row.put("visitCount", spec.getVisitCount());
            row.put("pageViewCount", spec.getPageViewCount());
            rows.add(row);
        }
        rows.sort(filter.comparator());
        return rows;
    }

    private List<Map<String, Object>> filteredConversations(Filter filter) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatMessageService.Conversation conversation : chatService.allConversations()) {
            String key = conversation.key();
            ChatMessageService.ChatEntry last = conversation.last();
            VisitorRecord record = recordService.get(key);
            VisitorRecord.Spec spec = record == null ? null : record.getSpec();
            boolean bot = spec != null && StatisticsQueryService.isBot(spec);
            String ip = last == null ? null : last.ip();
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
        StringBuilder out = new StringBuilder();
        out.append("IP,属地,最后访问页面,类型,首次访问,最后活跃,访问次数,浏览页面数,设备\n");
        for (Map<String, Object> row : filteredVisitors(filter)) {
            out.append(csv(row.get("ip"))).append(',')
                .append(csv(row.get("location"))).append(',')
                .append(csv(row.get("pageTitle"))).append(',')
                .append(Boolean.TRUE.equals(row.get("bot")) ? "机器人" : "真人").append(',')
                .append(csv(formatTime(asLong(row.get("firstSeenAt"))))).append(',')
                .append(csv(formatTime(asLong(row.get("lastSeenAt"))))).append(',')
                .append(asLong(row.get("visitCount"))).append(',')
                .append(asLong(row.get("pageViewCount"))).append(',')
                .append(csv(row.get("userAgent"))).append('\n');
        }
        return download("\uFEFF" + out, "访客数据-" + STAMP.format(Instant.now()) + ".csv",
            "text/csv; charset=utf-8");
    }

    /** One conversation, or every conversation, as readable plain text. */
    private Mono<ServerResponse> exportChat(ServerRequest request) {
        String key = request.queryParam("key").orElse("").trim();
        StringBuilder out = new StringBuilder();
        List<ChatMessageService.Conversation> chosen = new ArrayList<>();
        for (ChatMessageService.Conversation conversation : chatService.allConversations()) {
            if (key.isEmpty() || key.equals(conversation.key())) {
                chosen.add(conversation);
            }
        }
        for (ChatMessageService.Conversation conversation : chosen) {
            ChatMessageService.ChatEntry last = conversation.last();
            boolean single = !key.isEmpty();
            if (single) {
                out.append("访客：").append(last == null || last.ip() == null ? key : last.ip())
                    .append('\n');
                out.append("导出时间：").append(READABLE.format(Instant.now())).append("\n\n");
            } else {
                out.append("=== ").append(last == null || last.ip() == null
                    ? conversation.key() : last.ip()).append(" ===\n");
            }
            for (ChatMessageService.ChatEntry entry : chatService.thread(conversation.key())) {
                out.append('[').append(formatTime(entry.sentAt())).append("] ");
                out.append("admin".equals(entry.direction())
                    ? (entry.authorName() == null ? "管理员" : entry.authorName())
                    : "访客");
                out.append("：").append(entry.text()).append('\n');
            }
            out.append('\n');
        }
        String name = (key.isEmpty() ? "聊天记录-" : "聊天记录-" + shortKey(key) + "-")
            + STAMP.format(Instant.now()) + ".txt";
        return download(out, name, "text/plain; charset=utf-8");
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
            .flatMap(body -> {
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
                    return ServerResponse.badRequest().bodyValue(Map.of("error", "没有选中访客"));
                }

                int visitors = 0;
                int messages = 0;
                boolean wantsVisitors = "visitors".equals(what) || "both".equals(what);
                boolean wantsChat = "chat".equals(what) || "both".equals(what);
                if (all) {
                    if (wantsVisitors) {
                        visitors = recordService.deleteAll();
                    }
                    if (wantsChat) {
                        messages = chatService.clearAllConversations();
                    }
                } else {
                    for (String key : keys) {
                        if (wantsVisitors && recordService.deleteByKey(key)) {
                            visitors++;
                        }
                        if (wantsChat) {
                            messages += chatService.clearConversation(key);
                        }
                    }
                }
                log.info("Cleared {} visitor records and {} messages ({})", visitors, messages,
                    all ? "everything" : keys.size() + " selected");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", true);
                payload.put("visitors", visitors);
                payload.put("messages", messages);
                return ServerResponse.ok().bodyValue(payload);
            });
    }

    /** Whether the test button should be offered at all. */
    private Mono<ServerResponse> announceEnabled(ServerRequest request) {
        Map<String, String> chat = liveSettings.group(configMapName, OnlineStatsSettings.GROUP_CHAT)
            .orElse(Map.of());
        String text = chat.get("chatAnnounceText");
        String enabled = chat.get("chatAnnounceEnabled");
        boolean on = "true".equalsIgnoreCase(enabled) && text != null && !text.isBlank();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chatAnnounceEnabled", on);
        payload.put("hasText", text != null && !text.isBlank());
        return ServerResponse.ok().bodyValue(payload);
    }

    /**
     * Sends the greeting to one visitor on demand. Unlike the automatic send it works on a
     * device that has already talked, because checking the wording is the whole point.
     */
    private Mono<ServerResponse> announceTest(ServerRequest request) {
        return request.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
            })
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(Map.of())
            .flatMap(body -> {
                String key = body.get("key") == null ? "" : String.valueOf(body.get("key")).trim();
                if (key.isEmpty()) {
                    return ServerResponse.badRequest().bodyValue(Map.of("error", "缺少访客"));
                }
                // Read live, so the text that was just saved in the console is the one sent.
                Map<String, String> chat = liveSettings
                    .group(configMapName, OnlineStatsSettings.GROUP_CHAT).orElse(Map.of());
                String text = chat.get("chatAnnounceText");
                if (text == null || text.isBlank()) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "还没有填写自动消息内容"));
                }
                VisitorRecord record = recordService.get(key);
                String ip = record == null || record.getSpec() == null
                    ? null : record.getSpec().getIp();
                String name = chat.get("chatAnnounceName");
                ChatMessageService.ChatEntry entry = chatService.append(key, ip, "admin", text,
                    name == null || name.isBlank() ? "访客消息" : name, null, null, null);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", true);
                payload.put("seq", entry.seq());
                payload.put("text", text);
                return ServerResponse.ok().bodyValue(payload);
            });
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

        Filter(String type, String sort, String keyword) {
            this.type = type;
            this.sort = sort;
            // Normalised here so a caller cannot forget it; matching ignores case.
            this.keyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        }

        static Filter of(ServerRequest request) {
            return new Filter(
                request.queryParam("type").orElse("all"),
                request.queryParam("sort").orElse("lastSeenAt"),
                request.queryParam("keyword").orElse(""));
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