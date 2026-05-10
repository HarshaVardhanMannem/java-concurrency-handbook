package com.newsradar.index;

import com.newsradar.index.IndexFixture.StressResult;
import com.newsradar.model.Article;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexStressTest {

    private static final int ARTICLES = 4000;
    private static final int TOKENS_PER_ARTICLE = 8;
    private static final int VOCAB_SIZE = 200;
    private static final int THREADS = 8;
    private static final long SEED = 42L;

    private static List<Article> testArticles() {
        return IndexFixture.articles(ARTICLES, TOKENS_PER_ARTICLE, VOCAB_SIZE, SEED);
    }

    @Test
    void unsafeIndexIsBrokenUnderContention() throws Exception {
        // Unsafe HashMap *usually* loses postings or throws. Run up to 5 attempts;
        // any single failed attempt proves the demo. Across realistic hardware
        // 4000 articles * 8 tokens / 8 threads triggers the bug ~every time.
        boolean observedBroken = false;
        StressResult worst = null;
        for (int attempt = 0; attempt < 5 && !observedBroken; attempt++) {
            List<Article> articles = testArticles();
            StressResult r = IndexFixture.stress(new UnsafeHashMapIndex(), articles, THREADS);
            if (worst == null || (worst.isCorrect() && !r.isCorrect())) worst = r;
            if (!r.isCorrect()) observedBroken = true;
        }
        assertTrue(observedBroken,
                "Unsafe index unexpectedly correct across 5 attempts. " +
                "(Possible on a single-core sandbox; rerun on multi-core.) Last result: " + worst);
    }

    @Test
    void synchronizedIndexIsCorrectUnderContention() throws Exception {
        List<Article> articles = testArticles();
        StressResult r = IndexFixture.stress(new SynchronizedIndex(), articles, THREADS);
        assertNull(r.firstException(), "synchronized impl should not throw");
        assertEquals(r.expectedTokens(), r.tokens(), "all expected tokens present");
        assertEquals(r.expectedPostings(), r.postings(), "no postings lost");
    }

    @Test
    void concurrentIndexIsCorrectUnderContention() throws Exception {
        List<Article> articles = testArticles();
        StressResult r = IndexFixture.stress(new ConcurrentIndex(), articles, THREADS);
        assertNull(r.firstException(), "concurrent impl should not throw");
        assertEquals(r.expectedTokens(), r.tokens(), "all expected tokens present");
        assertEquals(r.expectedPostings(), r.postings(), "no postings lost");
    }
}
