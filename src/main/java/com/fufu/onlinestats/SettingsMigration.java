package com.fufu.onlinestats;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;

/**
 * Converts location settings that an earlier version stored as JSON into the line format
 * the settings page edits.
 *
 * <p>The field box reads what is stored, so a value left as a JSON array keeps showing as
 * JSON no matter what default the form declares. This rewrites it once, at startup, when
 * the stored value is still an array.</p>
 */
@Component
public class SettingsMigration {

    private static final Logger log = LoggerFactory.getLogger(SettingsMigration.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactiveExtensionClient client;
    private final String configMapName;

    public SettingsMigration(ReactiveExtensionClient client, PluginContext pluginContext) {
        this.client = client;
        this.configMapName = pluginContext == null ? null : pluginContext.getConfigMapName();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rewriteLocationLists() {
        if (configMapName == null || configMapName.isBlank()) {
            return;
        }
        try {
            ConfigMap configMap = client.fetch(ConfigMap.class, configMapName)
                .block(Duration.ofSeconds(10));
            if (configMap == null || configMap.getData() == null) {
                return;
            }
            String raw = configMap.getData().get(OnlineStatsSettings.GROUP_DATA);
            if (raw == null || !raw.trim().startsWith("{")) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> group = MAPPER.readValue(raw, Map.class);
            boolean changed = false;
            changed |= convert(group, "geoFields", "label", "path");
            changed |= convert(group, "geoParams", "name", "value");
            if (!changed) {
                return;
            }
            Map<String, String> data = new LinkedHashMap<>(configMap.getData());
            data.put(OnlineStatsSettings.GROUP_DATA, MAPPER.writeValueAsString(group));
            configMap.setData(data);
            client.update(configMap).block(Duration.ofSeconds(10));
            log.info("Location settings converted to one entry per line");
        } catch (Exception e) {
            log.warn("Could not convert the location settings", e);
        }
    }

    /** Rewrites one stored value from a JSON array to {@code name=value} lines. */
    private static boolean convert(Map<String, Object> group, String field,
                                   String nameKey, String valueKey) {
        Object value = group.get(field);
        if (!(value instanceof String text) || !text.trim().startsWith("[")) {
            return false;
        }
        try {
            var node = MAPPER.readTree(text);
            StringBuilder lines = new StringBuilder();
            for (var item : node) {
                String name = item.path(nameKey).asText("").trim();
                String path = item.path(valueKey).asText("").trim();
                if (name.isEmpty() || path.isEmpty()) {
                    continue;
                }
                if (lines.length() > 0) {
                    lines.append('\n');
                }
                lines.append(name).append('=').append(path);
            }
            group.put(field, lines.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}