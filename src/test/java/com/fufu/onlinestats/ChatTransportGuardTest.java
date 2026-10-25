package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * The plaintext guard once shipped enabled by default and cut the conversation off on a
 * site where every visitor was on HTTPS, because a proxied Halo only sees the internal
 * hop. These tests pin the behaviour that made it safe again: off unless asked for, and
 * tolerant of the ways a proxy can report the original scheme.
 */
class ChatTransportGuardTest {

    private static ServerRequest request(String scheme, String forwardedProto) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "probe");
        if (forwardedProto != null) {
            headers.set("X-Forwarded-Proto", forwardedProto);
        }
        return MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create(scheme
                + "://blog.fufu520.cn/apis/online-stats.halo.run/v1alpha1/chat-inbox"))
            .headers(headers)
            .build();
    }

    private static OnlineStatsSettings settings(Boolean requireHttps) {
        return new OnlineStatsSettings(null, null, null, null, null, null, null, null, null,
            null, requireHttps);
    }

    @Test
    void theGuardIsOffWhenNothingWasConfigured() {
        assertFalse(settings(null).chatRequireHttpsOrDefault());
        assertFalse(OnlineStatsSettings.empty().chatRequireHttpsOrDefault());
    }

    @Test
    void anUnconfiguredDeploymentNeverRefusesAPlainRequest() {
        // The reported failure: https to the visitor, http on the internal hop, no
        // usable forwarding header. The conversation must keep working.
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("http", null),
            settings(null)));
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("http", "http"),
            settings(false)));
    }

    @Test
    void anOptedInDeploymentRefusesWhatItCannotSeeAsEncrypted() {
        assertTrue(OnlineStatsEndpoint.rejectsPlaintext(request("http", null),
            settings(true)));
        assertTrue(OnlineStatsEndpoint.rejectsPlaintext(request("http", "http"),
            settings(true)));
    }

    @Test
    void anOptedInDeploymentAcceptsEveryWayOfReportingHttps() {
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("https", null),
            settings(true)));
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("http", "https"),
            settings(true)));
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("http", "https, http"),
            settings(true)));
    }
}