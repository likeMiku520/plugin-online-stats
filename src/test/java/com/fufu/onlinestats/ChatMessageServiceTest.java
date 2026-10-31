package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * The visitor card polls with a cursor and reaches its conversation through a device
 * key, and storage is bounded by the visitor retention window. All three contracts are
 * pinned here.
 */
class ChatMessageServiceTest {

    private static final String KEY = "v-0123456789abcdef";
    private static final String OTHER_KEY = "v-fedcba9876543210";
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static ChatMessageService service() {
        // Persistence is queued to a writer that start() creates, so an unstarted
        // service is memory only and needs no extension client.
        return new ChatMessageService(null, null);
    }

    private static void say(ChatMessageService service, String key, String direction,
                            String text) {
        service.append(key, "10.0.0.1", direction, text, "fufu", null, null, null);
    }

    private static ChatMessage stored(String name, long seq, long sentAt, String text) {
        ChatMessage message = new ChatMessage();
        message.setMetadata(new Metadata());
        message.getMetadata().setName(name);
        message.getSpec().setSeq(seq);
        message.getSpec().setVisitorKey(KEY);
        message.getSpec().setIp("10.0.0.1");
        message.getSpec().setDirection("admin");
        message.getSpec().setText(text);
        message.getSpec().setSentAt(sentAt);
        return message;
    }

    private static void await(BooleanSupplier condition, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("condition was not met in time");
    }

    @Test
    void firstPollReturnsTheNewestMessagesInsteadOfTheOldest() {
        ChatMessageService service = service();
        for (int i = 0; i < 5; i++) {
            say(service, KEY, "admin", "message " + i);
        }

        List<ChatMessageService.ChatEntry> batch = service.poll(KEY, 0L, 3);

        assertEquals(3, batch.size());
        assertEquals(List.of("message 2", "message 3", "message 4"),
            batch.stream().map(ChatMessageService.ChatEntry::text).toList());
    }

    @Test
    void cursorReturnsOnlyNewerMessagesInOrder() {
        ChatMessageService service = service();
        say(service, KEY, "admin", "hi");
        long first = service.latestSeq(KEY);
        say(service, KEY, "admin", "are you there");
        say(service, KEY, "visitor", "yes");

        List<ChatMessageService.ChatEntry> fresh = service.poll(KEY, first, 50);

        assertEquals(List.of("are you there", "yes"),
            fresh.stream().map(ChatMessageService.ChatEntry::text).toList());
        assertEquals(3L, service.latestSeq(KEY));
    }

    @Test
    void aConversationIsOnlyReachableThroughItsOwnDeviceKey() {
        ChatMessageService service = service();
        say(service, KEY, "admin", "for one device only");

        assertTrue(service.poll(OTHER_KEY, 0L, 50).isEmpty());
        assertEquals(0L, service.latestSeq(OTHER_KEY));
        assertEquals(1, service.conversations().size());
        assertEquals(KEY, service.conversations().get(0).key());
    }

    @Test
    void conversationCountsRepliesUntilTheConsoleReadsThem() {
        ChatMessageService service = service();
        say(service, KEY, "admin", "hi");
        say(service, KEY, "visitor", "hello");
        say(service, KEY, "visitor", "again");
        say(service, OTHER_KEY, "admin", "other device");

        assertEquals(2, service.conversations().size());
        assertEquals(2, service.unreadTotal());

        service.markReadByAdmin(KEY);

        assertEquals(0, service.unreadTotal());
    }

