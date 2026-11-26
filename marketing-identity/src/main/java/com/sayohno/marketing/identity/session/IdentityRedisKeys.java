package com.sayohno.marketing.identity.session;

import com.sayohno.marketing.common.redis.RedisKeyPolicy;

public final class IdentityRedisKeys {
    private IdentityRedisKeys() {
    }

    public static String session(String token) {
        return RedisKeyPolicy.require("identity:session:" + token);
    }

    /**
     * 用户级会话版本号键，用于改密等敏感操作后全端吊销历史会话。
     */
    public static String userSessionVersion(long userId) {
        return RedisKeyPolicy.require("identity:user:session-version:" + userId);
    }
}
