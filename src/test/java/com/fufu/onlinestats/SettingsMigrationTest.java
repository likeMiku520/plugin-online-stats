package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A stored value left as a JSON array must be recognised and rewritten, because the
 * settings box shows whatever is stored and would otherwise keep showing JSON forever.
 */
class SettingsMigrationTest {

    /** Mirrors the check the migration uses to decide whether a value needs rewriting. */
    private static boolean isJsonArray(Object value) {
        return value instanceof String text && text.trim().startsWith("[");
    }

    @Test
    void aStoredJsonArrayIsRecognised() {
        assertTrue(isJsonArray("[{\"path\":\"country\",\"label\":\"国家\"}]"));
        assertTrue(isJsonArray("  [ {\"name\":\"token\"} ]"));
    }

    @Test
    void theLineFormatIsLeftAlone() {
        assertFalse(isJsonArray("国家=country\n省份=province"));
        assertFalse(isJsonArray(""));
        assertFalse(isJsonArray(null));
    }

    @Test
    void theDefaultFieldListIsOneEntryPerLine() {
        StringBuilder text = new StringBuilder();
        for (OnlineStatsSettings.GeoField field : OnlineStatsSettings.DEFAULT_GEO_FIELDS) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(field.label()).append('=').append(field.path());
        }

        String value = text.toString();
        assertTrue(value.contains("国家=country"));
        assertFalse(value.contains("{"), "the shipped default is not JSON");
    }

    @Test
    void aConvertedValueIsReadBackAsFields() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("geoFields", "国家=country\n城市=city");

        OnlineStatsSettings settings = OnlineStatsSettings.fromTabs(null, null, null,
            OnlineStatsSettings.tab(data));

        assertEquals(2, settings.geoFields().size());
        assertEquals("国家", settings.geoFields().get(0).label());
        assertEquals("city", settings.geoFields().get(1).path());
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}