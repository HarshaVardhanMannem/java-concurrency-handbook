# NewsRadar

> A concurrent news aggregator — the capstone project for the [Java Concurrency Handbook](../README.md).  
> Built **one concurrency primitive at a time** across 7 phases.

[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Phases](https://img.shields.io/badge/Phases-3%20of%207%20complete-brightgreen)](#progress)

---

## What It Does

NewsRadar fetches RSS feeds from the web, parses articles, and builds a searchable in-memory index — using the **real Java concurrency stack**, not academic toy examples.

Each phase introduces exactly one new concurrency concept, applies it to a realistic problem, and produces a measurable result.

---

## Architecture (final shape)

```
                  ┌──────────────────────────────────────────┐
                  │               NewsRadar JVM              │
                  │                                          │
   feeds.yaml ──▶ │  Scheduler  ──every 30s──▶  Fetcher Pool │
                  │                             (HTTP, I/O)  │
                  │                                  │        │
                  │                                  ▼        │
                  │                     ArrayBlockingQueue    │
                  │                       <RawFeed, cap N>    │
                  │                                  │        │
                  │                                  ▼        │
                  │                           Parser Pool     │
                  │                           (CPU-bound)     │
                  │                                  │        │
                  │                                  ▼        │
                  │                       ConcurrentHashMap   │
                  │                        (Inverted Index)   │
                  │                                  ▲        │
   GET /search?q──┼──▶  HttpServer (virtual threads) ────────┤
                  └──────────────────────────────────────────┘
```

---

## Progress

| Phase | Topic | Key Concepts | Status |
|:---:|---|---|:---:|
| **0** | Maven scaffold + logging | Project layout, Logback, JUnit 5 | ✅ Done |
| **1** | Sequential baseline | Blocking I/O cost, `HttpClient.send()` | ✅ Done |
| **2** | Thread pool fetcher | `ExecutorService`, `Future`, graceful shutdown | ✅ Done · [write-up](docs/PHASE_02.md) |
| **3** | Bounded-queue pipeline | `BlockingQueue`, backpressure, poison pills | ✅ Done · [write-up](docs/PHASE_03.md) |
| **4** | Thread-safe inverted index | `ConcurrentHashMap`, `synchronized`, atomics | 🔜 Next |
| **5** | HTTP search API | Virtual threads, `ReadWriteLock`, load test | ⬜ |
| **6** | Scheduled refresh + shutdown | `ScheduledExecutorService`, `CountDownLatch` | ⬜ |
| **7** | JMH benchmarks | Measure, don't guess | ⬜ |

> Full roadmap with goals, code snippets, and expected outputs: **[PHASES.md](PHASES.md)**

---

## Prerequisites

| Tool | Minimum version | Install (Windows) |
|---|---|---|
| JDK | 21 | `winget install Microsoft.OpenJDK.21` |
| Maven | 3.9 | `winget install Apache.Maven` |

Restart your shell after `winget` so `mvn` is on PATH.

```powershell
java -version   # should print 21.x or later
mvn -version    # should print 3.9.x or later
```

---

## Build & Run

```powershell
# Build
mvn compile

# Smoke test (Phase 0 — just checks the scaffold wires up)
mvn exec:java

# Phase 1 — sequential, slow on purpose (~30–60 s for 20 feeds)
mvn exec:java "-Dexec.args=--mode=sequential"

# Phase 2 — thread pool (5–10× faster)
mvn exec:java "-Dexec.args=--mode=pooled --pool=16"

# Phase 3 — producer–consumer pipeline
mvn exec:java "-Dexec.args=--mode=pipeline --fetchers=16 --parsers=4 --queue=8"

# Force backpressure to see stalls in the log
mvn exec:java "-Dexec.args=--mode=pipeline --fetchers=16 --parsers=1 --queue=1"

# Side-by-side comparison of all modes
mvn exec:java "-Dexec.args=--mode=compare"

# Run all tests
mvn test
```

---

## Project Layout

```
newsradar/
├── pom.xml                         ← Maven build (Java 21+, no wrapper needed)
├── PHASES.md                       ← 7-phase learning roadmap
├── README.md                       ← you are here
├── .gitignore
│
├── docs/
│   ├── PHASE_02.md                 ← deep-dive: ExecutorService & Future
│   └── PHASE_03.md                 ← deep-dive: BlockingQueue pipeline
│
└── src/
    ├── main/
    │   ├── java/com/newsradar/
    │   │   ├── Main.java            ← CLI entry point (--mode flag)
    │   │   ├── config/              ← FeedConfig record + YAML loader
    │   │   ├── fetch/               ← HttpFeedFetcher (phases 1–3)
    │   │   ├── model/               ← RawFeed, Article (with SHUTDOWN sentinels)
    │   │   ├── parse/               ← FeedParser interface + RssParser (jsoup)
    │   │   ├── pipeline/            ← PipelineAggregator (Phase 3)
    │   │   └── util/                ← Stopwatch
    │   └── resources/
    │       ├── feeds.yaml           ← 20 real public RSS feeds
    │       └── logback.xml          ← structured console logging
    └── test/
        └── java/com/newsradar/
            ├── MainTest.java
            └── pipeline/
                └── PipelineAggregatorTest.java
```

---

## Key Results So Far

| Run | Wall time | Notes |
|---|---|---|
| Sequential (Phase 1) | ~35–60 s | CPU idle while network blocks |
| Pooled pool=16 (Phase 2) | ~2–4 s | 5–10× speedup, warm JVM |
| Pipeline (Phase 3, cold JVM) | **929 ms** | 20 feeds → 511 articles |
| Pipeline, backpressured | **877 ms** | `--parsers=1 --queue=1`, 12 fetcher stalls visible |

---

## Phase Write-ups

Deep-dive docs in [`docs/`](docs/) explain the *why* behind every design decision:

- **[PHASE_02.md](docs/PHASE_02.md)** — `ExecutorService`, `Callable<T>`, `Future`, graceful shutdown idiom, pool-size ladder
- **[PHASE_03.md](docs/PHASE_03.md)** — `ArrayBlockingQueue`, bounded vs unbounded queues, poison-pill propagation, backpressure mechanics, stage-decoupling trade-offs

---

## Tech Stack

| Library | Version | Used for |
|---|---|---|
| `java.net.http` | built-in | HTTP fetching |
| jsoup | 1.18 | RSS / XML parsing |
| SnakeYAML | 2.3 | `feeds.yaml` loader |
| Jackson | 2.18 | JSON HTTP responses (Phase 5) |
| SLF4J + Logback | 2.0 / 1.5 | structured logging |
| JUnit 5 | 5.11 | unit + stress tests |
| JMH | 1.37 | benchmarks (Phase 7) |
