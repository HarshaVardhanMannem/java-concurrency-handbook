package com.newsradar.parse;

import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;

import java.util.List;

public interface FeedParser {
    List<Article> parse(RawFeed raw);
}
