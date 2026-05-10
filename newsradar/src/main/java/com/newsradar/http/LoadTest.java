package com.newsradar.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fires {@code clients} concurrent requests at the target URL using virtual threads.
 * Each virtual thread does a synchronous {@code HttpClient.send} — they're cheap and
 * park happily while the kernel waits for the socket. A platform-thread pool of the
 * same size would force you to either undersize (under-utilised) or oversize (OOM).
 */
public final class LoadTest {

    private static final Logger log = LoggerFactory.getLogger(LoadTest.class);

    public record Result(
            int totalClients,
            int successful,
            int failed,
            long wallMillis,
            long minMicros, long p50Micros, long p95Micros, long p99Micros, long maxMicros) {

        public double requestsPerSecond() {
            return wallMillis == 0 ? 0 : (double) totalClients * 1000.0 / wallMillis;
        }
    }

    public Result run(String targetUrl, int clients, Duration perRequestTimeout) throws InterruptedException {
        URI uri = URI.create(targetUrl);
        long[] latenciesMicros = new long[clients];
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(clients);

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(perRequestTimeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            for (int i = 0; i < clients; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        long t0 = System.nanoTime();
                        HttpResponse<byte[]> r = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                        long elapsed = (System.nanoTime() - t0) / 1_000;
                        latenciesMicros[idx] = elapsed;
                        if (r.statusCode() / 100 == 2) ok.incrementAndGet();
                        else fail.incrementAndGet();
                    } catch (Throwable t) {
                        fail.incrementAndGet();
                        latenciesMicros[idx] = -1;
                    } finally {
                        done.countDown();
                    }
                });
            }

            log.info("LoadTest: firing {} virtual-thread clients at {}", clients, targetUrl);
            long t0 = System.nanoTime();
            start.countDown();   // release the gate, all clients start ~simultaneously
            done.await();
            long wallMs = (System.nanoTime() - t0) / 1_000_000;

            // collect successful latencies
            List<Long> successful = new ArrayList<>(ok.get());
            for (long l : latenciesMicros) if (l >= 0) successful.add(l);
            successful.sort(Long::compareTo);

            long min = successful.isEmpty() ? 0 : successful.get(0);
            long max = successful.isEmpty() ? 0 : successful.get(successful.size() - 1);
            long p50 = percentile(successful, 50);
            long p95 = percentile(successful, 95);
            long p99 = percentile(successful, 99);

            return new Result(clients, ok.get(), fail.get(), wallMs, min, p50, p95, p99, max);
        } finally {
            exec.shutdown();
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) exec.shutdownNow();
        }
    }

    private static long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int rank = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1)));
    }

    public static void printSummary(Result r) {
        log.info("");
        log.info("LoadTest results");
        log.info("  clients          : {}", r.totalClients());
        log.info("  successful       : {}", r.successful());
        if (r.failed() > 0) log.info("  failed           : {}", r.failed());
        log.info("  wall time        : {} ms", r.wallMillis());
        log.info("  throughput       : {} req/s", String.format("%.0f", r.requestsPerSecond()));
        log.info("  latency  min     : {} µs", r.minMicros());
        log.info("  latency  p50     : {} µs", r.p50Micros());
        log.info("  latency  p95     : {} µs", r.p95Micros());
        log.info("  latency  p99     : {} µs", r.p99Micros());
        log.info("  latency  max     : {} µs", r.maxMicros());
        log.info("");
    }

    // tiny CLI when called directly: java -cp ... LoadTest <url> <clients>
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: LoadTest <url> [clients]"); System.exit(2); }
        String url = args[0];
        int clients = args.length >= 2 ? Integer.parseInt(args[1]) : 10_000;
        Result r = new LoadTest().run(url, clients, Duration.ofSeconds(10));
        printSummary(r);
    }
}
