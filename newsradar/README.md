# NewsRadar

> A concurrent news aggregator — the capstone project for the [Java Concurrency Handbook](../README.md).
> Built **one concurrency primitive at a time** across 7 phases.

[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Phases](https://img.shields.io/badge/Phases-7%20of%207%20complete-brightgreen)](#progress)

---

## What It Does

NewsRadar fetches RSS feeds from the public web, parses articles, builds a thread-safe inverted index, and serves a `GET /search` API on virtual threads — using the **real Java concurrency stack**, not academic toy examples.

Each phase introduces exactly one new concurrency concept, applies it to a realistic problem, and produces a measurable, reproducible result.

---

## Architecture

```
                                  ┌──────────────────────────────────────────────┐
                                  │                  NewsRadar JVM               │
                                  │                                              │
   feeds.yaml ─────────────────►  │   Scheduler (Phase 6)                        │
   (20 real RSS URLs)             │       │  every 30 s                          │
                                  │       ▼                                      │
                                  │   ┌──────────────────────────┐               │
                                  │   │    Fetcher Pool           │              │
                                  │   │    16 platform threads    │              │
                                  │   │    HttpClient.send()      │              │
                                  │   └─────────────┬────────────┘                │
                                  │                 │ put(rawFeed)               │
                                  │                 ▼                            │
                                  │       ArrayBlockingQueue<RawFeed>            │
                                  │             (cap = 16)                       │
                                  │                 │ take()                     │
                                  │                 ▼                            │
                                  │   ┌──────────────────────────┐               │
                                  │   │     Parser Pool           │              │
                                  │   │     ≈ #cores threads      │              │
                                  │   │     jsoup XML  CPU-bound  │              │
                                  │   └─────────────┬────────────┘                │
                                  │                 │ put(article)               │
                                  │                 ▼                            │
                                  │       ArrayBlockingQueue<Article>            │
                                  │             (cap = 64)                       │
                                  │                 │ take()                     │
                                  │                 ▼                            │
                                  │   ┌──────────────────────────────────────┐   │
                                  │   │   SearchableIndex                    │   │
                                  │   │   ┌──────────────────────────────┐  │   │
                                  │   │   │  ConcurrentHashMap            │ │   │
                                  │   │   │    token  →  Set<articleId>   │ │   │
                                  │   │   │  Map<articleId, Article>      │ │   │
                                  │   │   └──────────────────────────────┘  │   │
                                  │   │   ReentrantReadWriteLock around swap │   │
                                  │   └────────────────▲─────────────────────┘   │
                                  │                    │ search(q, limit)        │
                                  │                    │                         │
                                  │   ┌────────────────┴─────────────────────┐   │
                                  │   │   HttpServer  (com.sun.net.httpserver)│  │
                                  │   │   executor = newVirtualThreadPerTask  │  │
                                  │   │   GET /search  GET /health            │  │
                                  │   └────────────────▲─────────────────────┘   │
                                  └────────────────────┼───────────────────────────┘
                                                       │ HTTP
                                                       │
                                       1,000s of concurrent virtual-thread clients
```

Every box on that diagram is a real class in `src/main/java/com/newsradar/`. Every arrow is either a `BlockingQueue` operation, a method call, a network socket, or a lock acquisition.

---

## Progress

| Phase | Topic | Key Concepts | Status |
|:---:|---|---|:---:|
| **0** | Maven scaffold + logging | Project layout, Logback, JUnit 5 | ✅ |
| **1** | Sequential baseline | Blocking I/O cost, `HttpClient.send()` | ✅ · [docs](docs/PHASE_01.md) |
| **2** | Thread pool fetcher | `ExecutorService`, `Future`, graceful shutdown | ✅ · [docs](docs/PHASE_02.md) |
| **3** | Bounded-queue pipeline | `BlockingQueue`, backpressure, poison pills | ✅ · [docs](docs/PHASE_03.md) |
| **4** | Thread-safe inverted index | `ConcurrentHashMap.computeIfAbsent`, `synchronized` foot-gun | ✅ · [docs](docs/PHASE_04.md) |
| **5** | HTTP search API on virtual threads | `newVirtualThreadPerTaskExecutor`, `ReadWriteLock`, p50/p99 | ✅ · [docs](docs/PHASE_05.md) |
| **6** | Scheduled refresh + shutdown | `ScheduledExecutorService`, `CountDownLatch` | ✅ |
| **7** | JMH benchmarks | Measure, don't guess | ✅ · [BENCHMARKS.md](BENCHMARKS.md) |

> Full roadmap with goals, code snippets, and expected outputs: **[PHASES.md](PHASES.md)**

---

## Prerequisites

| Tool | Minimum version | Install (Windows) |
|---|---|---|
| JDK | 21 | `winget install Microsoft.OpenJDK.21` |
| Maven | 3.9 | `winget install Apache.Maven` |

Restart your shell after `winget` so `mvn` is on `PATH`.

```powershell
java -version   # 21.x or later
mvn -version    # 3.9.x or later
```

---

## Build & Run

From `newsradar/` (where `pom.xml` lives):

```powershell
# Build
mvn compile

# Run all tests (26 of them — all offline)
mvn test

# Smoke test — Phase 0 wiring check
mvn exec:java

# Phase 1 — sequential, slow on purpose
mvn exec:java "-Dexec.args=--mode=sequential"

# Phase 2 — thread pool fetcher
mvn exec:java "-Dexec.args=--mode=pooled --pool=16"

# Phase 3 — producer–consumer pipeline
mvn exec:java "-Dexec.args=--mode=pipeline --fetchers=16 --parsers=4 --queue=8"
mvn exec:java "-Dexec.args=--mode=pipeline --fetchers=16 --parsers=1 --queue=1"   # forces backpressure stalls

# Phase 4 — three-way inverted index stress
mvn exec:java "-Dexec.args=--mode=index-stress --threads=16 --articles=50000 --vocab=100 --tokens=20"

# Phase 5 — fetch + index + serve on virtual threads
mvn exec:java "-Dexec.args=--mode=serve --port=8088"
# (in another shell)
curl "http://localhost:8088/health"
curl "http://localhost:8088/search?q=java&limit=5"
mvn exec:java "-Dexec.args=--mode=loadtest --url=http://localhost:8088/search?q=news --clients=2000"

# Phase 6 — long-running service with scheduled refresh + graceful shutdown
mvn exec:java "-Dexec.args=--mode=service --port=8088 --refresh=30 --fetchers=16"
# (Ctrl-C triggers the ordered shutdown hook)

# Phase 7 — JMH benchmarks (in-process; results written to target/jmh-results.json)
mvn test-compile exec:java -Pbench
# Run a single benchmark class
mvn exec:java -Pbench "-Dexec.args=IndexWriteBenchmark"

# Side-by-side comparison of fetch strategies
mvn exec:java "-Dexec.args=--mode=compare"
```

PowerShell tip — quote any `-D...` arg containing `=` so the shell doesn't eat it:
`"-Dexec.args=--mode=serve --port=8088"`.

---

## Project Layout

```
newsradar/
├── pom.xml                         Maven build (Java 21 release, no wrapper)
├── PHASES.md                       7-phase learning roadmap
├── README.md                       you are here
│
├── docs/
│   ├── PHASE_02.md                 ExecutorService + Future
│   ├── PHASE_03.md                 BlockingQueue pipeline + poison pills
│   ├── PHASE_04.md                 Three-way inverted-index stress
│   └── PHASE_05.md                 Virtual threads + ReadWriteLock + load test
│
└── src/
    ├── main/java/com/newsradar/
    │   ├── Main.java               CLI dispatcher (--mode flag)
    │   ├── config/                 FeedConfig + YAML loader
    │   ├── model/                  RawFeed, Article (with SHUTDOWN sentinels)
    │   ├── fetch/                  FeedFetcher interface + HttpFeedFetcher
    │   ├── parse/                  FeedParser interface + RssParser (jsoup)
    │   ├── pipeline/               SequentialAggregator (P1) │ PooledAggregator (P2) │ PipelineAggregator (P3)
    │   ├── index/                  Tokenizer │ InvertedIndex + 3 impls │ SearchableIndex (P4–5)
    │   ├── http/                   SearchServer │ SearchHandler │ HealthHandler │ LoadTest (P5)
    │   ├── service/                ScheduledRefresher (P6)
    │   └── util/                   Stopwatch
    │
    ├── main/resources/
    │   ├── feeds.yaml              20 real public RSS feeds
    │   └── logback.xml             structured console logging
    │
    └── test/java/com/newsradar/    30 tests across 10 classes
        ├── MainTest
        ├── config/FeedConfigLoaderTest
        ├── parse/RssParserTest
        ├── pipeline/PooledAggregatorTest │ PipelineAggregatorTest
        ├── index/IndexCorrectnessTest │ IndexStressTest │ SearchableIndexTest
        ├── http/SearchServerTest
        ├── service/ScheduledRefresherTest
        └── bench/                  TokenizerBenchmark │ IndexWriteBenchmark │ SearchableIndexBenchmark │ AggregatorBenchmark │ BenchmarkRunner
```

---

## Key Results So Far

### Fetch-strategy ladder (same 20 feeds, same JVM run)

```
mode          pool   wall_ms   articles   speedup
─────────────────────────────────────────────────
sequential       1     5,588        509     1.00×
pooled           4       492        509    11.36×
pooled           8       291        509    19.20×
pooled          16       194        509    28.80×   ← peak
pooled          32       214        509    26.11×   ← regresses
```

### Phase 3 pipeline — backpressure caught live

```
fetchers   parsers   queue   wall_ms   fetcher stalls   parser stalls
──────────────────────────────────────────────────────────────────────
   16         4       8        929              0              0
   16         1       1        877             12              1     ← bounded queue parking producers
```

### Phase 4 inverted-index stress (16 threads × 50,000 articles × 20 tokens, vocab 100)

```
impl                 correct?   tokens   postings    elapsed_ms   ops/sec
──────────────────────────────────────────────────────────────────────────
UnsafeHashMapIndex     NO         106    995,859           82    12,144,621   ← garbage-fast
SynchronizedIndex      yes        100  1,000,000          430     2,325,581
ConcurrentIndex        yes        100  1,000,000          165     6,060,606   ← 2.6× faster than synchronized
```

### Phase 5 virtual-thread search load test

```
clients   wall_ms   success   failed   throughput   p50_µs   p99_µs
─────────────────────────────────────────────────────────────────────
   500       903      500        0      554 r/s       756      851
  1000      1257     1000        0      796 r/s       836     1173
  2000      2026     2000        0      987 r/s     1,488    1,966
  5000      3144     3173    1827    1,590 r/s     2,080    2,768   ← OS listen-queue overflow
```

---

### Phase 7 — JMH benchmarks (summary)

```
Benchmark                             Mode   Score           Units
IndexWriteBenchmark.concurrentWrite   thrpt  1,512,033 ops/s   ← 6× faster than synchronized
IndexWriteBenchmark.synchronizedWrite thrpt    251,447 ops/s
SearchableIndexBenchmark.readOnly     thrpt  1,309,850 ops/s   ← 8 readers, zero writers
SearchableIndexBenchmark.mixed        thrpt  2,029,870 ops/s   ← 7 readers + 1 periodic writer
AggregatorBenchmark.sequential         avgt      1.503 ms/op
AggregatorBenchmark.pooled             avgt      1.274 ms/op   ← barely faster w/o I/O wait
AggregatorBenchmark.pipeline           avgt      1.792 ms/op   ← slower w/o I/O (queue overhead)
```

**Key lesson:** thread pools and pipelines are I/O-concurrency tools. The 28× real-world speedup came from overlapping network waits — not from CPUs running faster.

→ **[Full write-up with analysis →](BENCHMARKS.md)**

---

### Phase 6 — scheduled refresh (correctness)

```
First refresh completes in < 2 s (20 feeds, pipeline fetchers=16)
CountDownLatch released → server starts serving
Ctrl-C → shutdown hook fires:
  server.stop()     ← close socket, drain in-flight requests
  refresher.stop()  ← cancel schedule, await in-flight refresh
  shutdown complete ← clean exit, no thread leaks
```

---

## Phase Write-ups

Deep-dive docs in [`docs/`](docs/) explain the *why* behind every design decision:

- **[PHASE_01.md](docs/PHASE_01.md)** — sequential baseline, blocking I/O, head-of-line blocking, and baseline performance metrics
- **[PHASE_02.md](docs/PHASE_02.md)** — `ExecutorService`, `Callable<T>`, `Future`, graceful shutdown idiom, pool-size ladder
- **[PHASE_03.md](docs/PHASE_03.md)** — `ArrayBlockingQueue`, bounded vs unbounded queues, poison-pill propagation, backpressure mechanics
- **[PHASE_04.md](docs/PHASE_04.md)** — three-way inverted index: HashMap (broken), `synchronized` (slow), `ConcurrentHashMap.computeIfAbsent` (correct + scales)
- **[PHASE_05.md](docs/PHASE_05.md)** — virtual threads vs platform threads, `ReadWriteLock` for index swaps, p50/p99 latency under 2,000-concurrent load
- **Phase 6** — covered in [PHASES.md](PHASES.md): `ScheduledExecutorService`, `CountDownLatch` startup gate, ordered shutdown, atomics for lock-free `/health` stats
- **[BENCHMARKS.md](BENCHMARKS.md)** — Phase 7 trophy: JMH results for tokenizer, index writes, read-write lock, and aggregator strategies with full analysis

---

## Tech Stack

| Library | Version | Used for |
|---|---|---|
| `java.net.http` | built-in | HTTP fetching + load test client |
| `com.sun.net.httpserver` | built-in | embedded search API server |
| jsoup | 1.18 | RSS / XML parsing |
| SnakeYAML | 2.3 | `feeds.yaml` loader |
| Jackson | 2.18 | JSON HTTP responses |
| SLF4J + Logback | 2.0 / 1.5 | structured logging |
| JUnit 5 | 5.11 | unit, stress, and HTTP integration tests |
| JMH | 1.37 | benchmarks (Phase 7) |
