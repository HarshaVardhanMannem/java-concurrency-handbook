package com.newsradar.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsradar.index.SearchableIndex;
import com.newsradar.model.Article;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SearchHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(SearchHandler.class);

    private final SearchableIndex index;
    private final ObjectMapper json;

    public SearchHandler(SearchableIndex index, ObjectMapper json) {
        this.index = index;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                respond(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            Map<String, String> params = parseQuery(ex.getRequestURI());
            String q = params.getOrDefault("q", "").trim();
            int limit = parseInt(params.get("limit"), 10);
            if (q.isEmpty()) {
                respond(ex, 400, Map.of("error", "missing query param 'q'"));
                return;
            }

            long t0 = System.nanoTime();
            List<Article> hits = index.search(q, limit);
            long latencyMicros = (System.nanoTime() - t0) / 1_000;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", q);
            body.put("limit", limit);
            body.put("count", hits.size());
            body.put("latency_micros", latencyMicros);
            body.put("results", hits.stream().map(SearchHandler::toJson).toList());
            respond(ex, 200, body);
        } catch (Exception e) {
            log.warn("search handler failed", e);
            respond(ex, 500, Map.of("error", e.getClass().getSimpleName()));
        }
    }

    private static Map<String, Object> toJson(Article a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("feed", a.feedId());
        m.put("title", a.title());
        m.put("url", a.url());
        Instant t = a.publishedAt();
        if (t != null) m.put("published", t.toString());
        return m;
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isBlank()) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Math.max(1, Math.min(100, Integer.parseInt(s))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
