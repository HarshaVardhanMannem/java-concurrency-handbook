package com.newsradar.model;

import java.time.Instant;

public record Article(
        String id,
        String feedId,
        String title,
        String body,
        String url,
        Instant publishedAt) {
}
