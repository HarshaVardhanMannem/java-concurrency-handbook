# NewsRadar

A concurrent news aggregator — Java capstone project for learning concurrency in 7 phases.

See [`PHASES.md`](PHASES.md) for the full step-by-step journey.

## Prerequisites

- **Java 21+** (built and tested on Java 24)
- **Maven 3.9+**

If `mvn -v` does not work in PowerShell, install Maven:

```powershell
winget install Apache.Maven
```

(restart your shell afterward — `winget` adds Maven to PATH automatically).

## Build & run

```powershell
mvn compile
mvn exec:java
```

Expected output:

```
HH:mm:ss.SSS [main] INFO  com.newsradar.Main - NewsRadar starting (java 24.0.1 / vendor ...)
HH:mm:ss.SSS [main] INFO  com.newsradar.Main - Phase 0 scaffold OK. Next: Phase 1 — sequential baseline fetcher.
```

## Test

```powershell
mvn test
```

## Layout

```
newsradar/
├── pom.xml
├── PHASES.md            ← the 7-phase learning roadmap
├── README.md
├── .gitignore
└── src/
    ├── main/
    │   ├── java/com/newsradar/
    │   │   └── Main.java
    │   └── resources/
    │       ├── feeds.yaml      ← list of RSS feeds to crawl
    │       └── logback.xml     ← logging config
    └── test/
        └── java/com/newsradar/
            └── MainTest.java
```

## Where we are

- [x] **Phase 0** — scaffold
- [ ] Phase 1 — sequential baseline
- [ ] Phase 2 — thread pool fetcher
- [ ] Phase 3 — bounded-queue pipeline
- [ ] Phase 4 — thread-safe inverted index
- [ ] Phase 5 — HTTP search API on virtual threads
- [ ] Phase 6 — scheduled refresh + graceful shutdown
- [ ] Phase 7 — JMH benchmark write-up
