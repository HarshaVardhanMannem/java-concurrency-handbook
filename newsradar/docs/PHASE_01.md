# Phase 1 — Sequential Baseline

> Goal: build a working end-to-end news aggregator that fetches and parses RSS feeds **one at a time** on a single thread, and **measure how slow it is**. The slowness is the lesson — every later phase replaces a piece of this with a concurrent alternative and we measure the win.

---

## TL;DR

- 1 thread, blocking `HttpClient.send`, blocking `jsoup` parse.
- 20 real public RSS feeds → 509 articles, ~916 KB downloaded.
- Wall time ≈ **sum of all per-feed latencies** (~5.9 s on a fast network, ~13 s when one feed lags).
- CPU was idle ~100% of wall time — every millisecond was spent waiting on a socket.
- Phase 2 will replace the loop with an `ExecutorService` and the wall time should collapse to ≈ the slowest feed.

---

## What was built

### Source layout

```
newsradar/src/main/java/com/newsradar/
├── Main.java                       CLI entry: --mode=info | sequential
├── config/
│   ├── FeedConfig.java             record(id, name, URI url)
│   └── FeedConfigLoader.java       snakeyaml -> List<FeedConfig>
├── model/
│   ├── Article.java                record(id, feedId, title, body, url, publishedAt)
│   └── RawFeed.java                record(feedId, byte[] body, contentType)
├── fetch/
│   └── HttpFeedFetcher.java        java.net.http.HttpClient.send (synchronous)
├── parse/
│   └── RssParser.java              jsoup XML -> List<Article>  (RSS 2.0 + Atom)
├── pipeline/
│   └── SequentialAggregator.java   loop feeds: fetch -> parse -> log
└── util/
    └── Stopwatch.java              tiny System.nanoTime wrapper

newsradar/src/main/resources/
├── feeds.yaml                      20 real public RSS/Atom feeds
└── logback.xml                     console logging config

newsradar/src/test/java/com/newsradar/
├── MainTest.java                   smoke test
├── config/FeedConfigLoaderTest.java   YAML parsing (offline)
└── parse/RssParserTest.java        RSS 2.0 + Atom parsing (offline)
```

### How a single feed flows

```
feeds.yaml (classpath)
        │  FeedConfigLoader (snakeyaml)
        ▼
List<FeedConfig>
        │   for each feed (sequential loop)
        ▼
HttpFeedFetcher.fetch(feed)
        │   HttpClient.send(GET) — BLOCKS this thread
        ▼
RawFeed { feedId, byte[] body, contentType }
        │   RssParser.parse(raw) — jsoup XML parser
        ▼
List<Article> { id, feedId, title, body, url, publishedAt }
        │   accumulate + log row
        ▼
Aggregated List<Article> + summary stats
```

Nothing here is asynchronous. `HttpClient.send` parks the caller thread until the remote server returns bytes; that is the entire bottleneck.

---

## How to run it

From `newsradar/` (where `pom.xml` lives):

```powershell
# Unit tests (no network, ~5 tests)
mvn test

# Real run against 20 live RSS servers
mvn exec:java "-Dexec.args=--mode=sequential"

# Hello mode (no work, just confirms wiring)
mvn exec:java
```

Disable wifi → every feed will fail. Re-enable → article counts and timings change every run, because the data is fetched live.

---

## Where the data comes from

The only static input is `src/main/resources/feeds.yaml`, which contains **URLs only** (an address book, not data). At runtime:

1. Open a TLS connection to the feed's host (`news.ycombinator.com`, `feeds.bbci.co.uk`, …).
2. Send `GET /…` with `User-Agent: NewsRadar/0.1`.
3. Read the response bytes (the **current** RSS XML the publisher is serving right now).
4. Hand bytes to `RssParser`.

Run twice and the article counts will differ — those publishers churn within minutes.

---

## Logging design

The console pattern is `HH:mm:ss.SSS LEVEL Logger | message`. Phase 1 is single-threaded, so the thread name is omitted (it will return in Phase 2 where it actually carries information). The aggregator emits four sections:

1. **Banner** — phase name, feed count, strategy.
2. **Per-feed table** — index, id, status (`OK` / `FAIL` / `INTR`), articles, ms, bytes.
3. **Results block** — counts and bytes downloaded.
4. **Timing block** — wall time, sum of fetches, fastest, slowest (with feed id), average.
5. **Concurrency lesson** — the punchline: how much of wall time was spent waiting, and what Phase 2 should achieve.

