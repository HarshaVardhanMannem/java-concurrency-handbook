package com.newsradar.index;

import com.newsradar.model.Article;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Search-facing wrapper around an {@link InvertedIndex} plus an {@code articleId -> Article}
 * lookup map. Individual writes go straight through to the underlying CHM-based primitives
 * (no lock needed). The {@link ReadWriteLock} only guards the *whole-index swap* — phase 6's
 * scheduled refresh will build a fresh index then swap atomically without breaking readers.
 *
 *   read  side : search() takes the read lock briefly, snapshots the two refs, releases
 *   write side : swap()   takes the write lock, replaces refs, updates lastRefresh
 *
 * Many concurrent searches run in parallel; only the (rare) refresh blocks them.
 */
public final class SearchableIndex {

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile InvertedIndex index;
    private volatile Map<String, Article> articles;
    private volatile Instant lastRefresh;

    public SearchableIndex() {
        this(new ConcurrentIndex(), new ConcurrentHashMap<>());
    }

    public SearchableIndex(InvertedIndex index, Map<String, Article> articles) {
        this.index = Objects.requireNonNull(index);
        this.articles = Objects.requireNonNull(articles);
        this.lastRefresh = Instant.now();
    }

    /** Tokenize and index. Safe to call from many threads — relies on {@code index}'s thread-safety. */
    public void add(Article article) {
        // Snapshot under read lock so we don't index into a half-swapped pair.
        lock.readLock().lock();
        try {
            index.index(article);
            articles.put(article.id(), article);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Article> search(String query, int limit) {
        Set<String> tokens = Tokenizer.tokens(query);
        if (tokens.isEmpty() || limit <= 0) return List.of();

        lock.readLock().lock();
        try {
            // Capture both refs atomically under the lock.
            InvertedIndex idx = this.index;
            Map<String, Article> arts = this.articles;

            // AND semantics: result = intersection of postings for each query token.
            Set<String> matchingIds = null;
            for (String token : tokens) {
                Set<String> ids = idx.articleIdsFor(token);
                if (matchingIds == null) {
                    matchingIds = new HashSet<>(ids);
                } else {
                    matchingIds.retainAll(ids);
                }
                if (matchingIds.isEmpty()) return List.of();
            }

            List<Article> results = new ArrayList<>(Math.min(limit, matchingIds.size()));
            for (String id : matchingIds) {
                if (results.size() >= limit) break;
                Article a = arts.get(id);
                if (a != null) results.add(a);
            }
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Atomically replace the underlying index and article map. Phase 6 calls this on refresh. */
    public void swap(InvertedIndex newIndex, Map<String, Article> newArticles) {
        Objects.requireNonNull(newIndex);
        Objects.requireNonNull(newArticles);
        lock.writeLock().lock();
        try {
            this.index = newIndex;
            this.articles = newArticles;
            this.lastRefresh = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Snapshot snapshot() {
        lock.readLock().lock();
        try {
            return new Snapshot(articles.size(), index.tokenCount(), index.totalPostings(), lastRefresh);
        } finally {
            lock.readLock().unlock();
        }
    }

    public record Snapshot(int articles, int tokens, int postings, Instant lastRefresh) {}
}
