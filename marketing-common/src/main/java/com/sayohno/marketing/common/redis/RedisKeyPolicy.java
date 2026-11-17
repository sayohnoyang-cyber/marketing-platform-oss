package com.sayohno.marketing.common.redis;

/**
 * 键名在写入前必须自带命名空间。
 * 不安装会再次拼接前缀的 Redis Key 序列化器，避免 Java、Lua 与 Redisson 各加一次前缀。
 */
public final class RedisKeyPolicy {
    private RedisKeyPolicy() {
    }

    public static String require(String key) {
        if (key == null || key.isBlank() || !key.contains(":")) {
            throw new IllegalArgumentException("Redis 键必须包含模块命名空间");
        }
        if (key.startsWith("identity:identity:")
                || key.startsWith("coupon:coupon:")
                || key.startsWith("shortlink:shortlink:")
                || key.startsWith("campaign:campaign:")) {
            throw new IllegalArgumentException("Redis 键出现重复命名空间: " + key);
        }
        return key;
    }
}
