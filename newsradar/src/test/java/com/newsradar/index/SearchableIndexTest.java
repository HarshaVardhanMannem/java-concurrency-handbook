package com.newsradar.index;

import com.newsradar.model.Article;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchableIndexTest {

    private static Article art(String id, String title, String body) {
        return new Article(id, "f", title, body, "https://x/" + id, null);
    }

    @Test
    void searchAndsTokensAcrossArticles() {
        SearchableIndex idx = new SearchableIndex();
        idx.add(art("a1", "Java threads", "java concurrency primer"));
        idx.add(art("a2", "Java IO",      "java io blocking"));
        idx.add(art("a3", "Go scheduler", "go concurrency runtime"));

        // "java" alone matches a1 + a2
        assertEquals(2, idx.search("java", 10).size());
        // "java concurrency" matches only a1 (a2 lacks "concurrency", a3 lacks "java")
        List<Article> hits = idx.search("java concurrency", 10);
        assertEquals(1, hits.size());
        assertEquals("a1", hits.get(0).id());
        // unknown token returns empty
        assertEquals(0, idx.search("nonexistent", 10).size());
    }

    @Test
    void swapReplacesEntireIndexAtomically() {
        SearchableIndex idx = new SearchableIndex();
        idx.add(art("a1", "Java", "java only"));
        assertEquals(1, idx.search("java", 10).size());
        assertEquals(0, idx.search("python", 10).size());

        // Build a fresh index containing different data
        InvertedIndex newInner = new ConcurrentIndex();
        Map<String, Article> newArticles = new HashMap<>();
        Article py = art("b1", "Python", "python only");
        newInner.index(py);
        newArticles.put(py.id(), py);

        idx.swap(newInner, newArticles);

        assertEquals(0, idx.search("java", 10).size());
        assertEquals(1, idx.search("python", 10).size());
    }

    @Test
    void searchesContinueDuringConcurrentSwaps() throws Exception {
        SearchableIndex idx = new SearchableIndex();
        // Seed with 'java' so initial searches succeed.
        idx.add(art("seed", "Java", "java seed"));

        int searchers = 8;
        int searchesPer = 200;
        ExecutorService exec = Executors.newFixedThreadPool(searchers + 1);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(searchers);
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < searchers; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < searchesPer; j++) {
                            // every search should return at least 1 ('seed' or whatever the swap installed)
                            List<Article> hits = idx.search("java", 5);
                            if (hits.isEmpty()) errors.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            // swapper thread continually swaps in fresh indexes, all containing "java"
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        InvertedIndex newInner = new ConcurrentIndex();
                        Map<String, Article> newArts = new HashMap<>();
                        Article a = art("swap" + j, "Java", "java swap " + j);
                        newInner.index(a);
                        newArts.put(a.id(), a);
                        idx.swap(newInner, newArts);
                        Thread.sleep(1);
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                }
            });

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "searchers did not finish");
            assertEquals(0, errors.get(), "no searcher should have observed a torn or empty index");
        } finally {
            exec.shutdown();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
