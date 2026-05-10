package com.newsradar;

import com.newsradar.config.FeedConfig;
import com.newsradar.config.FeedConfigLoader;
import com.newsradar.fetch.HttpFeedFetcher;
import com.newsradar.http.LoadTest;
import com.newsradar.http.SearchServer;
import com.newsradar.index.ConcurrentIndex;
import com.newsradar.index.IndexFixture;
import com.newsradar.index.IndexFixture.StressResult;
import com.newsradar.index.InvertedIndex;
import com.newsradar.index.SearchableIndex;
import com.newsradar.index.SynchronizedIndex;
import com.newsradar.index.UnsafeHashMapIndex;
import com.newsradar.model.Article;
import com.newsradar.parse.RssParser;
import com.newsradar.pipeline.PipelineAggregator;
import com.newsradar.pipeline.PooledAggregator;
import com.newsradar.pipeline.SequentialAggregator;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        log.info("NewsRadar starting (java {} / vendor {})",
                System.getProperty("java.version"),
                System.getProperty("java.vendor"));

        String mode = argValue(args, "--mode", "info");

        switch (mode) {
            case "info" -> log.info("Modes: --mode=sequential | --mode=pooled --pool=N | "
                    + "--mode=pipeline --fetchers=N --parsers=M --queue=K | "
                    + "--mode=index-stress --threads=N --articles=M --vocab=K --tokens=T | "
                    + "--mode=serve --port=8080 | --mode=loadtest --url=... --clients=N | "
                    + "--mode=compare");
            case "sequential" -> runSequential();
            case "pooled" -> {
                int pool = Integer.parseInt(argValue(args, "--pool", "8"));
                runPooled(pool);
            }
            case "pipeline" -> {
                int fetchers = Integer.parseInt(argValue(args, "--fetchers", "16"));
                int parsers = Integer.parseInt(argValue(args, "--parsers",
                        String.valueOf(Runtime.getRuntime().availableProcessors())));
                int queue = Integer.parseInt(argValue(args, "--queue", "16"));
                runPipeline(fetchers, parsers, queue);
            }
            case "index-stress" -> {
                int threads = Integer.parseInt(argValue(args, "--threads",
                        String.valueOf(Runtime.getRuntime().availableProcessors())));
                int articles = Integer.parseInt(argValue(args, "--articles", "4000"));
                int vocab = Integer.parseInt(argValue(args, "--vocab", "200"));
                int tokens = Integer.parseInt(argValue(args, "--tokens", "8"));
                runIndexStress(threads, articles, vocab, tokens);
            }
            case "serve" -> {
                int port = Integer.parseInt(argValue(args, "--port", "8080"));
                runServe(port);
            }
            case "loadtest" -> {
                String url = argValue(args, "--url", "http://localhost:8080/search?q=java&limit=10");
                int clients = Integer.parseInt(argValue(args, "--clients", "10000"));
                runLoadTest(url, clients);
            }
            case "compare" -> runCompare();
            default -> {
                log.error("Unknown --mode={}. Known modes: info, sequential, pooled, pipeline, index-stress, serve, loadtest, compare", mode);
                System.exit(2);
            }
        }
    }

    private static void runSequential() {
        List<FeedConfig> feeds = FeedConfigLoader.loadFromClasspath("feeds.yaml");
        new SequentialAggregator(new HttpFeedFetcher(), new RssParser()).run(feeds);
    }

    private static void runPooled(int poolSize) {
        List<FeedConfig> feeds = FeedConfigLoader.loadFromClasspath("feeds.yaml");
        new PooledAggregator(new HttpFeedFetcher(), new RssParser(), poolSize).run(feeds);
    }

    private static void runPipeline(int fetchers, int parsers, int queueCap) {
        List<FeedConfig> feeds = FeedConfigLoader.loadFromClasspath("feeds.yaml");
        new PipelineAggregator(new HttpFeedFetcher(), new RssParser(),
                fetchers, parsers, queueCap).run(feeds);
    }

    private static void runServe(int port) {
        log.info("");
        log.info("================================================================");
        log.info("  PHASE 5 — Serve");
        log.info("================================================================");

        // 1. Use Phase 3 pipeline to fetch + parse all feeds.
        List<FeedConfig> feeds = FeedConfigLoader.loadFromClasspath("feeds.yaml");
        int cores = Runtime.getRuntime().availableProcessors();
        PipelineAggregator.Result pipe = new PipelineAggregator(
                new HttpFeedFetcher(), new RssParser(), 16, cores, 16).run(feeds);

        // 2. Index them into the SearchableIndex backing the HTTP server.
        SearchableIndex idx = new SearchableIndex();
        for (Article a : pipe.articles()) idx.add(a);
        SearchableIndex.Snapshot s = idx.snapshot();
        log.info("indexed {} articles, {} tokens, {} postings",
                s.articles(), s.tokens(), s.postings());

        // 3. Start the server on a virtual-thread executor.
        SearchServer server = new SearchServer(idx, port);
        try {
            server.start();
        } catch (Exception e) {
            log.error("failed to start HTTP server on port {}", port, e);
            return;
        }

        // 4. Block until SIGINT (Ctrl-C). Shutdown hook releases the latch.
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutdown hook fired");
            server.stop();
            shutdown.countDown();
        }, "shutdown-hook"));
        log.info("Press Ctrl-C to stop");
        try { shutdown.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static void runLoadTest(String url, int clients) {
        try {
            LoadTest.Result r = new LoadTest().run(url, clients, Duration.ofSeconds(10));
            LoadTest.printSummary(r);
        } catch (Exception e) {
            log.error("load test failed", e);
        }
    }

    private static void runIndexStress(int threads, int articles, int vocab, int tokensPerArticle) {
        log.info("");
        log.info("================================================================");
        log.info("  PHASE 4 — Inverted Index Stress");
        log.info("================================================================");
        log.info("  threads     : {}", threads);
        log.info("  articles    : {}", articles);
        log.info("  per-article : {} tokens drawn from {}-token vocabulary", tokensPerArticle, vocab);

        List<Article> sample = IndexFixture.articles(articles, tokensPerArticle, vocab, 42L);
        int expectedTokens = IndexFixture.expectedTokens(sample);
        int expectedPostings = IndexFixture.expectedPostings(sample);
        log.info("  expected    : tokens={} postings={}", expectedTokens, expectedPostings);
        log.info("");
        log.info(String.format("  %-20s %-9s %8s %10s %12s %12s",
                "impl", "correct?", "tokens", "postings", "elapsed_ms", "ops/sec"));
        log.info("  ----------------------------------------------------------------------------------");

        for (Supplier<InvertedIndex> ctor : List.of(
                (Supplier<InvertedIndex>) UnsafeHashMapIndex::new,
                (Supplier<InvertedIndex>) SynchronizedIndex::new,
                (Supplier<InvertedIndex>) ConcurrentIndex::new)) {
            try {
                StressResult r = IndexFixture.stress(ctor.get(), sample, threads);
                String correct = r.firstException() != null
                        ? "THREW " + r.firstException().getClass().getSimpleName()
                        : (r.isCorrect() ? "yes" : "NO");
                log.info(String.format("  %-20s %-9s %8d %10d %12d %12d",
                        r.impl(), correct, r.tokens(), r.postings(),
                        r.elapsedMillis(), r.opsPerSecond()));
            } catch (Exception e) {
                log.warn("stress run failed: {}", e.toString());
            }
        }

        log.info("");
        log.info("LESSON");
        log.info("  UnsafeHashMap  : data race on get-then-put loses postings (sometimes throws).");
        log.info("  Synchronized   : correct, but every call serialises through one monitor.");
        log.info("  Concurrent     : ConcurrentHashMap.computeIfAbsent + newKeySet — correct & scales.");
        log.info("");
    }

    private static void runCompare() {
        List<FeedConfig> feeds = FeedConfigLoader.loadFromClasspath("feeds.yaml");
        HttpFeedFetcher fetcher = new HttpFeedFetcher();
        RssParser parser = new RssParser();

        List<Row> rows = new ArrayList<>();
        SequentialAggregator.Result seq = new SequentialAggregator(fetcher, parser).run(feeds);
        rows.add(new Row("sequential", 1, seq.elapsedMillis(), seq.articles().size(), seq.failedFeeds()));

        for (int pool : new int[]{4, 8, 16, 32}) {
            PooledAggregator.Result r = new PooledAggregator(fetcher, parser, pool).run(feeds);
            rows.add(new Row("pooled", pool, r.elapsedMillis(), r.articles().size(), r.failedFeeds()));
        }

        int cores = Runtime.getRuntime().availableProcessors();
        PipelineAggregator.Result pip = new PipelineAggregator(fetcher, parser, 16, cores, 16).run(feeds);
        rows.add(new Row("pipeline", 16, pip.elapsedMillis(), pip.articles().size(), pip.fetchFailed()));

        long baseline = rows.get(0).wallMs;
        log.info("");
        log.info("================================================================");
        log.info("  COMPARISON — same {} feeds, back-to-back", feeds.size());
        log.info("================================================================");
        log.info(String.format("  %-11s %6s %11s %9s %8s %9s",
                "mode", "pool", "wall_ms", "articles", "failed", "speedup"));
        log.info("  ----------------------------------------------------------------");
        for (Row r : rows) {
            double speedup = (double) baseline / r.wallMs;
            log.info(String.format("  %-11s %6d %11s %9d %8d %8.2fx",
                    r.mode, r.pool, fmt(r.wallMs), r.articles, r.failed, speedup));
        }
        log.info("");
        log.info("Baseline = sequential ({} ms). Speedup = baseline / wall.", fmt(baseline));
    }

    private record Row(String mode, int pool, long wallMs, int articles, int failed) {}

    private static String fmt(long n) { return String.format("%,d", n); }

    private static String argValue(String[] args, String key, String fallback) {
        String prefix = key + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) return a.substring(prefix.length());
        }
        return fallback;
    }
}
