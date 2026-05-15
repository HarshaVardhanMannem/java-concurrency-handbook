# ☕ Java Concurrency Handbook

> A hands-on, progressive guide to mastering Java concurrency — from raw threads to virtual threads — built around a real, runnable capstone project.

[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Phases](https://img.shields.io/badge/Phases-6%20of%207%20complete-brightgreen)](#learning-path)

---

## 🎯 What Is This?

This repository is a **structured learning handbook** for Java concurrency. It is split into two complementary parts:

| Part | Description |
|---|---|
| **Standalone scripts** | Single-file Java programs that isolate one concept at a time (no Maven, runs anywhere) |
| **[`newsradar/`](newsradar/)** | A full-featured, concurrent news aggregator built phase-by-phase as a capstone project |

The goal is simple: **learn by building something real**. Every phase of the capstone ends with a runnable program and a measurable result you can see in your terminal.

---

## 📂 Repository Layout

```
java-concurrency-handbook/
│
├── ThreadCreationBasics.java   ← raw thread creation cost & join
├── MultithreadingBasics.java   ← start/join, race condition demo
├── ConcurrencyFlow.java        ← sequential vs threads vs pool benchmark
│
├── docs/
│   └── CONCEPTS.md             ← reference guide: threads, pools, locks, async
│
└── newsradar/                  ← 7-phase capstone project (Maven)
    ├── PHASES.md               ← full phase-by-phase learning roadmap
    ├── docs/
    │   ├── PHASE_02.md         ← deep-dive: thread pool fetcher
    │   ├── PHASE_03.md         ← deep-dive: bounded-queue pipeline
    │   ├── PHASE_04.md         ← deep-dive: thread-safe inverted index
    │   └── PHASE_05.md         ← deep-dive: virtual-thread HTTP server
    └── src/...
```

---

## 🚀 Quick Start — Standalone Scripts

No Maven required. Just `javac` + `java`.

```powershell
# Compile everything into out/
mkdir out -ErrorAction SilentlyContinue
javac -d out ThreadCreationBasics.java MultithreadingBasics.java ConcurrencyFlow.java

# Run
java -cp out ThreadCreationBasics 200 20000   # threadCount workPerThread
java -cp out MultithreadingBasics 3           # workerCount
java -cp out ConcurrencyFlow 30 120 10        # taskCount sleepMillis poolSize
```

---

## 🏗️ Capstone: NewsRadar

**NewsRadar** is a concurrent news aggregator that fetches, parses, and indexes RSS feeds — built one concurrency primitive at a time across 7 phases.

```
                  ┌──────────────────────────────────────────┐
                  │               NewsRadar JVM              │
                  │                                          │
   feeds.yaml ──▶ │  Scheduler  ──every 30s──▶  Fetcher Pool │
                  │                             (HTTP, I/O)  │
                  │                                  │        │
                  │                                  ▼        │
                  │                        ArrayBlockingQueue  │
                  │                                  │        │
                  │                                  ▼        │
                  │                           Parser Pool     │
                  │                           (CPU-bound)     │
                  │                                  │        │
                  │                                  ▼        │
                  │                       ConcurrentHashMap   │
                  │                        (Inverted Index)   │
                  │                                  ▲        │
   GET /search?q──┼──▶  HTTP Server (virtual threads) ───────┤
                  └──────────────────────────────────────────┘
```

→ **[Jump to NewsRadar →](newsradar/)**  
→ **[Full Phase Roadmap →](newsradar/PHASES.md)**

---

## 📚 Learning Path

Each phase builds directly on the last. Every concept is first **motivated** (why does this matter?), then **implemented** in a real program, then **measured**.

| Phase | Topic | Key Concepts | Status |
|:---:|---|---|:---:|
| **0** | Maven scaffold + logging | Project layout, Logback, JUnit 5 | ✅ Done |
| **1** | Sequential baseline | Blocking I/O cost, `HttpClient.send()` | ✅ Done |
| **2** | Thread pool fetcher | `ExecutorService`, `Future`, graceful shutdown | ✅ Done |
| **3** | Bounded-queue pipeline | `BlockingQueue`, backpressure, poison pills | ✅ Done |
| **4** | Thread-safe inverted index | `ConcurrentHashMap`, `synchronized`, lock-free | ✅ Done |
| **5** | HTTP search API | Virtual threads, `ReadWriteLock`, load testing | ✅ Done |
| **6** | Scheduled refresh + shutdown | `ScheduledExecutorService`, `CountDownLatch` | ✅ Done |
| **7** | JMH benchmark write-up | Measuring, not guessing | 🔜 Next |

> Detailed write-ups for each completed phase live in [`newsradar/docs/`](newsradar/docs/).

---

## 💡 Concepts Reference

The [`docs/CONCEPTS.md`](docs/CONCEPTS.md) file is a standalone reference covering:

- **Concurrency vs parallelism** — what actually happens on the CPU
- **Thread lifecycle** — NEW → RUNNABLE → BLOCKED → TERMINATED
- **Race conditions & deadlocks** — with annotated examples
- **`ExecutorService` → `CompletableFuture` → Virtual Threads** — when to use each
- **Synchronization primitives** — `synchronized`, `ReentrantLock`, atomics, concurrent collections
- **Quick decision tree** — I/O-bound vs CPU-bound vs mixed workloads

---

## 🔬 Phase Highlights

### Phase 2 — Thread Pool Fetcher · [5–10× speedup](newsradar/docs/PHASE_02.md)
- `Callable<List<Article>>` submitted to a fixed thread pool
- `Future.get()` with per-task timeout
- Pool size ladder: 4 / 8 / 16 / 32 — see where gains flatten

### Phase 6 — Scheduled Refresh + Graceful Shutdown
- `ScheduledExecutorService.scheduleAtFixedRate` re-fetches all feeds every 30 s
- `CountDownLatch` gates startup — `/search` never returns an empty index on cold start
- Ordered shutdown hook: stop server → stop refresher → `awaitTermination` → `shutdownNow`
- Atomics (`AtomicLong`, `AtomicBoolean`) expose refresh stats to `/health` without locking

### Phase 5 — Virtual-Thread HTTP Server · [load tested](newsradar/docs/PHASE_05.md)
- `com.sun.net.httpserver` backed by `newVirtualThreadPerTaskExecutor`
- `ReentrantReadWriteLock` around index swaps — readers never block each other
- **Real numbers:** 2,000 concurrent clients → 987 req/s, p50 = 1,488 µs, p99 = 1,966 µs

### Phase 4 — Thread-Safe Inverted Index · [stress-tested](newsradar/docs/PHASE_04.md)
- Three implementations compared: `HashMap` (broken), `synchronized` (slow), `ConcurrentHashMap.computeIfAbsent` (correct + fast)
- 16 threads × 50,000 articles × 20 tokens — `ConcurrentIndex` is **2.6× faster** than `SynchronizedIndex`
- `SearchableIndex` wraps the whole index behind a `ReentrantReadWriteLock` for atomic snapshots

### Phase 3 — Bounded-Queue Pipeline · [backpressure, live](newsradar/docs/PHASE_03.md)
- Two `ArrayBlockingQueue`s connect three independent stages
- Poison-pill sentinel for clean end-of-stream shutdown
- **Real numbers:** 20 feeds → 511 articles in **929 ms** on a cold JVM
- Forced run (`--parsers=1 --queue=1`) produced **12 visible fetcher stalls** — backpressure caught on camera

---

## 🛠️ Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java (JDK) | 21+ | [Adoptium](https://adoptium.net) or `winget install Microsoft.OpenJDK.21` |
| Maven | 3.9+ | `winget install Apache.Maven` |

Verify your setup:

```powershell
java -version
mvn -version
```

---

## ⚡ Run the Capstone

```powershell
cd newsradar

mvn compile                                                    # build
mvn exec:java                                                  # Phase 0 smoke test
mvn exec:java "-Dexec.args=--mode=sequential"                 # Phase 1 — slow on purpose
mvn exec:java "-Dexec.args=--mode=pooled --pool=16"           # Phase 2 — thread pool
mvn exec:java "-Dexec.args=--mode=pipeline --fetchers=16"     # Phase 3 — pipeline
mvn exec:java "-Dexec.args=--mode=index-stress --threads=16"  # Phase 4 — index stress
mvn exec:java "-Dexec.args=--mode=serve --port=8088"          # Phase 5 — serve + search
mvn exec:java "-Dexec.args=--mode=service --port=8088 --refresh=30"  # Phase 6 — live service
mvn exec:java "-Dexec.args=--mode=compare"                    # all modes, side-by-side
mvn test                                                       # run all tests
```

---

## 🗺️ Roadmap

- [x] Phase 0–6 complete with deep-dive write-ups
- [ ] Phase 7 — JMH benchmark suite + `BENCHMARKS.md` trophy

---

## 🤝 Contributing

This is primarily a personal learning project, but suggestions and corrections are welcome.

1. **Open an issue** if you spot a factual error in a phase write-up or concept doc
2. **Open a PR** with a clear description of what you changed and why
3. Keep PRs focused — one concept or phase per PR

---

## 📄 License

[MIT](LICENSE) — use freely, learn well.

---

<p align="center">
  Built one phase at a time · Java 21+ · Maven 3.9+
</p>