Per-feed `DEBUG` lines log the URL before each fetch — useful when a feed hangs.

---

## Real run — captured output

```
17:43:19.973 INFO  Main | NewsRadar starting (java 24.0.1 / vendor Oracle Corporation)
17:43:20.319 INFO  SequentialAggregator |
17:43:20.319 INFO  SequentialAggregator | ================================================================
17:43:20.319 INFO  SequentialAggregator |   PHASE 1 — Sequential Baseline (1 thread, blocking I/O)
17:43:20.319 INFO  SequentialAggregator | ================================================================
17:43:20.320 INFO  SequentialAggregator | feeds queued    : 20
17:43:20.320 INFO  SequentialAggregator | strategy        : fetch -> parse, one feed at a time
17:43:20.320 INFO  SequentialAggregator |
17:43:20.320 INFO  SequentialAggregator |   #   feed               stat articles         ms        bytes  note
17:43:20.320 INFO  SequentialAggregator |   ------------------------------------------------------------------------------
17:43:21.049 INFO  SequentialAggregator |    1/20 hn-frontpage       OK         30        725       11,355
17:43:21.469 INFO  SequentialAggregator |    2/20 hn-newest          OK         20        420       15,684
17:43:21.878 INFO  SequentialAggregator |    3/20 bbc-world          OK         37        408       28,015
17:43:22.213 INFO  SequentialAggregator |    4/20 bbc-tech           OK         21        335       15,195
17:43:22.424 INFO  SequentialAggregator |    5/20 nyt-home           OK         16        209       32,902
17:43:22.827 INFO  SequentialAggregator |    6/20 nyt-tech           OK         20        402       41,612
17:43:23.085 INFO  SequentialAggregator |    7/20 theverge           OK         10        258       77,353
17:43:23.330 INFO  SequentialAggregator |    8/20 arstechnica        OK         20        244       80,479
17:43:23.539 INFO  SequentialAggregator |    9/20 techcrunch         OK         20        208       18,449
17:43:23.746 INFO  SequentialAggregator |   10/20 wired              OK         50        206       46,152
17:43:23.973 INFO  SequentialAggregator |   11/20 engadget           OK         20        226       34,628
17:43:24.262 INFO  SequentialAggregator |   12/20 guardian-world     OK         45        289      147,662
17:43:24.405 INFO  SequentialAggregator |   13/20 guardian-tech      OK         24        142       82,985
17:43:24.486 WARN  SequentialAggregator |   14/20 reuters-top        FAIL        0         80            0  ConnectException
17:43:24.770 INFO  SequentialAggregator |   15/20 npr-news           OK         10        284       14,544
17:43:25.162 INFO  SequentialAggregator |   16/20 cnn-top            OK         69        390      174,866
17:43:25.419 INFO  SequentialAggregator |   17/20 aljazeera          OK         25        256       17,174
17:43:25.618 INFO  SequentialAggregator |   18/20 lobsters           OK         25        198       15,863
17:43:25.949 INFO  SequentialAggregator |   19/20 slashdot           OK         15        330       61,866
17:43:26.211 INFO  SequentialAggregator |   20/20 phoronix           OK         32        261       21,494
17:43:26.211 INFO  SequentialAggregator |   ------------------------------------------------------------------------------
17:43:26.225 INFO  SequentialAggregator | RESULTS
17:43:26.225 INFO  SequentialAggregator |   feeds ok        : 19 / 20
17:43:26.225 INFO  SequentialAggregator |   feeds failed    : 1
17:43:26.225 INFO  SequentialAggregator |   articles total  : 509
17:43:26.225 INFO  SequentialAggregator |   bytes downloaded: 938,278 (916 KB)
17:43:26.225 INFO  SequentialAggregator | TIMING
17:43:26.225 INFO  SequentialAggregator |   wall time       : 5,890 ms
17:43:26.225 INFO  SequentialAggregator |   sum of fetches  : 5,871 ms   (≈ wall time — proves serial blocking)
17:43:26.226 INFO  SequentialAggregator |   fastest feed    : 142 ms
17:43:26.226 INFO  SequentialAggregator |   slowest feed    : 725 ms   <- hn-frontpage
17:43:26.226 INFO  SequentialAggregator |   avg per feed    : 294 ms
17:43:26.226 INFO  SequentialAggregator | CONCURRENCY LESSON
17:43:26.227 INFO  SequentialAggregator |   CPU spent ~100% of wall time blocked on socket reads.
17:43:26.227 INFO  SequentialAggregator |   Phase 2 (thread pool) target: wall ≈ slowest feed (725 ms), not the sum.
```

