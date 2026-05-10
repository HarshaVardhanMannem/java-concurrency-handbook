package com.newsradar.index;

import com.newsradar.model.Article;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexCorrectnessTest {

    private static final List<Article> SAMPLE = List.of(
            new Article("a1", "f", "Java threads", "java threads concurrency",   "https://x/1", null),
            new Article("a2", "f", "Async I/O",    "java io concurrency virtual", "https://x/2", null),
            new Article("a3", "f", "GC tuning",    "java memory gc",              "https://x/3", null));

    private static void assertCorrectSingleThreaded(InvertedIndex idx) {
        SAMPLE.forEach(idx::index);

        // distinct tokens across all articles: java, threads, concurrency, async, io, virtual, gc, tuning, memory
        // tokenizer drops len<2 -> "i", "o" -> dropped. "I/O" -> "i", "o" -> both dropped.
        // expected distinct: java, threads, concurrency, async, io, virtual, gc, tuning, memory = 9
        Set<String> javaArticles = idx.articleIdsFor("java");
        assertEquals(Set.of("a1", "a2", "a3"), javaArticles);
        assertEquals(Set.of("a1", "a2"), idx.articleIdsFor("concurrency"));
        assertEquals(Set.of("a3"), idx.articleIdsFor("memory"));
        assertEquals(Set.of(), idx.articleIdsFor("zzznotfound"));

        int expectedPostings = IndexFixture.expectedPostings(SAMPLE);
        int expectedTokens   = IndexFixture.expectedTokens(SAMPLE);
        assertEquals(expectedTokens, idx.tokenCount(), "token count");
        assertEquals(expectedPostings, idx.totalPostings(), "posting count");
    }

    @Test
    void unsafeIsCorrectSingleThreaded() {
        assertCorrectSingleThreaded(new UnsafeHashMapIndex());
    }

    @Test
    void synchronizedIsCorrectSingleThreaded() {
        assertCorrectSingleThreaded(new SynchronizedIndex());
    }

    @Test
    void concurrentIsCorrectSingleThreaded() {
        assertCorrectSingleThreaded(new ConcurrentIndex());
    }

    @Test
    void tokenizerLowercasesAndStripsShortTokens() {
        Set<String> t = Tokenizer.tokens("Hello, World! a I/O 42.");
        assertTrue(t.contains("hello"));
        assertTrue(t.contains("world"));
        assertTrue(t.contains("42"));
        assertTrue(!t.contains("a"), "single-char dropped");
        assertTrue(!t.contains("i"), "single-char dropped");
    }
}
