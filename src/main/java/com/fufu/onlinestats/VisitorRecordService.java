package com.fufu.onlinestats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * Persists live visitors into {@link VisitorRecord} extensions and keeps an in-memory
 * index for fast console queries. Writes are throttled by flushing only dirty visitors
 * every few seconds, so a heartbeat every 15 seconds does not turn into a write every
 * 15 seconds.
 */
@Service
public class VisitorRecordService {

    private static final Logger log = LoggerFactory.getLogger(VisitorRecordService.class);
    private static final long FLUSH_INTERVAL_SECONDS = 20L;
    private static final long CLEANUP_INTERVAL_SECONDS = 1800L;

    private final Map<String, VisitorRecord> index = new ConcurrentHashMap<>();
    private final ReactiveExtensionClient client;
    private final OnlineVisitorRegistry registry;
    private final ReactiveSettingFetcher settingFetcher;

    private volatile boolean loaded;
    private volatile ScheduledExecutorService scheduler;
    private final java.util.concurrent.atomic.AtomicLong flushRuns =
        new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong saveSuccess =
        new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong saveFailure =
        new java.util.concurrent.atomic.AtomicLong();
    private volatile String lastError;
    private volatile long lastFlushAt;

    public VisitorRecordService(ReactiveExtensionClient client,
                                OnlineVisitorRegistry registry,
                                ReactiveSettingFetcher settingFetcher) {
        this.client = client;
        this.registry = registry;
        this.settingFetcher = settingFetcher;
    }

