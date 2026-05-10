package com.newsradar.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsradar.index.SearchableIndex;
import com.newsradar.model.Article;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    }
}
