package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * A visit to a page that is not an article is still a visit. Only counting article pages
 * was why the dashboard never matched what the site actually served.
 */
class AllPageCountingTest {

    @Test
    void aPageIsNamedByTitleAndRoute() {
        assertEquals("关于本站 (/about)", StatsAggregationService.pageKey(
            "关于本站", "https://blog.fufu520.cn/about"));
    }

    @Test
    void aQueryStringDoesNotSplitOnePageIntoTwo() {
        String plain = StatsAggregationService.pageKey("列表", "https://blog.fufu520.cn/tags");
        String filtered = StatsAggregationService.pageKey("列表",
            "https://blog.fufu520.cn/tags?page=2");

        assertEquals(plain, filtered, "the same route with different parameters is one page");
        assertEquals("列表 (/tags)", plain);
    }

    @Test
    void aPageWithoutATitleIsNamedByItsRoute() {
        assertEquals("/archives", StatsAggregationService.pageKey(null,
            "https://blog.fufu520.cn/archives"));
    }

    @Test
    void aMissingAddressIsStillNamed() {
        assertEquals("未知页面", StatsAggregationService.pageKey(null, null));
    }

    @Test
    void theHomePageIsItsOwnRow() {
        assertEquals("/", StatsAggregationService.pageKey(null, "https://blog.fufu520.cn/"));
    }
}