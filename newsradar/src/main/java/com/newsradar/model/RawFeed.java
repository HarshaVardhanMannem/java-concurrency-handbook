package com.newsradar.model;

public record RawFeed(String feedId, byte[] body, String contentType) {

    /** Sentinel pushed onto the raw-feed queue to signal end-of-stream to parser threads. */
    public static final RawFeed SHUTDOWN = new RawFeed("__SHUTDOWN__", new byte[0], "__SHUTDOWN__");

    public boolean isShutdown() {
        return this == SHUTDOWN;
    }
}
