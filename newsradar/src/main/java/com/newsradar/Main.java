package com.newsradar;

import com.newsradar.config.FeedConfig;
import com.newsradar.config.FeedConfigLoader;
import com.newsradar.fetch.HttpFeedFetcher;
import com.newsradar.parse.RssParser;
import com.newsradar.pipeline.PipelineAggregator;
import com.newsradar.pipeline.PooledAggregator;
import com.newsradar.pipeline.SequentialAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
                    + "--mode=pipeline --fetchers=N --parsers=M --queue=K | --mode=compare");
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
            case "compare" -> runCompare();
            default -> {
                log.error("Unknown --mode={}. Known modes: info, sequential, pooled, pipeline, compare", mode);
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
