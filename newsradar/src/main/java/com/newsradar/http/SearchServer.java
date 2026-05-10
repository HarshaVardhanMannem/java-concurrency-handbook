package com.newsradar.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsradar.index.SearchableIndex;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Embeds {@link com.sun.net.httpserver.HttpServer} on a virtual-thread executor. Each
 * incoming request gets its own virtual thread — cheap to spawn, cheap to park on I/O.
 * Phase 5's whole point: thousands of concurrent searches without thousands of OS threads.
 */
public final class SearchServer {

    private static final Logger log = LoggerFactory.getLogger(SearchServer.class);

    private final SearchableIndex index;
    private final int port;
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService exec;

    public SearchServer(SearchableIndex index, int port) {
        this.index = index;
        this.port = port;
    }

    public void start() throws IOException {
        // backlog = OS listen-queue depth. Default (0) is small and 10K-burst load tests
        // overflow it instantly, producing connection refusals. 1024 keeps the kernel happy.
        server = HttpServer.create(new InetSocketAddress(port), /* backlog */ 1024);

        // The executor is the load-bearing primitive. Virtual threads = unlimited
        // cheap concurrency; one platform thread can carry thousands of parked virtuals.
        exec = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(exec);

        server.createContext("/search", new SearchHandler(index, json));
        server.createContext("/health", new HealthHandler(index, json));
        server.start();
        log.info("HTTP server listening on http://localhost:{}", boundPort());
        log.info("  GET /search?q=<query>&limit=<n>");
        log.info("  GET /health");
    }

    public int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            // delay 0 = stop accepting now; existing exchanges complete.
            server.stop(0);
            log.info("HTTP server stopped");
        }
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
    }
}
