package com.newsradar.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsradar.index.SearchableIndex;
import com.newsradar.service.ScheduledRefresher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HealthHandler implements HttpHandler {

    private final SearchableIndex index;
    private final ScheduledRefresher refresher;   // nullable — Phase 5 mode has no refresher
    private final ObjectMapper json;

    public HealthHandler(SearchableIndex index, ObjectMapper json) {
        this(index, null, json);
    }

    public HealthHandler(SearchableIndex index, ScheduledRefresher refresher, ObjectMapper json) {
        this.index = index;
        this.refresher = refresher;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        SearchableIndex.Snapshot s = index.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("articles", s.articles());
        body.put("tokens", s.tokens());
        body.put("postings", s.postings());
        body.put("lastRefresh", s.lastRefresh() == null ? null : s.lastRefresh().toString());

        if (refresher != null) {
            ScheduledRefresher.Stats r = refresher.stats();
            Map<String, Object> rmap = new LinkedHashMap<>();
            rmap.put("count", r.refreshCount());
            rmap.put("failures", r.refreshFailures());
            rmap.put("inProgress", r.refreshing());
            rmap.put("lastDurationMs", r.lastRefreshDurationMs());
            rmap.put("lastArticles", r.lastRefreshArticles());
            rmap.put("nextAt", r.nextRefreshAt() == null ? null : r.nextRefreshAt().toString());
            rmap.put("intervalSec", r.interval().toSeconds());
            body.put("refresh", rmap);
        }

        body.put("thread", Thread.currentThread().toString());

        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
