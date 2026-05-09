package com.newsradar.fetch;

import com.newsradar.config.FeedConfig;
import com.newsradar.model.RawFeed;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpFeedFetcher implements FeedFetcher {

    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpFeedFetcher() {
        this(Duration.ofSeconds(15));
    }

    public HttpFeedFetcher(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public RawFeed fetch(FeedConfig feed) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(feed.url())
                .timeout(requestTimeout)
                .header("User-Agent", "NewsRadar/0.1 (+learning concurrency)")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.5")
                .GET()
                .build();

        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode() + " for " + feed.url());
        }
        String contentType = res.headers().firstValue("content-type").orElse("application/xml");
        return new RawFeed(feed.id(), res.body(), contentType);
    }
}
