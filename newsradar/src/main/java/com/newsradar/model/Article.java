package com.newsradar.model;

import java.time.Instant;

public record Article(
        String id,
        String feedId,
        String title,
        String body,
        String url,
        Instant publishedAt) {

    /** Sentinel pushed onto the article queue to signal end-of-stream to the indexer. */
    public static final Article SHUTDOWN = new Article("__SHUTDOWN__", "__SHUTDOWN__", "", "", "", null);

    public boolean isShutdown() {
        return this == SHUTDOWN;
    }
}
