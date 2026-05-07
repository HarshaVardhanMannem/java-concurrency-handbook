# Java Concurrency Fundamentals: A Beginner's Guide

## Table of Contents
1. What is Concurrency?
2. Why Do We Need Concurrency?
3. Basics First: Threads and Implementation
4. Core Concepts
5. Java Concurrency Building Blocks
6. Synchronization Mechanisms
7. Common Pitfalls
8. When to Use What
9. Quick Decision Tree
10. Our Web Scraper Journey

---

## 1. What is Concurrency?

**Concurrency** means multiple tasks making progress at the same time. Think of it like:
- A chef cooking multiple dishes simultaneously (switching between tasks)
- Multiple checkout counters at a grocery store (truly parallel work)

### Concurrency vs Parallelism

**Concurrency**: Managing multiple tasks at once (structure)
- Example: A single chef preparing soup, salad, and steak by switching between them

**Parallelism**: Executing multiple tasks simultaneously (execution)
- Example: Three chefs each working on a different dish at the same time

```
CONCURRENT (Single Core):
Time ->  [Task A][Task B][Task A][Task C][Task B]
         One CPU switching between tasks

PARALLEL (Multi-Core):
Core 1:  [Task A.................]
Core 2:  [Task B.................]
Core 3:  [Task C.................]
         Multiple CPUs working simultaneously
```

---

## 2. Why Do We Need Concurrency?

### Problem: Blocking Operations Waste Time

**Sequential Example - Web Scraping:**
```
URL 1: Fetch (2 sec) -> Parse (0.1 sec)
URL 2: Fetch (2 sec) -> Parse (0.1 sec)
URL 3: Fetch (2 sec) -> Parse (0.1 sec)
Total: ~6.3 seconds
```

During the 2-second fetch, the CPU is **idle** waiting for network response!

**Concurrent Example:**
```
URL 1: Fetch (2 sec) ------------------> Parse
URL 2:    Fetch (2 sec) -------------> Parse
URL 3:       Fetch (2 sec) ---------> Parse
Total: ~2.3 seconds (3x faster!)
```

### When Concurrency Helps:
✅ **I/O-bound tasks** (network, disk, database) - waiting time dominates
✅ **Independent tasks** (can run without depending on each other)
✅ **Responsive UIs** (keep interface active while processing)
✅ **Server applications** (handle multiple client requests)

