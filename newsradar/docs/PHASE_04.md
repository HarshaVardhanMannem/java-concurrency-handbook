# Phase 4 — Thread-Safe Inverted Index

> Goal: build the search index that will back Phase 5's HTTP API, and *prove with real numbers* why "I'll just put a `synchronized` on it" is the wrong answer at scale. Three implementations side-by-side; one is broken, one is correct-but-slow, one is correct-and-scales.

---

## TL;DR

- `InvertedIndex` interface: `Map<token, Set<articleId>>` plus tokenize-on-`index(article)`.
- Three implementations:
  1. **`UnsafeHashMapIndex`** — `HashMap` with no synchronisation. **Loses postings, sometimes corrupts the map** (saw 106 tokens reported when the truth was 100).
  2. **`SynchronizedIndex`** — every method `synchronized`. Correct. Throughput collapses to 1 op at a time across all threads.
  3. **`ConcurrentIndex`** — `ConcurrentHashMap.computeIfAbsent` + `ConcurrentHashMap.newKeySet`. Correct *and* scales.
- Live stress: 16 threads × 50,000 articles × 100-token vocab → 1,000,000 postings.
  - Unsafe: 4,141 postings lost, **106 tokens** reported instead of 100 (bucket corruption).
  - Synchronized: 1,000,000 postings, 100 tokens, **430 ms**.
  - Concurrent: 1,000,000 postings, 100 tokens, **165 ms** — **2.6× faster than synchronized** at the same correctness.

---

## What was built

### New files

```
src/main/java/com/newsradar/index/
├── InvertedIndex.java          interface
├── Tokenizer.java              lowercase + alphanumeric split + drop tokens len<2
├── UnsafeHashMapIndex.java     plain HashMap, no locks (broken)
├── SynchronizedIndex.java      synchronized methods (correct, slow)
├── ConcurrentIndex.java        CHM + computeIfAbsent + newKeySet (correct, scales)
└── IndexFixture.java           generator + multi-thread stress harness

src/test/java/com/newsradar/index/
├── IndexCorrectnessTest.java   single-thread sanity for all three
└── IndexStressTest.java        multi-thread proves Unsafe breaks, others don't
```

### Changed

- `Main` adds `--mode=index-stress --threads=N --articles=M --vocab=K --tokens=T`.

### The interface

```java
public interface InvertedIndex {
    void index(Article article);                  // tokenize + insert (token, id) postings
    Set<String> articleIdsFor(String token);      // for search lookup
    int tokenCount();                             // distinct tokens
    int totalPostings();                          // sum of |postingSet| across tokens
}
```

A *posting* is a (`token`, `articleId`) pair. If two articles both contain "java", we have 2 postings for the token "java" (one per article id). The total is what we hammer with N threads and check against an exact expected value.

---

## How the three implementations differ

### 1. `UnsafeHashMapIndex` — the cautionary tale

```java
Set<String> set = postings.get(token);
if (set == null) {
    set = new HashSet<>();
    postings.put(token, set);   // race: two threads can both miss the get()
}
set.add(article.id());          // race on HashSet itself
```

Two failure modes, both observed:
- **Lost postings.** Thread A gets `null`, creates set S₁, puts it. Thread B got `null` *just before* A's put, creates set S₂, overwrites A's put. S₁ is GC'd along with everything A added to it. Postings vanish.
- **Map corruption.** `HashMap.put` may resize the backing array. If two threads resize concurrently, the bucket linked-list can become a cycle (pre-Java-8) or you can lose entries / gain phantom entries (Java 8+). I observed `tokenCount()` returning **106 distinct tokens** when only 100 distinct tokens were ever inserted — that's a corrupted map iterator visiting some entries twice.

This impl exists to be measured, not used. It's the test that proves "without synchronisation, a `HashMap` is not a thread-safe data structure."

### 2. `SynchronizedIndex` — correct, single-monitor

```java
public synchronized void index(Article article) { ... }
public synchronized Set<String> articleIdsFor(String token) { ... }
```

Every operation grabs the same intrinsic lock on `this`. Correct. But:
- **All readers and writers serialise through one monitor.** Eight threads on an 8-core machine = effectively single-threaded for index work.
- **Reads block writes and writes block reads.** Phase 5's HTTP API will have many concurrent readers; on this impl they'd queue up behind a single writer thread.
- **Lock contention overhead grows with thread count** — more threads on the wait queue, more context switching, more cache-line ping-pong on the monitor word.

