package com.sayohno.marketing.shortlink.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicLinkAddressTest {
    @Test
    void forwardedHostIsIgnoredUntilTrustIsEnabled() {
        String ignored = PublicLinkAddress.base("http://127.0.0.1:9100", false, "evil.example", "https");
        assertEquals("http://127.0.0.1:9100", ignored);
        assertEquals("127.0.0.1:9100/s/abc123", PublicLinkAddress.full(ignored, "abc123"));

        String trusted = PublicLinkAddress.base("http://127.0.0.1:9100", true, "shop.example:443", "https");
        assertEquals("https://shop.example:443", trusted);
        assertEquals("shop.example:443/s/abc123", PublicLinkAddress.full(trusted, "abc123"));
    }
}
