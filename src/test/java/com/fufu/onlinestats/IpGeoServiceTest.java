package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

/**
 * The location lookup has to survive what providers actually send: GBK, HTML error
 * pages, and the shapes of more than one service.
 */
class IpGeoServiceTest {

    private static final String IP = "223.96.141.233";

    private final IpGeoService service = new IpGeoService();

    @Test
    void readsTheChineseShapeIncludingTheCarrier() {
        String body = "{\"ip\":\"" + IP + "\",\"pro\":\"山东省\",\"city\":\"枣庄市\","
            + "\"addr\":\"山东省枣庄市 移动\",\"err\":\"\"}";

        IpLocation location = service.parse(IP, 200, body);

        assertTrue(location.resolved());
        assertEquals("中国", location.country());
        assertEquals("山东省", location.province());
        assertEquals("枣庄市", location.city());
        assertEquals("移动", location.isp(), "the carrier is what the place names leave");
        assertEquals("山东省枣庄市 移动", location.location());
    }

    @Test
    void decodesGbkTheWayTheChineseProviderAnswers() {
        String json = "{\"pro\":\"\u5c71\u4e1c\u7701\"}";
        byte[] gbk = json.getBytes(Charset.forName("GB18030"));

        assertEquals(json, IpGeoService.decode(gbk));
        // UTF-8 bodies are untouched.
        assertEquals(json, IpGeoService.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void saysTheEndpointIsBrokenInsteadOfComplainingAboutJson() {
        String page = "<br />\n<b>Warning</b>: file_get_contents(http://test.ip.mir6.com/api):"
            + " failed to open stream";

        IpLocation location = service.parse(IP, 200, page);

        assertFalse(location.resolved());
        assertTrue(location.error().contains("网页"), location.error());
        assertTrue(location.error().contains("Warning"), location.error());
        assertFalse(location.error().contains("<b>"), "markup is stripped: " + location.error());
    }

    @Test
    void aRetiredEndpointIsIgnored() {
        assertTrue(IpGeoService.isRetired("https://api.mir6.com/api/ip?ip=%s&type=json"));
        assertTrue(IpGeoService.isRetired("http://ip-api.com/json/%s"));
        assertFalse(IpGeoService.isRetired(
            "https://whois.pconline.com.cn/ipJson.jsp?ip=%s&json=true"));

        service.configure("https://api.mir6.com/api/ip?ip=%s&type=json");
        assertEquals(IpGeoService.DEFAULT_ENDPOINT, service.endpoint(),
            "the console must not keep hitting a dead address");
    }

    @Test
    void aHandWrittenEndpointIsKept() {
        service.configure("https://example.com/geo?ip=%s");
        assertEquals("https://example.com/geo?ip=%s", service.endpoint());
    }

    @Test
    void anEmptyAnswerForAKnownAddressIsReportedAsSuch() {
        IpLocation location = service.parse(IP, 200, "{\"pro\":\"\",\"city\":\"\",\"addr\":\"\",\"err\":\"\"}");

        assertFalse(location.resolved());
        assertTrue(location.error().contains("没有查到"), location.error());
        assertNull(location.city());
    }

    @Test
    void stillReadsTheProviderShapesThatCameBefore() {
        IpLocation ipApi = service.parse(IP, 200,
            "{\"status\":\"success\",\"country\":\"中国\",\"regionName\":\"山东省\","
                + "\"city\":\"枣庄市\",\"isp\":\"移动\",\"query\":\"" + IP + "\"}");
        assertTrue(ipApi.resolved());
        assertEquals("移动", ipApi.isp());

        IpLocation nested = service.parse(IP, 200,
            "{\"code\":200,\"data\":{\"ip\":\"" + IP + "\",\"country\":\"中国\","
                + "\"province\":\"山东省\",\"city\":\"枣庄市\",\"isp\":\"移动\"}}");
        assertTrue(nested.resolved());
        assertEquals("山东省", nested.province());
    }
}