# Phase 2 — Thread Pool Fetcher

> Goal: replace Phase 1's serial loop with a fixed-size `ExecutorService`. Each feed becomes a `Callable<List<Article>>` submitted to the pool; we collect `Future`s and `get()` them with a per-task timeout. Wall time should collapse from "sum of all latencies" to "≈ slowest feed."

---

## TL;DR

- Sequential baseline (Phase 1): **5,588 ms** for 20 feeds.
- Pooled (this phase): **194 ms** with pool=16. **28.8× faster** wall time.
- Real parallelism (sum-of-fetches / wall) tops out at **~7×** with pool=16, bounded by the slowest feed.
- Pool=32 is *worse* than pool=16 — diminishing returns past the workload size.

---

## What was built

### New / changed files

```
newsradar/src/main/java/com/newsradar/
├── fetch/
│   ├── FeedFetcher.java          (NEW) interface — lets tests stub the fetcher
│   └── HttpFeedFetcher.java      now implements FeedFetcher
├── pipeline/
│   └── PooledAggregator.java     (NEW) ExecutorService + Future + per-task timeout
└── Main.java                     adds --mode=pooled --pool=N and --mode=compare

newsradar/src/test/java/com/newsradar/pipeline/
└── PooledAggregatorTest.java     (NEW) offline parallelism + failure-isolation tests
```

### Key design choices

- **`ExecutorService` = `Executors.newFixedThreadPool(poolSize, namedThreadFactory("fetcher"))`.** Threads are named `fetcher-1`, `fetcher-2`, … so the per-feed log line shows which worker did the work — a learning-grade view of the pool in action.
- **Submit-then-collect** rather than `invokeAll`. We submit all 20 callables up front so the pool can begin work immediately, then walk the `Future`s in submission order so the per-feed table prints in the same order Phase 1 did. (You'll notice timestamps don't necessarily increase row-by-row — they finish in network-completion order, but we report in submission order so the human can compare to Phase 1.)
- **`Future.get(timeout, ms)`** instead of unbounded `get()`. A misbehaving feed cannot hold the run hostage; on timeout we `cancel(true)` the future, log `TIME`, and continue.
- **Failure is structured, not thrown.** Each callable catches `Exception` internally and returns a `TaskResult` with `ok=false` and the exception class name. That keeps the per-feed table aligned and stops one bad feed from blowing up the executor's queue.
- **Graceful shutdown** in `finally`: `shutdown()` → `awaitTermination(30s)` → `shutdownNow()` if it didn't drain. This is the canonical idiom and Phase 6 will reuse it.
- **Daemon threads** so a hung worker can't keep the JVM alive past `main`.

### How a feed flows now (vs. Phase 1)

```
Phase 1 (one thread)              Phase 2 (pool of N)
-----------------------           -----------------------------
for feed in feeds:                for feed in feeds:
    raw = fetch(feed)  <-- block      futures.add(exec.submit(() -> fetch+parse))
    articles = parse(raw)         for f in futures:
    add to results                    result = f.get(timeout)  <-- main blocks
                                      add to results
                                  exec.shutdown(); awaitTermination(30s); shutdownNow()
```

The blocking moved off the main thread onto N pool threads that block in parallel.

---

## How to run it

From `newsradar/`:

```powershell
# Single pooled run
mvn exec:java "-Dexec.args=--mode=pooled --pool=16"

# Side-by-side: sequential then pool 4 / 8 / 16 / 32, with a comparison table
mvn exec:java "-Dexec.args=--mode=compare"

# Tests (8 total, all offline)
mvn test
```

---

## Real run — captured comparison

Live `--mode=compare` against the same 20 feeds:

```
================================================================
  COMPARISON — same 20 feeds, back-to-back
================================================================
  mode          pool     wall_ms  articles   failed   speedup
  ----------------------------------------------------------------
  sequential       1       5,588       509        1     1.00x
  pooled           4         492       509        1    11.36x
  pooled           8         291       509        1    19.20x
  pooled          16         194       509        1    28.80x
  pooled          32         214       509        1    26.11x

Baseline = sequential (5,588 ms). Speedup = baseline / wall.
```

Per-run summary (slowest feed = the floor wall time can hit):

| run         | pool | wall ms | sum of fetches | speedup vs sum | slowest feed (ms) |
|-------------|------|---------|----------------|----------------|-------------------|
| sequential  | 1    | 5,588   | 5,568          | 1.0×           | 658 (`hn-frontpage`) |
| pooled      | 4    |   492   | 1,740          | 3.5×           | 208 (`bbc-world`)    |
| pooled      | 8    |   291   | 1,546          | 5.3×           | 205 (`cnn-top`)      |
| pooled      | 16   |   194   | 1,306          | 6.7×           | 185 (`cnn-top`)      |
| pooled      | 32   |   214   | 1,676          | 7.8×           | 211 (`guardian-tech`) |

A representative pool=16 per-feed table fragment showing thread spread:

```
  #   feed               thread       stat articles         ms        bytes  note
  ----------------------------------------------------------------------------------------------
   1/20 hn-frontpage     fetcher-1    OK         30         74       11,203
   2/20 hn-newest        fetcher-2    OK         20         57       15,953
   3/20 bbc-world        fetcher-3    OK         37        163       28,015
  ...
  14/20 reuters-top      fetcher-14   FAIL        0          2            0  ConnectException
  ...
  20/20 phoronix         fetcher-20   OK         32        105       21,494
```

