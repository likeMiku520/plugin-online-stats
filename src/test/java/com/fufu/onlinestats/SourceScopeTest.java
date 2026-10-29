package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * A filtered source row has to be named exactly like the aggregate it came from,
 * otherwise the row shows a value the drill-down cannot find.
 */
class SourceScopeTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String HUMAN = "v-human";
    private static final String BOT = "v-bot";

    private static VisitorRecord record(String name, boolean bot, String referer) {
        VisitorRecord record = new VisitorRecord();
        record.setMetadata(new Metadata());
        record.getMetadata().setName(name);
        VisitorRecord.Spec spec = record.getSpec();
        spec.setIp("10.0.0.1");
        spec.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120");
        spec.setBot(bot);
        spec.setFirstReferer(referer);
        spec.setLastReferer(referer);
        spec.setFirstSeenAt(1_700_000_000_000L);
        spec.setLastSeenAt(System.currentTimeMillis());
        spec.setPageViewCount(7);
        return record;
    }

    private static VisitorRecordService records() throws InterruptedException {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        doReturn(Flux.just(
            record(HUMAN, false, "https://www.example.com/post/1"),
            record(BOT, true, "https://spam.example.net/x")))
            .when(client).listAll(eq(VisitorRecord.class), any(ListOptions.class),
                any(Sort.class));
        VisitorRecordService service = new VisitorRecordService(client,
            new OnlineVisitorRegistry(), null);
        service.start();
        // The history is read off the reactive thread, so wait for it like the plugin
        // does before serving a request.
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!service.isLoaded() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        return service;
    }

    private static StatisticsQueryService query(VisitorRecordService records) {
        return new StatisticsQueryService(null, records);
    }

    private static OnlineStatsSettings settings() {
        return OnlineStatsSettings.empty();
    }

    @Test
    void aFilteredReferrerRowIsNamedLikeTheAggregate() throws InterruptedException {
        VisitorRecordService records = records();
        try {
            Map<String, Object> human = query(records).sources(settings(), 30, "referer",
                null, null, StatisticsQueryService.Scope.HUMAN);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) human.get("items");

            assertEquals(1, items.size());
            // Bare host, the same label the aggregation service stores.
            assertEquals("example.com", items.get(0).get("name"));
            assertEquals(7L, items.get(0).get("views"));
            assertEquals(1L, items.get(0).get("visitors"));
        } finally {
            records.stop();
        }
    }

    @Test
    void theBotViewOnlyCountsRobots() throws InterruptedException {
        VisitorRecordService records = records();
        try {
            Map<String, Object> bots = query(records).sources(settings(), 30, "referer",
                null, null, StatisticsQueryService.Scope.BOT);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) bots.get("items");

            assertEquals(1, items.size());
            assertEquals("spam.example.net", items.get(0).get("name"));
        } finally {
            records.stop();
        }
    }

    @Test
    void aBlankReferrerIsCountedAsADirectVisit() throws InterruptedException {
        VisitorRecordService records = records();
        try {
            Map<String, Object> human = query(records).sources(settings(), 30, "referer",
                null, null, StatisticsQueryService.Scope.HUMAN);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) human.get("items");

            assertEquals(1, items.size(), "no synthetic row for a referrer that exists");
            assertEquals("example.com", items.get(0).get("name"));
        } finally {
            records.stop();
        }
    }

    @Test
    void theScopeOnlyAcceptsKnownValues() {
        assertEquals(StatisticsQueryService.Scope.ALL,
            StatisticsQueryService.Scope.of(null));
        assertEquals(StatisticsQueryService.Scope.HUMAN,
            StatisticsQueryService.Scope.of("HUMAN"));
        assertEquals(StatisticsQueryService.Scope.BOT,
            StatisticsQueryService.Scope.of(" bots "));
        assertEquals(StatisticsQueryService.Scope.ALL,
            StatisticsQueryService.Scope.of("whatever"));
        assertEquals(LocalDate.now(ZoneId.systemDefault()).format(DAY),
            LocalDate.now(ZoneId.systemDefault()).format(DAY));
    }
}