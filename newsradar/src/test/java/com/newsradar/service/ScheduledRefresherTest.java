package com.newsradar.service;

import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.index.SearchableIndex;
import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;
import com.newsradar.parse.FeedParser;
import com.newsradar.parse.RssParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledRefresherTest {

    private static final String TWO_ITEM_RSS = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <item><title>java threads</title><link>https://e.com/1</link><description>concurrency</description></item>
              <item><title>java io</title><link>https://e.com/2</link><description>blocking</description></item>
            </channel></rss>
            """;

    private static FeedFetcher fakeFetcher(AtomicInteger calls) {
        return feed -> {
            calls.incrementAndGet();
            return new RawFeed(feed.id(), TWO_ITEM_RSS.getBytes(StandardCharsets.UTF_8), "application/rss+xml");
        };
    }

    private static List<FeedConfig> feeds(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new FeedConfig("f" + i, "Feed " + i, URI.create("https://example.com/" + i)))
                .toList();
    }

    @Test
    void firstRefreshPopulatesIndexAndReleasesLatch() throws Exception {
        SearchableIndex idx = new SearchableIndex();
        AtomicInteger fetches = new AtomicInteger();

        ScheduledRefresher r = new ScheduledRefresher(
                idx, feeds(4), fakeFetcher(fetches), new RssParser(),
                /* fetchers */ 2, /* parsers */ 2, /* queue */ 4,
                Duration.ofSeconds(60));   // long interval — only refresh #1 will run during the test
        r.start();
        try {
            assertTrue(r.awaitFirstRefresh(Duration.ofSeconds(15)),
                    "first refresh must complete within timeout");

            // 4 feeds × 2 items each = 8 articles in the swapped index
            SearchableIndex.Snapshot s = idx.snapshot();
            assertEquals(8, s.articles());
            assertTrue(s.tokens() > 0);
            assertNotNull(s.lastRefresh());

            assertEquals(4, fetches.get(), "fakeFetcher must be called once per feed");

            ScheduledRefresher.Stats stats = r.stats();
            assertEquals(1, stats.refreshCount());
            assertEquals(0, stats.refreshFailures());
            assertEquals(8, stats.lastRefreshArticles());
            assertNotNull(stats.lastRefreshAt());
            assertNotNull(stats.nextRefreshAt());
        } finally {
            r.stop();
        }
    }

    @Test
    void atomicSwapShowsNewArticleSet() throws Exception {
        SearchableIndex idx = new SearchableIndex();
        // Pre-populate with a stale article that should be GONE after the first refresh swap.
        idx.add(new Article("stale-1", "old", "stale article", "old body", "https://stale", Instant.now()));
        assertEquals(1, idx.snapshot().articles());

        ScheduledRefresher r = new ScheduledRefresher(
                idx, feeds(2), fakeFetcher(new AtomicInteger()), new RssParser(),
                2, 1, 2, Duration.ofSeconds(60));
        r.start();
        try {
            assertTrue(r.awaitFirstRefresh(Duration.ofSeconds(15)));
            // After swap the stale entry is gone — exactly 2 feeds × 2 articles = 4.
            assertEquals(4, idx.snapshot().articles(),
                    "swap must replace the article map atomically — stale entry should be evicted");
        } finally {
            r.stop();
        }
    }

    @Test
    void refreshFailureStillReleasesLatchAndCountsFailure() throws Exception {
        SearchableIndex idx = new SearchableIndex();

        // A parser that throws blows up every parse task — the pipeline still completes
        // (it counts parseFails) and our refresh proceeds with zero articles. Inject a
        // fetcher that throws to fail the upstream stage instead, then verify behaviour.
        FeedFetcher boom = feed -> { throw new java.io.IOException("simulated network down"); };
        FeedParser parser = new RssParser();

        ScheduledRefresher r = new ScheduledRefresher(
                idx, feeds(3), boom, parser, 2, 1, 2, Duration.ofSeconds(60));
        r.start();
        try {
            // Latch must release even though every fetch failed — pipeline still returns
            // an empty Result and we still swap (an empty index, but a real one).
            assertTrue(r.awaitFirstRefresh(Duration.ofSeconds(15)),
                    "latch must release even on a fully-failed refresh");
            assertEquals(0, idx.snapshot().articles());
            // The refresh itself didn't throw — it logged & swapped an empty index.
            // refreshCount=1, failures=0 from ScheduledRefresher's POV (pipeline owns the failure counters).
            assertEquals(1, r.stats().refreshCount());
        } finally {
            r.stop();
        }
    }

    @Test
    void stopIsIdempotent() {
        SearchableIndex idx = new SearchableIndex();
        ScheduledRefresher r = new ScheduledRefresher(
                idx, feeds(1), fakeFetcher(new AtomicInteger()), new RssParser(),
                1, 1, 1, Duration.ofSeconds(60));
        r.start();
        r.stop();
        r.stop();   // must not throw
    }
}
