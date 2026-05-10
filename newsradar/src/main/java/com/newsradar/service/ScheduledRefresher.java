package com.newsradar.service;

import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.index.ConcurrentIndex;
import com.newsradar.index.InvertedIndex;
import com.newsradar.index.SearchableIndex;
import com.newsradar.model.Article;
import com.newsradar.parse.FeedParser;
import com.newsradar.pipeline.PipelineAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 6 — periodically rebuilds the index from feeds.yaml on a
 * {@link ScheduledExecutorService} and atomically swaps the result into
 * a live {@link SearchableIndex} so search traffic never sees a half-built view.
 *
 * Lifecycle:
 *   start()                 -> schedules refresh#1 immediately, then every {@code interval}
 *   awaitFirstRefresh(t)    -> CountDownLatch — block the main thread before serving
 *   stats()                 -> non-blocking snapshot for /health
 *   stop()                  -> cancels the schedule, awaits in-flight refresh, shuts pool
 */
public final class ScheduledRefresher {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRefresher.class);

    private final SearchableIndex target;
    private final List<FeedConfig> feeds;
    private final FeedFetcher fetcher;
    private final FeedParser parser;
    private final int fetcherCount;
    private final int parserCount;
    private final int queueCapacity;
    private final Duration interval;
    private final Duration shutdownTimeout;

    // First-refresh gate: callers can block until the first build completes (success OR failure).
    // CountDownLatch is the canonical "fire once, wake all waiters" primitive.
    private final CountDownLatch firstRefreshLatch = new CountDownLatch(1);

    // Stats — published via stats() to /health. All atomics so /health never blocks the refresher.
    private final AtomicLong refreshCount = new AtomicLong();
    private final AtomicLong refreshFailures = new AtomicLong();
    private final AtomicLong lastRefreshDurationMs = new AtomicLong();
    private final AtomicInteger lastRefreshArticles = new AtomicInteger();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Instant lastRefreshAt;
    private volatile Instant nextRefreshAt;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> handle;

    public ScheduledRefresher(SearchableIndex target,
                              List<FeedConfig> feeds,
                              FeedFetcher fetcher,
                              FeedParser parser,
                              int fetcherCount,
                              int parserCount,
                              int queueCapacity,
                              Duration interval) {
        this(target, feeds, fetcher, parser, fetcherCount, parserCount, queueCapacity,
                interval, Duration.ofSeconds(30));
    }

    public ScheduledRefresher(SearchableIndex target,
                              List<FeedConfig> feeds,
                              FeedFetcher fetcher,
                              FeedParser parser,
                              int fetcherCount,
                              int parserCount,
                              int queueCapacity,
                              Duration interval,
                              Duration shutdownTimeout) {
        if (fetcherCount <= 0 || parserCount <= 0)
            throw new IllegalArgumentException("pool sizes must be > 0");
        if (queueCapacity <= 0)
            throw new IllegalArgumentException("queue capacity must be > 0");
        if (interval.toMillis() <= 0)
            throw new IllegalArgumentException("interval must be > 0");
        this.target = Objects.requireNonNull(target);
        this.feeds = List.copyOf(feeds);
        this.fetcher = Objects.requireNonNull(fetcher);
        this.parser = Objects.requireNonNull(parser);
        this.fetcherCount = fetcherCount;
        this.parserCount = parserCount;
        this.queueCapacity = queueCapacity;
        this.interval = interval;
        this.shutdownTimeout = shutdownTimeout;
    }

    public synchronized void start() {
        if (scheduler != null) throw new IllegalStateException("already started");
        scheduler = Executors.newSingleThreadScheduledExecutor(namedFactory("refresher"));
        // Fixed-rate so refreshes are scheduled relative to the start of the previous one.
        // If a refresh runs longer than the interval, scheduleAtFixedRate serialises them
        // (they don't overlap on a single-thread executor) but they may queue back-to-back.
        // The refreshing flag exists so /health can surface that.
        handle = scheduler.scheduleAtFixedRate(
                this::refresh, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        nextRefreshAt = Instant.now();
        log.info("ScheduledRefresher started — interval={}s, feeds={}", interval.toSeconds(), feeds.size());
    }

    private void refresh() {
        // Single-thread executor guarantees no overlap, but keep the flag for /health visibility.
        refreshing.set(true);
        long t0 = System.currentTimeMillis();
        long refreshNumber = refreshCount.get() + 1;
        log.info("refresh #{} starting", refreshNumber);
        try {
            PipelineAggregator pipeline = new PipelineAggregator(
                    fetcher, parser, fetcherCount, parserCount, queueCapacity);
            PipelineAggregator.Result result = pipeline.run(feeds);

            // Build a brand-new index off-line, then swap atomically. Readers continue
            // to hit the previous (consistent) index until swap() completes.
            InvertedIndex freshIndex = new ConcurrentIndex();
            Map<String, Article> freshArticles = new ConcurrentHashMap<>(result.articles().size() * 2);
            for (Article a : result.articles()) {
                freshIndex.index(a);
                freshArticles.put(a.id(), a);
            }
            target.swap(freshIndex, freshArticles);

            long elapsed = System.currentTimeMillis() - t0;
            lastRefreshDurationMs.set(elapsed);
            lastRefreshArticles.set(result.articles().size());
            lastRefreshAt = Instant.now();
            nextRefreshAt = lastRefreshAt.plus(interval);
            refreshCount.incrementAndGet();
            log.info("refresh #{} done in {} ms — {} articles indexed (next refresh ≈ {})",
                    refreshNumber, elapsed, result.articles().size(), nextRefreshAt);
        } catch (Throwable t) {
            // Never let the scheduler thread die — scheduleAtFixedRate cancels itself on uncaught.
            refreshFailures.incrementAndGet();
            lastRefreshDurationMs.set(System.currentTimeMillis() - t0);
            log.error("refresh #{} failed", refreshNumber, t);
        } finally {
            refreshing.set(false);
            // Latch counts down on the *first* attempt (success OR failure) so the main thread
            // doesn't hang forever on a broken network.
            firstRefreshLatch.countDown();
        }
    }

    /** Block (up to {@code timeout}) until the first refresh attempt completes. */
    public boolean awaitFirstRefresh(Duration timeout) throws InterruptedException {
        return firstRefreshLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public Stats stats() {
        return new Stats(refreshCount.get(),
                refreshFailures.get(),
                refreshing.get(),
                lastRefreshAt,
                nextRefreshAt,
                lastRefreshDurationMs.get(),
                lastRefreshArticles.get(),
                interval);
    }

    /** Cancel the schedule and wait for any in-flight refresh to finish. */
    public synchronized void stop() {
        if (scheduler == null) return;
        log.info("ScheduledRefresher stopping (refreshes={}, failures={})",
                refreshCount.get(), refreshFailures.get());
        if (handle != null) handle.cancel(false);   // false = let the running task complete
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(shutdownTimeout.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("refresher did not terminate in {}s, forcing shutdownNow",
                        shutdownTimeout.toSeconds());
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        scheduler = null;
        log.info("ScheduledRefresher stopped");
    }

    private static ThreadFactory namedFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public record Stats(long refreshCount,
                        long refreshFailures,
                        boolean refreshing,
                        Instant lastRefreshAt,
                        Instant nextRefreshAt,
                        long lastRefreshDurationMs,
                        int lastRefreshArticles,
                        Duration interval) {}
}
