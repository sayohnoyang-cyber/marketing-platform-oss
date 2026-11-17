package com.sayohno.marketing.shortlink.web;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedirectControllerTest {
    @Test
    void localActivityKeepsTheVisitorsHostWithoutRewritingExternalDestinations() {
        String base = "http://127.0.0.1:9100";
        assertEquals("/a/123?c=wx", RedirectController.localActivityDestination(base + "/a/123?c=wx", base));
        assertEquals("https://example.com/a/123", RedirectController.localActivityDestination("https://example.com/a/123", base));
        assertEquals(base + "/help", RedirectController.localActivityDestination(base + "/help", base));
        assertEquals("https://short.example/a/123", RedirectController.localActivityDestination("https://short.example/a/123", "https://short.example"));
    }
}
