package com.newsradar.pipeline;

import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;
import com.newsradar.parse.RssParser;
import com.newsradar.util.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class PooledAggregator {

    private static final Logger log = LoggerFactory.getLogger(PooledAggregator.class);

    private final FeedFetcher fetcher;
    private final RssParser parser;
    private final int poolSize;
    private final Duration perTaskTimeout;
    private final Duration shutdownTimeout;

    public PooledAggregator(FeedFetcher fetcher, RssParser parser, int poolSize) {
        this(fetcher, parser, poolSize, Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    public PooledAggregator(FeedFetcher fetcher, RssParser parser,
                            int poolSize, Duration perTaskTimeout, Duration shutdownTimeout) {
        if (poolSize <= 0) throw new IllegalArgumentException("poolSize must be > 0");
        this.fetcher = fetcher;
        this.parser = parser;
        this.poolSize = poolSize;
        this.perTaskTimeout = perTaskTimeout;
        this.shutdownTimeout = shutdownTimeout;
    }

    public Result run(List<FeedConfig> feeds) {
        banner("PHASE 2 — Pooled Fetcher (pool=" + poolSize + ", " + feeds.size() + " feeds)");
        log.info("strategy        : submit Callable per feed -> Future.get(timeout={}s)",
                perTaskTimeout.toSeconds());
        log.info("");
        log.info("{}", header());
        log.info("{}", divider());

        ExecutorService exec = Executors.newFixedThreadPool(poolSize, namedThreadFactory("fetcher"));
        List<FeedStat> stats = new ArrayList<>(feeds.size());
        List<Article> all = new ArrayList<>();

        Stopwatch total = Stopwatch.start();
        try {
            List<Future<TaskResult>> futures = new ArrayList<>(feeds.size());
            for (FeedConfig feed : feeds) {
                futures.add(exec.submit(taskFor(feed)));
            }

            for (int i = 0; i < futures.size(); i++) {
                FeedConfig feed = feeds.get(i);
                Future<TaskResult> f = futures.get(i);
                FeedStat row;
                try {
                    TaskResult tr = f.get(perTaskTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    if (tr.ok) {
                        all.addAll(tr.articles);
                        row = FeedStat.ok(feed.id(), tr.thread, tr.articles.size(), tr.ms, tr.bytes);
                        log.info("{}", rowLine(i + 1, feeds.size(), row, "OK  ", ""));
                    } else {
                        row = FeedStat.fail(feed.id(), tr.thread, tr.ms);
                        log.warn("{}", rowLine(i + 1, feeds.size(), row, "FAIL", tr.errorClass));
                    }
                } catch (TimeoutException te) {
                    f.cancel(true);
                    row = FeedStat.fail(feed.id(), "-", perTaskTimeout.toMillis());
                    log.warn("{}", rowLine(i + 1, feeds.size(), row, "TIME", "task timeout"));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    row = FeedStat.fail(feed.id(), "-", 0);
                    log.warn("{}", rowLine(i + 1, feeds.size(), row, "INTR", "interrupted"));
                    break;
                } catch (Exception e) {
                    row = FeedStat.fail(feed.id(), "-", 0);
                    log.warn("{}", rowLine(i + 1, feeds.size(), row, "FAIL", e.getClass().getSimpleName()));
                }
                stats.add(row);
            }
        } finally {
            shutdownGracefully(exec);
        }
        long wall = total.elapsedMillis();

        log.info("{}", divider());
        summarise(stats, all.size(), wall);
        return new Result(all, wall, (int) stats.stream().filter(s -> !s.ok).count(), poolSize);
    }

    private Callable<TaskResult> taskFor(FeedConfig feed) {
        return () -> {
            String thread = Thread.currentThread().getName();
            Stopwatch sw = Stopwatch.start();
            try {
                RawFeed raw = fetcher.fetch(feed);
                List<Article> articles = parser.parse(raw);
                return TaskResult.ok(thread, articles, sw.elapsedMillis(), raw.body().length);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return TaskResult.fail(thread, sw.elapsedMillis(), "Interrupted");
            } catch (Exception e) {
                return TaskResult.fail(thread, sw.elapsedMillis(), e.getClass().getSimpleName());
            }
        };
    }

    private void shutdownGracefully(ExecutorService exec) {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(shutdownTimeout.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("pool did not terminate in {}s — forcing shutdownNow", shutdownTimeout.toSeconds());
                exec.shutdownNow();
            }
        } catch (InterruptedException ie) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private void summarise(List<FeedStat> stats, int totalArticles, long wallMs) {
        long ok = stats.stream().filter(s -> s.ok).count();
        long failed = stats.size() - ok;
        long sumLatency = stats.stream().mapToLong(s -> s.ms).sum();
        long maxLatency = stats.stream().mapToLong(s -> s.ms).max().orElse(0);
        long minLatency = stats.stream().filter(s -> s.ok).mapToLong(s -> s.ms).min().orElse(0);
        long bytes = stats.stream().mapToLong(s -> s.bytes).sum();
        FeedStat slowest = stats.stream().filter(s -> s.ok)
                .max(Comparator.comparingLong(s -> s.ms)).orElse(null);
        double avg = stats.isEmpty() ? 0 : (double) sumLatency / stats.size();
        double speedup = wallMs == 0 ? 0 : (double) sumLatency / wallMs;

        log.info("");
        log.info("RESULTS");
        log.info("  pool size       : {}", poolSize);
        log.info("  feeds ok        : {} / {}", ok, stats.size());
        if (failed > 0) log.info("  feeds failed    : {}", failed);
        log.info("  articles total  : {}", fmt(totalArticles));
        log.info("  bytes downloaded: {} ({} KB)", fmt(bytes), fmt(bytes / 1024));
        log.info("");
        log.info("TIMING");
        log.info("  wall time       : {} ms", fmt(wallMs));
        log.info("  sum of fetches  : {} ms   (would be wall time if serial)", fmt(sumLatency));
        log.info("  speedup vs serial: {}x  (sum / wall)", String.format("%.2f", speedup));
        log.info("  fastest feed    : {} ms", fmt(minLatency));
        log.info("  slowest feed    : {} ms{}", fmt(maxLatency),
                slowest == null ? "" : "   <- " + slowest.id);
        log.info("  avg per feed    : {} ms", fmt(Math.round(avg)));
        log.info("");
        log.info("CONCURRENCY LESSON");
        log.info("  Wall time floor ≈ slowest feed ({} ms) + small queueing tail.", fmt(maxLatency));
        log.info("  Pool of {} threads parallelised {} feeds; CPU still mostly idle on I/O.",
                poolSize, stats.size());
        log.info("");
    }

    private static String header() {
        return String.format("  %-3s %-18s %-12s %-4s %8s %10s %12s  %s",
                "#", "feed", "thread", "stat", "articles", "ms", "bytes", "note");
    }

    private static String divider() {
        return "  " + "-".repeat(94);
    }

    private static String rowLine(int idx, int total, FeedStat s, String status, String note) {
        return String.format("  %2d/%-2d %-18s %-12s %-4s %8d %10s %12s  %s",
                idx, total, truncate(s.id, 18), truncate(s.thread, 12), status,
                s.articles, fmt(s.ms), fmt(s.bytes), note);
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    private static void banner(String title) {
        log.info("");
        log.info("================================================================");
        log.info("  {}", title);
        log.info("================================================================");
    }

    public record Result(List<Article> articles, long elapsedMillis, int failedFeeds, int poolSize) {
    }

    private record TaskResult(String thread, boolean ok, List<Article> articles,
                              long ms, long bytes, String errorClass) {
        static TaskResult ok(String t, List<Article> a, long ms, long bytes) {
            return new TaskResult(t, true, a, ms, bytes, "");
        }
        static TaskResult fail(String t, long ms, String err) {
            return new TaskResult(t, false, List.of(), ms, 0, err);
        }
    }

    private record FeedStat(String id, String thread, boolean ok,
                            int articles, long ms, long bytes) {
        static FeedStat ok(String id, String thread, int articles, long ms, long bytes) {
            return new FeedStat(id, thread, true, articles, ms, bytes);
        }
        static FeedStat fail(String id, String thread, long ms) {
            return new FeedStat(id, thread, false, 0, ms, 0);
        }
    }
}
