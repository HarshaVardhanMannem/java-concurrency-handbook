package com.newsradar.bench;

import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;
import com.newsradar.parse.RssParser;
import com.newsradar.pipeline.PipelineAggregator;
import com.newsradar.pipeline.PooledAggregator;
import com.newsradar.pipeline.SequentialAggregator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1 vs Phase 2 vs Phase 3 — end-to-end aggregator throughput.
 *
 * Uses an in-memory stub fetcher (zero network latency) so the benchmark isolates
 * the concurrency model overhead rather than I/O variance.
 *
 * KEY INSIGHT the numbers will teach:
 *   When per-task work is CPU-bound and fast (no I/O wait), thread pools add overhead.
 *   The thread pool advantage only materialises when tasks block on I/O and threads
 *   can overlap the waiting.  Measure — don't guess.
 *
 * Each benchmark drives 20 "feeds" through the full parse pipeline once per invocation.
 * Mode.AverageTime measures wall-clock time per complete run; lower is better.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 4, time = 3)
@Fork(1)
@State(Scope.Thread)
public class AggregatorBenchmark {

    private static final int FEED_COUNT = 20;
    private static final int ITEMS_PER_FEED = 10;

    private List<FeedConfig> feeds;
    private FeedFetcher stubFetcher;
    private RssParser parser;

    @Setup(Level.Trial)
    public void setup() {
        byte[] rssBytes = buildRss(ITEMS_PER_FEED).getBytes(StandardCharsets.UTF_8);
        feeds = new ArrayList<>(FEED_COUNT);
        for (int i = 0; i < FEED_COUNT; i++) {
            feeds.add(new FeedConfig("feed-" + i, "Feed " + i,
                    URI.create("https://example.com/feed" + i)));
        }
        // Stub: ignore URL, return the pre-built bytes immediately (no network).
        stubFetcher = feed -> new RawFeed(feed.id(), rssBytes, "application/rss+xml");
        parser = new RssParser();
    }

    @Benchmark
    public List<Article> sequential() {
        return new SequentialAggregator(stubFetcher, parser)
                .run(feeds).articles();
    }

    @Benchmark
    public List<Article> pooled() {
        return new PooledAggregator(stubFetcher, parser, 8)
                .run(feeds).articles();
    }

    @Benchmark
    public List<Article> pipeline() {
        return new PipelineAggregator(stubFetcher, parser, 4, 2, 16)
                .run(feeds).articles();
    }

    // -------------------------------------------------------------------------

    private static String buildRss(int items) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<rss version=\"2.0\"><channel><title>Bench Feed</title>\n");
        for (int i = 1; i <= items; i++) {
            sb.append("<item>\n")
              .append("  <title>Technology and markets news article number ").append(i).append("</title>\n")
              .append("  <link>https://example.com/").append(i).append("</link>\n")
              .append("  <description>")
              .append("Comprehensive analysis of global markets, technology trends, ")
              .append("economic indicators, and policy changes. Article ").append(i).append(" ")
              .append("covers inflation data, Federal Reserve decisions, equity valuations, ")
              .append("and the impact of AI on productivity across industries.")
              .append("</description>\n")
              .append("  <pubDate>Mon, 01 Jan 2024 12:00:00 GMT</pubDate>\n")
              .append("  <guid>bench:").append(i).append("</guid>\n")
              .append("</item>\n");
        }
        sb.append("</channel></rss>");
        return sb.toString();
    }
}
