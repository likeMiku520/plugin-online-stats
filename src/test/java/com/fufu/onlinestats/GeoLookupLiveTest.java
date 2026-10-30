package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End to end check of the three things the console lets people configure: the address,
 * the extra parameters and the field list. A local server stands in for the provider so
 * the exact URL that leaves the plugin can be inspected.
 */
class GeoLookupLiveTest {

    private HttpServer server;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geo", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastPath.set(exchange.getRequestURI().getPath());
            byte[] body = ("{\"data\":{\"city\":\"武汉市\",\"isp\":\"中国移动\","
                + "\"province\":\"湖北省\"},\"code\":200}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aCustomAddressIsUsedWithItsParameters() {
        IpGeoService service = new IpGeoService();
        service.configure(base + "/geo?ip={ip}");
        service.setExtraParams(List.of(new OnlineStatsSettings.GeoParam("token", "abc"),
            new OnlineStatsSettings.GeoParam("lang", "zh-CN")));

        IpLocation location = service.lookup("1.2.3.4").block();

        assertEquals("/geo", lastPath.get(), "the configured address was called");
        assertEquals("ip=1.2.3.4&token=abc&lang=zh-CN", lastQuery.get(),
            "the address and both extra parameters reached the provider");
        assertTrue(location.resolved());
        assertEquals("武汉市", location.city());
    }

    @Test
    void theFieldListDecidesWhatComesBack() {
        IpGeoService service = new IpGeoService();
        service.configure(base + "/geo?ip={ip}");
        service.setFields(List.of(
            new OnlineStatsSettings.GeoField("城市", "data.city"),
            new OnlineStatsSettings.GeoField("运营商", "data.isp")));

        IpLocation location = service.lookup("1.2.3.4").block();

        assertEquals("武汉市", location.fields().get("城市"));
        assertEquals("中国移动", location.fields().get("运营商"));
    }

    @Test
    void aStoredOldPlaceholderStillResolvesToTheConfiguredAddress() {
        // A setting saved before the placeholder changed.
        IpGeoService service = new IpGeoService();
        service.configure(base + "/geo?ip=%s");

        IpLocation location = service.lookup("1.2.3.4").block();

        assertEquals("/geo", lastPath.get());
        assertEquals("ip=1.2.3.4", lastQuery.get());
        assertTrue(location.resolved());
    }
}