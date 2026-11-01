package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The page ranking has to name a page the same way in the list and in its drill-down,
 * and it has to say which route the page lives at, otherwise two pages with the same
 * title would collapse into one row.
 */
class PageLabelTest {

    private static String label(String title, String url) throws Exception {
        Method method = StatisticsQueryService.class
            .getDeclaredMethod("pageLabel", VisitorRecord.Spec.class);
        method.setAccessible(true);
        VisitorRecord.Spec spec = new VisitorRecord.Spec();
        spec.setLastPageTitle(title);
        spec.setLastPageUrl(url);
        return (String) method.invoke(null, spec);
    }

    private static String route(String url) throws Exception {
        Method method = StatisticsQueryService.class
            .getDeclaredMethod("routeOf", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, url);
    }

    @Test
    void aPageIsNamedByItsTitleAndRoute() throws Exception {
        assertEquals("你好，世界 (/archives/hello)",
            label("你好，世界", "https://blog.fufu520.cn/archives/hello"));
    }

    @Test
    void aQueryStringIsNotPartOfTheRoute() throws Exception {
        assertEquals("/archives/hello", route("https://blog.fufu520.cn/archives/hello?from=feed"));
    }

    @Test
    void aPageWithoutATitleIsNamedByItsRoute() throws Exception {
        assertEquals("/about", label(null, "https://blog.fufu520.cn/about"));
    }

    @Test
    void twoPagesWithTheSameTitleStayApart() throws Exception {
        String first = label("归档", "https://blog.fufu520.cn/archives/2025");
        String second = label("归档", "https://blog.fufu520.cn/archives/2026");

        assertTrue(!first.equals(second), "the route has to disambiguate them");
    }

    @Test
    void anAddressWithoutAPathFallsBackToTheRoot() throws Exception {
        assertEquals("/", route("https://blog.fufu520.cn"));
    }

    @Test
    void anEmptyAddressHasNoRoute() throws Exception {
        assertNull(route(null));
        assertNull(route(""));
    }

    @Test
    void aMissingPageIsNamedAsSuch() throws Exception {
        assertEquals("未知页面", label(null, null));
    }
}