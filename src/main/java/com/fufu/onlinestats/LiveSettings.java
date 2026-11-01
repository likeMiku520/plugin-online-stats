package com.fufu.onlinestats;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * Reads a plugin setting group as it is stored right now.
 *
 * <p>Two sources are tried, in order: Halo's own setting fetcher, which is what every
 * other part of the console reads, and the ConfigMap underneath it. Using both means a
 * value is found even when one of the two paths is not available, which is what made the
 * greeting look permanently empty.</p>
 */
@Component
public class LiveSettings {

    private static final Logger log = LoggerFactory.getLogger(LiveSettings.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    /** Short enough that a console save is picked up almost at once, long enough that a
     * heartbeat from every visitor does not turn into a read each time. */
    private static final long CACHE_MILLIS = 2_000L;

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** A read result and when it was taken; a console save is picked up within a couple
     * of seconds, while a heartbeat per visitor stays cheap. */
    private record Cached(Optional<Map<String, String>> values, long at) {
    }

    public LiveSettings(ReactiveExtensionClient client, ReactiveSettingFetcher settingFetcher) {
        this.client = client;
        this.settingFetcher = settingFetcher;
    }

    /**
     * One settings tab as a flat map.
     *
     * @param configMapName the plugin's ConfigMap, used by the fallback read
     * @param group         the tab name
     * @return the stored values, empty when neither source has the tab
     */
    public Optional<Map<String, String>> group(String configMapName, String group) {
        String cacheKey = (configMapName == null ? "" : configMapName) + '/' + group;
        Cached hit = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < CACHE_MILLIS) {
            return hit.values();
        }
        if (reactor.core.scheduler.Schedulers.isInNonBlockingThread()) {
            /* Only a background read may block. A request thread takes the previous
             * value — a couple of seconds out of date beats an exception that the caller
             * turns into "nothing was filled in". */
            return hit == null ? Optional.empty() : hit.values();
        }
        Optional<Map<String, String>> fresh = readFromFetcher(group);
        if (fresh.isEmpty()) {
            fresh = readFromConfigMap(configMapName, group);
        }
        cache.put(cacheKey, new Cached(fresh, now));
        return fresh;
    }

    /** Halo's own reader: the same one the settings page is built from. */
    private Optional<Map<String, String>> readFromFetcher(String group) {
        try {
            var node = settingFetcher.get(group).block(TIMEOUT);
            if (node == null || node.isNull() || !node.isObject() || node.isEmpty()) {
                return Optional.empty();
            }
            Map<String, String> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    values.put(entry.getKey(), entry.getValue().asText(""));
                }
            });
            return values.isEmpty() ? Optional.empty() : Optional.of(values);
        } catch (Exception e) {
            log.debug("Setting fetcher could not read the {} tab", group, e);
            return Optional.empty();
        }
    }

    /** The ConfigMap underneath it, for when the fetcher cannot answer. */
    private Optional<Map<String, String>> readFromConfigMap(String configMapName, String group) {
        if (configMapName == null || configMapName.isBlank()) {
            return Optional.empty();
        }
        try {
            ConfigMap configMap = client.fetch(ConfigMap.class, configMapName).block(TIMEOUT);
            if (configMap == null || configMap.getData() == null) {
                return Optional.empty();
            }
            String raw = configMap.getData().get(group);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(raw, Map.class);
            Map<String, String> values = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (value != null) {
                    values.put(key, String.valueOf(value));
                }
            });
            return values.isEmpty() ? Optional.empty() : Optional.of(values);
        } catch (Exception e) {
            log.debug("ConfigMap could not provide the {} tab", group, e);
            return Optional.empty();
        }
    }

    /** Forgets the cached copies, so the next read goes to the store. */
    public void invalidate() {
        cache.clear();
    }

    /**
     * The conversation tab together with a greeting an older version saved under its own
     * group. The current tab wins, so an edit made now is not overridden by that leftover.
     */
    public Map<String, String> greetingValues(String configMapName) {
        return OnlineStatsSettings.mergeGreeting(
            group(configMapName, OnlineStatsSettings.GROUP_ANNOUNCE).orElse(Map.of()),
            group(configMapName, OnlineStatsSettings.GROUP_CHAT).orElse(Map.of()));
    }

    public Optional<OnlineStatsSettings> effectiveChatSettings(String configMapName) {
        Map<String, String> merged = greetingValues(configMapName);
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(OnlineStatsSettings.fromTabs(
            OnlineStatsSettings.tab(null),
            OnlineStatsSettings.tab(merged),
            OnlineStatsSettings.tab(merged),
            OnlineStatsSettings.tab(null)));
    }

    /**
     * Finds a single field wherever the configuration actually keeps it.
     *
     * <p>The greeting has lived in two different tabs across versions, and a value can
     * also end up under a key this plugin does not know about. Rather than trusting one
     * group name, every tab is searched, starting with the current one.</p>
     *
     * @return the value, or empty when no tab has it
     */
    public Optional<String> findValue(String configMapName, String field) {
        List<String> preferred = List.of(OnlineStatsSettings.GROUP_CHAT,
            OnlineStatsSettings.GROUP_ANNOUNCE, OnlineStatsSettings.GROUP,
            OnlineStatsSettings.GROUP_DATA);
        for (String group : preferred) {
            String value = group(configMapName, group).map(m -> m.get(field)).orElse(null);
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        // Anything else the ConfigMap holds, so an unexpected key still answers.
        for (String group : configMapKeys(configMapName)) {
            if (preferred.contains(group)) {
                continue;
            }
            String value = readFromConfigMap(configMapName, group).map(m -> m.get(field)).orElse(null);
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** The tabs actually stored, which is what the diagnostics report. */
    public List<String> configMapKeys(String configMapName) {
        if (configMapName == null || configMapName.isBlank()) {
            return List.of();
        }
        try {
            ConfigMap configMap = client.fetch(ConfigMap.class, configMapName).block(TIMEOUT);
            if (configMap == null || configMap.getData() == null) {
                return List.of();
            }
            return List.copyOf(configMap.getData().keySet());
        } catch (Exception e) {
            log.debug("Could not list the stored tabs", e);
            return List.of();
        }
    }

    /**
     * What each source can see right now, so a failed lookup can say why instead of just
     * reporting that nothing was found.
     */
    public Map<String, Object> diagnostics(String configMapName) {
        cache.clear();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("configMap", configMapName == null ? "" : configMapName);
        report.put("storedTabs", configMapKeys(configMapName));

        Map<String, Object> perTab = new LinkedHashMap<>();
        for (String group : List.of(OnlineStatsSettings.GROUP_CHAT,
            OnlineStatsSettings.GROUP_ANNOUNCE, OnlineStatsSettings.GROUP,
            OnlineStatsSettings.GROUP_DATA)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("fetcher", readFromFetcher(group).map(Map::keySet)
                .map(List::copyOf).orElse(List.of()));
            one.put("configMap", readFromConfigMap(configMapName, group).map(Map::keySet)
                .map(List::copyOf).orElse(List.of()));
            perTab.put(group, one);
        }
        report.put("tabs", perTab);
        report.put("text", findValue(configMapName, "chatAnnounceText").orElse(null));
        return report;
    }
}