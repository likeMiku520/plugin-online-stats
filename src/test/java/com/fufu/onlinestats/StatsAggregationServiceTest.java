package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Guards the adjustment counter against the regression where a successful update still
 * ran the create branch, so Halo answered every cycle with 400 "Duplicate name
 * detected.".
 */
class StatsAggregationServiceTest {

    private static final String ADJUSTMENT_COUNTER =
        "posts.content.halo.run/online-stats-adjustment";

    @Test
    void updatingAnExistingCounterDoesNotCreateItAgain() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        Counter existing = Counter.emptyCounter(ADJUSTMENT_COUNTER);
        existing.getMetadata().setVersion(7L);
        existing.setVisit(3);
        when(client.fetch(Counter.class, ADJUSTMENT_COUNTER)).thenReturn(Mono.just(existing));
        doReturn(Mono.just(existing)).when(client).update(any(Counter.class));
        doReturn(Mono.just(existing)).when(client).create(any(Counter.class));
        CountDownLatch refreshed = stubRefresh(client);

        StatsAggregationService service = new StatsAggregationService(client);
        service.ensureAdjustment(12L);

        verify(client, timeout(5_000)).update(any(Counter.class));
        assertTrue(refreshed.await(5, TimeUnit.SECONDS), "the success path never completed");
        verify(client, timeout(500).times(0)).create(any(Counter.class));
        verify(client, timeout(500).times(0)).delete(any(Counter.class));
        assertNull(service.adjustmentError());
    }

    @Test
    void createsTheCounterOnlyWhenItIsMissing() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        Counter created = Counter.emptyCounter(ADJUSTMENT_COUNTER);
        when(client.fetch(Counter.class, ADJUSTMENT_COUNTER)).thenReturn(Mono.empty());
        doReturn(Mono.just(created)).when(client).create(any(Counter.class));
        CountDownLatch refreshed = stubRefresh(client);

        StatsAggregationService service = new StatsAggregationService(client);
        service.ensureAdjustment(12L);

        verify(client, timeout(5_000)).create(any(Counter.class));
        assertTrue(refreshed.await(5, TimeUnit.SECONDS), "the success path never completed");
        verify(client, timeout(500).times(0)).update(any(Counter.class));
        assertNull(service.adjustmentError());
    }

    @Test
    void removingTheAdjustmentDeletesTheCounterWithoutRecreatingIt() throws Exception {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        Counter existing = Counter.emptyCounter(ADJUSTMENT_COUNTER);
        existing.getMetadata().setVersion(7L);
        when(client.fetch(Counter.class, ADJUSTMENT_COUNTER)).thenReturn(Mono.just(existing));
        doReturn(Mono.just(existing)).when(client).delete(any(Counter.class));
        doReturn(Mono.just(existing)).when(client).create(any(Counter.class));
        CountDownLatch refreshed = stubRefresh(client);

        StatsAggregationService service = new StatsAggregationService(client);
        service.ensureAdjustment(0L);

        verify(client, timeout(5_000)).delete(any(Counter.class));
        assertTrue(refreshed.await(5, TimeUnit.SECONDS), "the success path never completed");
        verify(client, timeout(500).times(0)).create(any(Counter.class));
        assertNull(service.adjustmentError());
    }

    /** The success path refreshes the official counters, which is the completion signal. */
    private static CountDownLatch stubRefresh(ReactiveExtensionClient client) {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return Flux.empty();
        }).when(client).listAll(eq(Counter.class), any(ListOptions.class), any(Sort.class));
        return latch;
    }
}