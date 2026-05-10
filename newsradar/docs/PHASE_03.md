# Phase 3 — Bounded-Queue Pipeline (Producer–Consumer)

> Goal: split fetch and parse onto independent thread pools connected by **bounded** `BlockingQueue`s. The bounded queue is the load-bearing primitive: a slow stage stalls upstream via `put()` instead of letting the heap balloon. Coordinate end-of-stream with **poison pills**.

---

## TL;DR

- Two pools: `fetcher` (I/O-bound, large) → `parser` (CPU-bound, small ≈ cores) → single `indexer`.
- Two bounded `ArrayBlockingQueue`s carry data between stages.
- Poison-pill sentinels `RawFeed.SHUTDOWN` and `Article.SHUTDOWN` cleanly terminate the consumer loops.
- Live monitor logs queue depths and stall counters every 250 ms.
- 20 live feeds → 511 articles in **929 ms** (cold JVM); fetchers, parsers, and indexer all run concurrently with visible interleaving in the logs.
- A tightened run with `--queue=1 --parsers=1` produced **12 fetcher stalls** — backpressure caught on camera.

---

## Architecture

```
                         feeds (20)
                             │
                             ▼
        ┌─────────────────────────────────────────┐
        │  fetcher pool  (16 threads, blocking)   │
        │     fetcher-1 .. fetcher-16             │
        └────────────────┬────────────────────────┘
                         │  put(rawFeed)        ← BLOCKS if queue full
                         ▼
                ┌──────────────────┐
                │  rawQueue (cap 8)│   ArrayBlockingQueue<RawFeed>
                └────────┬─────────┘
                         │  take()
                         ▼
        ┌─────────────────────────────────────────┐
        │  parser pool  (4 threads, CPU-bound)    │
        │     parser-1 .. parser-4                │
        │     loop { take; if SHUTDOWN exit;      │
        │            parse; for a in articles put}│
        └────────────────┬────────────────────────┘
                         │  put(article)        ← BLOCKS if queue full
                         ▼
                ┌──────────────────────┐
                │ articleQueue (cap 64)│   ArrayBlockingQueue<Article>
                └────────┬─────────────┘
                         │  take()
                         ▼
        ┌─────────────────────────────────────────┐
        │  indexer  (1 thread, accumulate)        │
        │  loop { take; if SHUTDOWN return; add } │
        └─────────────────────────────────────────┘
```

Three executors, two queues, four control points. Plus a `ScheduledExecutorService` that ticks every 250 ms and prints queue depths + stall counters.

### Coordination — how the pipeline shuts down cleanly

The tricky part of producer–consumer isn't the data flow, it's the *closing*. Solution:

1. Submit one task per feed to the fetcher pool. Each fetch task does the `rawQueue.put(raw)` and exits.
2. Parser threads sit in `while(true) { raw = take(); if SHUTDOWN return; parse + put }` — they never exit on their own.
3. Indexer thread does the same: `while(true) { a = take(); if SHUTDOWN return; index }`.
4. Main thread waits for **all fetcher Futures** to finish (success or failure).
5. Main puts **N poison pills** onto `rawQueue`, where N = parser pool size. Each parser takes exactly one and exits.
6. Main waits for **all parser Futures**, then puts **one poison pill** onto `articleQueue`.
7. Indexer takes it and exits, returning the indexed list.
8. `finally` block calls `shutdown() → awaitTermination(15s) → shutdownNow()` on all four executors.

This is the canonical pattern. Phase 6 will reuse it for the live service shutdown hook.

### Why poison pills, not just `shutdownNow()`?

Calling `shutdownNow()` interrupts working threads. That's fine for fire-and-forget tasks but bad for stages mid-write — you'd lose articles already pulled from `rawQueue` and not yet pushed to `articleQueue`. Poison pills let each stage finish its current item, drain its input queue, and exit voluntarily. **Clean** end-of-stream.

---

## What was built

### New files

