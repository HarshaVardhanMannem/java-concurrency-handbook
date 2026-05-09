package com.newsradar.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FeedConfigLoader {

    private FeedConfigLoader() {}

    public static List<FeedConfig> loadFromClasspath(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("classpath resource not found: " + resource);
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<FeedConfig> loadFromPath(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<FeedConfig> parse(InputStream in) {
        Map<String, Object> root = new Yaml().load(in);
        if (root == null || !root.containsKey("feeds")) {
            throw new IllegalStateException("YAML missing top-level 'feeds' key");
        }
        List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("feeds");
        List<FeedConfig> feeds = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            String id = (String) entry.get("id");
            String name = (String) entry.get("name");
            String url = (String) entry.get("url");
            feeds.add(new FeedConfig(id, name, URI.create(url)));
        }
        return List.copyOf(feeds);
    }
}