### 3. `ConcurrentIndex` — correct and scaling

```java
postings.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet())
        .add(article.id());
```

Three carefully chosen primitives:
- **`ConcurrentHashMap`** — each bucket has its own fine-grained lock. Concurrent writers to *different* tokens don't contend at all.
- **`computeIfAbsent`** — atomic check-and-create. The "missed get / double put" race that breaks `UnsafeHashMapIndex` cannot happen: at most one Set is created per token, ever.
- **`ConcurrentHashMap.newKeySet()`** — a thread-safe Set backed by CHM. Concurrent `add` calls on the same key set use the same fine-grained CHM bucket locking. (`CopyOnWriteArraySet` would also be correct but quadratic on writes; we want write-scaled, not read-scaled.)

Reads are non-blocking and weakly consistent: an iterator may or may not see writes that happen during iteration, but it never crashes and never returns corrupt state. That's the right tradeoff for a search index.

---

## How to run it

```powershell
# Default: 8 threads, 4000 articles, 8 tokens each, 200-token vocab
mvn exec:java "-Dexec.args=--mode=index-stress"

# Crank it up
mvn exec:java "-Dexec.args=--mode=index-stress --threads=16 --articles=50000 --vocab=100 --tokens=20"

# Tests (19 total)
mvn test
```

---

## Real run — captured output

### Default contention (`--threads=8 --articles=10000 --vocab=500 --tokens=12`)

```
================================================================
  PHASE 4 — Inverted Index Stress
================================================================
  threads     : 8
  articles    : 10000
  per-article : 12 tokens drawn from 500-token vocabulary
  expected    : tokens=500 postings=120000

  impl                 correct?    tokens   postings   elapsed_ms      ops/sec
  ----------------------------------------------------------------------------------
  UnsafeHashMapIndex   NO             497     119988          127       944,787
  SynchronizedIndex    yes            500     120000           72     1,666,666
  ConcurrentIndex      yes            500     120000           42     2,857,142
```

- Unsafe lost 12 postings *and* lost 3 tokens entirely — those tokens existed in the input but their single Set was orphaned by a put-overwrites-put race.
- Synchronized correct, slower because it reports JIT-warm-up time for the first run; even after warming, it's serial.
- Concurrent: 1.7× faster than Synchronized at this contention level.

### High contention (`--threads=16 --articles=50000 --vocab=100 --tokens=20`)

```
  expected    : tokens=100 postings=1,000,000

  impl                 correct?    tokens    postings   elapsed_ms      ops/sec
  ----------------------------------------------------------------------------------
  UnsafeHashMapIndex   NO             106      995,859          82    12,144,621
  SynchronizedIndex    yes            100    1,000,000         430     2,325,581
  ConcurrentIndex      yes            100    1,000,000         165     6,060,606
```

This is the demonstration:

- **Unsafe reports 106 distinct tokens** when only 100 tokens exist in the entire vocabulary. There is **no input** that produces 106. That number is the symptom of a corrupted HashMap whose iterator is double-visiting bucket entries during a concurrent resize. The "ops/sec" of 12M is meaningless — it's *broken* fast, not correct fast.
- **Synchronized: 430 ms.** All 16 threads serialised through one monitor. Adding cores does nothing; the monitor is the bottleneck.
- **Concurrent: 165 ms — 2.6× faster than synchronized**, with identical correctness. Fine-grained bucket locks let 16 cores actually do 16 things at once when they hit different tokens.

---

## Result analysis

### 1. The Unsafe row is the demo, not the result

Look at the Unsafe results across two scales:

| scale | tokens reported | tokens expected | postings reported | postings expected |
|---|---|---|---|---|
| 8t × 10k articles, vocab 500 | 497 | 500 | 119,988 | 120,000 |
| 16t × 50k articles, vocab 100 | **106** | 100 | 995,859 | 1,000,000 |

At low contention you lose a few postings. At high contention the *map structure itself* corrupts and you start *fabricating* tokens. The bug is not "races make data wrong" — it's "races make the data structure stop being a HashMap." There is no recovery; you can't tell which entries are real. Throw the index away.

### 2. Why Concurrent beats Synchronized at the same correctness

The Synchronized impl has *one* lock. With 16 cores writing simultaneously, **15 of them are always parked**. Throughput is bounded by:

```
1 / (lock-acquire + critical-section + lock-release)
```

…regardless of core count. Adding cores increases contention overhead (more parking/unparking, more cache-line invalidation on the monitor word) — sometimes throughput *decreases* with more threads.

