# NewsRadar — JMH Benchmark Results

> Machine: Windows 11, JDK 24.0.1 (HotSpot 64-bit), in-process run (`@Fork(0)`)
> Command: `mvn test-compile exec:java -Pbench`

---

## Summary table

```
Benchmark                                    Mode  Cnt        Score        Error  Units
IndexWriteBenchmark.concurrentWrite         thrpt    5  1512033.714 ±433633.054  ops/s
IndexWriteBenchmark.synchronizedWrite       thrpt    5   251447.646 ± 12788.384  ops/s
SearchableIndexBenchmark.readOnly           thrpt    5  1309850.712 ± 90081.440  ops/s
SearchableIndexBenchmark.mixed              thrpt    5  2029870.005 ±200159.673  ops/s
SearchableIndexBenchmark.mixed:mixedSearch  thrpt    5   733893.559 ± 82917.453  ops/s
SearchableIndexBenchmark.mixed:mixedSwap    thrpt    5  1295976.446 ±131081.201  ops/s
TokenizerBenchmark.shortTitle               thrpt    5  1331450.129 ±212166.815  ops/s
TokenizerBenchmark.longBody                 thrpt    5   135321.002 ± 26922.425  ops/s
AggregatorBenchmark.sequential               avgt    4        1.503 ±     0.403  ms/op
AggregatorBenchmark.pooled                   avgt    4        1.274 ±     0.088  ms/op
AggregatorBenchmark.pipeline                 avgt    4        1.792 ±     0.159  ms/op
```

---

## Benchmark 1 — Tokenizer (CPU baseline)

**What it measures:** Pure tokenization throughput, single-threaded. No contention, no I/O.
This is the primitive every other benchmark builds on.

| Benchmark        | Score       | Unit  |
|------------------|-------------|-------|
| shortTitle (10 words) | 1,331,450 | ops/s |
| longBody  (100 words) |   135,321 | ops/s |

**What to notice:** throughput scales inversely with text length — 10× more text, ~10× fewer ops/s. Tokenization is O(n) in the number of characters, as expected.

At 135K article-bodies/second on one thread, the tokenizer is **not** the bottleneck — network I/O is orders of magnitude slower.

---

## Benchmark 2 — Index write throughput (Phase 4 proof)

**What it measures:** 8 threads hammering `SynchronizedIndex` and `ConcurrentIndex`
simultaneously. Each thread picks a different article from a 1 000-article pool to
exercise both same-token and different-token contention.

| Implementation      | Score       | Speedup |
|---------------------|-------------|---------|
| SynchronizedIndex   |   251,447 ops/s | 1.0× (baseline) |
| ConcurrentIndex     | 1,512,033 ops/s | **6.0×** |

**What the numbers prove:**

`SynchronizedIndex` serialises every `index()` call — 8 threads each wait their turn.
You pay the full cost of a monitor acquire/release plus cache-line invalidation on every
single write, regardless of which token is being written.

`ConcurrentIndex` uses `ConcurrentHashMap`, which stripes the lock across 16 (or more)
internal segments. Writes to *different* tokens can proceed in parallel on different
CPUs with no coordination. Only writes to the *same* token in the *same* bin compete.
With 1 000 distinct articles and 10 topic prefixes, same-bin contention is rare.

**Lesson:** `synchronized` is correct but doesn't scale. When multiple threads write to
*disjoint* parts of a data structure, `ConcurrentHashMap`'s fine-grained locking
turns contention into parallelism. **6× more writes/second** with identical correctness.

---

## Benchmark 3 — SearchableIndex read vs read+write (Phase 5/6 proof)

**What it measures:** `ReadWriteLock` behaviour under two scenarios.

- **readOnly** — 8 threads all calling `search()`, zero writers.
- **mixed** — 7 threads calling `search()`, 1 thread calling `swap()` (simulates a
  scheduled refresh).

| Scenario            | Search throughput | Notes |
|---------------------|-------------------|-------|
| readOnly (8 readers)    | 1,309,850 ops/s | baseline |
| mixed (7 readers + 1 writer) — search | 733,893 ops/s | −44% per-reader |
| mixed — swap        | 1,295,976 ops/s | writer is fast |

**What the numbers prove:**

With a `ReentrantReadWriteLock`, multiple `search()` calls hold the read lock
**simultaneously** — that is why 8 readers get ~164K ops/s per thread instead of
being serialised to one thread's worth.

When a `swap()` writer arrives, it must wait for all current readers to release their
locks before taking the write lock. While it holds the write lock, all readers block.
This is the temporary reader slow-down visible in the `mixed` row.

**Lesson:** `ReadWriteLock` is the right tool when reads are frequent and writes are rare.
The penalty for one periodic writer (scheduled refresh every 30 s in production) is a
brief, infrequent pause — not a permanent throughput halving.

---

## Benchmark 4 — Aggregator comparison (Phase 1 / 2 / 3 proof)

**Setup:** 20 feeds, 10 articles each = 200 articles total. A stub fetcher returns
pre-built RSS bytes instantly (zero network latency) so the benchmark isolates the
concurrency model overhead from I/O variance.

| Mode       | Avg time | vs sequential |
|------------|----------|---------------|
| sequential | 1.503 ms | 1.00× (baseline) |
| pooled (pool=8) | 1.274 ms | **1.18× faster** |
| pipeline (4 fetchers, 2 parsers) | 1.792 ms | 1.19× **slower** |

**What the numbers prove — and a critical lesson:**

With **zero I/O latency**, the three designs deliver surprisingly similar wall times.
The thread pool wins narrowly because its 8 threads can parallelise the CPU-bound
parsing of 20 feeds; sequential must parse them one by one.

The pipeline is **slower** than sequential despite using more threads, because it has
extra overhead: two `ArrayBlockingQueue` put/take round-trips per item, plus the
coordination cost of poison pills and multiple pool shutdowns.

**This is the key insight of Phase 7 — measure, don't guess:**
In Phase 2, the real-world `--mode=compare` showed **28.8× speedup** with pool=16.
That speedup came entirely from overlapping I/O wait — each feed fetch blocked the
network for ~1 000 ms; 16 threads ran those waits in parallel. Remove the I/O and
the speedup nearly vanishes.

The pipeline design (Phase 3) pays dividends when:
- fetch latency >> parse latency (backpressure shields the parser from a slow network)
- parse work is heterogeneous (one expensive feed doesn't block cheaper ones)
- the system runs indefinitely and the refresh cycle amortises the startup cost

None of those conditions exist in this synthetic benchmark. The numbers are correct
— they just measure a different workload from the production one.

**Summary:** thread pools and pipelines are I/O-concurrency tools. For CPU-bound,
low-latency work on a single machine, sequential is surprisingly competitive.

---

## How to reproduce

```powershell
cd newsradar
mvn test-compile exec:java -Pbench
# Optional: run a single class
mvn exec:java -Pbench -Dexec.args="IndexWriteBenchmark"
```

Results are also written to `target/jmh-results.json`.

> **Note:** benchmarks run in-process (`forks=0`) because the exec plugin does not
> build a fat-JAR. For production-grade numbers, package a benchmark uber-JAR with
> `maven-shade-plugin` and run `java -jar benchmarks.jar`. The relative ordering of
> results will not change, but absolute throughput numbers may differ.
