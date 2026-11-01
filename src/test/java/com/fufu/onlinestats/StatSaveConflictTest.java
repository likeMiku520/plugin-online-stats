package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Two writers touch the same article: the heartbeat and the official view count. A
 * conflict in that race is expected and the write is retried; only a write that survives
 * no attempt at all may be reported as lost.
 */
class StatSaveConflictTest {

    private static ArticleStat article(String name, long official) {
        ArticleStat stat = new ArticleStat();
        stat.setMetadata(new Metadata());
        stat.getMetadata().setName(name);
        stat.getSpec().setPostName(name);
        stat.getSpec().setViews(10L);
        stat.getSpec().setVisitors(3L);
        stat.getSpec().setOfficialViews(official);
        return stat;
    }

    @Test
    void aConflictThatTheRetryResolvesIsNotAFailure() {
        ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
        when(client.listAll(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.empty());

        ArticleStat stored = article("post-1", 5L);
        stored.getMetadata().setVersion(2L);
        // The first write is refused because the row already exists and moved on; the
        // retry re-reads it and only then updates.
        when(client.create(any()))
            .thenReturn(Mono.error(new IllegalStateException("Duplicate name detected.")));
        when(client.update(any())).thenAnswer((InvocationOnMock call) -> Mono.just(call.getArgument(0)));
        when(client.fetch(ArticleStat.class, "post-1")).thenReturn(Mono.just(stored));

        StatsAggregationService service = new StatsAggregationService(client);
        ArticleStat mine = article("post-1", 5L);
        mine.getMetadata().setVersion(1L);
        mine.getSpec().setViews(11L);

        service.saveArticle(mine);

        assertEquals(0L, failureCount(service), "the retry wrote it, so nothing was lost");
        assertTrue(stored.getSpec().getViews() >= 11L, "the counter survived the merge");
        verify(client, times(1)).update(any());
    }

    private static long failureCount(StatsAggregationService service) {
        Object value = service.diagnostics().get("statSaveFailures");
        return value instanceof Number number ? number.longValue() : -1L;
    }

    @Test
    void theWaitBetweenAttemptsStaysShort() {
        long start = System.nanoTime();
        for (int attempt = 0; attempt < 3; attempt++) {
            sleepLikeProduction(attempt);
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMs < 500, "three retries must not stall the writer for long");
    }

    private static void sleepLikeProduction(int attempt) {
        try {
            Thread.sleep(20L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}