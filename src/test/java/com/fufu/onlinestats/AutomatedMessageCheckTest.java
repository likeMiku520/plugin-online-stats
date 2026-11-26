package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "Management replied" must not include a conversation that only holds the automatic
 * greeting. Rows written before the automated flag existed carry no mark, so the text and
 * the sender decide — and every caller has to ask the same question.
 */
class AutomatedMessageCheckTest {

    private static final String KEY = "v-check-key";

    private ChatMessageService service() {
        return new ChatMessageService(null, null);
    }

    @Test
    void anOldGreetingIsRecognisedByItsText() {
        ChatMessageService service = service();
        // written before the flag existed: append() leaves automated false
        service.append(KEY, "10.0.0.1", "admin", "欢迎光临", "访客自动消息", null, "/", "首页");
        service.setGreeting("欢迎光临", "访客自动消息");

        ChatMessageService.ChatEntry entry = service.thread(KEY).get(0);
        assertTrue(service.isAutomatedMessage(entry), "the greeting is not a reply");
    }

    @Test
    void anOldGreetingIsAlsoRecognisedByItsSender() {
        ChatMessageService service = service();
        // the wording was edited later, so the text no longer matches
        service.append(KEY, "10.0.0.1", "admin", "旧版的欢迎语", "访客自动消息", null, "/", "首页");
        service.setGreeting("新版的欢迎语", "访客自动消息");

        ChatMessageService.ChatEntry entry = service.thread(KEY).get(0);
        assertTrue(service.isAutomatedMessage(entry), "the sender gives it away");
    }

    @Test
    void aMessageTypedByHandIsNotAutomated() {
        ChatMessageService service = service();
        service.setGreeting("欢迎光临", "访客自动消息");
        service.append(KEY, "10.0.0.1", "admin", "看到你的留言了", "fufu", null, "/", "首页");

        ChatMessageService.ChatEntry entry = service.thread(KEY).get(0);
        assertFalse(service.isAutomatedMessage(entry));
    }

    @Test
    void aVisitorMessageIsNeverAutomated() {
        ChatMessageService service = service();
        service.append(KEY, "10.0.0.1", "visitor", "欢迎光临", "访客自动消息", null, "/", "首页");

        ChatMessageService.ChatEntry entry = service.thread(KEY).get(0);
        assertFalse(service.isAutomatedMessage(entry), "only an administrative message counts");
    }
}