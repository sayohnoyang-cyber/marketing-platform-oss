package com.sayohno.marketing.shortlink.service;

public final class QueryMerge {
    private QueryMerge() {
    }

    public static String merge(String origin, String query) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("落地页不能为空");
        }
        if (query == null || query.isBlank()) {
            return origin;
        }
        String cleaned = query.charAt(0) == '?' ? query.substring(1) : query;
        if (cleaned.isBlank()) {
            return origin;
        }
        return origin + (origin.contains("?") ? "&" : "?") + cleaned;
    }
}
