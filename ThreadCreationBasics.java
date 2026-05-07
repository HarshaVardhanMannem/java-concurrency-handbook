import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ThreadCreationBasics {

    public static void main(String[] args) {
        int threadCount = parseOrDefault(args, 0, 500);
        int workPerThread = parseOrDefault(args, 1, 100_000);
        ThreadRunStats stats = measureThreadCreationAndRun(threadCount, workPerThread);

        System.out.println("=== Step 1: Thread Creation + Runnable Execution ===");
        System.out.println("Threads created: " + stats.threadCount);
        System.out.println("Total creation time: " + stats.creationMicros + " microseconds");
        System.out.println("Average create time/thread: " + stats.avgCreationMicrosPerThread + " microseconds");
        System.out.println("Total run (start+join) time: " + stats.runMicros + " microseconds");
        System.out.println("Average run time/thread: " + stats.avgRunMicrosPerThread + " microseconds");
        System.out.println("Checksum (to prove runnable executed): " + stats.checksum);
    }

    private static ThreadRunStats measureThreadCreationAndRun(int threadCount, int workPerThread) {
        List<Thread> created = new ArrayList<>(threadCount);
        List<Long> outputs = Collections.synchronizedList(new ArrayList<>(threadCount));

        long start = System.nanoTime();
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i + 1;
            created.add(new Thread(() -> outputs.add(doSmallWork(threadIndex, workPerThread))));
        }
        long creationMicros = (System.nanoTime() - start) / 1_000;
        long avgCreationMicros = threadCount == 0 ? 0 : creationMicros / threadCount;

        long runStart = System.nanoTime();
        for (Thread thread : created) {
            thread.start();
        }
        for (Thread thread : created) {
            try {
                thread.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }
        long runMicros = (System.nanoTime() - runStart) / 1_000;
        long avgRunMicros = threadCount == 0 ? 0 : runMicros / threadCount;

        long checksum = outputs.stream().mapToLong(Long::longValue).sum();
        return new ThreadRunStats(threadCount, creationMicros, avgCreationMicros, runMicros, avgRunMicros, checksum);
    }

    private static long doSmallWork(int threadIndex, int workPerThread) {
        long value = 0;
        for (int i = 0; i < workPerThread; i++) {
            value += (long) threadIndex * i;
        }
        return value;
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

    private record ThreadRunStats(
            int threadCount,
            long creationMicros,
            long avgCreationMicrosPerThread,
            long runMicros,
            long avgRunMicrosPerThread,
            long checksum
    ) {}
}
