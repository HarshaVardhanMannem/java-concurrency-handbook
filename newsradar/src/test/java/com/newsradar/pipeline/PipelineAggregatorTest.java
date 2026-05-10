package com.newsradar.pipeline;

import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.model.RawFeed;
import com.newsradar.parse.RssParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineAggregatorTest {

    private static final String TWO_ITEM_RSS = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <item><title>One</title><link>https://e.com/1</link><description>x</description></item>
              <item><title>Two</title><link>https://e.com/2</link><description>y</description></item>
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
            return new RawFeed(feed.id(), TWO_ITEM_RSS.getBytes(StandardCharsets.UTF_8), "application/rss+xml");
        };
    }

    @Test
    void pipelineDeliversAllArticles() {
        int feeds = 10;
        AtomicInteger calls = new AtomicInteger();

        PipelineAggregator.Result r = new PipelineAggregator(
                slowFetcher(60, calls), new RssParser(),
                /* fetchers */ 4, /* parsers */ 2, /* rawCap */ 4)
                .run(fakeFeeds(feeds));

        assertEquals(feeds, calls.get(), "every feed should be fetched once");
        assertEquals(feeds * 2, r.articles().size(), "each fake feed contributes 2 articles");
        assertEquals(0, r.fetchFailed());
        assertEquals(0, r.parseFailed());
    }

    @Test
    void pipelineParallelisesAcrossStages() {
        int feeds = 8;
        long perFeedMs = 100;
        AtomicInteger calls = new AtomicInteger();

        PipelineAggregator.Result r = new PipelineAggregator(
                slowFetcher(perFeedMs, calls), new RssParser(),
                4, 2, 4)
                .run(fakeFeeds(feeds));

        long serialFloor = (long) feeds * perFeedMs;
        assertTrue(r.elapsedMillis() < serialFloor,
                "wall " + r.elapsedMillis() + "ms must be < serial floor " + serialFloor + "ms");
    }

    @Test
    void smallQueueForcesBackpressure() throws Exception {
        // Tiny queue + slow parser path: with rawCap=1, the moment a parser is busy
        // a second fetcher's put() must block, registering a stall.
        FeedFetcher fast = feed -> new RawFeed(feed.id(),
                TWO_ITEM_RSS.getBytes(StandardCharsets.UTF_8), "application/rss+xml");

        // Slow parser via Parser interface: sleeps inside parse() to keep the rawQueue full.
        RssParser real = new RssParser();
        com.newsradar.parse.FeedParser slowParser = raw -> {
            try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return real.parse(raw);
        };

        PipelineAggregator.Result r = new PipelineAggregator(
                fast, slowParser, 8, 1, 1)
                .run(fakeFeeds(20));

        assertEquals(40, r.articles().size());
        assertTrue(r.fetcherStalls() > 0,
                "expected fetcher stalls > 0 with rawCap=1 and slow parser, got " + r.fetcherStalls());
    }

    @Test
    void failingFeedDoesNotBlockPipeline() {
        FeedFetcher mixed = feed -> {
            if (feed.id().equals("f1")) throw new IOException("simulated boom");
            Thread.sleep(20);
            return new RawFeed(feed.id(), TWO_ITEM_RSS.getBytes(StandardCharsets.UTF_8), "application/rss+xml");
        };

        PipelineAggregator.Result r = new PipelineAggregator(
                mixed, new RssParser(), 4, 2, 4)
                .run(fakeFeeds(5));

        assertEquals(1, r.fetchFailed());
        assertEquals(8, r.articles().size(), "4 successful feeds × 2 articles each");
    }
}
