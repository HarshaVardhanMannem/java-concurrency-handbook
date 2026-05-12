package com.newsradar.bench;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Entry point for running all NewsRadar JMH benchmarks.
 *
 * Usage (from the newsradar/ directory):
 *
 *   mvn test-compile
 *   mvn exec:java -Pbench
 *
 * Results are printed to stdout and also saved to target/jmh-results.json
 * which can be visualised at https://jmh.moger.me/ (offline: open the JSON yourself).
 *
 * To run a single benchmark class pass its simple name as the first arg:
 *   mvn exec:java -Pbench -Dexec.args="IndexWriteBenchmark"
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        String filter = args.length > 0 ? "com\\.newsradar\\.bench\\." + args[0] + ".*"
                                        : "com\\.newsradar\\.bench\\..*";

        Options opts = new OptionsBuilder()
                .include(filter)
                .forks(0)           // in-process: exec:java has no fat-jar, forked VM can't see jars
                .resultFormat(ResultFormatType.JSON)
                .result("target/jmh-results.json")
                .build();

        new Runner(opts).run();
    }
}
