package com.newsradar.config;

import java.net.URI;

public record FeedConfig(String id, String name, URI url) {
    public FeedConfig {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("feed id required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("feed name required");
        if (url == null) throw new IllegalArgumentException("feed url required");
    }
}
