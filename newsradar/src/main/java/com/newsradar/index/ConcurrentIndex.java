package com.newsradar.index;

import com.newsradar.model.Article;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Correct *and* scales:
 *  - {@link ConcurrentHashMap#computeIfAbsent} guarantees at most one Set is created per token
 *    (atomic check-and-create), so we lose no postings to the get/put race.
 *  - {@link ConcurrentHashMap#newKeySet()} gives us a thread-safe Set with fine-grained locking
 *    that matches the outer map's striping — concurrent writers to *different* tokens never
 *    contend, and concurrent writers to the *same* token use the same fine-grained lock as the
 *    outer map's bin.
 *
 * Reads (articleIdsFor / tokenCount) are non-blocking on CHM and stream over a weakly-consistent
 * iterator — you may miss writes that happened during iteration, but you never crash and never
 * see corrupt state. That's the right tradeoff for a search index.
 */
public final class ConcurrentIndex implements InvertedIndex {

    private final ConcurrentMap<String, Set<String>> postings = new ConcurrentHashMap<>();

    @Override
    public void index(Article article) {
        for (String token : Tokenizer.tokensOf(article)) {
            postings.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet())
                    .add(article.id());
        }
    }

    @Override
    public Set<String> articleIdsFor(String token) {
        Set<String> set = postings.get(token);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    @Override
    public int tokenCount() {
        return postings.size();
    }

    @Override
    public int totalPostings() {
        int total = 0;
        for (Set<String> set : postings.values()) total += set.size();
        return total;
    }
}
