package com.newsradar.pipeline;

import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;
import com.newsradar.parse.FeedParser;
import com.newsradar.util.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 3 — bounded-queue pipeline:
 *
 *   feeds  ->  fetcher pool  ->  rawQueue  ->  parser pool  ->  articleQueue  ->  indexer
 *
 * Bounded queues apply backpressure: a slow stage stalls upstream rather than letting the
 * heap balloon. Poison-pill sentinels (RawFeed.SHUTDOWN / Article.SHUTDOWN) signal clean
 * end-of-stream so consumers know when to exit their take-loops.
 */
public final class PipelineAggregator {

    private static final Logger log = LoggerFactory.getLogger(PipelineAggregator.class);

    private final FeedFetcher fetcher;
    private final FeedParser parser;
    private final int fetcherCount;
    private final int parserCount;
    private final int rawQueueCapacity;
    private final int articleQueueCapacity;
    private final Duration shutdownTimeout;

    public PipelineAggregator(FeedFetcher fetcher, FeedParser parser,
                              int fetcherCount, int parserCount, int rawQueueCapacity) {
        this(fetcher, parser, fetcherCount, parserCount,
                rawQueueCapacity, Math.max(rawQueueCapacity * 4, 64),
                Duration.ofSeconds(15));
    }

    public PipelineAggregator(FeedFetcher fetcher, FeedParser parser,
                              int fetcherCount, int parserCount,
                              int rawQueueCapacity, int articleQueueCapacity,
                              Duration shutdownTimeout) {
        if (fetcherCount <= 0 || parserCount <= 0)
            throw new IllegalArgumentException("pool sizes must be > 0");
        if (rawQueueCapacity <= 0 || articleQueueCapacity <= 0)
            throw new IllegalArgumentException("queue capacities must be > 0");
        this.fetcher = fetcher;
        this.parser = parser;
        this.fetcherCount = fetcherCount;
        this.parserCount = parserCount;
        this.rawQueueCapacity = rawQueueCapacity;
        this.articleQueueCapacity = articleQueueCapacity;
        this.shutdownTimeout = shutdownTimeout;
    }