```
src/main/java/com/newsradar/parse/
└── FeedParser.java                 (NEW) interface — lets tests swap in a slow parser

src/main/java/com/newsradar/pipeline/
└── PipelineAggregator.java         (NEW) the whole producer-consumer machine

src/test/java/com/newsradar/pipeline/
└── PipelineAggregatorTest.java     (NEW) 4 offline tests

src/main/java/com/newsradar/model/
├── RawFeed.java                    + SHUTDOWN sentinel
└── Article.java                    + SHUTDOWN sentinel
```

### Changed files

- `RssParser` — now `implements FeedParser` (was `final`; named the interface `FeedParser` to avoid clashing with `org.jsoup.parser.Parser`).
- `Main` — adds `--mode=pipeline --fetchers=N --parsers=M --queue=K` plus a pipeline row in `--mode=compare`.

### Sentinel design

```java
public record RawFeed(String feedId, byte[] body, String contentType) {
    public static final RawFeed SHUTDOWN = new RawFeed("__SHUTDOWN__", new byte[0], "__SHUTDOWN__");
    public boolean isShutdown() { return this == SHUTDOWN; }
}
```

Comparison is **reference equality** (`==`), not `.equals()`. Records auto-generate `equals/hashCode` from values, but a sentinel must be unique by identity, not by content. `==` also avoids any accidental collision with a real feed that happens to have id `__SHUTDOWN__`.

### Backpressure mechanism

`ArrayBlockingQueue.put(e)` blocks the caller until space is available. So:

```java
out.put(rawFeed);   // fetcher thread: parks here when rawQueue is full
out.put(article);   // parser  thread: parks here when articleQueue is full
```

A blocked `put` is the queue **enforcing the contract** that work doesn't accumulate faster than it can be processed. Without bounding (e.g. `LinkedBlockingQueue` with no capacity), there's no signal — memory just grows. Bounded = safe. Unbounded = OOM waiting to happen.

We track stalls with `if (out.remainingCapacity() == 0) stalls.incrementAndGet();` *before* the `put`. The stall counter is the runtime-visible signal of backpressure activating.

---

## How to run it

```powershell
# Default sizing (16 fetchers, parsers = #cores, raw queue 16)
mvn exec:java "-Dexec.args=--mode=pipeline"

# Tune knobs
mvn exec:java "-Dexec.args=--mode=pipeline --fetchers=16 --parsers=4 --queue=8"

# Force backpressure to see real stalls
mvn exec:java "-Dexec.args=--mode=pipeline --fetchers=16 --parsers=1 --queue=1"

# Side-by-side ladder includes pipeline at the end
mvn exec:java "-Dexec.args=--mode=compare"

# Tests (12 total)
mvn test
```

---

## Real run — captured output

### Default sizing (`--fetchers=16 --parsers=4 --queue=8`)

