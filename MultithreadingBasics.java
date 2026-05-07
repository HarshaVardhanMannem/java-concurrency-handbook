import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MultithreadingBasics {

    public static void main(String[] args) {
        int workerCount = parseOrDefault(args, 0, 3);
        runStartJoinDemo(workerCount);
        System.out.println();
        runRaceConditionDemo(100_000);
    }

    private static void runStartJoinDemo(int workerCount) {
        System.out.println("=== Step 2A: Basic Multithreading (start + join) ===");
        List<Thread> workers = new ArrayList<>();

        for (int i = 1; i <= workerCount; i++) {
            int workerId = i;
            Thread worker = new Thread(() -> {
                try {
                    Thread.sleep(150L * workerId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                System.out.println("Worker " + workerId + " finished on " + Thread.currentThread().getName());
            }, "worker-" + workerId);
            workers.add(worker);
            worker.start();
        }

        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        System.out.println("All workers completed.");
    }

    private static void runRaceConditionDemo(int incrementsPerThread) {
        System.out.println("=== Step 2B: Race Condition Demo ===");

        UnsafeCounter unsafe = new UnsafeCounter();
        SafeCounter safe = new SafeCounter();

        Thread t1 = new Thread(() -> incrementMany(unsafe, incrementsPerThread));
        Thread t2 = new Thread(() -> incrementMany(unsafe, incrementsPerThread));
        Thread t3 = new Thread(() -> incrementMany(safe, incrementsPerThread));
        Thread t4 = new Thread(() -> incrementMany(safe, incrementsPerThread));

        t1.start();
        t2.start();
        t3.start();
        t4.start();

        try {
            t1.join();
            t2.join();
            t3.join();
            t4.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        int expected = incrementsPerThread * 2;
        System.out.println("Expected: " + expected);
        System.out.println("Unsafe counter: " + unsafe.value);
        System.out.println("Safe counter:   " + safe.value.get());
    }

    private static void incrementMany(Counter counter, int times) {
        for (int i = 0; i < times; i++) {
            counter.increment();
        }
    }

    private static int parseOrDefault(String[] args, int index, int fallback) {
        if (index >= args.length) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private interface Counter {
        void increment();
    }

    private static class UnsafeCounter implements Counter {
        int value = 0;

        @Override
        public void increment() {
            value++;
        }
    }

    private static class SafeCounter implements Counter {
        AtomicInteger value = new AtomicInteger(0);

        @Override
        public void increment() {
            value.incrementAndGet();
        }
    }
}
