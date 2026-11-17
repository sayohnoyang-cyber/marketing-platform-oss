package com.sayohno.marketing.shortlink.redis;

import com.sayohno.marketing.common.redis.RedisKeyPolicy;

public final class ShortLinkRedisKeys {
    public static final String BLOOM_URI = RedisKeyPolicy.require("shortlink:bloom:uri");
    public static final String BLOOM_READY = RedisKeyPolicy.require("shortlink:bloom:uri:ready");

    private ShortLinkRedisKeys() {
    }

    public static String gotoCache(String fullShortUrl) {
        return RedisKeyPolicy.require("shortlink:goto:" + fullShortUrl);
    }

    public static String gotoMiss(String fullShortUrl) {
        return RedisKeyPolicy.require("shortlink:goto-miss:" + fullShortUrl);
    }
}
