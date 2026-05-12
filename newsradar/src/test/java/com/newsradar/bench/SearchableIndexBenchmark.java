package com.newsradar.bench;

import com.newsradar.index.ConcurrentIndex;
import com.newsradar.index.SearchableIndex;
import com.newsradar.model.Article;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 5 proof: ReadWriteLock in SearchableIndex allows parallel reads.
 *
 * Two scenarios:
 *   readOnly  — 8 concurrent search threads, zero writers.
 *               Shows that the read lock does NOT serialise reads; throughput
 *               scales with thread count.
 *
 *   mixed     — 7 search threads + 1 swap thread (simulates a periodic refresh).
 *               Shows the write lock's impact: each swap briefly pauses all readers
 *               but the cost is small because swaps are infrequent.
 *
 * The gap between readOnly and mixed tells you what a scheduled refresh costs.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@State(Scope.Benchmark)
public class SearchableIndexBenchmark {

    private static final String[] QUERIES = {"economy", "technology", "climate", "markets", "science"};
    private final AtomicInteger qCursor = new AtomicInteger();

    private SearchableIndex liveIndex;
    private ConcurrentIndex swapIndex;
    private Map<String, Article> swapArticles;

    @Setup(Level.Trial)
    public void setup() {
        liveIndex = new SearchableIndex();
        swapIndex = new ConcurrentIndex();
        swapArticles = new ConcurrentHashMap<>();

        String[] topics = {
            "economy markets inflation", "technology silicon software",
            "climate environment energy", "science research discovery",
            "health medicine vaccines", "sports championship league",
            "world diplomacy conflict", "business revenue earnings"
        };
        for (int i = 0; i < 500; i++) {
            String body = topics[i % topics.length];
            Article a = new Article(
                    "art:" + i, "feed:" + (i % 10),
                    body + " news update " + i,
                    "Detailed coverage of " + body + " worldwide. Story " + i + ".",
                    "https://example.com/" + i, Instant.EPOCH);
            liveIndex.add(a);
            swapIndex.index(a);
            swapArticles.put(a.id(), a);
        }
    }

    // ---- Scenario A: pure readers -------------------------------------------

    @Benchmark
    @Threads(8)
    public List<Article> readOnly() {
        return liveIndex.search(QUERIES[Math.abs(qCursor.getAndIncrement() % QUERIES.length)], 10);
    }

    // ---- Scenario B: readers + one periodic writer --------------------------

    @Benchmark
    @Group("mixed")
    @GroupThreads(7)
    public List<Article> mixedSearch() {
        return liveIndex.search(QUERIES[Math.abs(qCursor.getAndIncrement() % QUERIES.length)], 10);
    }

    @Benchmark
    @Group("mixed")
    @GroupThreads(1)
    public void mixedSwap() {
        // Simulates a ScheduledRefresher calling swap() with a freshly built index.
        liveIndex.swap(swapIndex, swapArticles);
    }
}
