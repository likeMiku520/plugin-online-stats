package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The conversation window filters on two flags, and the list it reads comes from the
 * maintenance endpoint rather than from the conversation view. The flags have to be
 * produced where that list is built.
 */
class ConversationFilterFlagsTest {

    private static final String KEY = "v-flag-key";

    private ChatMessageService service() {
        return new ChatMessageService(null, null);
    }

    @Test
    void aGreetingAloneIsNeitherRepliedNorAnswered() {
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "欢迎光临", "访客自动消息", null, "/", "首页");

        ChatMessageService.Conversation conversation = service.conversations().get(0);
        assertFalse(conversation.replied(), "the visitor said nothing");
        assertFalse(conversation.answered(), "only the greeting was written");
    }

    @Test
    void aVisitorMessageCountsAsReplied() {
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "欢迎光临", "访客自动消息", null, "/", "首页");
        service.append(KEY, "10.0.0.1", "visitor", "在的", null, null, "/", "首页");

        ChatMessageService.Conversation conversation = service.conversations().get(0);
        assertTrue(conversation.replied());
        assertFalse(conversation.answered(), "nobody wrote to the visitor by hand");
    }

    @Test
    void aMessageTypedByHandCountsAsAnswered() {
        ChatMessageService service = service();
        service.announce(KEY, "10.0.0.1", "欢迎光临", "访客自动消息", null, "/", "首页");
        service.append(KEY, "10.0.0.1", "admin", "看到你的留言了", "fufu", null, "/", "首页");

        ChatMessageService.Conversation conversation = service.conversations().get(0);
        assertFalse(conversation.replied(), "the visitor still said nothing");
        assertTrue(conversation.answered());
    }

    @Test
    void anOldGreetingWithoutTheFlagIsStillRecognised() {
        ChatMessageService service = service();
        // A row written before the flag existed arrives with automated = false and only
        // its text to go by.
        service.append(KEY, "10.0.0.1", "admin", "欢迎光临", "访客自动消息", null, "/", "首页");
        service.setGreetingText("欢迎光临");

        List<ChatMessageService.Conversation> conversations = service.conversations();
        assertEquals(1, conversations.size());
        assertFalse(conversations.get(0).answered(), "the greeting is not a reply");
    }
}