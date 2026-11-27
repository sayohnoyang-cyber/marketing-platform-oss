package com.sayohno.marketing.identity.session;

import com.sayohno.marketing.common.redis.RedisKeyPolicy;

public final class IdentityRedisKeys {
    private IdentityRedisKeys() {
    }

    public static String session(String token) {
        // 旧令牌使用 Redis 撤销版本，迁移时不能把它们当作数据库版本为零的新令牌。
        return RedisKeyPolicy.require("identity:session:v2:" + token);
    }
}
