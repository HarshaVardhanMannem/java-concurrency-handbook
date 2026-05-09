package com.newsradar.util;

import java.time.Duration;

public final class Stopwatch {
    private final long startNanos;

    private Stopwatch() {
        this.startNanos = System.nanoTime();
    }

    public static Stopwatch start() {
        return new Stopwatch();
    }

    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    public long elapsedMillis() {
        return elapsed().toMillis();
    }
}
