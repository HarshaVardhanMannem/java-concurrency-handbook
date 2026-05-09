package com.newsradar.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FeedConfigLoaderTest {

    @Test
    void parsesMinimalFeedsYaml() {
        String yaml = """
                feeds:
                  - id: test-a
                    name: "Test A"
                    url: "https://example.com/a.xml"
                  - id: test-b
                    name: "Test B"
                    url: "https://example.com/b.xml"
                """;
        List<FeedConfig> feeds = FeedConfigLoader.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, feeds.size());
        assertEquals("test-a", feeds.get(0).id());
        assertEquals("Test B", feeds.get(1).name());
        assertEquals("https://example.com/a.xml", feeds.get(0).url().toString());
    }

    @Test
    void loadsBundledFeedsResource() {
        List<FeedConfig> feeds = FeedConfigLoader.loadFromClasspath("feeds.yaml");
        assertNotNull(feeds);
        assertEquals(false, feeds.isEmpty(), "bundled feeds.yaml should have entries");
    }
}
