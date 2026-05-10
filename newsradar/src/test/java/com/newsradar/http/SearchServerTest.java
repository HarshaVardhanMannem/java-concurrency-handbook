package com.newsradar.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsradar.config.FeedConfig;
import com.newsradar.fetch.FeedFetcher;
import com.newsradar.index.SearchableIndex;
import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;
import com.newsradar.parse.RssParser;
import com.newsradar.service.ScheduledRefresher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServerTest {

    private SearchServer server;
    private SearchableIndex index;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        index = new SearchableIndex();
        index.add(new Article("a1", "f", "Java threads", "java concurrency primer", "https://x/1", null));
        index.add(new Article("a2", "f", "Java IO",      "java io blocking",         "https://x/2", null));
        server = new SearchServer(index, 0);   // port 0 = ephemeral
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.boundPort() + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void searchReturnsExpectedArticles() throws Exception {
        HttpResponse<String> r = get("/search?q=java&limit=10");
        assertEquals(200, r.statusCode());
        Map<?, ?> body = json.readValue(r.body(), Map.class);
        assertEquals("java", body.get("query"));
        assertEquals(2, ((Number) body.get("count")).intValue());
        assertNotNull(body.get("results"));
    }

    @Test
    void searchAndsTokens() throws Exception {
        HttpResponse<String> r = get("/search?q=java%20concurrency");
        Map<?, ?> body = json.readValue(r.body(), Map.class);
        assertEquals(1, ((Number) body.get("count")).intValue());
    }

    @Test
    void missingQueryReturns400() throws Exception {
        HttpResponse<String> r = get("/search");
        assertEquals(400, r.statusCode());
    }

    @Test
    void healthReportsIndexState() throws Exception {
        HttpResponse<String> r = get("/health");
        assertEquals(200, r.statusCode());
        Map<?, ?> body = json.readValue(r.body(), Map.class);
        assertEquals("ok", body.get("status"));
        assertEquals(2, ((Number) body.get("articles")).intValue());
        assertTrue(((Number) body.get("tokens")).intValue() > 0);
        // proves we're on a virtual thread
        assertTrue(((String) body.get("thread")).contains("VirtualThread"),
                "expected request to run on a virtual thread, got: " + body.get("thread"));
        // No refresher attached in this fixture, so /health must omit the refresh block.
        assertTrue(body.get("refresh") == null,
                "expected no 'refresh' block when refresher is not wired");
    }

    @Test
    void healthIncludesRefreshStatsWhenRefresherAttached() throws Exception {
        // Tear down the default server-without-refresher and stand up a wired one.
        server.stop();

        SearchableIndex idx = new SearchableIndex();
        FeedFetcher fakeFetcher = feed -> new RawFeed(feed.id(),
                ("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
                        + "<item><title>x</title><link>https://e/1</link><description>x</description></item>"
                        + "</channel></rss>").getBytes(StandardCharsets.UTF_8),
                "application/rss+xml");

        ScheduledRefresher refresher = new ScheduledRefresher(
                idx,
                List.of(new FeedConfig("f0", "F0", URI.create("https://example.com/0"))),
                fakeFetcher, new RssParser(),
                1, 1, 1,
                Duration.ofSeconds(60));
        refresher.start();
        try {
            assertTrue(refresher.awaitFirstRefresh(Duration.ofSeconds(10)));

            SearchServer wired = new SearchServer(idx, refresher, 0);
            wired.start();
            try {
                HttpResponse<String> r = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://localhost:" + wired.boundPort() + "/health"))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, r.statusCode());
                Map<?, ?> body = json.readValue(r.body(), Map.class);
                Map<?, ?> refresh = (Map<?, ?>) body.get("refresh");
                assertNotNull(refresh, "/health must include 'refresh' block when wired");
                assertEquals(1, ((Number) refresh.get("count")).intValue());
                assertEquals(0, ((Number) refresh.get("failures")).intValue());
                assertEquals(1, ((Number) refresh.get("lastArticles")).intValue());
                assertEquals(60, ((Number) refresh.get("intervalSec")).intValue());
                assertNotNull(refresh.get("nextAt"));
            } finally {
                wired.stop();
            }
        } finally {
            refresher.stop();
        }

        // Re-create the original fixture so @AfterEach has something valid to stop.
        server = new SearchServer(index, 0);
        server.start();
    }
}
