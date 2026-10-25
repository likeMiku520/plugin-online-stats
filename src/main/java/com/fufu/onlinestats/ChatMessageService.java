package com.fufu.onlinestats;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.User;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.AnonymousUserConst;

/**
 * Conversations between the console and the people browsing the blog. A conversation
 * is keyed by the visitor's IP address: the console writes to an address and every
 * browser behind that address receives the message on its next poll.
 *
 * <p>Messages are served from memory so a poll is cheap, and written to
 * {@link ChatMessage} extensions in the background so a plugin restart or an upgrade
 * does not lose the thread.</p>
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    /** Public text limit, applied by the endpoint before anything reaches this service. */
    public static final int MAX_TEXT_LENGTH = 1000;

    private static final int MAX_PER_CONVERSATION = 100;
    private static final int MAX_CONVERSATIONS = 200;
    private static final int MAX_PENDING_WRITES = 500;
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);
    private static final String ADMIN_FALLBACK_NAME = "博主";

    private final ReactiveExtensionClient client;
    private final UserService userService;

    private final Map<String, Deque<ChatEntry>> conversations = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicInteger pendingWrites = new AtomicInteger();

    private volatile ExecutorService writer;
    private volatile boolean loaded;

    public ChatMessageService(ReactiveExtensionClient client, UserService userService) {
        this.client = client;
        this.userService = userService;
    }

    public void start() {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "online-stats-chat");
            thread.setDaemon(true);
            return thread;
        });
        writer = executor;
        loadAll();
    }

    public void stop() {
        ExecutorService executor = writer;
        writer = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Identity shown to the visitor. The current administrator is read from the
     * security context rather than from the request body, so the name and the avatar a
     * visitor sees cannot be forged by a caller.
     */
    public Mono<AdminView> resolveAdmin() {
        Mono<AdminView> fallback = Mono.just(new AdminView(ADMIN_FALLBACK_NAME, null));
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Authentication::getName)
            .filter(name -> name != null && !name.isBlank()
                && !AnonymousUserConst.isAnonymousUser(name))
            .flatMap(userService::getUser)
            .map(user -> new AdminView(displayNameOf(user), avatarOf(user)))
            .onErrorReturn(new AdminView(ADMIN_FALLBACK_NAME, null))
            .switchIfEmpty(fallback);
    }

    private static String displayNameOf(User user) {
        if (user == null) {
            return ADMIN_FALLBACK_NAME;
        }
        String displayName = user.getSpec() == null ? null : user.getSpec().getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return user.getMetadata() == null || user.getMetadata().getName() == null
            ? ADMIN_FALLBACK_NAME : user.getMetadata().getName();
    }

    private static String avatarOf(User user) {
        if (user == null || user.getSpec() == null) {
            return null;
        }
        String avatar = user.getSpec().getAvatar();
        return avatar == null || avatar.isBlank() ? null : avatar;
    }

    /** Adds a message to the conversation and queues its persistence. */
    public ChatEntry append(String ip, String visitorKey, String direction, String text,
                            String adminName, String adminAvatar, String pageUrl,
                            String pageTitle) {
        long now = System.currentTimeMillis();
        long order = seq.incrementAndGet();
        ChatEntry entry = new ChatEntry(order, ip, visitorKey, direction, text, now,
            adminName, adminAvatar, pageUrl, pageTitle, !"visitor".equals(direction));
        Deque<ChatEntry> thread =
            conversations.computeIfAbsent(ip, key -> new ConcurrentLinkedDeque<>());
        thread.addLast(entry);
        evictOldest(thread);
        trimConversations();
        persist(entry);
        return entry;
    }

    private void evictOldest(Deque<ChatEntry> thread) {
        while (thread.size() > MAX_PER_CONVERSATION) {
            ChatEntry removed = thread.pollFirst();
            if (removed == null) {
                return;
            }
            delete(removed);
        }
    }

    private void trimConversations() {
        while (conversations.size() > MAX_CONVERSATIONS) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Deque<ChatEntry>> entry : conversations.entrySet()) {
                ChatEntry last = entry.getValue().peekLast();
                long at = last == null ? 0L : last.sentAt();
                if (at < oldestAt) {
                    oldestAt = at;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) {
                return;
            }
            conversations.remove(oldest);
        }
    }

    /**
     * Messages after the caller's cursor. A cursor of zero means "first poll of this
     * browser", which returns the newest batch without replaying the whole history.
     */
    public List<ChatEntry> poll(String ip, long since, int limit) {
        Deque<ChatEntry> thread = conversations.get(ip);
        if (thread == null) {
            return List.of();
        }
        int size = thread.size();
        List<ChatEntry> result = new ArrayList<>();
        if (since <= 0) {
            int skip = Math.max(0, size - limit);
            int index = 0;
            for (ChatEntry entry : thread) {
                if (index++ >= skip) {
                    result.add(entry);
                }
            }
            return result;
        }
        for (ChatEntry entry : thread) {
            if (entry.seq() > since) {
                result.add(entry);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    public long latestSeq(String ip) {
        Deque<ChatEntry> thread = conversations.get(ip);
        ChatEntry last = thread == null ? null : thread.peekLast();
        return last == null ? 0L : last.seq();
    }

    /** Full thread for the console, newest last, capped like the poll path. */
    public List<ChatEntry> thread(String ip) {
        Deque<ChatEntry> thread = conversations.get(ip);
        if (thread == null) {
            return List.of();
        }
        return new ArrayList<>(thread);
    }

    public List<Conversation> conversations() {
        List<Conversation> result = new ArrayList<>();
        for (Map.Entry<String, Deque<ChatEntry>> entry : conversations.entrySet()) {
            Deque<ChatEntry> thread = entry.getValue();
            ChatEntry last = thread.peekLast();
            if (last == null) {
                continue;
            }
            int unread = 0;
            for (ChatEntry message : thread) {
                if (!message.readByAdmin()) {
                    unread++;
                }
            }
            result.add(new Conversation(entry.getKey(), last, unread, thread.size()));
        }
        result.sort(Comparator.comparingLong(
            (Conversation conversation) -> conversation.last().sentAt()).reversed());
        return result;
    }

    public int unreadTotal() {
        int unread = 0;
        for (Conversation conversation : conversations()) {
            unread += conversation.unread();
        }
        return unread;
    }

    /** Marks every visitor reply of one conversation as seen by the console. */
    public void markReadByAdmin(String ip) {
        Deque<ChatEntry> thread = conversations.get(ip);
        if (thread == null) {
            return;
        }
        for (ChatEntry entry : thread) {
            if (entry.markReadByAdmin()) {
                persist(entry);
            }
        }
    }

    private void loadAll() {
        List<ChatMessage> collected = new ArrayList<>();
        client.listAll(ChatMessage.class, ListOptions.builder().build(), Sort.unsorted())
            .onErrorResume(error -> {
                log.debug("Listing chat messages failed: {}", error.toString());
                return Flux.empty();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(collected::add, error -> log.warn("Failed to load chat messages", error),
                () -> {
                    Map<String, List<ChatEntry>> grouped = new LinkedHashMap<>();
                    for (ChatMessage message : collected) {
                        ChatEntry entry = toEntry(message);
                        if (entry != null) {
                            grouped.computeIfAbsent(entry.ip(), key -> new ArrayList<>())
                                .add(entry);
                        }
                    }
                    long highest = 0L;
                    for (Map.Entry<String, List<ChatEntry>> group : grouped.entrySet()) {
                        List<ChatEntry> thread = group.getValue();
                        thread.sort(Comparator.comparingLong(ChatEntry::seq));
                        Deque<ChatEntry> deque = new ConcurrentLinkedDeque<>();
                        int from = Math.max(0, thread.size() - MAX_PER_CONVERSATION);
                        for (int i = from; i < thread.size(); i++) {
                            ChatEntry entry = thread.get(i);
                            deque.addLast(entry);
                            highest = Math.max(highest, entry.seq());
                        }
                        for (int i = 0; i < from; i++) {
                            delete(thread.get(i));
                        }
                        if (!deque.isEmpty()) {
                            conversations.put(group.getKey(), deque);
                        }
                    }
                    seq.set(highest);
                    loaded = true;
                    trimConversations();
                    log.debug("Loaded {} chat conversations", conversations.size());
                });
    }

    private static ChatEntry toEntry(ChatMessage message) {
        if (message == null || message.getMetadata() == null
            || message.getMetadata().getName() == null || message.getSpec() == null) {
            return null;
        }
        ChatMessage.Spec spec = message.getSpec();
        if (spec.getIp() == null || spec.getSeq() == null) {
            return null;
        }
        return new ChatEntry(spec.getSeq(), spec.getIp(), spec.getVisitorKey(),
            spec.getDirection() == null ? "admin" : spec.getDirection(),
            spec.getText() == null ? "" : spec.getText(),
            spec.getSentAt() == null ? 0L : spec.getSentAt(),
            spec.getAdminName(), spec.getAdminAvatar(), spec.getPageUrl(),
            spec.getPageTitle(), Boolean.TRUE.equals(spec.getReadByAdmin()), true);
    }

    private void persist(ChatEntry entry) {
        ExecutorService executor = writer;
        if (executor == null || pendingWrites.get() >= MAX_PENDING_WRITES) {
            return;
        }
        pendingWrites.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    writeThrough(entry);
                } catch (Exception e) {
                    log.debug("Failed to persist chat message {}: {}", entry.name(),
                        e.toString());
                } finally {
                    pendingWrites.decrementAndGet();
                }
            });
        } catch (Exception e) {
            pendingWrites.decrementAndGet();
        }
    }

    private void writeThrough(ChatEntry entry) {
        if (!entry.isPersisted()) {
            try {
                client.create(toExtension(entry)).block(WRITE_TIMEOUT);
                entry.markPersisted();
                return;
            } catch (Exception createError) {
                // A message that is already in the store is updated instead, which keeps
                // the read flag of an existing thread correct.
                log.debug("Creating chat message {} failed: {}", entry.name(),
                    createError.toString());
            }
        }
        try {
            ChatMessage existing =
                client.fetch(ChatMessage.class, entry.name()).block(WRITE_TIMEOUT);
            if (existing == null || existing.getSpec() == null) {
                return;
            }
            existing.getSpec().setReadByAdmin(entry.readByAdmin());
            existing.getSpec().setText(entry.text());
            client.update(existing).block(WRITE_TIMEOUT);
            entry.markPersisted();
        } catch (Exception updateError) {
            log.debug("Failed to update chat message {}: {}", entry.name(),
                updateError.toString());
        }
    }

    private void delete(ChatEntry entry) {
        ExecutorService executor = writer;
        if (executor == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    client.fetch(ChatMessage.class, entry.name())
                        .flatMap(client::delete)
                        .block(WRITE_TIMEOUT);
                } catch (Exception e) {
                    log.debug("Failed to drop chat message {}: {}", entry.name(),
                        e.toString());
                }
            });
        } catch (Exception e) {
            log.debug("Failed to queue chat cleanup: {}", e.toString());
        }
    }

    private static ChatMessage toExtension(ChatEntry entry) {
        ChatMessage message = new ChatMessage();
        message.setMetadata(new Metadata());
        message.getMetadata().setName(entry.name());
        ChatMessage.Spec spec = message.getSpec();
        spec.setSeq(entry.seq());
        spec.setIp(entry.ip());
        spec.setVisitorKey(entry.visitorKey());
        spec.setDirection(entry.direction());
        spec.setText(entry.text());
        spec.setSentAt(entry.sentAt());
        spec.setAdminName(entry.adminName());
        spec.setAdminAvatar(entry.adminAvatar());
        spec.setPageUrl(entry.pageUrl());
        spec.setPageTitle(entry.pageTitle());
        spec.setReadByAdmin(entry.readByAdmin());
        return message;
    }

    /**
     * One message. The read flag is mutated in place because marking a conversation as
     * read must not copy the whole thread.
     */
    public static final class ChatEntry {

        private final long seq;
        private final String ip;
        private final String visitorKey;
        private final String direction;
        private final String text;
        private final long sentAt;
        private final String adminName;
        private final String adminAvatar;
        private final String pageUrl;
        private final String pageTitle;
        private final String name;
        private volatile boolean readByAdmin;
        private volatile boolean persisted;

        ChatEntry(long seq, String ip, String visitorKey, String direction, String text,
                  long sentAt, String adminName, String adminAvatar, String pageUrl,
                  String pageTitle, boolean readByAdmin) {
            this(seq, ip, visitorKey, direction, text, sentAt, adminName, adminAvatar,
                pageUrl, pageTitle, readByAdmin, false);
        }

        ChatEntry(long seq, String ip, String visitorKey, String direction, String text,
                  long sentAt, String adminName, String adminAvatar, String pageUrl,
                  String pageTitle, boolean readByAdmin, boolean persisted) {
            this.seq = seq;
            this.ip = ip;
            this.visitorKey = visitorKey;
            this.direction = direction;
            this.text = text;
            this.sentAt = sentAt;
            this.adminName = adminName;
            this.adminAvatar = adminAvatar;
            this.pageUrl = pageUrl;
            this.pageTitle = pageTitle;
            this.readByAdmin = readByAdmin;
            this.persisted = persisted;
            this.name = "c-" + Long.toString(seq, 36) + "-" + shortHash(ip);
        }

        private static String shortHash(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash, 0, 4);
            } catch (Exception e) {
                return Integer.toHexString(String.valueOf(value).hashCode());
            }
        }

        boolean markReadByAdmin() {
            if (readByAdmin) {
                return false;
            }
            readByAdmin = true;
            return true;
        }

        boolean isPersisted() {
            return persisted;
        }

        void markPersisted() {
            persisted = true;
        }

        public long seq() {
            return seq;
        }

        public String name() {
            return name;
        }

        public String ip() {
            return ip;
        }

        public String visitorKey() {
            return visitorKey;
        }

        public String direction() {
            return direction;
        }

        public String text() {
            return text;
        }

        public long sentAt() {
            return sentAt;
        }

        public String adminName() {
            return adminName;
        }

        public String adminAvatar() {
            return adminAvatar;
        }

        public String pageUrl() {
            return pageUrl;
        }

        public String pageTitle() {
            return pageTitle;
        }

        public boolean readByAdmin() {
            return readByAdmin;
        }
    }

    /** One row of the console conversation list. */
    public record Conversation(String ip, ChatEntry last, int unread, int total) {
    }

    /** Author snapshot attached to every console message. */
    public record AdminView(String name, String avatar) {
    }
}