CHM strips locks across buckets. With a 16-bucket internal table and 16 random-token writes per cycle, all 16 can run concurrently. Effective throughput scales with cores until the workload runs out of distinct tokens (here, 100 tokens limits theoretical max parallelism).

### 3. Why our 2.6× isn't 16×

You might expect Concurrent to be 16× faster than Synchronized on a 16-thread workload. It isn't, because:

- **Token reuse forces same-bucket contention.** Vocabulary is only 100 tokens; many writes hit the same key and use the same per-bucket lock. The contention is *fine-grained but not zero*.
- **`HashSet.add` inside the value Set** is itself a write that synchronises (we use `ConcurrentHashMap.newKeySet()` which has its own CHM-backed locking). Two threads writing the same `(token, articleId)` go through that.
- **Thread spin-up, JIT warm-up, GC pressure** — the JVM spent some of those 165 ms on overhead unrelated to indexing.

A vocabulary of 10,000 tokens with 16 threads would push closer to the theoretical max. For *this* vocab and *this* hardware, 2.6× is the honest number.

### 4. Tokens vs. ops/sec — read both columns

Ops/sec is a fine secondary metric, but it's *only* meaningful when correctness holds:

- Unsafe at 12M ops/sec is **garbage-fast**. Throwing away every other write would be even faster. Nobody cares.
- Synchronized at 2.3M ops/sec is the **floor of correct**.
- Concurrent at 6M ops/sec is the **scaling reward** for picking the right primitive.

The first column (`correct?`) gates the others. If it says NO, the rest of the row is noise.

### 5. The test that proves Unsafe breaks

`IndexStressTest.unsafeIndexIsBrokenUnderContention` runs the unsafe impl up to 5 times and asserts that *at least one run* observed loss or a thrown exception. On modern multi-core hardware it observes the bug on the first attempt every time. The retry loop exists only to keep CI robust on single-core sandbox environments where the bug is suppressed by lack of true parallelism.

The other two tests (`synchronizedIndexIsCorrectUnderContention`, `concurrentIndexIsCorrectUnderContention`) assert exact equality of token and posting counts — and pass deterministically.

---

## Concepts locked in

- **Atomicity vs. visibility.** `HashMap.get` followed by `HashMap.put` is two operations. Without atomicity (a single locked region or a CAS-based primitive), another thread can squeeze in between. Volatile would help with visibility but not atomicity.
- **`computeIfAbsent` is the right primitive** for "create-if-missing" patterns. It's atomic with respect to concurrent writers — at most one mapping function call per key.
- **`ConcurrentHashMap.newKeySet()`** — when you need a thread-safe Set with high write throughput (vs. `CopyOnWriteArraySet` which is only fast for read-heavy workloads).
- **`synchronized` collapses throughput** as soon as the workload has any concurrent access. It's the *right* answer for guaranteed mutual exclusion of a small critical section, the *wrong* answer for a hot data structure.
- **A broken map is worse than a slow map.** Phantom tokens are a failure mode that doesn't exist with locks. Pick a primitive that can't get into that state.

---

## What this still doesn't have

- **Search.** We can look up `articleIdsFor(token)` but there's nothing exposing it. Phase 5 puts a `GET /search?q=…` endpoint on virtual threads in front of the index.
- **Article retrieval.** The index stores postings, not articles. Phase 5 will add an `articleId → Article` map alongside the index.
- **Read-write coordination.** Right now we only stress writes. Phase 5 will introduce concurrent readers; we may want a `ReadWriteLock` around index *swaps* (replacing the entire index on refresh) so writers can't starve readers.
- **Pipeline integration.** `PipelineAggregator`'s indexer still collects to a `List<Article>`. Phase 5 will plug `ConcurrentIndex` in as the real indexer.

---

## Next: Phase 5 — HTTP search API on virtual threads

- Embed `com.sun.net.httpserver.HttpServer` with `Executors.newVirtualThreadPerTaskExecutor()`.
- `GET /search?q=foo&limit=10` → JSON via Jackson, backed by `ConcurrentIndex`.
- A `LoadTest.main` that fires 10,000 concurrent clients on virtual threads and prints p50 / p99 latency.
- Add a `ReadWriteLock` around index swaps so the writer side doesn't starve readers when Phase 6's scheduled refresh kicks in.
- Concepts on deck: virtual threads vs. platform threads, cheap I/O concurrency, when `ReadWriteLock` matters.
