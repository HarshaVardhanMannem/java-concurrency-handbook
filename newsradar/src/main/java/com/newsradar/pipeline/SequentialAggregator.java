package com.newsradar.pipeline;

import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;
import com.newsradar.parse.RssParser;
import com.newsradar.util.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SequentialAggregator {

    private static final Logger log = LoggerFactory.getLogger(SequentialAggregator.class);

    private final FeedFetcher fetcher;
    private final RssParser parser;

    public SequentialAggregator(FeedFetcher fetcher, RssParser parser) {
        this.fetcher = fetcher;
        this.parser = parser;
    }

    public Result run(List<FeedConfig> feeds) {
        banner("PHASE 1 — Sequential Baseline (1 thread, blocking I/O)");
        log.info("feeds queued    : {}", feeds.size());
        log.info("strategy        : fetch -> parse, one feed at a time");
        log.info("");
        log.info("{}", header());
        log.info("{}", divider());

        Stopwatch total = Stopwatch.start();
        List<Article> all = new ArrayList<>();
        List<FeedStat> stats = new ArrayList<>();

        for (int i = 0; i < feeds.size(); i++) {
            FeedConfig feed = feeds.get(i);
            log.debug("[{}/{}] fetching {} <{}>", i + 1, feeds.size(), feed.id(), feed.url());
            Stopwatch perFeed = Stopwatch.start();
            try {
                RawFeed raw = fetcher.fetch(feed);
                List<Article> articles = parser.parse(raw);
                long ms = perFeed.elapsedMillis();
                all.addAll(articles);
                stats.add(FeedStat.ok(feed.id(), articles.size(), ms, raw.body().length));
                log.info("{}", row(i + 1, feeds.size(), feed.id(), "OK  ", articles.size(), ms, raw.body().length, ""));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long ms = perFeed.elapsedMillis();
                stats.add(FeedStat.fail(feed.id(), ms));
                log.warn("{}", row(i + 1, feeds.size(), feed.id(), "INTR", 0, ms, 0, "interrupted"));
                break;
            } catch (Exception e) {
                long ms = perFeed.elapsedMillis();
                stats.add(FeedStat.fail(feed.id(), ms));
                log.warn("{}", row(i + 1, feeds.size(), feed.id(), "FAIL", 0, ms, 0, e.getClass().getSimpleName()));
            }
        }

        long totalMs = total.elapsedMillis();
        log.info("{}", divider());
        summarise(stats, all.size(), totalMs);
        return new Result(all, totalMs, (int) stats.stream().filter(s -> !s.ok).count());
    }

    private static String header() {
        return String.format("  %-3s %-18s %-4s %8s %10s %12s  %s",
                "#", "feed", "stat", "articles", "ms", "bytes", "note");
    }

    private static String divider() {
        return "  " + "-".repeat(78);
    }

    private static String row(int idx, int total, String id, String status,
                              int articles, long ms, long bytes, String note) {
        return String.format("  %2d/%-2d %-18s %-4s %8d %10s %12s  %s",
                idx, total, truncate(id, 18), status,
                articles, fmt(ms), fmt(bytes), note);
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

    private void summarise(List<FeedStat> stats, int totalArticles, long wallMs) {
        long ok = stats.stream().filter(s -> s.ok).count();
        long failed = stats.size() - ok;
        long sumLatency = stats.stream().mapToLong(s -> s.ms).sum();
        long maxLatency = stats.stream().mapToLong(s -> s.ms).max().orElse(0);
        long minLatency = stats.stream().filter(s -> s.ok).mapToLong(s -> s.ms).min().orElse(0);
        long bytes = stats.stream().mapToLong(s -> s.bytes).sum();
        FeedStat slowest = stats.stream().filter(s -> s.ok)
                .max(Comparator.comparingLong(s -> s.ms)).orElse(null);
        double avg = ok == 0 ? 0 : (double) sumLatency / stats.size();
        double idlePct = wallMs == 0 ? 0 : 100.0 * (wallMs - 0) / wallMs; // CPU was waiting all of wallMs

        log.info("");
        log.info("RESULTS");
        log.info("  feeds ok        : {} / {}", ok, stats.size());
        if (failed > 0) log.info("  feeds failed    : {}", failed);
        log.info("  articles total  : {}", fmt(totalArticles));
        log.info("  bytes downloaded: {} ({} KB)", fmt(bytes), fmt(bytes / 1024));
        log.info("");
        log.info("TIMING");
        log.info("  wall time       : {} ms", fmt(wallMs));
        log.info("  sum of fetches  : {} ms   (≈ wall time — proves serial blocking)", fmt(sumLatency));
        log.info("  fastest feed    : {} ms", fmt(minLatency));
        log.info("  slowest feed    : {} ms{}", fmt(maxLatency),
                slowest == null ? "" : "   <- " + slowest.id);
        log.info("  avg per feed    : {} ms", fmt(Math.round(avg)));
        log.info("");
        log.info("CONCURRENCY LESSON");
        log.info("  CPU spent ~{}% of wall time blocked on socket reads.", String.format("%.0f", idlePct));
        log.info("  Phase 2 (thread pool) target: wall ≈ slowest feed ({} ms), not the sum.", fmt(maxLatency));
        log.info("");
    }

    public record Result(List<Article> articles, long elapsedMillis, int failedFeeds) {
    }

    private record FeedStat(String id, boolean ok, int articles, long ms, long bytes) {
        static FeedStat ok(String id, int articles, long ms, long bytes) {
            return new FeedStat(id, true, articles, ms, bytes);
        }
        static FeedStat fail(String id, long ms) {
            return new FeedStat(id, false, 0, ms, 0);
        }
    }
}
