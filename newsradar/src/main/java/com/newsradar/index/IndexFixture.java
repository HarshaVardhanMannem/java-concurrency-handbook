package com.newsradar.index;

import com.newsradar.model.Article;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Shared fixtures + multi-threaded stress driver used by both tests and the CLI demo. */
public final class IndexFixture {

    private IndexFixture() {}

    /**
     * Generate {@code count} articles, each with {@code tokensPerArticle} distinct tokens
     * drawn from a vocabulary of size {@code vocabSize}. Title is empty so only body
     * tokens contribute postings — that guarantees cross-article token sharing
     * (lots of contention on the same map keys).
     */
    public static List<Article> articles(int count, int tokensPerArticle, int vocabSize, long seed) {
        if (tokensPerArticle > vocabSize)
            throw new IllegalArgumentException("tokensPerArticle > vocabSize");
        Random r = new Random(seed);
        List<Article> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Set<String> tokens = new LinkedHashSet<>();
            while (tokens.size() < tokensPerArticle) {
                tokens.add("term" + r.nextInt(vocabSize));
            }
            String body = String.join(" ", tokens);
            list.add(new Article("a" + i, "f0", "", body, "https://x/" + i, null));
        }
        return list;
    }

    public static int expectedTokens(List<Article> articles) {
        Set<String> all = new HashSet<>();
        for (Article a : articles) all.addAll(Tokenizer.tokensOf(a));
        return all.size();
    }

    public static int expectedPostings(List<Article> articles) {
        int total = 0;
        for (Article a : articles) total += Tokenizer.tokensOf(a).size();
        return total;
    }

    public record StressResult(
            String impl,
            int tokens,
            int postings,
            int expectedTokens,
            int expectedPostings,
            long elapsedMillis,
            Throwable firstException) {

        public boolean isCorrect() {
            return firstException == null
                    && tokens == expectedTokens
                    && postings == expectedPostings;
        }

        public long opsPerSecond() {
            return elapsedMillis == 0 ? 0 : (long) postings * 1000L / elapsedMillis;
        }
    }

    /** Hammer {@code idx} from {@code threads} workers, partitioning {@code articles} evenly. */
    public static StressResult stress(InvertedIndex idx, List<Article> articles, int threads) throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier gate = new CyclicBarrier(threads);
            AtomicReference<Throwable> firstException = new AtomicReference<>();

            int chunk = articles.size() / threads;
            List<Future<?>> futures = new ArrayList<>();
            long start = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                int from = t * chunk;
                int to = (t == threads - 1) ? articles.size() : from + chunk;
                List<Article> mine = articles.subList(from, to);
                futures.add(exec.submit(() -> {
                    try {
                        gate.await();
                        for (Article a : mine) idx.index(a);
                    } catch (Throwable th) {
                        firstException.compareAndSet(null, th);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
            long elapsed = System.nanoTime() - start;

            return new StressResult(
                    idx.getClass().getSimpleName(),
                    idx.tokenCount(),
                    idx.totalPostings(),
                    expectedTokens(articles),
                    expectedPostings(articles),
                    TimeUnit.NANOSECONDS.toMillis(elapsed),
                    firstException.get());
        } finally {
            exec.shutdown();
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
