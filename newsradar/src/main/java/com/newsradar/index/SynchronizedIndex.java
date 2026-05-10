package com.newsradar.index;

import com.newsradar.model.Article;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Correct under contention, but every reader and writer serialises through a single
 * monitor. Throughput collapses to "one operation at a time" no matter how many cores
 * you throw at it.
 */
public final class SynchronizedIndex implements InvertedIndex {

    private final Map<String, Set<String>> postings = new HashMap<>();

    @Override
    public synchronized void index(Article article) {
        for (String token : Tokenizer.tokensOf(article)) {
            postings.computeIfAbsent(token, k -> new HashSet<>()).add(article.id());
        }
    }

    @Override
    public synchronized Set<String> articleIdsFor(String token) {
        Set<String> set = postings.get(token);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    @Override
    public synchronized int tokenCount() {
        return postings.size();
    }

    @Override
    public synchronized int totalPostings() {
        int total = 0;
        for (Set<String> set : postings.values()) total += set.size();
        return total;
    }
}
