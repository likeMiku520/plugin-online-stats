package com.fufu.onlinestats;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Reads a plugin setting group straight from its ConfigMap.
 *
 * <p>Halo's {@code ReactiveSettingFetcher} keeps the ConfigMap in memory and only drops
 * that copy when its own watcher notices a change. Anything that has to react to an edit
 * right away must not go through it, so the greeting and the settings test button both
 * read from here instead. Reading is cheap and the values are small.</p>
 */
@Component
public class LiveSettings {

    private static final Logger log = LoggerFactory.getLogger(LiveSettings.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final ReactiveExtensionClient client;

    public LiveSettings(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * One settings tab as a flat map.
     *
     * @param configMapName the plugin's ConfigMap
     * @param group         the tab name, which is the key inside the ConfigMap
     * @return the stored values, or empty when the ConfigMap or the tab is not there
     */
    public Optional<Map<String, String>> group(String configMapName, String group) {
        if (configMapName == null || configMapName.isBlank()) {
            return Optional.empty();
        }
        try {
            ConfigMap configMap = client.fetch(ConfigMap.class, configMapName)
                .block(TIMEOUT);
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
            return Optional.of(values);
        } catch (Exception e) {
            log.debug("Could not read the {} settings from the stored configuration", group, e);
            return Optional.empty();
        }
    }

    /** One group as a settings object, so the parsing rules stay in one place. */
    public Optional<OnlineStatsSettings> chatSettings(String configMapName) {
        return group(configMapName, OnlineStatsSettings.GROUP_CHAT).map(values ->
            OnlineStatsSettings.fromTabs(
                OnlineStatsSettings.tab(null),
                OnlineStatsSettings.tab(values),
                OnlineStatsSettings.tab(values),
                OnlineStatsSettings.tab(null)));
    }
}