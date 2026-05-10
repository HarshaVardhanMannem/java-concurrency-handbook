package com.newsradar.index;

import com.newsradar.model.Article;

import java.util.Set;

/**
 * Maps token -> set of article ids that contain that token.
 * Three implementations exist to show the cost of getting concurrency wrong, right-but-slow,
 * and right-and-scaling.
 */
public interface InvertedIndex {

    /** Tokenize the article and add (token, articleId) postings. */
    void index(Article article);

    /** Article ids that contain the given token (lowercased). */
    Set<String> articleIdsFor(String token);

    /** Number of distinct tokens currently in the index. */
    int tokenCount();

    /** Sum over all tokens of postings count. */
    int totalPostings();
}