    public Result run(List<FeedConfig> feeds) {
        banner(feeds.size());

        BlockingQueue<RawFeed> rawQueue = new ArrayBlockingQueue<>(rawQueueCapacity);
        BlockingQueue<Article> articleQueue = new ArrayBlockingQueue<>(articleQueueCapacity);

        ExecutorService fetcherPool = Executors.newFixedThreadPool(fetcherCount, namedFactory("fetcher"));
        ExecutorService parserPool = Executors.newFixedThreadPool(parserCount, namedFactory("parser"));
        ExecutorService indexerExec = Executors.newSingleThreadExecutor(namedFactory("indexer"));
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(namedFactory("monitor"));

        // Stage counters
        AtomicInteger fetchOk = new AtomicInteger();
        AtomicInteger fetchFail = new AtomicInteger();
        AtomicInteger parseOk = new AtomicInteger();
        AtomicInteger parseFail = new AtomicInteger();
        AtomicLong bytesIn = new AtomicLong();
        AtomicInteger articlesIn = new AtomicInteger();
        AtomicInteger fetcherStalls = new AtomicInteger();   // times fetcher.put() blocked
        AtomicInteger parserStalls = new AtomicInteger();    // times parser.put() blocked

        // Schedule queue-depth monitor every 250ms
        ScheduledFuture<?> monitorHandle = monitor.scheduleAtFixedRate(
                () -> log.info("[monitor] raw={}/{}  articles={}/{}  fetched={} parsed={} indexed={} stalls(fetch={},parse={})",
                        rawQueue.size(), rawQueueCapacity,
                        articleQueue.size(), articleQueueCapacity,
                        fetchOk.get(), parseOk.get(), articlesIn.get(),
                        fetcherStalls.get(), parserStalls.get()),
                250, 250, TimeUnit.MILLISECONDS);

        Stopwatch wall = Stopwatch.start();
        try {
            // 1. Submit fetch tasks
            List<Future<?>> fetcherFutures = new ArrayList<>();
            for (FeedConfig feed : feeds) {
                fetcherFutures.add(fetcherPool.submit(fetchTask(
                        feed, rawQueue, fetchOk, fetchFail, bytesIn, fetcherStalls)));
            }

            // 2. Submit parser workers (long-running loops)
            List<Future<?>> parserFutures = new ArrayList<>();
            for (int i = 0; i < parserCount; i++) {
                parserFutures.add(parserPool.submit(parserLoop(
                        rawQueue, articleQueue, parseOk, parseFail, parserStalls)));
            }

            // 3. Submit indexer (single-thread accumulator)
            Future<List<Article>> indexerFuture = indexerExec.submit(
                    indexerLoop(articleQueue, articlesIn));

            // 4. Wait for all fetchers to finish their put() then poison the parser stage
            for (Future<?> f : fetcherFutures) {
                try { f.get(); } catch (Exception ignored) { /* failure already counted */ }
            }
            for (int i = 0; i < parserCount; i++) {
                rawQueue.put(RawFeed.SHUTDOWN);
            }

            // 5. Wait for parsers to drain, then poison the indexer
            for (Future<?> f : parserFutures) {
                try { f.get(); } catch (Exception ignored) { /* shouldn't happen */ }
            }
            articleQueue.put(Article.SHUTDOWN);

            // 6. Collect indexed articles
            List<Article> indexed = indexerFuture.get();
            long elapsed = wall.elapsedMillis();

            monitorHandle.cancel(false);
            summarise(feeds.size(),
                    fetchOk.get(), fetchFail.get(),
                    parseOk.get(), parseFail.get(),
                    indexed.size(), bytesIn.get(),
                    fetcherStalls.get(), parserStalls.get(),
                    elapsed);
            return new Result(indexed, elapsed,
                    fetchFail.get(), parseFail.get(),
                    fetcherStalls.get(), parserStalls.get());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("pipeline interrupted");
            return new Result(List.of(), wall.elapsedMillis(), 0, 0, 0, 0);
        } catch (Exception e) {
            log.error("pipeline failed", e);
            return new Result(List.of(), wall.elapsedMillis(), 0, 0, 0, 0);
        } finally {
            monitorHandle.cancel(false);
            shutdownGracefully(fetcherPool, "fetcher");
            shutdownGracefully(parserPool, "parser");
            shutdownGracefully(indexerExec, "indexer");
            shutdownGracefully(monitor, "monitor");
        }
    }

    // ---- stage tasks --------------------------------------------------------

    private Runnable fetchTask(FeedConfig feed,
                               BlockingQueue<RawFeed> out,
                               AtomicInteger ok, AtomicInteger fail,
                               AtomicLong bytesIn, AtomicInteger stalls) {
        return () -> {
            String thread = Thread.currentThread().getName();
            Stopwatch sw = Stopwatch.start();
            try {
                RawFeed raw = fetcher.fetch(feed);
                bytesIn.addAndGet(raw.body().length);
                long fetchMs = sw.elapsedMillis();
                if (out.remainingCapacity() == 0) stalls.incrementAndGet();
                out.put(raw);   // blocks here if downstream is full -> backpressure
                ok.incrementAndGet();
                log.info("[{}] fetched  {}  in {} ms  ({} bytes)  -> raw queue (depth {})",
                        thread, feed.id(), fetchMs, raw.body().length, out.size());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                fail.incrementAndGet();
                log.warn("[{}] fetch INTR {}", thread, feed.id());
            } catch (Exception e) {
                fail.incrementAndGet();
                log.warn("[{}] fetch FAIL {} after {} ms : {}",
                        thread, feed.id(), sw.elapsedMillis(), e.getClass().getSimpleName());
            }
        };
    }

