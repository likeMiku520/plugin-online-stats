package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The visitor card polls with a cursor, so the cursor contract is what the whole
 * conversation feature rests on: zero means "first poll, send the newest batch
 * without replaying history", anything else means "only what is newer".
 */
class ChatMessageServiceTest {

    private static ChatMessageService service() {
        // Persistence is queued to a writer that start() creates, so an unstarted
        // service is memory only and needs no extension client.
        return new ChatMessageService(null, null);
    }

    @Test
    void firstPollReturnsTheNewestMessagesInsteadOfTheOldest() {
        ChatMessageService service = service();
        for (int i = 0; i < 5; i++) {
            service.append("10.0.0.1", "v-1", "admin", "message " + i, "fufu", null, null, null);
        }

        List<ChatMessageService.ChatEntry> batch = service.poll("10.0.0.1", 0L, 3);

        assertEquals(3, batch.size());
        assertEquals(List.of("message 2", "message 3", "message 4"),
            batch.stream().map(ChatMessageService.ChatEntry::text).toList());
    }

    @Test
    void cursorReturnsOnlyNewerMessagesInOrder() {
        ChatMessageService service = service();
        long first = service.append("10.0.0.1", "v-1", "admin", "hi", "fufu", null, null, null).seq();
        service.append("10.0.0.1", "v-1", "admin", "are you there", "fufu", null, null, null);
        service.append("10.0.0.1", "v-1", "visitor", "yes", null, null, "/post", "Post");

        List<ChatMessageService.ChatEntry> fresh = service.poll("10.0.0.1", first, 50);

        assertEquals(List.of("are you there", "yes"),
            fresh.stream().map(ChatMessageService.ChatEntry::text).toList());
        assertEquals(3L, service.latestSeq("10.0.0.1"));
    }

    @Test
    void conversationCountsRepliesUntilTheConsoleReadsThem() {
        ChatMessageService service = service();
        service.append("10.0.0.1", "v-1", "admin", "hi", "fufu", null, null, null);
        service.append("10.0.0.1", "v-1", "visitor", "hello", null, null, null, null);
        service.append("10.0.0.1", "v-1", "visitor", "again", null, null, null, null);
        service.append("10.0.0.2", "v-2", "admin", "other visitor", "fufu", null, null, null);

        List<ChatMessageService.Conversation> conversations = service.conversations();

        assertEquals(2, conversations.size());
        assertEquals(2, service.unreadTotal());

        service.markReadByAdmin("10.0.0.1");

        assertEquals(0, service.unreadTotal());
    }

    @Test
    void anUnknownAddressHasNoConversation() {
        ChatMessageService service = service();
        service.append("10.0.0.1", "v-1", "admin", "hi", "fufu", null, null, null);

        assertTrue(service.poll("10.0.0.9", 0L, 50).isEmpty());
        assertEquals(0L, service.latestSeq("10.0.0.9"));
    }
}