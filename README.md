# Concurrency (Java) — Step by Step

This repo is a minimal, **no-Maven** set of Java files to learn concurrency in steps.

## Files
- `ThreadCreationBasics.java`: measures thread creation time and runs runnable work (`start` + `join`).
- `MultithreadingBasics.java`: basic multithreading (`start/join`) + race condition demo.
- `ConcurrencyFlow.java`: sequential vs raw threads vs thread pool comparison.

## Compile (save `.class` files into `out/`)

```powershell
mkdir out -ErrorAction SilentlyContinue
javac -d out ThreadCreationBasics.java MultithreadingBasics.java ConcurrencyFlow.java
```

## Run

```powershell
java -cp out ThreadCreationBasics 200 20000
java -cp out MultithreadingBasics 3
java -cp out ConcurrencyFlow 30 120 10
```

Args:
- `ThreadCreationBasics`: `threadCount workPerThread`
- `MultithreadingBasics`: `workerCount`
- `ConcurrencyFlow`: `taskCount sleepMillis poolSize`
