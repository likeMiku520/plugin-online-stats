package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Page counters are filed under the route, not under the title.
 *
 * A title is what the browser reports at heartbeat time: a client side page swap leaves
 * it one navigation behind, and the chat widget rewrites it while counting unread
 * messages. Using it as part of the key split one address into a dozen rows.
 */
class PageRouteKeyTest {

    @Test
    void aRouteDropsTheQueryAndTheHost() {
        assertEquals("/archives/01a16756",
            StatsAggregationService.pageRoute("https://blog.fufu520.cn/archives/01a16756?from=rss"));
        assertEquals("/copyright", StatsAggregationService.pageRoute("/copyright"));
        assertEquals("/", StatsAggregationService.pageRoute("/"));
    }

    @Test
    void anEmptyAddressStillHasAHome() {
        assertEquals("未知页面", StatsAggregationService.pageRoute(null));
        assertEquals("未知页面", StatsAggregationService.pageRoute(""));
    }

    @Test
    void theSameRouteKeepsOneKeyWhateverTheTitle() {
        String first = StatsAggregationService.pageRoute("/links");
        String second = StatsAggregationService.pageRoute("https://blog.fufu520.cn/links?x=1");
        assertEquals(first, second, "the title plays no part in the key");
    }
}
