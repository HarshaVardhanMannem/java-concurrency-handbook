import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ConcurrencyFlow {

    public static void main(String[] args) {
        int taskCount = parseOrDefault(args, 0, 30);
        int sleepMillis = parseOrDefault(args, 1, 120);
        int poolSize = parseOrDefault(args, 2, 10);

        ResultSummary sequential = runSequential(taskCount, sleepMillis);
        ResultSummary rawThreads = runRawThreads(taskCount, sleepMillis);
        ResultSummary threadPool = runThreadPool(taskCount, sleepMillis, poolSize);

        System.out.println("=== Step 3: Concurrency Flow Comparison ===");
        System.out.println("Task count: " + taskCount + ", sleep per task: " + sleepMillis + "ms, pool size: " + poolSize);
        System.out.println();
        System.out.printf("%-18s %-10s %-10s %-12s %-12s%n",
                "Strategy", "Tasks", "Done", "Elapsed(ms)", "Checksum");
        System.out.println("---------------------------------------------------------------");
        printSummary(sequential);
        printSummary(rawThreads);
        printSummary(threadPool);
    }

    private static ResultSummary runSequential(int taskCount, int sleepMillis) {
        long start = System.nanoTime();
        List<Integer> results = new ArrayList<>();
        for (int i = 1; i <= taskCount; i++) {
            results.add(simulatedTask(i, sleepMillis));
        }
        return buildSummary("Sequential", taskCount, results, start);
    }

    private static ResultSummary runRawThreads(int taskCount, int sleepMillis) {
        long start = System.nanoTime();
        List<Integer> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (int i = 1; i <= taskCount; i++) {
            int taskId = i;
            Thread t = new Thread(() -> results.add(simulatedTask(taskId, sleepMillis)));
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        return buildSummary("RawThreads", taskCount, results, start);
    }

    private static ResultSummary runThreadPool(int taskCount, int sleepMillis, int poolSize) {
        long start = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int i = 1; i <= taskCount; i++) {
                int taskId = i;
                futures.add(executor.submit(() -> simulatedTask(taskId, sleepMillis)));
            }

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    throw new RuntimeException(e.getCause());
                }
            }
            return buildSummary("ThreadPool(" + poolSize + ")", taskCount, results, start);
        } finally {
            executor.shutdown();
        }
    }

    private static int simulatedTask(int taskId, int sleepMillis) {
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        return taskId * 2;
    }

    private static ResultSummary buildSummary(String strategy, int taskCount, List<Integer> results, long startNano) {
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        int checksum = results.stream().mapToInt(Integer::intValue).sum();
        return new ResultSummary(strategy, taskCount, results.size(), elapsedMs, checksum);
    }

    private static void printSummary(ResultSummary summary) {
        System.out.printf("%-18s %-10d %-10d %-12d %-12d%n",
                summary.strategy, summary.taskCount, summary.completedCount, summary.elapsedMillis, summary.checksum);
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

    private record ResultSummary(String strategy, int taskCount, int completedCount, long elapsedMillis, int checksum) {}
}
