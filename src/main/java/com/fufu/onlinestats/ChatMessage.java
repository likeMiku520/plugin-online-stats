package com.fufu.onlinestats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * One message of a console-to-visitor conversation. A conversation is keyed by the
 * visitor's IP address, so every browser of that address sees the same thread.
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

        private String ip;

        /** Device fingerprint of the sender, so the console can link to its record. */
        private String visitorKey;

        /** "admin" for a message written in the console, "visitor" for a reply. */
        private String direction;

        private String text;

        private Long sentAt;

        /** Author snapshot, so the visitor popup needs no user lookup of its own. */
        private String adminName;

        private String adminAvatar;

        /** Page the visitor was on when the message was written. */
        private String pageUrl;

        private String pageTitle;

        private Boolean readByAdmin;
    }
}