package com.newsradar.pipeline;

import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.model.RawFeed;
import com.newsradar.parse.RssParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PooledAggregatorTest {

    private static final String FAKE_RSS = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <item><title>One</title><link>https://e.com/1</link><description>x</description></item>
            </channel></rss>
            """;

    private static List<FeedConfig> fakeFeeds(int n) {
        List<FeedConfig> feeds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            feeds.add(new FeedConfig("f" + i, "Feed " + i, URI.create("https://example.com/" + i)));
        }
        return feeds;
    }

    private static FeedFetcher slowFetcher(long sleepMillis, AtomicInteger calls) {
        return feed -> {
            calls.incrementAndGet();
            Thread.sleep(sleepMillis);
            return new RawFeed(feed.id(), FAKE_RSS.getBytes(StandardCharsets.UTF_8), "application/rss+xml");
        };
    }

    @Test
    void poolParallelisesFetches() {
        int feeds = 8;
        long perFeedMs = 200;
        AtomicInteger calls = new AtomicInteger();

        PooledAggregator.Result r = new PooledAggregator(
                slowFetcher(perFeedMs, calls), new RssParser(),
                feeds, Duration.ofSeconds(5), Duration.ofSeconds(5))
                .run(fakeFeeds(feeds));

        assertEquals(feeds, calls.get(), "every feed should be fetched once");
        assertEquals(feeds, r.articles().size(), "each fake feed contributes 1 article");
        assertEquals(0, r.failedFeeds(), "no failures expected");

        long sumLatency = (long) feeds * perFeedMs;
        assertTrue(r.elapsedMillis() < sumLatency,
                "wall " + r.elapsedMillis() + "ms must be < serial sum " + sumLatency + "ms");
        assertTrue(r.elapsedMillis() < perFeedMs * 3,
                "with pool=" + feeds + " wall should be near 1x perFeed (~" + perFeedMs
                        + "ms), got " + r.elapsedMillis() + "ms");
    }

    @Test
    void poolSizeOneBehavesSerially() {
        int feeds = 4;
        long perFeedMs = 100;
        AtomicInteger calls = new AtomicInteger();

        PooledAggregator.Result r = new PooledAggregator(
                slowFetcher(perFeedMs, calls), new RssParser(),
                1, Duration.ofSeconds(5), Duration.ofSeconds(5))
                .run(fakeFeeds(feeds));

        long sumLatency = (long) feeds * perFeedMs;
        assertTrue(r.elapsedMillis() >= sumLatency - 50,
                "pool=1 should be ~serial; wall=" + r.elapsedMillis() + " sum=" + sumLatency);
    }

    @Test
    void failingFeedDoesNotKillRun() {
        FeedFetcher mixed = feed -> {
            if (feed.id().equals("f1")) throw new java.io.IOException("simulated boom");
            Thread.sleep(50);
            return new RawFeed(feed.id(), FAKE_RSS.getBytes(StandardCharsets.UTF_8), "application/rss+xml");
        };

        PooledAggregator.Result r = new PooledAggregator(
                mixed, new RssParser(), 4, Duration.ofSeconds(5), Duration.ofSeconds(5))
                .run(fakeFeeds(4));

        assertEquals(1, r.failedFeeds());
        assertEquals(3, r.articles().size());
    }
}
