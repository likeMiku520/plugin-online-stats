package com.fufu.onlinestats;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Conversations between the console and the people browsing the blog.
 *
 * <p>A conversation belongs to one device, identified by the same fingerprint the
 * visitor records use (client address plus User-Agent). The console addresses a device
 * rather than an address, and the poll path derives the key from the request itself,
 * never from a parameter, so a request can only ever reach its own conversation: a
 * second browser behind the same address, or a forged forwarding header, lands on a
 * different key and sees nothing.</p>
 *
 * <p>Messages are served from memory so a poll is cheap, and written to
 * {@link ChatMessage} extensions in the background so a plugin restart or an upgrade
 * does not lose the thread. Storage is bounded both by a per-conversation cap and by
 * the visitor retention setting.</p>
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
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(10);
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

    /**
     * Reads what is already stored before the first message can be written.
     *
     * <p>The order counter has to be seeded from storage first: a message written while
     * the load was still running reused a sequence number that already existed, which
     * both hid an older message behind the newcomer and let the read flag of the stored
     * copy come back.</p>
     */
    private void loadAll() {
        try {
            List<ChatMessage> stored = client
                .listAll(ChatMessage.class, ListOptions.builder().build(), Sort.unsorted())
                .collectList()
                .block(LOAD_TIMEOUT);
            applyLoaded(stored == null ? List.of() : stored);
        } catch (IllegalStateException blockingNotAllowed) {
            // Running on a thread that must not block; fall back to the asynchronous
            // load, which is still correct as long as it finishes before a write.
            log.debug("Loading chat messages asynchronously: {}", blockingNotAllowed.toString());
            client.listAll(ChatMessage.class, ListOptions.builder().build(), Sort.unsorted())
                .collectList()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(this::applyLoaded,
                    error -> log.warn("Failed to load chat messages", error));
        } catch (Exception error) {
            log.warn("Failed to load chat messages", error);
            loaded = true;
        }
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
     * The account behind the current request, or empty when nobody is signed in. The
     * name and avatar a visitor sees on their own message are read from the security
     * context rather than from the request body, so they cannot be claimed by a caller.
     */
    public Mono<Author> resolveSignedInUser() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Authentication::getName)
            .filter(name -> name != null && !name.isBlank()
                && !AnonymousUserConst.isAnonymousUser(name))
            .flatMap(userService::getUser)
            .map(user -> new Author(displayNameOf(user), avatarOf(user)))
            .onErrorResume(error -> Mono.empty());
    }

    /** Identity shown to the visitor for console messages. */
    public Mono<Author> resolveAdmin() {
        return resolveSignedInUser()
            .defaultIfEmpty(new Author(ADMIN_FALLBACK_NAME, null));
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

    /** Adds a message to one device's conversation and queues its persistence. */
    public ChatEntry append(String key, String ip, String direction, String text,
                            String authorName, String authorAvatar, String pageUrl,
                            String pageTitle) {
        long now = System.currentTimeMillis();
        long order = seq.incrementAndGet();
        boolean fromConsole = !"visitor".equals(direction);
        ChatEntry entry = new ChatEntry(order, key, ip, direction, text, now,
            authorName, authorAvatar, pageUrl, pageTitle, fromConsole, false,
            !fromConsole);
        Deque<ChatEntry> thread =
            conversations.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        thread.addLast(entry);
        evictOldest(thread);
        trimConversations();
        persist(entry);
        return entry;
    }

    /**
     * Sends the automatic greeting to a device that has never had a conversation, and
     * only once. Two tabs of the same browser can arrive at the same moment, so the
     * decision and the write happen under one lock.
     *
     * @return the message that was written, or null when this device already has one
     */
    public synchronized ChatEntry announceOnce(String key, String ip, String text,
                                               String authorName, String authorAvatar,
                                               String pageUrl, String pageTitle) {
        if (key == null || text == null || text.isBlank()) {
            return null;
        }
        if (latestSeq(key) > 0 || hasConversation(key)) {
            return null;
        }
        return append(key, ip, "admin", text, authorName, authorAvatar, pageUrl, pageTitle);
    }

    private boolean hasConversation(String key) {
        Deque<ChatEntry> thread = conversations.get(key);
        return thread != null && !thread.isEmpty();
    }

    /** Every conversation, used by the maintenance page to export and clear. */
    public List<Conversation> allConversations() {
        return conversations();
    }

    /**
     * Deletes one conversation and its stored messages.
     *
     * @return how many messages were removed
     */
    public int clearConversation(String key) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        Deque<ChatEntry> thread = conversations.remove(key);
        if (thread == null) {
            return 0;
        }
        List<ChatEntry> copy = new ArrayList<>(thread);
        for (ChatEntry entry : copy) {
            delete(entry);
        }
        return copy.size();
    }

    /** Deletes every conversation and its stored messages. */
    public int clearAllConversations() {
        int removed = 0;
        for (String key : new ArrayList<>(conversations.keySet())) {
            removed += clearConversation(key);
        }
        return removed;
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
            String oldestKey = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Deque<ChatEntry>> entry : conversations.entrySet()) {
                ChatEntry last = entry.getValue().peekLast();
                long at = last == null ? 0L : last.sentAt();
                if (at < oldestAt) {
                    oldestAt = at;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            conversations.remove(oldestKey);
        }
    }

    /**
     * Messages after the caller's cursor. A cursor of zero means "first poll of this
     * page", which returns the newest batch without replaying the whole history.
     */
    public List<ChatEntry> poll(String key, long since, int limit) {
        Deque<ChatEntry> thread = conversations.get(key);
        if (thread == null) {
            return List.of();
        }
        if (since <= 0) {
            return newest(thread, limit);
        }
        List<ChatEntry> result = new ArrayList<>();
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

    private static List<ChatEntry> newest(Deque<ChatEntry> thread, int limit) {
        List<ChatEntry> result = new ArrayList<>();
        int skip = Math.max(0, thread.size() - limit);
        int index = 0;
        for (ChatEntry entry : thread) {
            if (index++ >= skip) {
                result.add(entry);
            }
        }
        return result;
    }

    public long latestSeq(String key) {
        Deque<ChatEntry> thread = conversations.get(key);
        ChatEntry last = thread == null ? null : thread.peekLast();
        return last == null ? 0L : last.seq();
    }

    /** Full thread for the console, oldest first. */
    public List<ChatEntry> thread(String key) {
        List<ChatEntry> result = new ArrayList<>(poll(key, 0L, MAX_PER_CONVERSATION));
        return result;
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

    /**
     * Console messages this device has not been shown yet. Unlike an unread counter this
     * survives the visitor leaving the page: the message is handed over on the next
     * visit instead of living only in a poll that never happened.
     */
    public int pendingForVisitor(String key) {
        Deque<ChatEntry> thread = conversations.get(key);
        if (thread == null) {
            return 0;
        }
        int pending = 0;
        for (ChatEntry entry : thread) {
            if ("admin".equals(entry.direction()) && !entry.seenByVisitor()) {
                pending++;
            }
        }
        return pending;
    }

    /** Records that the visitor's page displayed the conversation. */
    public int markSeenByVisitor(String key) {
        Deque<ChatEntry> thread = conversations.get(key);
        if (thread == null) {
            return 0;
        }
        int marked = 0;
        for (ChatEntry entry : thread) {
            if ("admin".equals(entry.direction()) && entry.markSeenByVisitor()) {
                marked++;
                persist(entry);
            }
        }
        return marked;
    }

    /**
     * Who the console is talking to: the identity carried by the newest reply. A visitor
     * who was signed in to Halo is remembered by name and picture; everyone else has
     * neither, and the console shows a plain "访客".
     */
    public Author peer(String key) {
        Deque<ChatEntry> thread = conversations.get(key);
        if (thread == null) {
            return new Author(null, null);
        }
        String name = null;
        String avatar = null;
        for (ChatEntry entry : thread) {
            if ("visitor".equals(entry.direction()) && entry.authorName() != null
                && !entry.authorName().isBlank()) {
                name = entry.authorName();
                avatar = entry.authorAvatar();
            }
        }
        return new Author(name, avatar);
    }

    /** Marks every visitor reply of one conversation as seen by the console. */
    public void markReadByAdmin(String key) {
        Deque<ChatEntry> thread = conversations.get(key);
        if (thread == null) {
            return;
        }
        for (ChatEntry entry : thread) {
            if (entry.markReadByAdmin()) {
                persist(entry);
            }
        }
    }

    /**
     * Marks every conversation as seen. Reading a thread normally does this one
     * conversation at a time; this clears the reminder in one go for an administrator
     * who has already gone through the replies.
     */
    public int markAllReadByAdmin() {
        int marked = 0;
        for (String key : conversations.keySet()) {
            Deque<ChatEntry> thread = conversations.get(key);
            if (thread == null) {
                continue;
            }
            for (ChatEntry entry : thread) {
                if (entry.markReadByAdmin()) {
                    marked++;
                    persist(entry);
                }
            }
        }
        return marked;
    }

    /**
     * Drops messages older than the visitor retention window, in memory and in storage,
     * so a conversation cannot outlive the history it belongs to.
     */
    public void prune(int retentionDays) {
        long cutoff = Instant.now().minus(Math.max(1, retentionDays), ChronoUnit.DAYS)
            .toEpochMilli();
        for (Map.Entry<String, Deque<ChatEntry>> conversation : conversations.entrySet()) {
            Deque<ChatEntry> thread = conversation.getValue();
            List<ChatEntry> expired = new ArrayList<>();
            for (ChatEntry entry : thread) {
                if (entry.sentAt() < cutoff) {
                    expired.add(entry);
                }
            }
            for (ChatEntry entry : expired) {
                thread.remove(entry);
                delete(entry);
            }
            if (thread.isEmpty()) {
                conversations.remove(conversation.getKey());
            }
        }
    }

    private void applyLoaded(List<ChatMessage> collected) {
        Map<String, List<ChatEntry>> grouped = new LinkedHashMap<>();
        long highest = 0L;
        for (ChatMessage message : collected) {
            ChatEntry entry = toEntry(message);
            if (entry == null) {
                continue;
            }
            grouped.computeIfAbsent(entry.key(), ignored -> new ArrayList<>()).add(entry);
            highest = Math.max(highest, entry.seq());
        }
        for (Map.Entry<String, List<ChatEntry>> group : grouped.entrySet()) {
            List<ChatEntry> thread = group.getValue();
            thread.sort(Comparator.comparingLong(ChatEntry::seq));
            int from = Math.max(0, thread.size() - MAX_PER_CONVERSATION);
            for (int i = 0; i < from; i++) {
                delete(thread.get(i));
            }
            mergeLoaded(group.getKey(), thread.subList(from, thread.size()));
        }
        seq.set(Math.max(seq.get(), highest));
        loaded = true;
        trimConversations();
        log.debug("Loaded {} chat conversations", conversations.size());
    }

    /**
     * Folds stored messages into a conversation that may already be live.
     *
     * <p>An administrator can open a thread before this load finishes; marking it read
     * at that moment would be undone by replacing the conversation wholesale, which is
     * what made a reminder come back after a restart. Entries already in memory win, so
     * the read state is never downgraded.</p>
     */
    private void mergeLoaded(String key, List<ChatEntry> stored) {
        Deque<ChatEntry> live = conversations.get(key);
        if (live == null) {
            Deque<ChatEntry> deque = new ConcurrentLinkedDeque<>();
            stored.forEach(deque::addLast);
            if (!deque.isEmpty()) {
                conversations.put(key, deque);
            }
            return;
        }
        Set<Long> present = new HashSet<>();
        for (ChatEntry entry : live) {
            present.add(entry.seq());
        }
        List<ChatEntry> merged = new ArrayList<>(live);
        for (ChatEntry entry : stored) {
            if (!present.contains(entry.seq())) {
                merged.add(entry);
            }
        }
        merged.sort(Comparator.comparingLong(ChatEntry::seq));
        while (merged.size() > MAX_PER_CONVERSATION) {
            merged.remove(0);
        }
        live.clear();
        merged.forEach(live::addLast);
    }

    private static ChatEntry toEntry(ChatMessage message) {
        if (message == null || message.getMetadata() == null
            || message.getMetadata().getName() == null || message.getSpec() == null) {
            return null;
        }
        ChatMessage.Spec spec = message.getSpec();
        // Rows written before conversations were keyed by device have no key and belong
        // to no device; they are left for prune() to age out.
        if (spec.getVisitorKey() == null || spec.getSeq() == null) {
            return null;
        }
        return new ChatEntry(spec.getSeq(), spec.getVisitorKey(), spec.getIp(),
            spec.getDirection() == null ? "admin" : spec.getDirection(),
            spec.getText() == null ? "" : spec.getText(),
            spec.getSentAt() == null ? 0L : spec.getSentAt(),
            spec.getAuthorName(), spec.getAuthorAvatar(), spec.getPageUrl(),
            spec.getPageTitle(), Boolean.TRUE.equals(spec.getReadByAdmin()), true,
            Boolean.TRUE.equals(spec.getSeenByVisitor()));
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
            existing.getSpec().setSeenByVisitor(entry.seenByVisitor());
            existing.getSpec().setText(entry.text());
            existing.getSpec().setAuthorName(entry.authorName());
            existing.getSpec().setAuthorAvatar(entry.authorAvatar());
            client.update(existing).block(WRITE_TIMEOUT);
            entry.markPersisted();
        } catch (Exception updateError) {
            // A lost update would bring a read reply back as unread after a restart, so
            // the flag is written once more against the freshly stored revision.
            log.debug("Updating chat message {} failed, retrying: {}", entry.name(),
                updateError.toString());
            try {
                ChatMessage latest =
                    client.fetch(ChatMessage.class, entry.name()).block(WRITE_TIMEOUT);
                if (latest == null || latest.getSpec() == null) {
                    return;
                }
                latest.getSpec().setReadByAdmin(entry.readByAdmin());
                latest.getSpec().setSeenByVisitor(entry.seenByVisitor());
                latest.getSpec().setText(entry.text());
                latest.getSpec().setAuthorName(entry.authorName());
                latest.getSpec().setAuthorAvatar(entry.authorAvatar());
                client.update(latest).block(WRITE_TIMEOUT);
                entry.markPersisted();
            } catch (Exception retryError) {
                log.debug("Chat message {} still not updated: {}", entry.name(),
                    retryError.toString());
            }
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
        spec.setVisitorKey(entry.key());
        spec.setIp(entry.ip());
        spec.setDirection(entry.direction());
        spec.setText(entry.text());
        spec.setSentAt(entry.sentAt());
        spec.setAuthorName(entry.authorName());
        spec.setAuthorAvatar(entry.authorAvatar());
        spec.setPageUrl(entry.pageUrl());
        spec.setPageTitle(entry.pageTitle());
        spec.setReadByAdmin(entry.readByAdmin());
        spec.setSeenByVisitor(entry.seenByVisitor());
        return message;
    }

    /**
     * One message. The read flag is mutated in place because marking a conversation as
     * read must not copy the whole thread.
     */
    public static final class ChatEntry {

        private final long seq;
        private final String key;
        private final String ip;
        private final String direction;
        private final String text;
        private final long sentAt;
        private final String authorName;
        private final String authorAvatar;
        private final String pageUrl;
        private final String pageTitle;
        private final String name;
        private volatile boolean readByAdmin;
        private volatile boolean seenByVisitor;
        private volatile boolean persisted;

        ChatEntry(long seq, String key, String ip, String direction, String text,
                  long sentAt, String authorName, String authorAvatar, String pageUrl,
                  String pageTitle, boolean readByAdmin) {
            this(seq, key, ip, direction, text, sentAt, authorName, authorAvatar, pageUrl,
                pageTitle, readByAdmin, false);
        }

        ChatEntry(long seq, String key, String ip, String direction, String text,
                  long sentAt, String authorName, String authorAvatar, String pageUrl,
                  String pageTitle, boolean readByAdmin, boolean persisted) {
            this(seq, key, ip, direction, text, sentAt, authorName, authorAvatar, pageUrl,
                pageTitle, readByAdmin, persisted, true);
        }

        ChatEntry(long seq, String key, String ip, String direction, String text,
                  long sentAt, String authorName, String authorAvatar, String pageUrl,
                  String pageTitle, boolean readByAdmin, boolean persisted,
                  boolean seenByVisitor) {
            this.seq = seq;
            this.key = key;
            this.ip = ip;
            this.direction = direction;
            this.text = text;
            this.sentAt = sentAt;
            this.authorName = authorName;
            this.authorAvatar = authorAvatar;
            this.pageUrl = pageUrl;
            this.pageTitle = pageTitle;
            this.readByAdmin = readByAdmin;
            this.seenByVisitor = seenByVisitor;
            this.persisted = persisted;
            this.name = "c-" + Long.toString(seq, 36) + "-" + shortHash(key);
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

        boolean markSeenByVisitor() {
            if (seenByVisitor) {
                return false;
            }
            seenByVisitor = true;
            return true;
        }

        public boolean seenByVisitor() {
            return seenByVisitor;
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

        public String key() {
            return key;
        }

        public String ip() {
            return ip;
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

        public String authorName() {
            return authorName;
        }

        public String authorAvatar() {
            return authorAvatar;
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
    public record Conversation(String key, ChatEntry last, int unread, int total) {
    }

    /** A display identity: who wrote a message. */
    public record Author(String name, String avatar) {
    }
}