package com.newsradar.index;

import com.newsradar.model.Article;

import java.util.LinkedHashSet;
import java.util.Set;

public final class Tokenizer {

    private Tokenizer() {}

    /** Lowercase, split on non-alphanumeric, drop tokens of length < 2, dedupe within article. */
    public static Set<String> tokens(String text) {
        if (text == null || text.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() >= 2) out.add(raw);
        }
        return out;
    }

    /** Tokens drawn from the article's title + body. */
    public static Set<String> tokensOf(Article a) {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(tokens(a.title()));
        out.addAll(tokens(a.body()));
        return out;
    }
}
