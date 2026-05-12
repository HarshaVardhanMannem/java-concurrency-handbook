package com.newsradar.bench;

import com.newsradar.index.ConcurrentIndex;
import com.newsradar.index.SynchronizedIndex;
import com.newsradar.model.Article;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 4 proof: SynchronizedIndex vs ConcurrentIndex under 8-thread write load.
 *
 * Every thread indexes a different article drawn round-robin from a 1 000-article pool,
 * so threads contend on the index data structure but work on distinct tokens most of the
 * time — the same contention pattern as a real feed refresh.
 *
 * Expected result: ConcurrentIndex throughput >> SynchronizedIndex because CHM's
 * per-bin striping lets concurrent writers on different tokens proceed in parallel,
 * while SynchronizedIndex serialises every operation through one monitor.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@Threads(8)
public class IndexWriteBenchmark {

    @State(Scope.Benchmark)
    public static class SharedState {
        final List<Article> articles = new ArrayList<>(1000);
        final AtomicInteger cursor = new AtomicInteger();
        SynchronizedIndex syncIndex;
        ConcurrentIndex concIndex;

        @Setup(Level.Trial)
        public void setup() {
            String[] topics = {
                "economy inflation markets", "politics senate congress",
                "technology silicon valley", "science research discovery",
                "health medicine vaccine", "sports championship league",
                "world diplomacy foreign", "business earnings revenue",
                "climate environment energy", "media streaming broadcast"
            };
            for (int i = 0; i < 1000; i++) {
                String body = topics[i % topics.length];
                articles.add(new Article(
                        "art:" + i, "feed:" + (i % 20),
                        body + " news number " + i,
                        "Full coverage of " + body + " article " + i + " with expert analysis.",
                        "https://example.com/" + i, Instant.EPOCH));
            }
            syncIndex = new SynchronizedIndex();
            concIndex = new ConcurrentIndex();
        }

        Article next() {
            return articles.get(Math.abs(cursor.getAndIncrement() % articles.size()));
        }
    }

    @Benchmark
    public void synchronizedWrite(SharedState s) {
        s.syncIndex.index(s.next());
    }

    @Benchmark
    public void concurrentWrite(SharedState s) {
        s.concIndex.index(s.next());
    }
}