```
================================================================
  PHASE 3 — Bounded-Queue Pipeline
================================================================
  feeds            : 20
  fetcher pool     : 16
  parser  pool     : 4
  rawQueue cap     : 8
  articleQueue cap : 64
  monitor          : every 250 ms

[fetcher-14] fetch FAIL reuters-top after 166 ms : ConnectException
[monitor]    raw=0/8  articles=0/64  fetched=0 parsed=0 indexed=0   stalls(fetch=0,parse=0)
[fetcher-16] fetched  cnn-top         in 470 ms  (174,866 B)  -> raw queue (depth 1)
[fetcher-9 ] fetched  techcrunch      in 578 ms  (18,303 B)   -> raw queue (depth 1)
[fetcher-10] fetched  wired           in 582 ms  (46,227 B)   -> raw queue (depth 1)
[fetcher-15] fetched  npr-news        in 590 ms  (14,389 B)   -> raw queue (depth 1)
[fetcher-3 ] fetched  bbc-world       in 605 ms  (28,850 B)   -> raw queue (depth 1)
[fetcher-8 ] fetched  arstechnica     in 607 ms  (80,479 B)   -> raw queue (depth 2)
[parser-3  ] parsed   npr-news        -> 10 articles in 19 ms  (article queue depth 10)
[fetcher-1 ] fetched  hn-frontpage    in 611 ms  (11,240 B)   -> raw queue (depth 2)
[parser-1  ] parsed   techcrunch      -> 20 articles in 35 ms  (article queue depth 19)
[fetcher-2 ] fetched  hn-newest       in 622 ms  (16,996 B)   -> raw queue (depth 6)
[parser-3  ] parsed   bbc-world       -> 38 articles in 14 ms  (article queue depth 31)
[parser-4  ] parsed   wired           -> 50 articles in 44 ms  (article queue depth 49)
[parser-2  ] parsed   cnn-top         -> 69 articles in 159 ms (article queue depth 32)
... (12 more interleaved fetch/parse rows) ...
[monitor]    raw=0/8  articles=0/64  fetched=16 parsed=16 indexed=439  stalls(fetch=0,parse=0)
[fetcher-10] fetched  phoronix        in 292 ms                -> raw queue (depth 1)
[parser-4  ] parsed   phoronix        -> 32 articles in 4 ms
[fetcher-16] fetched  lobsters        in 451 ms                -> raw queue (depth 1)
[parser-1] saw poison pill, exiting
[parser-4] saw poison pill, exiting
[parser-3] saw poison pill, exiting
[parser-2] parsed   lobsters        -> 25 articles in 6 ms
[parser-2] saw poison pill, exiting
[indexer-1] saw poison pill, exiting

RESULTS
  feeds            : 20
  fetched ok       : 19
  fetched failed   : 1            (reuters-top, ConnectException)
  parsed feeds     : 19
  articles indexed : 511
  bytes downloaded : 945,231 (923 KB)

BACKPRESSURE
  fetcher put() stalls (rawQueue full)    : 0
  parser  put() stalls (articleQ full)    : 0

TIMING
  wall time        : 929 ms
```

### Forced backpressure run (`--parsers=1 --queue=1`)

```
[monitor]    raw=0/1  articles=0/64  fetched=14 parsed=14 indexed=374  stalls(fetch=12,parse=1)

RESULTS
  fetched ok       : 19
  articles indexed : 511

BACKPRESSURE
  fetcher put() stalls (rawQueue full)    : 12
  parser  put() stalls (articleQ full)    : 1

TIMING
  wall time        : 877 ms
```

---

## Result analysis

### 1. The interleaving in the log *is* the point

Look at this fragment from the default run:

```
[fetcher-3] fetched bbc-world      -> raw queue (depth 1)
[fetcher-8] fetched arstechnica    -> raw queue (depth 2)
[parser-3]  parsed  npr-news       -> 10 articles
[fetcher-1] fetched hn-frontpage   -> raw queue (depth 2)
[parser-1]  parsed  techcrunch     -> 20 articles
[fetcher-5] fetched nyt-home       -> raw queue (depth 3)
```

Within the same millisecond window, fetchers are putting onto `rawQueue` *while* parsers are taking off it. Three independent stages running on three independent pools sharing data through bounded queues. **That is producer-consumer working in real time**, and it's something Phase 2 (single-pool fetch+parse) cannot do.

### 2. Parsing is dirt cheap; fetching dominates

