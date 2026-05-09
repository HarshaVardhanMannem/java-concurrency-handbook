package com.newsradar.parse;

import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssParserTest {

    @Test
    void parsesRss2Items() {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>Demo</title>
                    <item>
                      <title>First</title>
                      <link>https://example.com/1</link>
                      <description>Hello world</description>
                      <pubDate>Mon, 01 Jan 2024 12:00:00 GMT</pubDate>
                      <guid>urn:1</guid>
                    </item>
                    <item>
                      <title>Second</title>
                      <link>https://example.com/2</link>
                      <description>Another</description>
                    </item>
                  </channel>
                </rss>
                """;
        List<Article> articles = new RssParser().parse(
                new RawFeed("demo", rss.getBytes(StandardCharsets.UTF_8), "application/rss+xml"));

        assertEquals(2, articles.size());
        assertEquals("First", articles.get(0).title());
        assertEquals("https://example.com/1", articles.get(0).url());
        assertNotNull(articles.get(0).publishedAt());
        assertTrue(articles.get(0).id().startsWith("demo:"));
        assertEquals("demo", articles.get(0).feedId());
    }

    @Test
    void parsesAtomEntries() {
        String atom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Atom Demo</title>
                  <entry>
                    <title>Atom one</title>
                    <link href="https://example.com/a1"/>
                    <id>tag:example.com,2024:a1</id>
                    <summary>Summary one</summary>
                    <published>2024-01-01T12:00:00Z</published>
                  </entry>
                </feed>
                """;
        List<Article> articles = new RssParser().parse(
                new RawFeed("atom", atom.getBytes(StandardCharsets.UTF_8), "application/atom+xml"));

        assertEquals(1, articles.size());
        assertEquals("Atom one", articles.get(0).title());
        assertEquals("https://example.com/a1", articles.get(0).url());
        assertNotNull(articles.get(0).publishedAt());
    }
}
