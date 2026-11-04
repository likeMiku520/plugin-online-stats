package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Exports are read by people, so they are written as indented JSON rather than a flat
 * row that loses the nesting of a page address or a conversation.
 */
class JsonExportTest {

    @Test
    void anExportIsIndented() {
        String json = MaintenanceEndpoint.toJson(java.util.Map.of("total", 1));

        assertTrue(json.contains("\n"), "the document is pretty printed");
        assertTrue(json.contains("\"total\""), "the keys keep their names");
    }

    @Test
    void aValueThatCannotBeWrittenStillProducesJson() {
        String json = MaintenanceEndpoint.toJson(new Object());

        assertTrue(json.startsWith("{"), "the caller always receives a document");
    }

    @Test
    void timeOnSiteReadsAsWords() {
        assertTrue(MaintenanceEndpoint.readableDuration(0).isEmpty());
        assertTrue(MaintenanceEndpoint.readableDuration(45_000).contains("秒"));
        assertTrue(MaintenanceEndpoint.readableDuration(8L * 60 * 1000).contains("分"));
        assertTrue(MaintenanceEndpoint.readableDuration(3L * 60 * 60 * 1000).contains("小时"));
    }
}