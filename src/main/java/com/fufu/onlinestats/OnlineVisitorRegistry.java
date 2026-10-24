package com.fufu.onlinestats;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory registry of live visitors. Every query purges stale entries first so
 * results are always correct; a scheduled task keeps memory tidy between queries and
 * a separate service persists the same data as history records.
 */
@Service
public class OnlineVisitorRegistry {

    private static final int MAX_VISITORS = 10_000;

    private final Map<String, OnlineVisitor> visitors = new ConcurrentHashMap<>();

    /**
     * Records one heartbeat and returns the affected visitor.
     */
    public OnlineVisitor heartbeat(String ip, String userAgent, Heartbeat heartbeat,
                                   long now, long timeoutMillis) {
        String key = deviceKey(ip, userAgent);
        OnlineVisitor visitor = visitors.compute(key, (k, existing) -> {
            OnlineVisitor current = existing;
            if (current == null) {
                current = new OnlineVisitor(k, ip, userAgent, now);
            } else if (now - current.getLastSeenAt() > timeoutMillis) {
                current.markResumed();
            }
            current.touch(heartbeat.visitorId(), heartbeat.url(), heartbeat.title(),
                heartbeat.referer(), heartbeat.screen(), heartbeat.viewport(),
                heartbeat.timezone(), heartbeat.language(), heartbeat.platform(),
                heartbeat.cores(), heartbeat.memory(), heartbeat.connection(), now);
            return current;
        });
        if (visitors.size() >= MAX_VISITORS) {
            evictOldest();
        }
        return visitor;
    }

    public static String deviceKey(String ip, String userAgent) {
        String raw = (ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "v-" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception e) {
            return "v-" + Integer.toHexString(raw.hashCode());
        }
    }

    private void evictOldest() {
        visitors.entrySet().stream()
            .min(Comparator.comparingLong(e -> e.getValue().getLastSeenAt()))
            .ifPresent(e -> visitors.remove(e.getKey(), e.getValue()));
    }

    public int count(long timeoutMillis) {
        return list(timeoutMillis).size();
    }

    public List<OnlineVisitor> list(long timeoutMillis) {
        purgeStale(timeoutMillis);
        List<OnlineVisitor> result = new ArrayList<>(visitors.values());
        result.sort(Comparator.comparingLong(OnlineVisitor::getLastSeenAt).reversed());
        return result;
    }

    public List<OnlineVisitor> snapshot() {
        return new ArrayList<>(visitors.values());
    }

    public OnlineVisitor get(String key) {
        return visitors.get(key);
    }

    public void purgeStale(long timeoutMillis) {
        long now = System.currentTimeMillis();
        visitors.entrySet().removeIf(e -> now - e.getValue().getLastSeenAt() > timeoutMillis);
    }

    public void clear() {
        visitors.clear();
    }

    /**
     * One heartbeat payload, already sanitized by the endpoint.
     */
    public record Heartbeat(String visitorId, String url, String title, String referer,
                            String screen, String viewport, String timezone, String language,
                            String platform, String cores, String memory, String connection) {
    }
}