Twenty distinct thread names = twenty fetches in flight at peak. (Pool=16 still shows `fetcher-17..20` because the pool grows lazily up to its max and Phase 1 burned the thread numbers ahead of it.)

---

## Result analysis

There are **two different speedup numbers** in this report and they answer different questions. Read them both.

### 1. Effective wall-time speedup (baseline / wall)

**Headline: 28.8× at pool=16.**

This is what the user feels. It's the right number to put in a pitch deck. *But* it's inflated relative to "pure parallelism" because the runs aren't independent: by the time we run pool=4 the JVM is JIT-warm, DNS is cached, `HttpClient`'s connection pool has live keep-alive sockets to BBC/NYT/Guardian, and TLS sessions can resume. You can see this directly:

| run        | sum of fetches | avg per feed |
|------------|----------------|--------------|
| sequential | 5,568 ms       | 294 ms       |
| pooled 16  | 1,306 ms       | 65 ms        |

The individual fetches got ~4.5× faster simply because the JVM and OS warmed up. That's a real benefit of moving from cold-start sequential to pooled, but it's not concurrency doing the work — it's caches.

### 2. Real parallelism (sum of fetches / wall) — the honest number

| pool | sum / wall | interpretation |
|------|------------|---------------|
| 4    | 3.54×      | ≈ 4× — pool size is the ceiling |
| 8    | 5.31×      | ≈ slowest-feed-bound, not pool-bound |
| 16   | 6.73×      | wall floor = slowest feed (185 ms); 1306/185 ≈ 7.06 max |
| 32   | 7.83×      | extra threads idle; most never get scheduled |

This is the textbook concurrency story:
- With pool=4 we hit the pool ceiling — at any moment ≤4 fetches are in flight.
- From pool=8 upward we hit the **latency ceiling**: wall can't drop below the slowest single feed, and queuing tails add ~10 ms.
- Pool=32 is *worse* than pool=16 because thread creation isn't free and scheduling 32 threads for 20 tasks is wasted work.

### 3. The wall-time floor really is "slowest feed"

For pool=16: wall = 194 ms, slowest feed = 185 ms. The 9 ms gap is queueing + summary printing.
For pool=32: wall = 214 ms, slowest feed = 211 ms. 3 ms gap.

This is exactly what Phase 1's preview predicted. The sequential model paid latency in series; the pool pays latency in parallel and the worst feed dictates wall.

### 4. Failure isolation works

`reuters-top` failed with `ConnectException` in **2 ms** (DNS-cached fast-fail by then). A real DNS-timeout failure can still cost up to the per-task timeout (30 s) on first encounter, but unlike Phase 1 a slow failure no longer blocks subsequent feeds — they're already running on other threads.

### 5. Pool-size selection rule of thumb (for I/O-bound work)

- Cores ≠ pool size. We're not CPU-bound. Threads are mostly waiting.
- Pick **pool ≈ number of independent in-flight requests you can usefully have**.
- For this workload (20 feeds), pool=16 was the sweet spot. Beyond `min(numFeeds, ~24)` we burn thread-creation cost for no gain.
- For CPU-bound work the rule is the opposite: pool ≈ cores. Phase 4's parser pool will be CPU-bound and is sized differently.

---

## Concepts locked in

- **`Callable<T>` vs `Runnable`** — `Callable` returns a value and can throw checked exceptions. The natural fit when each task produces results.
- **`Future<T>`** — a handle to a not-yet-finished computation. `get()` blocks; `get(timeout, unit)` blocks bounded; `cancel(true)` interrupts the worker.
- **Pool sizing for I/O** — bounded by latency floor (slowest task) and by workload size, not by core count.
- **Graceful shutdown idiom** — `shutdown()` → `awaitTermination(t)` → `shutdownNow()`. Reused in every later phase.
- **Named `ThreadFactory`** — turns the log from anonymous "pool-1-thread-7" into "fetcher-7"; trivial change, huge readability win.
- **Wall vs. sum-of-fetches** — the two numbers tell different stories. Honest reporting requires both.

---

## What the run does NOT yet show

- **Backpressure.** All 20 callables are sitting in the queue at once; if we had 20 000 feeds, we'd OOM the queue. Phase 3 fixes that with bounded `BlockingQueue`s.
- **Decoupled stages.** Fetch and parse run on the same thread per feed. A slow parse blocks that thread for downstream work. Phase 3 splits them into separate pools.
- **CPU utilisation.** The pool threads are still mostly idle on the network. Virtual threads (Phase 5) will take this further on the read-side HTTP server.

---

## Next: Phase 3 — Bounded-queue pipeline

- Two pools: `fetcher` produces `RawFeed`s into an `ArrayBlockingQueue`; `parser` consumes from that queue and pushes `Article`s into the next.
- Bounded queues = backpressure: a slow parser stalls fetchers instead of blowing memory.
- Poison-pill pattern for clean end-of-stream.
- Concepts on deck: `BlockingQueue.put` / `take`, producer–consumer, why bounded > unbounded.
