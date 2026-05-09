package com.newsradar.parse;

import com.newsradar.model.Article;
import com.newsradar.model.RawFeed;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RssParser {

    private static final DateTimeFormatter RFC_822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    public List<Article> parse(RawFeed raw) {
        String xml = new String(raw.body(), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());

        List<Article> articles = new ArrayList<>();

        // RSS 2.0: <item> nested under <channel>
        for (Element item : doc.select("item")) {
            String title = textOf(item, "title");
            String link = textOf(item, "link");
            String desc = textOf(item, "description");
            String guid = firstNonBlank(textOf(item, "guid"), link, title);
            Instant published = parseDate(textOf(item, "pubDate"));
            articles.add(new Article(
                    raw.feedId() + ":" + guid,
                    raw.feedId(),
                    title,
                    desc,
                    link,
                    published));
        }

        // Atom: <entry>
        for (Element entry : doc.select("entry")) {
            String title = textOf(entry, "title");
            String link = entry.select("link").stream()
                    .map(e -> e.attr("href"))
                    .filter(s -> !s.isBlank())
                    .findFirst()
                    .orElse("");
            String summary = firstNonBlank(textOf(entry, "summary"), textOf(entry, "content"));
            String guid = firstNonBlank(textOf(entry, "id"), link, title);
            Instant published = parseDate(firstNonBlank(textOf(entry, "published"), textOf(entry, "updated")));
            articles.add(new Article(
                    raw.feedId() + ":" + guid,
                    raw.feedId(),
                    title,
                    summary,
                    link,
                    published));
        }

        return articles;
    }

    private static String textOf(Element parent, String tag) {
        Element child = parent.selectFirst("> " + tag);
        return child == null ? "" : child.text().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static Instant parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ZonedDateTime.parse(value, RFC_822).toInstant();
        } catch (Exception ignored) {}
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {}
        return null;
    }
}