### When Concurrency Doesn't Help:
❌ **CPU-bound tasks** (heavy computation) - adding threads won't speed it up
❌ **Sequential dependencies** (Task B needs Task A's result)
❌ **Shared mutable state** (adds complexity with locks)

---

## 3. Basics First: Threads and Implementation

Before async APIs and thread pools, understand what a thread actually is and how it runs.

### A. What Is a Thread?

A **thread** is the smallest unit of execution inside a process.
- A process can have many threads
- Threads in the same process share heap memory
- Each thread has its own stack (method calls + local variables)

```java
public class ThreadBasics {
    public static void main(String[] args) {
        Thread worker = new Thread(() -> {
            System.out.println("Running on: " + Thread.currentThread().getName());
        });

        worker.start(); // Starts a new thread
    }
}
```

### B. Thread Lifecycle (What Happens Internally)

A Java thread usually moves through these states:
- **NEW** -> created, not started
- **RUNNABLE** -> ready/running on CPU
- **BLOCKED / WAITING / TIMED_WAITING** -> paused for lock/signal/time
- **TERMINATED** -> work complete

```java
Thread t = new Thread(() -> doWork());
System.out.println(t.getState()); // NEW
t.start();                        // RUNNABLE
t.join();                         // Wait for TERMINATED
```

### C. How Threads Are Implemented (JVM + OS)

Java threads are typically mapped to **native OS threads** (1:1 model):
- You create `Thread` in Java
- JVM asks OS to create a native thread
- OS scheduler decides when it runs on CPU cores

So even though your code is Java, scheduling is done by the operating system.

### D. Why Raw Thread Creation Is Limited

Creating too many platform threads is expensive because each thread needs:
- Native OS resources
- Stack memory
- Scheduler overhead

That is why production systems prefer `ExecutorService` or virtual threads.

---

## 4. Core Concepts

### A. Process vs Thread

**Process:**
- Independent program execution
- Has its own memory space
- Heavy to create (~MB of memory)
- Example: Chrome browser window

**Thread:**
- Lightweight unit within a process
- Shares memory with other threads in same process
- Cheap to create (~KB of memory)
- Example: Multiple tabs in Chrome window

```
PROCESS (Java Application)
├── Memory Space (Heap)
│   ├── Shared Data
│   └── Objects
├── Thread 1 (Main)
│   └── Stack (Local Variables)
├── Thread 2 (Worker)
│   └── Stack (Local Variables)
└── Thread 3 (Worker)
    └── Stack (Local Variables)
```

### B. Synchronous vs Asynchronous

**Synchronous (Blocking):**
```java
String result = fetchData();  // WAIT here until complete
System.out.println(result);   // Only runs after fetch completes
```

**Asynchronous (Non-blocking):**
```java
CompletableFuture<String> future = fetchDataAsync();  // Start and continue
System.out.println("Doing other work...");            // Runs immediately
future.thenAccept(result -> System.out.println(result)); // Handle when ready
```

### C. Race Conditions

When multiple threads access shared data without proper coordination:

```java
// UNSAFE CODE
class Counter {
    private int count = 0;
    
    public void increment() {
        count++;  // NOT atomic! Actually 3 operations:
                  // 1. Read count
                  // 2. Add 1
                  // 3. Write back
    }
}

// With 2 threads calling increment():
Thread 1: Read (0) -> Add 1 -> Write (1)
Thread 2: Read (0) -> Add 1 -> Write (1)  // Should be 2, but both wrote 1!
```

### D. Deadlock

When threads wait for each other forever:

```
Thread A: 
  Lock Resource 1 ✓
  Wait for Resource 2... (Thread B has it)

Thread B:
  Lock Resource 2 ✓
  Wait for Resource 1... (Thread A has it)

Both threads stuck forever! 🔒💀
```

### E. Thread Safety

Code is **thread-safe** if it behaves correctly when accessed by multiple threads.

**Making Code Thread-Safe:**
1. **Immutability** - Data that never changes
2. **Synchronization** - Use locks to protect shared data
3. **Atomic operations** - Use atomic classes
4. **Thread confinement** - Each thread has its own data

---

## 5. Java Concurrency Building Blocks

### Level 1: Basic Threads (Low-level, avoid in production)

```java
// Creating a thread
Thread thread = new Thread(() -> {
    System.out.println("Hello from thread: " + Thread.currentThread().getName());
});
thread.start();  // Starts execution
thread.join();   // Wait for completion
```

**Problems:**
- Manual thread management
- No built-in error handling
- Hard to return results
- No resource pooling

### Level 2: ExecutorService (Recommended for thread pools)

```java
// Create a pool of 10 threads
ExecutorService executor = Executors.newFixedThreadPool(10);

// Submit tasks
for (int i = 0; i < 100; i++) {
    executor.submit(() -> {
        // Task logic here
    });
}

// Shutdown
executor.shutdown();
executor.awaitTermination(1, TimeUnit.MINUTES);
```

**Benefits:**
- Thread reuse (efficient)
- Automatic queue management
- Easy to control concurrency level
- Built-in error handling

### Level 3: CompletableFuture (Modern async programming)

```java
// Start async operation
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    return fetchWebPage("https://example.com");
});

// Chain operations
future
    .thenApply(html -> parseTitle(html))
    .thenAccept(title -> System.out.println(title))
    .exceptionally(ex -> {
        System.err.println("Error: " + ex.getMessage());
        return null;
    });
```

**Benefits:**
- Composable operations (chain actions)
- Better error handling
- Non-blocking
- Cleaner code for async workflows

### Level 4: Virtual Threads (Java 21+, cutting-edge)

```java
// Create millions of lightweight threads!
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 1_000_000; i++) {
        executor.submit(() -> {
            // Each request gets its own virtual thread
        });
    }
}
```

**Benefits:**
- Can create millions of threads (vs thousands of platform threads)
- Simpler than async/await
- Perfect for I/O-bound tasks
- JVM manages scheduling

---

## 6. Synchronization Mechanisms

### A. Synchronized Keyword

```java
class SafeCounter {
    private int count = 0;
    
    public synchronized void increment() {
        count++;  // Now thread-safe!
    }
    
    public synchronized int getCount() {
        return count;
    }
}
```

### B. ReentrantLock (More flexible)

```java
class LockCounter {
    private int count = 0;
    private final Lock lock = new ReentrantLock();
    
    public void increment() {
        lock.lock();
        try {
            count++;
        } finally {
            lock.unlock();  // Always unlock in finally!
        }
    }
}
```

### C. Atomic Classes (Lock-free)

```java
class AtomicCounter {
    private AtomicInteger count = new AtomicInteger(0);
    
    public void increment() {
        count.incrementAndGet();  // Thread-safe, no locks!
    }
}
```

### D. ConcurrentCollections

```java
// UNSAFE for concurrent access
List<String> list = new ArrayList<>();

// SAFE for concurrent access
List<String> list = new CopyOnWriteArrayList<>();
Map<String, String> map = new ConcurrentHashMap<>();
Queue<String> queue = new ConcurrentLinkedQueue<>();
```

---

## 7. Common Pitfalls

### Pitfall 1: Too Many Threads
```java
// BAD: Creating 10,000 threads
for (int i = 0; i < 10000; i++) {
    new Thread(() -> doWork()).start();
}

// GOOD: Use thread pool
ExecutorService executor = Executors.newFixedThreadPool(20);
for (int i = 0; i < 10000; i++) {
    executor.submit(() -> doWork());
}
```

### Pitfall 2: Forgetting to Handle Exceptions
```java
// BAD: Exception kills thread silently
executor.submit(() -> {
    riskyOperation();  // If this throws, you'll never know!
});

// GOOD: Handle exceptions
executor.submit(() -> {
    try {
        riskyOperation();
    } catch (Exception e) {
        logger.error("Task failed", e);
    }
});
```

### Pitfall 3: Not Shutting Down Executors
```java
// BAD: Executor keeps JVM alive
ExecutorService executor = Executors.newFixedThreadPool(10);
// ... use it ...
// Program never exits!

// GOOD: Always shutdown
executor.shutdown();
executor.awaitTermination(1, TimeUnit.MINUTES);
```

---

## 8. When to Use What

### Use **Single Thread** when:
- Task is very fast (< 100ms)
- Sequential dependencies
- Simplicity is priority

### Use **Thread Pool (ExecutorService)** when:
- Multiple independent I/O tasks
- Need to limit concurrency
- Want thread reuse
- **Best for web scraping!**

### Use **CompletableFuture** when:
- Complex async workflows
- Need to chain operations
- Composing multiple async calls
- Better error handling needed

### Use **ForkJoinPool** when:
- Recursive divide-and-conquer algorithms
- CPU-bound parallel processing
- Stream parallel operations

### Use **Virtual Threads** when:
- Massive I/O concurrency (thousands of connections)
- Simple blocking code style preferred
- Java 21+ available

---

## 9. Quick Decision Tree

```
Do you have I/O-bound tasks? (network, disk, database)
    YES →
        Are tasks independent?
            YES →
                How many tasks? 
                    < 100 → ExecutorService with fixed pool
                    100-10K → ExecutorService with cached pool
                    > 10K → Virtual Threads (Java 21+)
            NO → 
                Can you structure as pipeline?
                    YES → CompletableFuture chains
                    NO → Sequential execution
    NO → (CPU-bound)
        Use parallel streams or ForkJoinPool
        Thread count = CPU cores
```

---

## 10. Our Web Scraper Journey

We'll build the same web scraper three ways:

1. **Sequential** (baseline - no concurrency)
   - Simplest to understand
   - Slowest performance
   - Good for measuring improvement

2. **Thread Pool** (ExecutorService)
   - Classic Java concurrency
   - Predictable resource usage
   - Great balance of speed and complexity

3. **CompletableFuture** (modern async)
   - Most elegant code
   - Best error handling
   - Production-ready pattern

**Expected Results:**
- Sequential: ~30 seconds for 30 URLs
- Thread Pool: ~3 seconds (10x faster!)
- CompletableFuture: ~3 seconds (same speed, cleaner code)

---

## Key Takeaways

✅ Concurrency helps when you're **waiting** (I/O), not computing (CPU)
✅ Use **thread pools**, not raw threads
✅ Always **handle exceptions** in concurrent code
✅ **Immutable data** is automatically thread-safe
✅ **Less shared state** = fewer bugs
✅ Start simple, add concurrency when needed
✅ **Measure first** - don't assume what's slow

---

## Next Steps

Now that you understand the concepts, we'll:
1. Build sequential scraper (see the problem)
2. Add thread pool (see the solution)
3. Refactor to CompletableFuture (see the elegance)
4. Measure and compare (prove it works)

Ready to code? Let's build! 🚀
