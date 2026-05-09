package com.newsradar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        log.info("NewsRadar starting (java {} / vendor {})",
                System.getProperty("java.version"),
                System.getProperty("java.vendor"));
        log.info("Phase 0 scaffold OK. Next: Phase 1 — sequential baseline fetcher.");
    }
}
