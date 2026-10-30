package com.fufu.onlinestats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The two visitor scopes have to partition everything, so the unfiltered view is always
 * the sum of the other two. A record whose bot flag was never written belongs to the
 * human side instead of disappearing from both.
 */
class ScopePartitionTest {

    private static VisitorRecord.Spec human(String userAgent) {
        VisitorRecord.Spec spec = new VisitorRecord.Spec();
        spec.setUserAgent(userAgent);
        spec.setScreen("1920x1080");
        return spec;
    }

    @Test
    void aStoredFlagWins() {
        VisitorRecord.Spec spec = human("Mozilla/5.0 Chrome/120");
        spec.setBot(true);

        assertTrue(StatisticsQueryService.isBot(spec));
    }

    @Test
    void anUnsetFlagFallsBackToTheSameRuleAsTheConsole() {
        // Records saved by earlier versions have no bot flag at all.
        VisitorRecord.Spec unset = human("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120");

        assertFalse(StatisticsQueryService.isBot(unset),
            "a real browser with a fingerprint is a person");
    }

    @Test
    void aCrawlerWithoutAFlagIsStillABot() {
        VisitorRecord.Spec crawler = human(
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");

        assertTrue(StatisticsQueryService.isBot(crawler));
    }

    @Test
    void aBrowserWithoutFingerprintCountsAsABot() {
        VisitorRecord.Spec empty = new VisitorRecord.Spec();
        empty.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120");

        assertTrue(StatisticsQueryService.isBot(empty),
            "no screen, timezone or language is what a script looks like");
    }

    @Test
    void nothingFallsBetweenTheTwoScopes() {
        VisitorRecord.Spec[] specs = {
            human("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120"),
            human("Mozilla/5.0 (compatible; bingbot/2.0)"),
            new VisitorRecord.Spec(),
        };

        int bots = 0;
        for (VisitorRecord.Spec spec : specs) {
            if (StatisticsQueryService.isBot(spec)) {
                bots++;
            }
        }
        assertEquals(specs.length, (specs.length - bots) + bots,
            "every record lands in exactly one scope");
        assertEquals(2, bots);
    }
}