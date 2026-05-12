package com.newsradar.bench;

import com.newsradar.index.Tokenizer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Baseline: how fast can we tokenize text?
 *
 * This is the primitive operation every other benchmark builds on.
 * Single-threaded so the result is a pure CPU cost with no contention noise.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class TokenizerBenchmark {

    private static final String SHORT_TITLE =
            "Federal Reserve raises interest rates amid inflation concerns";

    private static final String LONG_BODY =
            "The Federal Reserve announced another quarter-point rate hike on Wednesday, " +
            "citing persistent inflation concerns and a historically strong labor market. " +
            "Markets had widely expected the decision following a series of strong economic " +
            "indicators showing consumer spending remains robust despite higher borrowing costs. " +
            "Fed chair Jerome Powell emphasized that the committee remains data-dependent and " +
            "will adjust its stance as economic conditions evolve. Analysts expect one more hike " +
            "before a potential pause later in the year as the central bank monitors the lagged " +
            "effects of its aggressive tightening cycle on housing and credit markets.";

    @Benchmark
    public Set<String> shortTitle() {
        return Tokenizer.tokens(SHORT_TITLE);
    }

    @Benchmark
    public Set<String> longBody() {
        return Tokenizer.tokens(LONG_BODY);
    }
}
