package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lookup address is a template: {@code {ip}} is where the address goes, extra
 * parameters are appended, and the shown values come from a configurable path list.
 */
class GeoTemplateTest {

    private static final String IP = "223.96.141.233";

    @Test
    void thePlaceholderIsFilled() {
        assertEquals("https://example.com/?ip=" + IP,
            IpGeoService.buildUrl("https://example.com/?ip={ip}", IP, List.of()));
    }

    @Test
    void theOldPlaceholderStillWorks() {
        // Settings saved before 3.0.0 use %s; they keep working until saved again.
        assertEquals("https://example.com/?ip=" + IP,
            IpGeoService.buildUrl("https://example.com/?ip=%s", IP, List.of()));
        assertTrue(IpGeoService.hasIpPlaceholder("https://x/?ip=%s"));
        assertTrue(IpGeoService.hasIpPlaceholder("https://x/?ip={ip}"));
        assertFalse(IpGeoService.hasIpPlaceholder("https://x/?ip="));
    }

    @Test
    void extraParametersAreAppendedAndEncoded() {
        String url = IpGeoService.buildUrl("https://example.com/?ip={ip}", IP,
            List.of(new OnlineStatsSettings.GeoParam("token", "a+b&c"),
                new OnlineStatsSettings.GeoParam("lang", "zh-CN")));

        assertEquals("https://example.com/?ip=" + IP + "&token=a%2Bb%26c&lang=zh-CN", url);
    }

    @Test
    void aTemplateWithoutAQueryGetsOneAppended() {
        String url = IpGeoService.buildUrl("https://example.com/geo/{ip}", IP,
            List.of(new OnlineStatsSettings.GeoParam("key", "v")));

        assertEquals("https://example.com/geo/" + IP + "?key=v", url);
    }

    @Test
    void aConfiguredFieldListDecidesWhatIsShown() throws Exception {
        IpGeoService service = new IpGeoService();
        service.setFields(List.of(
            new OnlineStatsSettings.GeoField("城市", "data.city"),
            new OnlineStatsSettings.GeoField("运营商", "data.isp")));

        IpLocation location = service.parse(IP, 200,
            "{\"data\":{\"city\":\"武汉市\",\"isp\":\"中国移动\",\"noise\":\"x\"}}");

        assertTrue(location.resolved());
        assertEquals("武汉市", location.fields().get("城市"));
        assertEquals("中国移动", location.fields().get("运营商"));
        assertEquals("武汉市", location.city(), "the standard fields follow the labels");
        assertEquals("中国移动", location.isp());
    }

    @Test
    void anUnconfiguredListKeepsTheBuiltInMapping() {
        IpGeoService service = new IpGeoService();

        IpLocation location = service.parse(IP, 200,
            "{\"code\":200,\"data\":{\"ip\":\"" + IP + "\",\"province\":\"湖北省\","
                + "\"city\":\"武汉市\",\"isp\":\"中国移动\"}}");

        assertTrue(location.resolved());
        assertEquals("湖北省", location.province());
    }

    @Test
    void aPathWalksIntoNestedObjectsAndArrays() throws Exception {
        var root = new ObjectMapper().readTree(
            "{\"data\":{\"list\":[{\"name\":\"first\"}]},\"plain\":\"v\"}");

        assertEquals("v", IpGeoService.readPath(root, "plain"));
        assertEquals("first", IpGeoService.readPath(root, "data.list.0.name"));
        assertNull(IpGeoService.readPath(root, "data.missing"));
        assertNull(IpGeoService.readPath(root, "plain.deeper"));
    }

    @Test
    void settingsReadThePairListsWrittenByTheForm() {
        OnlineStatsSettings settings = OnlineStatsSettings.fromTabs(null, null, null,
            OnlineStatsSettings.tab(java.util.Map.of(
                "geoEndpoint", "https://example.com/?ip={ip}",
                "geoParams", "token=abc\n\nlang=zh-CN",
                "geoFields", "城市=data.city\n运营商=data.isp")));

        assertEquals("https://example.com/?ip={ip}", settings.geoEndpointOrDefault());
        assertEquals(2, settings.geoParams().size());
        assertEquals("token", settings.geoParams().get(0).name());
        assertEquals("abc", settings.geoParams().get(0).value());
        assertEquals("城市", settings.geoFields().get(0).label());
        assertEquals("data.city", settings.geoFields().get(0).path());
    }
}