    public void start() {
        try {
            loadAll();
        } catch (Exception e) {
            recordError(e);
            log.warn("Failed to load visitor history", e);
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "online-stats-persist");
            thread.setDaemon(true);
            return thread;
        });
        scheduler = executor;
        executor.scheduleWithFixedDelay(this::flushAll,
            FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::cleanup,
            CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        ScheduledExecutorService executor = scheduler;
        scheduler = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public List<VisitorRecord> list() {
        return new ArrayList<>(index.values());
    }

    public VisitorRecord get(String name) {
        return index.get(name);
    }

    public int size() {
        return index.size();
    }

    private void loadAll() {
        client.listAll(VisitorRecord.class, ListOptions.builder().build(), Sort.unsorted())
            .onErrorResume(error -> client.list(VisitorRecord.class, record -> true,
                Comparator.comparing(record -> record.getMetadata().getName())))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                record -> {
                    if (record.getMetadata() != null && record.getMetadata().getName() != null) {
                        index.put(record.getMetadata().getName(), record);
                    }
                },
                error -> log.warn("Failed to load visitor history", error),
                () -> loaded = true);
    }

    public void flushAll() {
        try {
            long now = System.currentTimeMillis();
            flushRuns.incrementAndGet();
            lastFlushAt = now;
            OnlineStatsSettings settings = currentSettings();
            for (OnlineVisitor visitor : registry.snapshot()) {
                try {
                    flush(visitor, now);
                } catch (Exception e) {
                    recordError(e);
                    log.warn("Failed to persist visitor {}", visitor.getKey(), e);
                }
            }
            if (settings != null) {
                registry.purgeStale(settings.onlineTimeoutMillis());
            }
        } catch (Exception e) {
            recordError(e);
            log.warn("Visitor flush cycle failed", e);
        }
    }

    private void recordError(Throwable error) {
        String message = error.getClass().getSimpleName() + ": " + error.getMessage();
        lastError = message.length() > 300 ? message.substring(0, 300) : message;
    }

    /**
     * Runtime counters surfaced on the console page so a failing write path is not
     * silently invisible.
     */
    public Map<String, Object> diagnostics() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("loaded", loaded);
        info.put("records", index.size());
        info.put("flushRuns", flushRuns.get());
        info.put("lastFlushAt", lastFlushAt);
        info.put("saved", saveSuccess.get());
        info.put("failed", saveFailure.get());
        info.put("lastError", lastError);
        return info;
    }

    private void flush(OnlineVisitor visitor, long now) {
        VisitorRecord record = index.get(visitor.getKey());
        String name = visitor.getKey();
        boolean isNew = record == null;
        if (isNew) {
            record = new VisitorRecord();
            record.setMetadata(new Metadata());
            record.getMetadata().setName(name);
        }
        if (record.getSpec() == null) {
            record.setSpec(new VisitorRecord.Spec());
        }
        VisitorRecord.Spec spec = record.getSpec();

        int newPageViews = visitor.getPageViewCount() - visitor.getFlushedPageViewCount();
        long newOnlineMs = visitor.getTotalOnlineMs() - visitor.getFlushedOnlineMs();
        int newSessions = visitor.getSessionCount() - visitor.getFlushedSessionCount();

        spec.setIp(visitor.getIp());
        spec.setUserAgent(visitor.getUserAgent());
        boolean uaBot = UaParser.isBot(visitor.getUserAgent());
        boolean noFingerprint = visitor.getScreen() == null && visitor.getViewport() == null
            && visitor.getTimezone() == null && visitor.getLanguage() == null;
        boolean bot = uaBot || noFingerprint;
        spec.setBot(bot);
        if (bot) {
            spec.setBotReason(uaBot ? UaParser.botReason(visitor.getUserAgent())
                : "未上报屏幕、时区等浏览器信息");
        } else {
            spec.setBotReason(null);
        }
        spec.setLastPageUrl(visitor.getPageUrl());
        spec.setLastPageTitle(visitor.getPageTitle());
        spec.setLastReferer(visitor.getReferer());
        if (spec.getFirstPageUrl() == null) {
            spec.setFirstPageUrl(visitor.getPageUrl());
            spec.setFirstPageTitle(visitor.getPageTitle());
        }
        if (spec.getFirstReferer() == null) {
            spec.setFirstReferer(visitor.getFirstReferer());
        }
        long firstSeen = spec.getFirstSeenAt() == null
            ? visitor.getFirstSeenAt()
            : Math.min(spec.getFirstSeenAt(), visitor.getFirstSeenAt());
        spec.setFirstSeenAt(firstSeen);
        long storedSeen = spec.getLastSeenAt() == null ? 0L : spec.getLastSeenAt();
        spec.setLastSeenAt(Math.max(storedSeen, visitor.getLastSeenAt()));
        spec.setVisitCount(intValue(spec.getVisitCount()) + newSessions);
        spec.setPageViewCount(intValue(spec.getPageViewCount()) + newPageViews);
        spec.setTotalOnlineMs(longValue(spec.getTotalOnlineMs()) + newOnlineMs);

        if (visitor.getScreen() != null) {
            spec.setScreen(visitor.getScreen());
        }
        if (visitor.getViewport() != null) {
            spec.setViewport(visitor.getViewport());
        }
        if (visitor.getTimezone() != null) {
            spec.setTimezone(visitor.getTimezone());
        }
        if (visitor.getLanguage() != null) {
            spec.setLanguage(visitor.getLanguage());
        }
        if (visitor.getPlatform() != null) {
            spec.setPlatform(visitor.getPlatform());
        }
        if (visitor.getCpuCores() != null) {
            spec.setCpuCores(visitor.getCpuCores());
        }
        if (visitor.getDeviceMemory() != null) {
            spec.setDeviceMemory(visitor.getDeviceMemory());
        }
        if (visitor.getConnection() != null) {
            spec.setConnection(visitor.getConnection());
        }

        if (save(record, isNew)) {
            visitor.markFlushed(visitor.getPageViewCount(), visitor.getTotalOnlineMs(),
                visitor.getSessionCount(), now);
        }
    }

    /**
     * Flushes one live visitor immediately, so a console action (like a location
     * lookup from the table) can persist against an existing record at once instead of
     * waiting for the 20 second cycle.
     */
    public boolean flushVisitor(String key) {
        OnlineVisitor visitor = registry.get(key);
        if (visitor == null) {
            return false;
        }
        try {
            flush(visitor, System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            recordError(e);
            log.warn("Failed to flush visitor {}", key, e);
            return false;
        }
    }

    /**
     * Stores a location result requested from the console. Returns the updated record
     * so the caller can answer with fresh data.
     */
    public VisitorRecord applyLocation(String name, IpLocation location) {
        VisitorRecord record = index.get(name);
        if (record == null) {
            return null;
        }
        VisitorRecord.Spec spec = record.getSpec();
        spec.setCountry(location.country());
        spec.setCountryCode(location.countryCode());
        spec.setRegion(location.province());
        spec.setCity(location.city());
        spec.setDistricts(location.districts());
        spec.setIsp(location.isp());
        spec.setNet(location.net());
        spec.setIdc(location.idc());
        spec.setZipcode(location.zipcode());
        spec.setAreacode(location.areacode());
        spec.setProtocol(location.protocol());
        spec.setLocation(location.location());
        spec.setDec(location.dec());
        spec.setGeoResolvedAt(location.resolvedAt());
        save(record, false);
        return index.getOrDefault(name, record);
    }

    private synchronized boolean save(VisitorRecord record, boolean isNew) {
        String name = record.getMetadata().getName();
        try {
            VisitorRecord result;
            if (isNew) {
                result = client.create(record).block(Duration.ofSeconds(10));
            } else {
                result = client.update(record).block(Duration.ofSeconds(10));
            }
            index.put(name, result != null ? result : record);
            saveSuccess.incrementAndGet();
            return true;
        } catch (Exception e) {
            if (!isNew) {
                saveFailure.incrementAndGet();
                recordError(e);
                log.warn("Failed to update visitor record {}", name, e);
                return false;
            }
        }
        try {
            VisitorRecord existing = client.fetch(VisitorRecord.class, name)
                .block(Duration.ofSeconds(5));
            if (existing == null) {
                saveFailure.incrementAndGet();
                return false;
            }
            record.getMetadata().setVersion(existing.getMetadata().getVersion());
            VisitorRecord result = client.update(record).block(Duration.ofSeconds(10));
            index.put(name, result != null ? result : record);
            saveSuccess.incrementAndGet();
            return true;
        } catch (Exception second) {
            saveFailure.incrementAndGet();
            recordError(second);
            log.warn("Failed to save visitor record {}", name, second);
            return false;
        }
    }

    private void cleanup() {
        try {
            OnlineStatsSettings settings = currentSettings();
            if (settings == null) {
                return;
            }
            int retentionDays = settings.historyRetentionDaysOrDefault();
            int maxRecords = settings.maxRecordsOrDefault();
            long cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000;
            List<VisitorRecord> expired = index.values().stream()
                .filter(r -> r.getSpec().getLastSeenAt() != null
                    && r.getSpec().getLastSeenAt() < cutoff)
                .toList();
            for (VisitorRecord record : expired) {
                remove(record);
            }
            List<VisitorRecord> remaining = index.values().stream()
                .sorted(Comparator.comparingLong(
                    r -> r.getSpec().getLastSeenAt() == null ? 0L : r.getSpec().getLastSeenAt()))
                .toList();
            int overflow = remaining.size() - maxRecords;
            for (int i = 0; i < overflow; i++) {
                remove(remaining.get(i));
            }
        } catch (Exception e) {
            log.warn("Visitor history cleanup failed", e);
        }
    }

    private void remove(VisitorRecord record) {
        String name = record.getMetadata().getName();
        try {
            client.delete(record).block(Duration.ofSeconds(10));
            index.remove(name);
        } catch (Exception e) {
            log.warn("Failed to delete visitor record {}", name, e);
        }
    }

    private OnlineStatsSettings currentSettings() {
        try {
            return settingFetcher.fetch(OnlineStatsSettings.GROUP, OnlineStatsSettings.class)
                .block(Duration.ofSeconds(2));
        } catch (Exception e) {
            return null;
        }
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private static long longValue(Long value) {
        return value == null ? 0L : value;
    }
}
