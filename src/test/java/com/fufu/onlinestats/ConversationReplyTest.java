package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A thread exists as soon as the automatic greeting is written, so counting threads
 * reported a conversation for every visitor who merely loaded a page. Only a thread the
 * visitor wrote in is a conversation a reader would recognise.
 */
class ConversationReplyTest {

    private static final String KEY = "v-test-key";
    private static final String OTHER = "v-other-key";

    private ChatMessageService service() {
        return new ChatMessageService(null, null);
    }

    @Test
    void aThreadWithOnlyTheGreetingIsNotAConversation() {
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "欢迎", "访客消息", null, "/", "首页");

        ChatMessageService.Conversation conversation = service.conversations().get(0);
        assertEquals(1, conversation.total());
        assertFalse(conversation.replied(), "the visitor never wrote anything");
    }

    @Test
    void aThreadTheVisitorAnsweredIsAConversation() {
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "欢迎", "访客消息", null, "/", "首页");
        service.append(KEY, "10.0.0.1", "visitor", "在的", null, null, "/", "首页");

        ChatMessageService.Conversation conversation = service.conversations().get(0);
        assertTrue(conversation.replied(), "the visitor replied");
    }

    @Test
    void theTwoKindsStayTellable() {
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "欢迎", "访客消息", null, "/", "首页");
        service.announce(OTHER, "10.0.0.2", "欢迎", "访客消息", null, "/", "首页");
        service.append(OTHER, "10.0.0.2", "visitor", "看到了", null, null, "/", "首页");

        List<ChatMessageService.Conversation> all = service.conversations();
        assertEquals(2, all.size(), "both threads exist");
        assertEquals(1, all.stream().filter(ChatMessageService.Conversation::replied).count(),
            "only one of them is a real exchange");
    }
}