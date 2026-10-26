package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
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
        return request(scheme, Map.of(), forwardedProto);
    }

    private static ServerRequest request(String scheme, Map<String, String> extraHeaders,
                                         String forwardedProto) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "probe");
        if (forwardedProto != null) {
            headers.set("X-Forwarded-Proto", forwardedProto);
        }
        extraHeaders.forEach(headers::set);
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

    @Test
    void aCdnThatPullsFromTheOriginOverHttpIsStillRecognisedAsEncrypted() {
        // The reported deployment: TLS ends at the CDN, the origin is reached in plain
        // on an internal port, and nothing rewrites the scheme. The browser still says
        // where the page came from, which is the signal that keeps the guard usable.
        Map<String, String> referer =
            Map.of(HttpHeaders.REFERER, "https://blog.fufu520.cn/archives/1");
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("http", referer, null),
            settings(true)));

        Map<String, String> origin = Map.of(HttpHeaders.ORIGIN, "https://blog.fufu520.cn");
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("http", origin, null),
            settings(true)));
    }

    @Test
    void otherForwardingDialectsAreUnderstoodToo() {
        Map<String, String> forwarded = Map.of("Forwarded", "for=1.2.3.4;proto=https;by=cdn");
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("http", forwarded, null),
            settings(true)));

        Map<String, String> cfVisitor = Map.of("CF-Visitor", "{\"scheme\":\"https\"}");
        assertFalse(OnlineStatsEndpoint.rejectsPlaintext(request("http", cfVisitor, null),
            settings(true)));
    }

    @Test
    void aGenuinelyPlainPageIsStillRefusedWhenTheGuardIsOn() {
        Map<String, String> plainReferer =
            Map.of(HttpHeaders.REFERER, "http://blog.fufu520.cn/archives/1");
        assertTrue(OnlineStatsEndpoint.rejectsPlaintext(request("http", plainReferer, null),
            settings(true)));

        Map<String, String> plainCf = Map.of("CF-Visitor", "{\"scheme\":\"http\"}");
        assertTrue(OnlineStatsEndpoint.rejectsPlaintext(request("http", plainCf, null),
            settings(true)));
    }
}