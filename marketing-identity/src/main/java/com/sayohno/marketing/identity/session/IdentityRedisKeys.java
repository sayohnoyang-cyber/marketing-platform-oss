package com.sayohno.marketing.identity.session;

import com.sayohno.marketing.common.redis.RedisKeyPolicy;

public final class IdentityRedisKeys {
    private IdentityRedisKeys() {
    }

    public static String session(String token) {
        return RedisKeyPolicy.require("identity:session:" + token);
    }
}
