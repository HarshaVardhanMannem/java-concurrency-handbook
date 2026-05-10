package com.newsradar.index;

import com.newsradar.model.Article;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Demo of what NOT to do. Plain HashMap + HashSet, no synchronization.
 *
 * Under concurrent index() calls this will:
 *   - lose postings (read-modify-write races on the inner Set)
 *   - sometimes throw (HashMap resize during concurrent put can NPE / corrupt buckets)
 *   - in pathological cases spin forever (pre-Java-8 HashMap had a famous CPU-pegging bug)
 *
 * The point of keeping this around is to *measure* how broken it is.
 */
public final class UnsafeHashMapIndex implements InvertedIndex {

    private final Map<String, Set<String>> postings = new HashMap<>();

    @Override
    public void index(Article article) {
        for (String token : Tokenizer.tokensOf(article)) {
            // The classic check-then-act race: another thread can sneak in between
            // get() and put(), so the second writer overwrites the first writer's set.
            Set<String> set = postings.get(token);
            if (set == null) {
                set = new HashSet<>();
                postings.put(token, set);
            }
            set.add(article.id());
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
        for (Set<String> set : postings.values()) {
            total += set.size();
        }
        return total;
    }
}
