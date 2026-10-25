package com.fufu.onlinestats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * One message of a console-to-visitor conversation.
 *
 * <p>A conversation is keyed by the visitor's device fingerprint (client address plus
 * User-Agent), the same key the visitor records use, so only that one device can read
 * the thread: another device behind the same address gets a different key.</p>
 */
@GVK(group = "online-stats.halo.run", version = "v1alpha1",
    kind = "ChatMessage", plural = "chatmessages", singular = "chatmessage")
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage extends AbstractExtension {

    private Spec spec = new Spec();

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Spec {

        /** Monotonic order of the conversation, used as the client's poll cursor. */
        private Long seq;

        /** Device fingerprint that owns this conversation. */
        private String visitorKey;

        /** Address the device was seen from, for the console to show. */
        private String ip;

        /** "admin" for a message written in the console, "visitor" for a reply. */
        private String direction;

        private String text;

        private Long sentAt;

        /**
         * Who wrote it: the administrator's name for a console message, and the signed
         * in account's name when a logged in visitor replies. Null means "访客".
         */
        private String authorName;

        private String authorAvatar;

        /** Page the visitor was on when the message was written. */
        private String pageUrl;

        private String pageTitle;

        private Boolean readByAdmin;
    }
}