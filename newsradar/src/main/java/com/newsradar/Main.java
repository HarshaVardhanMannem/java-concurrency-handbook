package com.newsradar;

import com.newsradar.config.FeedConfig;
import com.newsradar.config.FeedConfigLoader;
import com.newsradar.fetch.HttpFeedFetcher;
import com.newsradar.parse.RssParser;
import com.newsradar.pipeline.SequentialAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            case "info" -> log.info("Phase 0 scaffold OK. Try --mode=sequential to run Phase 1.");
            case "sequential" -> runSequential();
            default -> {
                log.error("Unknown --mode={}. Known modes: info, sequential", mode);
                System.exit(2);
            }
        }
    }

    private static void runSequential() {
        List<FeedConfig> feeds = FeedConfigLoader.loadFromClasspath("feeds.yaml");
        SequentialAggregator agg = new SequentialAggregator(new HttpFeedFetcher(), new RssParser());
        agg.run(feeds);
    }

    private static String argValue(String[] args, String key, String fallback) {
        String prefix = key + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) return a.substring(prefix.length());
        }
        return fallback;
    }
}