Per-feed parse times: 2 – 44 ms (cnn-top's 69-article XML at 159 ms is the outlier).
Per-feed fetch times: 292 – 716 ms.

Fetch is **~10× more expensive** than parse on this workload. That justifies sizing the fetcher pool aggressively (16 threads for 20 I/O-bound tasks) and the parser pool conservatively (4 threads ≈ cores for CPU-bound work). Mixing them in one pool — Phase 2 — is suboptimal because either:
- the pool is sized for fetch (16) → parsing wastes CPU context-switching across 16 threads, or
- the pool is sized for parse (~cores) → fetching can't get enough threads in flight.

Splitting stages with their own pools fixes that.

### 3. Backpressure is real, even though the default run shows zero stalls

Default run: `stalls(fetch=0, parse=0)`. That's because parsing is so much faster than fetching that the rawQueue never stayed full long enough to block a put.

Tighten the screws — `--parsers=1 --queue=1` — and the same workload produces **12 fetcher stalls and 1 parser stall**. The exact same code, just smaller buffers. The bounded queue isn't decorative: it's a runtime guarantee that memory cannot grow without bound, *enforced by parking the producer thread*.

### 4. Wall time vs. Phase 2

| run | wall ms | comment |
|---|---|---|
| Phase 2 pool=16 (in `--mode=compare`, warm JVM) | 194 | inherits warm DNS/TLS/JIT from sequential before it |
| Phase 3 pipeline (cold JVM) | 929 | fresh JVM; HttpClient and DNS cold-start |
| Phase 3 backpressured | 877 | counter-intuitive — same shape, JVM starting to warm |

A direct cold-vs-cold or warm-vs-warm comparison would put Phase 2 and Phase 3 within ~10–20% on this small workload. **Phase 3 is not fundamentally faster than Phase 2 on 20 feeds.** Where it wins:
- **Memory safety at scale** — 20,000 feeds in Phase 2 = 20,000 in-flight `Future`s + accumulated articles in heap. Phase 3 = at most `rawQueueCapacity + articleQueueCapacity` items in flight, period.
- **Stage independence** — a parser-side issue (e.g. a malformed feed taking 10 s) doesn't tie up a fetcher thread; the fetcher dropped its bytes onto the queue and moved on.
- **Foundation for Phase 5/6** — the article queue is exactly where the indexer (Phase 4) and the live HTTP search server (Phase 5) will hook in.

### 5. The poison-pill propagation in the log

```
[parser-1] saw poison pill, exiting
[parser-4] saw poison pill, exiting
[parser-3] saw poison pill, exiting
[parser-2] parsed lobsters -> 25 articles in 6 ms
[parser-2] saw poison pill, exiting
[indexer-1] saw poison pill, exiting
```

Three parsers picked up their pills before parser-2 finished its last real feed. That's correct behaviour: with 4 parser pills in the rawQueue and 4 parsers calling `take()`, whichever parser is free first takes a pill. Parser-2 was busy parsing `lobsters`, so it took a pill last. Each parser exits exactly once. The indexer takes its single pill and the run ends.

### 6. `reuters-top` failure is local

The fetcher task caught `ConnectException`, incremented `fetchFail`, and returned without putting anything onto `rawQueue`. **Zero impact on the rest of the pipeline.** Compare to Phase 1 where every failure tax was paid serially.

---

## Concepts locked in

- **`BlockingQueue.put` / `take`** — thread-safe, blocking, bounded. The producer-consumer primitive.
- **Backpressure** — `put()` parks the producer when the queue is full. The bounded queue is the upper bound on in-flight work.
- **Poison pill** — N pills for N consumer threads of a stage; reference-equality sentinel; clean voluntary exit.
- **Stage decoupling** — separate pool per stage, sized per workload type (I/O-heavy → big, CPU-bound → ≈ cores).
- **`ScheduledExecutorService.scheduleAtFixedRate`** — observability without blocking application threads. Cancel its `ScheduledFuture` then shutdown the scheduler.
- **Ordered shutdown** — drain producers → poison consumers → wait → forced fallback.

---

## What this still doesn't have

- **Inverted index.** The indexer just collects articles into a list. Phase 4 builds a real searchable index and explores three thread-safety strategies.
- **Read-side concurrency.** Nothing yet exposes the articles to readers. Phase 5 puts an HTTP server on virtual threads in front of the index.
- **Refresh + lifecycle.** This is one-shot. Phase 6 schedules `scheduleAtFixedRate` re-fetches and adds a real shutdown hook.

---

## Next: Phase 4 — Thread-safe inverted index

- Build `InvertedIndex` of `Map<String, Set<ArticleId>>` (token → matching articles).
- Three implementations side-by-side: `HashMap` (broken), `synchronized` (correct, slow), `ConcurrentHashMap + computeIfAbsent + CopyOnWriteArraySet` (correct + scales).
- JUnit stress tests that hammer each from N threads and assert total token counts.
- Concepts on deck: atomicity vs visibility, when `synchronized` is a foot-gun under load, lock-free collections.
