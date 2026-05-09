package com.newsradar.fetch;

import com.newsradar.config.FeedConfig;
import com.newsradar.model.RawFeed;

import java.io.IOException;

public interface FeedFetcher {
    RawFeed fetch(FeedConfig feed) throws IOException, InterruptedException;
}