    private Runnable parserLoop(BlockingQueue<RawFeed> in,
                                BlockingQueue<Article> out,
                                AtomicInteger ok, AtomicInteger fail,
                                AtomicInteger stalls) {
        return () -> {
            String thread = Thread.currentThread().getName();
            try {
                while (true) {
                    RawFeed raw = in.take();
                    if (raw.isShutdown()) {
                        log.debug("[{}] saw poison pill, exiting", thread);
                        return;
                    }
                    Stopwatch sw = Stopwatch.start();
                    try {
                        List<Article> articles = parser.parse(raw);
                        for (Article a : articles) {
                            if (out.remainingCapacity() == 0) stalls.incrementAndGet();
                            out.put(a);   // blocks if indexer is slow
                        }
                        ok.incrementAndGet();
                        log.info("[{}] parsed   {}  -> {} articles in {} ms  (article queue depth {})",
                                thread, raw.feedId(), articles.size(), sw.elapsedMillis(), out.size());
                    } catch (Exception e) {
                        fail.incrementAndGet();
                        log.warn("[{}] parse FAIL {} : {}", thread, raw.feedId(), e.getClass().getSimpleName());
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[{}] parser interrupted", thread);
            }
        };
    }

    private java.util.concurrent.Callable<List<Article>> indexerLoop(BlockingQueue<Article> in,
                                                                     AtomicInteger counter) {
        return () -> {
            String thread = Thread.currentThread().getName();
            List<Article> indexed = new ArrayList<>();
            try {
                while (true) {
                    Article a = in.take();
                    if (a.isShutdown()) {
                        log.debug("[{}] saw poison pill, exiting", thread);
                        return indexed;
                    }
                    indexed.add(a);
                    counter.incrementAndGet();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[{}] indexer interrupted", thread);
                return indexed;
            }
        };
    }

    // ---- helpers ------------------------------------------------------------

    private void shutdownGracefully(ExecutorService exec, String name) {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(shutdownTimeout.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("{} pool did not terminate in {}s, forcing shutdownNow",
                        name, shutdownTimeout.toSeconds());
                exec.shutdownNow();
            }
        } catch (InterruptedException ie) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private void banner(int feedCount) {
        log.info("");
        log.info("================================================================");
        log.info("  PHASE 3 — Bounded-Queue Pipeline");
        log.info("================================================================");
        log.info("  feeds            : {}", feedCount);
        log.info("  fetcher pool     : {}", fetcherCount);
        log.info("  parser  pool     : {}", parserCount);
        log.info("  rawQueue cap     : {}", rawQueueCapacity);
        log.info("  articleQueue cap : {}", articleQueueCapacity);
        log.info("  monitor          : every 250 ms");
        log.info("");
    }

    private void summarise(int feedCount, int fetchOk, int fetchFail,
                           int parseOk, int parseFail,
                           int indexed, long bytes,
                           int fetcherStalls, int parserStalls,
                           long wallMs) {
        log.info("");
        log.info("RESULTS");
        log.info("  feeds            : {}", feedCount);
        log.info("  fetched ok       : {}", fetchOk);
        if (fetchFail > 0) log.info("  fetched failed   : {}", fetchFail);
        log.info("  parsed feeds     : {}", parseOk);
        if (parseFail > 0) log.info("  parsed failed    : {}", parseFail);
        log.info("  articles indexed : {}", indexed);
        log.info("  bytes downloaded : {} ({} KB)", bytes, bytes / 1024);
        log.info("");
        log.info("BACKPRESSURE");
        log.info("  fetcher put() stalls (rawQueue full)    : {}", fetcherStalls);
        log.info("  parser  put() stalls (articleQ full)    : {}", parserStalls);
        log.info("  (a stall = an upstream thread had to wait — the bounded queue working as designed)");
        log.info("");
        log.info("TIMING");
        log.info("  wall time        : {} ms", wallMs);
        log.info("");
        log.info("CONCURRENCY LESSON");
        log.info("  Each stage runs on its own pool with bounded buffers between them.");
        log.info("  A slow downstream stalls upstream via .put() instead of growing the heap.");
        log.info("  Poison pills (RawFeed.SHUTDOWN / Article.SHUTDOWN) close stages cleanly.");
        log.info("");
    }

    public record Result(List<Article> articles, long elapsedMillis,
                         int fetchFailed, int parseFailed,
                         int fetcherStalls, int parserStalls) {
    }
}