    @Test
    void repliesCarryTheIdentityTheServerResolvedForThem() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "visitor", "hello", "小明",
            "https://blog.fufu520.cn/upload/x.webp", "/post", "Post");

        ChatMessageService.ChatEntry entry = service.thread(KEY).get(0);

        assertEquals("小明", entry.authorName());
        assertEquals("https://blog.fufu520.cn/upload/x.webp", entry.authorAvatar());
        assertEquals("/post", entry.pageUrl());
        assertEquals("Post", entry.pageTitle());
        assertFalse(entry.readByAdmin());
    }

    @Test
    void markingEverythingReadClearsEveryConversation() {
        ChatMessageService service = service();
        say(service, KEY, "visitor", "one");
        say(service, KEY, "admin", "answer");
        say(service, KEY, "visitor", "two");
        say(service, OTHER_KEY, "visitor", "other device");
        assertEquals(3, service.unreadTotal());

        int marked = service.markAllReadByAdmin();

        assertEquals(3, marked);
        assertEquals(0, service.unreadTotal());
        assertEquals(0, service.markAllReadByAdmin(), "nothing left to mark");
    }

    @Test
    void aRestartKeepsWhatWasAlreadyRead() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        ChatMessage read = stored("c-1", 1L, System.currentTimeMillis(), "from the visitor");
        read.getSpec().setDirection("visitor");
        read.getSpec().setReadByAdmin(true);
        ChatMessage unread = stored("c-2", 2L, System.currentTimeMillis(), "a newer reply");
        unread.getSpec().setDirection("visitor");
        unread.getSpec().setReadByAdmin(false);
        doReturn(Flux.just(read, unread))
            .when(client).listAll(eq(ChatMessage.class), any(ListOptions.class),
                any(Sort.class));

        ChatMessageService service = new ChatMessageService(client, null);
        service.start();
        try {
            assertTrue(service.isLoaded());
            assertEquals(1, service.unreadTotal(), "only the reply that was never read");
        } finally {
            service.stop();
        }
    }

    @Test
    void aMessageWrittenAfterARestartDoesNotReuseAStoredSequence() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        doReturn(Flux.just(
            stored("c-1", 1L, System.currentTimeMillis(), "older"),
            stored("c-2", 2L, System.currentTimeMillis(), "newer")))
            .when(client).listAll(eq(ChatMessage.class), any(ListOptions.class),
                any(Sort.class));

        ChatMessageService service = new ChatMessageService(client, null);
        service.start();
        try {
            ChatMessageService.ChatEntry entry =
                service.append(KEY, "10.0.0.1", "admin", "after the restart", "fufu", null,
                    null, null);

            assertEquals(3L, entry.seq(), "the counter continues where storage stopped");
            assertEquals(3, service.thread(KEY).size());
        } finally {
            service.stop();
        }
    }

    @Test
    void aMessageWrittenToAnAbsentVisitorWaitsForTheNextVisit() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "admin", "while nobody was here", "fufu", null,
            null, null);

        assertEquals(1, service.pendingForVisitor(KEY), "never displayed, so it waits");
        assertEquals(0, service.pendingForVisitor(OTHER_KEY));

        service.markSeenByVisitor(KEY);

        assertEquals(0, service.pendingForVisitor(KEY));
        assertEquals(1, service.thread(KEY).size(), "the message is still there");
    }

    @Test
    void aVisitorReplyIsNotWaitingToBeDelivered() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "visitor", "hello", null, null, null, null);

        assertEquals(0, service.pendingForVisitor(KEY));
    }

    @Test
    void clearingTheUnreadReminderDoesNotCountAsDelivered() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "visitor", "a reply", null, null, null, null);
        service.append(KEY, "10.0.0.1", "admin", "the answer", "fufu", null, null, null);

        service.markAllReadByAdmin();

        assertEquals(0, service.unreadTotal(), "nothing left for the console to read");
        assertEquals(1, service.pendingForVisitor(KEY),
            "the visitor still has not seen the console's answer");
    }

    @Test
    void theGreetingIsWrittenOnceAndWaitsForTheVisitor() {
        ChatMessageService service = service();

        ChatMessageService.ChatEntry first = service.announce(KEY, "10.0.0.1",
            "第一次来，随便看看～", "访客消息", "https://cdn.example.com/a.png", "/", "首页");
        ChatMessageService.ChatEntry second = service.announce(KEY, "10.0.0.1",
            "第一次来，随便看看～", "访客消息", null, "/", "首页");

        assertTrue(first != null);
        assertTrue(second == null, "two tabs of one browser must not greet twice");
        assertEquals("访客消息", first.authorName());
        assertEquals(1, service.pendingForVisitor(KEY),
            "nothing displayed it yet, so the next poll opens the card");
    }

    @Test
    void aVisitorWhoAlreadyTalkedIsNotGreeted() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "visitor", "我先说了一句", null, null, null, null);

        assertTrue(service.announce(KEY, "10.0.0.1", "第一次来", "访客消息", null,
            "/", "首页") == null);
        assertEquals(1, service.thread(KEY).size());
    }

    @Test
    void anEmptyGreetingIsNotSent() {
        ChatMessageService service = service();

        assertTrue(service.announce(KEY, "10.0.0.1", "   ", "访客消息", null,
            "/", "首页") == null);
        assertEquals(0, service.thread(KEY).size());
    }

    @Test
    void anEditedGreetingReachesADeviceThatAlreadyHasOne() {
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "第一版内容", "访客消息", null, "/", "首页");
        service.announce(KEY, "10.0.0.1", "第二版内容", "访客消息", null, "/", "首页");

        List<ChatMessageService.ChatEntry> thread = service.thread(KEY);
        assertEquals(2, thread.size(), "the edited text is sent again");
        assertEquals("第二版内容", thread.get(thread.size() - 1).text());
    }

    @Test
    void anUnchangedGreetingIsNotSentTwice() {
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "同一段内容", "访客消息", null, "/", "首页");
        service.announce(KEY, "10.0.0.1", "同一段内容", "访客消息", null, "/", "首页");

        assertEquals(1, service.thread(KEY).size(), "the same text never repeats");
    }

    @Test
    void aVisitorWhoSpokeFirstKeepsTheirOwnConversation() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "visitor", "我先说话的", null, null, "/", "首页");
        service.announce(KEY, "10.0.0.1", "公告", "访客消息", null, "/", "首页");

        List<ChatMessageService.ChatEntry> thread = service.thread(KEY);
        assertEquals(1, thread.size(), "nothing is inserted ahead of the visitor");
        assertEquals("我先说话的", thread.get(0).text());
    }

    @Test
    void anEditedGreetingIsWhatTheNextGuestActuallyGets() {
        // Two guests arriving at different times, with the text changed in between.
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "第一版内容", "访客消息", null, "/", "首页");
        service.announce(OTHER_KEY, "10.0.0.2", "第二版内容", "访客消息", null, "/", "首页");

        assertEquals("第一版内容", service.thread(KEY).get(0).text());
        assertEquals("第二版内容", service.thread(OTHER_KEY).get(0).text(),
            "the newer text is used, not the one stored first");
    }

    @Test
    void messagesOlderThanTheRetentionWindowAreDropped() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        doReturn(Flux.just(
            stored("c-old", 1L, System.currentTimeMillis() - 40L * DAY, "ancient"),
            stored("c-new", 2L, System.currentTimeMillis(), "fresh")))
            .when(client).listAll(eq(ChatMessage.class), any(ListOptions.class),
                any(Sort.class));

        ChatMessageService service = new ChatMessageService(client, null);
        service.start();
        try {
            await(service::isLoaded, 5_000L);
            assertEquals(2, service.thread(KEY).size());

            service.prune(30);

            List<ChatMessageService.ChatEntry> left = service.thread(KEY);
            assertEquals(1, left.size());
            assertEquals("fresh", left.get(0).text());
        } finally {
            service.stop();
        }
    }

    @Test
    void theConsoleSeesWhoItIsTalkingTo() {
        ChatMessageService service = service();
        say(service, KEY, "admin", "hi");
        assertEquals(null, service.peer(KEY).name(), "no reply yet, so no identity");

        service.append(KEY, "10.0.0.1", "visitor", "hello", "小明",
            "https://blog.fufu520.cn/upload/x.webp", null, null);

        assertEquals("小明", service.peer(KEY).name());
        assertEquals("https://blog.fufu520.cn/upload/x.webp", service.peer(KEY).avatar());
    }

    @Test
    void anAnonymousReplyLeavesThePeerUnnamed() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "visitor", "hello", null, null, null, null);

        assertEquals(null, service.peer(KEY).name());
        assertEquals(null, service.peer(OTHER_KEY).name(), "another device has its own peer");
    }

    @Test
    void eachMessageKeepsTheAuthorItWasWrittenWith() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "admin", "first", "甲管理员",
            "https://blog.fufu520.cn/upload/a.webp", null, null);
        service.append(KEY, "10.0.0.1", "admin", "second", "乙管理员",
            "https://blog.fufu520.cn/upload/b.webp", null, null);

        List<ChatMessageService.ChatEntry> thread = service.thread(KEY);

        assertEquals("甲管理员", thread.get(0).authorName());
        assertEquals("https://blog.fufu520.cn/upload/a.webp", thread.get(0).authorAvatar());
        assertEquals("乙管理员", thread.get(1).authorName());
        assertEquals("https://blog.fufu520.cn/upload/b.webp", thread.get(1).authorAvatar());
    }
}