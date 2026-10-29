package com.fufu.onlinestats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * On demand IP location lookup. Nothing is resolved in the background: the console
 * asks for one address when the user clicks the lookup button, and the answer is kept
 * in a small cache for a week.
 */
@Service
public class IpGeoService {

    private static final Logger log = LoggerFactory.getLogger(IpGeoService.class);

    /**
     * An address that answers with data, in Chinese, over HTTPS and without a key. It
     * returns GBK, which {@link #decode(byte[])} handles.
     */
    public static final String DEFAULT_ENDPOINT =
        "https://whois.pconline.com.cn/ipJson.jsp?ip=%s&json=true";

    /**
     * Addresses shipped by earlier versions that no longer answer with data. The one
     * behind {@code api.mir6.com} now returns a PHP warning page, so a stored value
     * pointing there is ignored instead of showing that error to the console.
     */
    private static final List<String> RETIRED_HOSTS = List.of("api.mir6.com", "ip-api.com");

    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_CACHE = 2000;
    private static final int MAX_FIELD = 120;

    private final Map<String, IpLocation> cache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private volatile String endpoint = DEFAULT_ENDPOINT;

    public void configure(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || !endpoint.contains("%s")) {
            this.endpoint = DEFAULT_ENDPOINT;
            return;
        }
        if (isRetired(endpoint)) {
            log.info("IP location endpoint {} no longer returns data, using {}",
                endpoint, DEFAULT_ENDPOINT);
            this.endpoint = DEFAULT_ENDPOINT;
            return;
        }
        this.endpoint = endpoint;
    }

    /** Whether an address belongs to one of the retired providers. */
    public static boolean isRetired(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        String lower = endpoint.toLowerCase(Locale.ROOT);
        for (String host : RETIRED_HOSTS) {
            if (lower.contains(host)) {
                return true;
            }
        }
        return false;
    }

    public String endpoint() {
        return endpoint;
    }

    public Optional<IpLocation> cached(String ip) {
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        IpLocation location = cache.get(ip);
        if (location == null || System.currentTimeMillis() - location.resolvedAt() > TTL_MS) {
            return Optional.empty();
        }
        return Optional.of(location);
    }

    /**
     * Resolves one address. Never blocks a request thread: the HTTP call is issued
     * asynchronously and its future is exposed as a {@link Mono}.
     */
    public Mono<IpLocation> lookup(String ip) {
        if (ip == null || ip.isBlank()) {
            return Mono.just(IpLocation.failed(ip, "缺少 IP 地址"));
        }
        Optional<IpLocation> hit = cached(ip);
        if (hit.isPresent()) {
            return Mono.just(hit.get());
        }
        if (isLocal(ip)) {
            IpLocation local = IpLocation.local(ip);
            cache.put(ip, local);
            return Mono.just(local);
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(String.format(endpoint, ip)))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (compatible; halo-online-stats/1.2)")
                .GET()
                .build();
        } catch (Exception e) {
            return Mono.just(IpLocation.failed(ip, "接口地址无效：" + endpoint));
        }
        CompletableFuture<HttpResponse<byte[]>> future =
            http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        return Mono.fromFuture(future)
            .map(response -> parse(ip, response.statusCode(), decode(response.body())))
            .timeout(Duration.ofSeconds(15))
            .onErrorResume(error -> {
                log.debug("IP location lookup for {} failed: {}", ip, error.toString());
                return Mono.just(IpLocation.failed(ip, "请求接口失败：" + shortText(error.toString())));
            })
            .doOnNext(location -> {
                if (location.resolved()) {
                    if (cache.size() >= MAX_CACHE) {
                        cache.clear();
                    }
                    cache.put(ip, location);
                }
            });
    }

    /**
     * Decodes a response body. Chinese providers answer in GBK while most others use
     * UTF-8, so the bytes are tried as UTF-8 first and only re-read as GB18030 when that
     * is not valid — no per-provider table to keep up to date.
     */
    static String decode(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
        } catch (CharacterCodingException e) {
            try {
                return Charset.forName("GB18030").decode(ByteBuffer.wrap(body)).toString();
            } catch (Exception ignored) {
                return new String(body, StandardCharsets.UTF_8);
            }
        }
    }

    IpLocation parse(String ip, int status, String body) {
        if (status != 200) {
            return IpLocation.failed(ip, "接口返回 HTTP " + status);
        }
        if (body == null || body.isBlank()) {
            return IpLocation.failed(ip, "接口返回空响应");
        }
        String payload = body.trim();
        if (payload.startsWith("<")) {
            // A page instead of data, which is how a provider with a broken backend
            // answers. Say that clearly instead of complaining about JSON.
            return IpLocation.failed(ip,
                "接口返回了网页而不是数据，接口地址可能已经失效：" + stripTags(payload));
        }
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode data = root.path("data");
            if (!data.isMissingNode() && root.path("code").asInt(0) == 200) {
                return fromMir6(ip, data);
            }
            if (root.has("pro") || root.has("addr")) {
                return fromPconline(ip, root);
            }
            if (root.has("query") || root.has("status")) {
                return fromIpApi(ip, root);
            }
            String message = root.path("msg").asText("");
            return IpLocation.failed(ip, "接口返回了无法识别的数据"
                + (message.isBlank() ? "" : "：" + message));
        } catch (Exception e) {
            return IpLocation.failed(ip, "响应不是合法 JSON：" + stripTags(payload));
        }
    }

    /**
     * Mapping for the pconline shape: {@code pro}, {@code city} and a ready made
     * {@code addr} such as {@code 山东省枣庄市 移动}.
     */
    private IpLocation fromPconline(String ip, JsonNode root) {
        String province = text(root, "pro", null);
        String city = text(root, "city", null);
        String addr = text(root, "addr", null);
        if (province == null && city == null && addr == null) {
            String error = text(root, "err", null);
            return IpLocation.failed(ip,
                error == null || error.isBlank() ? "接口没有查到该地址的属地" : error);
        }
        String country = text(root, "country", null);
        if (country == null && province != null) {
            country = "中国";
        }
        String location = addr;
        if (location == null) {
            location = city == null || city.equals(province) ? province
                : province == null ? city : province + " " + city;
        }
        return new IpLocation(ip, null, country, text(root, "countryCode", null),
            province, city, null, networkOf(addr, province, city), null, null,
            null, null, ip != null && ip.contains(":") ? "IPv6" : "IPv4",
            location, System.currentTimeMillis(), null);
    }

    /**
     * The carrier is what remains of the address once the place names are taken out:
     * {@code 山东省枣庄市 移动} leaves {@code 移动}.
     */
    private static String networkOf(String addr, String province, String city) {
        if (addr == null) {
            return null;
        }
        String rest = addr;
        if (province != null && !province.isBlank()) {
            rest = rest.replace(province, "");
        }
        if (city != null && !city.isBlank()) {
            rest = rest.replace(city, "");
        }
        rest = rest.trim();
        return rest.isEmpty() ? null : (rest.length() > MAX_FIELD
            ? rest.substring(0, MAX_FIELD) : rest);
    }

    /** Strips markup so an HTML error page reads as one line of text. */
    private static String stripTags(String value) {
        if (value == null) {
            return "";
        }
        return shortText(value.replaceAll("<[^>]*>", " "));
    }

    private IpLocation fromMir6(String ip, JsonNode data) {
        return new IpLocation(
            text(data, "ip", ip),
            text(data, "dec", null),
            text(data, "country", null),
            text(data, "countryCode", null),
            text(data, "province", null),
            text(data, "city", null),
            text(data, "districts", null),
            text(data, "isp", null),
            text(data, "net", null),
            text(data, "idc", null),
            text(data, "zipcode", null),
            text(data, "areacode", null),
            text(data, "protocol", null),
            text(data, "location", null),
            System.currentTimeMillis(),
            null);
    }

    /**
     * Fallback mapping for providers shaped like ip-api, so switching the endpoint in
     * settings to another service does not break the console.
     */
    private IpLocation fromIpApi(String ip, JsonNode root) {
        if (!"success".equalsIgnoreCase(root.path("status").asText())) {
            String message = root.path("message").asText("查询失败");
            return IpLocation.failed(ip, message);
        }
        String country = text(root, "country", null);
        String province = text(root, "regionName", null);
        String city = text(root, "city", null);
        String isp = text(root, "isp", null);
        StringBuilder location = new StringBuilder();
        if (country != null && !"中国".equals(country)) {
            location.append(country).append(' ');
        }
        if (province != null) {
            location.append(province);
        }
        if (city != null && !city.equals(province)) {
            location.append(' ').append(city);
        }
        return new IpLocation(ip, null, country, text(root, "countryCode", null),
            province, city, null, isp, null, text(root, "as", null),
            text(root, "zip", null), null, "IPv4",
            location.toString().trim().isEmpty() ? null : location.toString().trim(),
            System.currentTimeMillis(), null);
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            return fallback;
        }
        return value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) : value;
    }

    private static String shortText(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() > 160 ? single.substring(0, 160) : single;
    }

    public static boolean isLocal(String ip) {
        if (ip == null || ip.isBlank() || "unknown".equals(ip)) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")
            || ip.startsWith("169.254.") || ip.startsWith("0.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) {
                    return true;
                }
            } catch (Exception ignored) {
                return true;
            }
        }
        String lower = ip.toLowerCase();
        return lower.equals("::1") || lower.startsWith("fc") || lower.startsWith("fd")
            || lower.startsWith("fe80");
    }
}
