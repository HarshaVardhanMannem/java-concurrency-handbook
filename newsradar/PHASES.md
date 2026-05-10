# NewsRadar — The 7-Phase Concurrency Journey

A real, end-to-end concurrent news aggregator built one concept at a time.
Each phase ends with a **runnable program** and a **measurable result**.

---

## Architecture (final shape)

```
                  ┌──────────────────────────────────────────┐
                  │            NewsRadar JVM                 │
                  │                                          │
   feeds.yaml ──▶ │  Scheduler  ─every 30s─▶  Fetcher Pool   │
                  │                          (HTTP, N⇄M)     │
                  │                                │         │
                  │                                ▼         │
                  │                       BlockingQueue<Raw> │
                  │                                │         │
                  │                                ▼         │
                  │                          Parser Pool     │
                  │                          (CPU, ForkJoin) │
                  │                                │         │
                  │                                ▼         │
                  │                       Concurrent Index   │
                  │                       (ConcHashMap +     │
                  │                        ReadWriteLock)    │
                  │                                ▲         │
   GET /search?q ─┼─▶  HTTP Server (virtual threads) ────────┤
                  └──────────────────────────────────────────┘
```

---

## Phase 0 — Maven scaffolding (✅ done)

**Goal:** prove the build works.

- `pom.xml` (Java 21+ release, jsoup, snakeyaml, jackson, SLF4J + Logback, JUnit 5, JMH).
- `src/main/java/com/newsradar/Main.java` — logs a startup line.
- `feeds.yaml` with five real public RSS feeds.
- `logback.xml` for clean console logging.
- One JUnit test that runs `Main.main` to confirm wiring.

**Verify**

```powershell
mvn compile
mvn exec:java
mvn test
```

**Concept locked in:** how a real Java project is laid out.

---

## Phase 1 — Sequential baseline (the slow version, on purpose) (✅ done)

**Goal:** feel why blocking I/O wastes CPU.

- `FeedConfig` record + YAML loader (snakeyaml).
- `HttpFeedFetcher` using `java.net.http.HttpClient` (synchronous `send`).
- `RssParser` using jsoup XML mode → returns `List<Article>`.
- `SequentialAggregator.run()`: loop through feeds, fetch, parse, count.
- A tiny `Stopwatch` utility.

**Run**

```powershell
mvn exec:java -Dexec.args="--mode=sequential"
```

**Expected:** ~30–60 seconds for 20 feeds. Mostly idle CPU.

**Concept locked in:** what blocking I/O actually costs.

---

## Phase 2 — Thread pool fetcher (the classic win) (✅ done)

**Goal:** the standard `ExecutorService` pattern.

- `PooledAggregator` submits each feed as a `Callable<List<Article>>`.
- Collects `Future`s, calls `get()` with a per-task timeout.
- Compares pool sizes 4 / 8 / 16 / 32 with the same feed list.
- Graceful shutdown: `shutdown()` → `awaitTermination(30s)` → `shutdownNow()` fallback.

**Run**

```powershell
mvn exec:java -Dexec.args="--mode=pooled --pool=16"
```

**Expected:** 5–10× speedup vs Phase 1.

**Concepts locked in:** `Callable<T>`, `Future`, why pool size > cores for I/O,
graceful shutdown idiom.

---

## Phase 3 — Bounded-queue pipeline (producer–consumer) (✅ done)

**Goal:** decouple stages and apply backpressure.

```
Fetcher pool ──put──▶ ArrayBlockingQueue<RawFeed> ──take──▶ Parser pool
                                                     │
                                                     └──put──▶ ArrayBlockingQueue<Article> ──take──▶ Indexer
```

- `RawFeed` (id + bytes) and `Article` (id + title + body + url + ts) records.
- Bounded queues so a slow stage **stalls upstream** instead of blowing memory.
- **Poison pills** (`RawFeed.SHUTDOWN`) to signal end-of-stream cleanly.
- Log queue depths every second.

**Concepts locked in:** backpressure, `BlockingQueue.put / take`, poison pills,
why bounded queues > unbounded.

---

## Phase 4 — Thread-safe inverted index

**Goal:** see shared mutable state break and learn the three fixes.

`InvertedIndex`: `Map<String, Set<ArticleId>>` (token → matching articles).

Three implementations side-by-side:
1. **`HashMap` + no locks** — race condition demo. Stress test loses entries.
2. **`synchronized` everything** — correct, but throughput collapses under load.
3. **`ConcurrentHashMap` + `computeIfAbsent` + `CopyOnWriteArraySet`** — correct *and* scales.

Each impl gets a JUnit stress test that hammers it from N threads and asserts the
total token count.

**Concepts locked in:** atomicity vs visibility, when `synchronized` is a foot-gun
under load, lock-free collections.

---

## Phase 5 — HTTP search API on virtual threads

**Goal:** modern Java I/O concurrency.

- Embed `com.sun.net.httpserver.HttpServer`.
- Executor: `Executors.newVirtualThreadPerTaskExecutor()`.
- `GET /search?q=foo&limit=10` → JSON via Jackson.
- A tiny load-tester (`LoadTest.main`) that fires 10,000 concurrent clients
  using virtual threads and prints p50 / p99 latency.
- Add `ReadWriteLock` around index swaps so writers do not starve readers.

**Concepts locked in:** virtual threads vs platform threads, cheap I/O concurrency,
when `ReadWriteLock` matters.

---

## Phase 6 — Scheduled refresh + graceful shutdown

**Goal:** treat NewsRadar like a real service.

- `ScheduledExecutorService.scheduleAtFixedRate` re-fetches feeds every 30 s.
- `Runtime.addShutdownHook`: drain queues → shutdown pools in dependency order →
  `awaitTermination` with timeouts → `shutdownNow` if needed.
- `GET /health` returns JSON: queue depths, last refresh ts, total articles indexed.
- Use `CountDownLatch` to wait until the first refresh completes before serving.

**Concepts locked in:** lifecycle of a long-running concurrent service,
ordered shutdown, `CountDownLatch`.

---

## Phase 7 — JMH benchmark write-up

**Goal:** prove every choice with numbers.

- JMH benchmark in `src/test/java/com/newsradar/bench/`.
- Compares Phase 1 (sequential) vs Phase 2 (pooled) vs Phase 3+5 (pipeline) on the
  same feed set, at warm-up + measurement iterations.
- Output table goes into `BENCHMARKS.md` — the trophy of the journey.

**Concept locked in:** measure, do not guess.

---

## Decision log (kept here as we go)

- **Java 21+ release**: virtual threads needed in Phase 5.
- **java.net.http (built-in)**: avoids an extra HTTP client dependency.
- **jsoup XML mode**: handles RSS robustly, no separate XML library needed.
- **Maven (no wrapper)**: user installs once via `winget`; keeps the repo small.
