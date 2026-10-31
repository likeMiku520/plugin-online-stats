package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

/**
 * The three filters share one computation, so what the unfiltered view reports for a
 * browser has to be exactly what the two halves add up to.
 */
class SourceScopeSumTest {

    private static VisitorRecord record(String name, Boolean bot, String userAgent, int views) {
        VisitorRecord record = new VisitorRecord();
        record.setMetadata(new Metadata());
        record.getMetadata().setName(name);
        VisitorRecord.Spec spec = record.getSpec();
        spec.setIp("10.0.0.1");
        spec.setUserAgent(userAgent);
        spec.setScreen("1920x1080");
        spec.setBot(bot);
        spec.setLastSeenAt(System.currentTimeMillis());
        spec.setPageViewCount(views);
        return record;
    }

    private static StatisticsQueryService service(List<VisitorRecord> records) {
        VisitorRecordService recordService = mock(VisitorRecordService.class);
        when(recordService.list()).thenReturn(records);
        return new StatisticsQueryService(mock(StatsAggregationService.class), recordService);
    }

    private static long viewsOf(StatisticsQueryService service,
                                StatisticsQueryService.Scope scope, String name) {
        Map<String, Object> result = service.sources(null, 30, "browser", null, null, scope);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        for (Map<String, Object> item : items) {
            if (name.equals(item.get("name"))) {
                return ((Number) item.get("views")).longValue();
            }
        }
        return 0L;
    }

    private static long refererViewOf(StatisticsQueryService service,
                                      StatisticsQueryService.Scope scope, String name) {
        Map<String, Object> result = service.sources(null, 30, "referer", null, null, scope);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        for (Map<String, Object> item : items) {
            if (name.equals(item.get("name"))) {
                return ((Number) item.get("views")).longValue();
            }
        }
        return 0L;
    }

    @Test
    void theUnfilteredRowIsTheSumOfTheTwoHalves() {
        StatisticsQueryService service = service(List.of(
            record("h1", false, "Mozilla/5.0 (Windows NT 10.0) Chrome/120", 1000),
            record("h2", false, "Mozilla/5.0 (Windows NT 10.0) Chrome/121", 500),
            record("b1", true, "Mozilla/5.0 (compatible; Googlebot/2.1) Chrome/120", 500)));

        long humans = viewsOf(service, StatisticsQueryService.Scope.HUMAN, "Chrome");
        long bots = viewsOf(service, StatisticsQueryService.Scope.BOT, "Chrome");
        long all = viewsOf(service, StatisticsQueryService.Scope.ALL, "Chrome");

        assertEquals(1500, humans);
        assertEquals(500, bots);
        assertEquals(humans + bots, all, "全部 must equal 真人 + 机器人");
        assertEquals(2000, all);
    }

    @Test
    void aRecordWithoutAFlagIsCountedOnTheHumanSideEverywhere() {
        StatisticsQueryService service = service(List.of(
            record("u1", null, "Mozilla/5.0 (Macintosh) Safari/605", 300)));

        assertEquals(300, viewsOf(service, StatisticsQueryService.Scope.HUMAN, "Safari"));
        assertEquals(0, viewsOf(service, StatisticsQueryService.Scope.BOT, "Safari"));
        assertEquals(300, viewsOf(service, StatisticsQueryService.Scope.ALL, "Safari"));
    }

    @Test
    void theSameHoldsForReferrers() {
        VisitorRecord human = record("h1", false, "Mozilla/5.0 Chrome/120", 10);
        human.getSpec().setFirstReferer("https://www.google.com/search?q=x");
        VisitorRecord bot = record("b1", true, "Mozilla/5.0 (compatible; Bingbot/2.0)", 4);
        bot.getSpec().setFirstReferer("https://www.google.com/search?q=y");

        StatisticsQueryService service = service(List.of(human, bot));
        long humans = refererViewOf(service, StatisticsQueryService.Scope.HUMAN, "google.com");
        long bots = refererViewOf(service, StatisticsQueryService.Scope.BOT, "google.com");
        long all = refererViewOf(service, StatisticsQueryService.Scope.ALL, "google.com");

        assertEquals(10L, humans);
        assertEquals(4L, bots);
        assertEquals(humans + bots, all, "the referrer rows follow the same rule");
    }
}