---

## Result analysis

### Headline numbers

| metric              | value         |
|---------------------|---------------|
| feeds attempted     | 20            |
| feeds succeeded     | 19            |
| feeds failed        | 1 (`reuters-top`, dead endpoint) |
| articles parsed     | 509           |
| bytes downloaded    | 938,278 (~916 KB) |
| wall time           | **5,890 ms**  |
| sum of fetches      | **5,871 ms**  |
| fastest feed        | 142 ms (`guardian-tech`) |
| slowest feed        | 725 ms (`hn-frontpage`)  |
| avg per feed        | 294 ms        |

### Observation 1 — wall time = sum of fetches

```
sum of fetches : 5,871 ms
wall time      : 5,890 ms     (delta = 19 ms, parsing + bookkeeping)
```

This is *the* defining shape of a serial blocking pipeline. Each `HttpClient.send` parks the only thread; nothing else can happen until that one feed's bytes have arrived. Add a 21st feed at +300 ms and the wall time goes up by ~300 ms. Drop a feed and it goes down by exactly that feed's latency. The relationship is linear and one-to-one.

### Observation 2 — CPU was ~100% idle

`HttpClient.send` performs a blocking socket read. While the BBC server thinks about your request, the JVM thread is in `WAITING` state — not on the CPU run queue, not doing parsing, not even reading the next feed's URL. Across the 5.9 s run, parsing accounted for at most ~19 ms (the wall - sum delta). **The CPU spent ~99.7% of wall time waiting on the network.** That is wasted capacity Phase 2 will reclaim.

### Observation 3 — one slow feed punishes everyone behind it

A second run on the same 20 feeds produced this distribution:

| run        | hn-newest | wall time |
|------------|-----------|-----------|
| this run   | 420 ms    | 5,890 ms  |
| earlier run| 7,423 ms  | 12,942 ms |

Same code, same 20 feeds, network variability gave `hn-newest` a 7.4 s slow response. **In the sequential model, that single laggard added 7 seconds to every feed scheduled after it** — even though those feeds were fast. The remote endpoint dictated our latency; we had no isolation.

### Observation 4 — failure is contained, but still serial

`reuters-top` failed with `ConnectException` after 80 ms. The aggregator logged a `FAIL` row and continued. Good — but that 80 ms still cost the run 80 ms, and a real DNS-timeout failure can cost 5–30 s on the same path. In sequential mode, every failure tax is paid in series.

### Observation 5 — bytes ≠ time

The two largest payloads (`cnn-top` 174 KB, `guardian-world` 147 KB) were both *under* 400 ms. The smallest (`bbc-tech` 15 KB) took 335 ms. Latency on these public feeds is dominated by **server-side response time and TLS round-trips**, not by download size. That is exactly the workload pattern where concurrency wins big — most of the time is wait, not work.

### What Phase 2 has to beat

Theoretical best for a thread-per-feed run: **wall ≈ max latency = 725 ms** (this run) or **~7.5 s** (the slow run). A pool size of 8 should land within a small constant factor of that. The headline KPI for Phase 2 will be `wall_phase2 / wall_phase1` on the same 20-feed list.

---

## Concepts locked in

- **Blocking I/O** — `HttpClient.send` parks the calling thread until bytes arrive.
- **Wall vs. CPU time** — wall is what the user feels; CPU is what was actually used. The gap is "where concurrency lives."
- **Head-of-line blocking** — in a serial pipeline the slowest item delays every item behind it.
- **Failure tax** — exception paths still cost wall time and still serialise.
- **Why `Future`/`ExecutorService` exist** — the next phase exists *because* of what this one shows.

---

## Next: Phase 2 — Thread pool fetcher

- Replace the for-loop with `ExecutorService.submit(Callable<List<Article>>)`.
- Compare pool sizes 4 / 8 / 16 / 32 against this exact baseline.
- Expected wall time on this 20-feed list: ≈ slowest feed plus a small queueing tail.
- Concepts on deck: `Callable`, `Future.get` with timeout, why pool size > cores for I/O work, graceful shutdown idiom.
