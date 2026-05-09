package com.newsradar.model;

public record RawFeed(String feedId, byte[] body, String contentType) {
}
