package com.fufu.onlinestats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight User-Agent analysis. Produces human readable Chinese labels so the
 * console does not need an extra parser dependency.
 */
public final class UaParser {

    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+){0,2})");

    private static final String[][] BROWSERS = {
        {"MicroMessenger", "微信内置浏览器"},
        {"QQBrowser", "QQ 浏览器"},
        {"Quark", "夸克浏览器"},
        {"UCBrowser", "UC 浏览器"},
        {"HuaweiBrowser", "华为浏览器"},
        {"MiuiBrowser", "小米浏览器"},
        {"HeyTapBrowser", "OPPO 浏览器"},
        {"VivoBrowser", "vivo 浏览器"},
        {"baidubrowser", "百度浏览器"},
        {"360SE", "360 安全浏览器"},
        {"360EE", "360 极速浏览器"},
        {"SogouMobileBrowser", "搜狗浏览器"},
        {"Maxthon", "遨游浏览器"},
        {"MQQBrowser", "手机 QQ 浏览器"},
        {"Edg", "Edge"},
        {"EdgiOS", "Edge"},
        {"EdgA", "Edge"},
        {"OPR", "Opera"},
        {"Opera", "Opera"},
        {"SamsungBrowser", "三星浏览器"},
        {"Firefox", "Firefox"},
        {"FxiOS", "Firefox"},
        {"CriOS", "Chrome"},
        {"Chrome", "Chrome"},
        {"Safari", "Safari"},
        {"MSIE", "Internet Explorer"},
        {"Trident", "Internet Explorer"},
    };

    private static final String[][] SYSTEMS = {
        {"HarmonyOS", "HarmonyOS"},
        {"Android", "Android"},
        {"iPhone", "iOS"},
        {"iPad", "iPadOS"},
        {"iPod", "iOS"},
        {"Windows NT 10.0", "Windows 10/11"},
        {"Windows NT 6.3", "Windows 8.1"},
        {"Windows NT 6.2", "Windows 8"},
        {"Windows NT 6.1", "Windows 7"},
        {"Windows", "Windows"},
        {"Mac OS X", "macOS"},
        {"Macintosh", "macOS"},
        {"CrOS", "ChromeOS"},
        {"Ubuntu", "Ubuntu"},
        {"Linux", "Linux"},
    };

    private static final Pattern BOT = Pattern.compile(
        "(?i)(bot|crawler|spider|slurp|archiver|facebookexternalhit|"
            + "python-requests|python-urllib|aiohttp|httpx|scrapy|"
            + "curl/|wget|go-http-client|java/|okhttp|axios|node-fetch|undici|"
            + "headlesschrome|phantomjs|selenium|puppeteer|playwright|"
            + "bytespider|petalbot|yandex|baiduspider|googlebot|bingbot|sogou|360spider|"
            + "semrush|ahrefs|mj12|dotbot|masscan|zgrab|nmap|sqlmap|"
            + "uptime|monitor|pingdom|statuscake|check_http|libwww-perl)");

    private UaParser() {
    }

    public static boolean isBot(String userAgent) {
        return userAgent != null && !userAgent.isBlank() && BOT.matcher(userAgent).find();
    }

    /**
     * Human readable reason used by the console to explain why a visit was classified
     * as a bot.
     */
    public static String botReason(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "没有上报 User-Agent";
        }
        if (BOT.matcher(userAgent).find()) {
            return "User-Agent 命中爬虫特征";
        }
        return null;
    }

    public static Map<String, String> parse(String userAgent) {
        Map<String, String> info = new LinkedHashMap<>();
        String ua = userAgent == null ? "" : userAgent;
        if (ua.isBlank()) {
            info.put("deviceType", "未知");
            info.put("os", "未知");
            info.put("browser", "未知");
            info.put("engine", "未知");
            return info;
        }

        boolean bot = BOT.matcher(ua).find();
        String deviceType;
        if (bot) {
            deviceType = "爬虫/机器人";
        } else if (ua.contains("iPad") || ua.contains("Tablet")
            || (ua.contains("Android") && !ua.contains("Mobile"))) {
            deviceType = "平板";
        } else if (ua.contains("Mobile") || ua.contains("iPhone")
            || ua.contains("Android") || ua.contains("HarmonyOS")) {
            deviceType = "手机";
        } else {
            deviceType = "桌面";
        }
        info.put("deviceType", deviceType);

        String os = "未知";
        String osVersion = null;
        for (String[] entry : SYSTEMS) {
            if (ua.contains(entry[0])) {
                os = entry[1];
                osVersion = pickVersion(ua, entry[0]);
                break;
            }
        }
        info.put("os", os);
        if (osVersion != null) {
            info.put("osVersion", osVersion);
        }

        String browser = "未知";
        String browserVersion = null;
        for (String[] entry : BROWSERS) {
            if (ua.contains(entry[0])) {
                browser = entry[1];
                browserVersion = pickVersion(ua, entry[0]);
                break;
            }
        }
        info.put("browser", browser);
        if (browserVersion != null) {
            info.put("browserVersion", browserVersion);
        }

        String engine;
        if (ua.contains("Trident") || ua.contains("MSIE")) {
            engine = "Trident";
        } else if (ua.contains("Gecko/") && !ua.contains("like Gecko")) {
            engine = "Gecko";
        } else if (ua.contains("AppleWebKit")) {
            engine = "Blink/WebKit";
        } else {
            engine = "未知";
        }
        info.put("engine", engine);

        if (ua.contains("MicroMessenger")) {
            info.put("app", "微信");
        } else if (ua.contains("QQ/") && !ua.contains("QQBrowser")) {
            info.put("app", "QQ");
        } else if (ua.contains("Weibo")) {
            info.put("app", "微博");
        } else if (ua.contains("DingTalk")) {
            info.put("app", "钉钉");
        } else if (ua.contains("AlipayClient")) {
            info.put("app", "支付宝");
        } else if (ua.contains("Douyin") || ua.contains("aweme")) {
            info.put("app", "抖音");
        }

        if (bot) {
            info.put("bot", "是");
        }
        return info;
    }

    private static String pickVersion(String ua, String token) {
        int index = ua.indexOf(token);
        if (index < 0) {
            return null;
        }
        String tail = ua.substring(index + token.length());
        Matcher matcher = VERSION.matcher(tail);
        if (matcher.find()) {
            String version = matcher.group(1);
            return version.length() > 12 ? version.substring(0, 12) : version;
        }
        return null;
    }
}
