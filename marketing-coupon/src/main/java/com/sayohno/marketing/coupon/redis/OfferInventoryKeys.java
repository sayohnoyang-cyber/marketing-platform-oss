package com.sayohno.marketing.coupon.redis;

import com.sayohno.marketing.common.redis.RedisKeyPolicy;

/**
 * 优惠券与投放库存 Redis 键名约定：
 * 所有键均以 {planId} 作为 Redis Cluster Hash Tag，保证同一方案下的库存、个人限领计数与预扣票据
 * 落在同一个哈希槽（Hash Slot）内，防止多键 Lua 脚本在 Redis Cluster 模式下触发 CROSSSLOT 错误。
 */
public final class OfferInventoryKeys {
    private OfferInventoryKeys() {
    }

    public static String planInventory(long planId) {
        return RedisKeyPolicy.require("offer:plan:{" + planId + "}:inventory");
    }

    public static String planQuota(long userId, long planId) {
        return RedisKeyPolicy.require("offer:plan:{" + planId + "}:quota:" + userId);
    }

    public static String ticket(long planId, long claimTicketId) {
        return RedisKeyPolicy.require("offer:plan:{" + planId + "}:ticket:" + claimTicketId);
    }

    public static String inventory(long planId, long inventoryId) {
        return RedisKeyPolicy.require("offer:plan:{" + planId + "}:inventory:" + inventoryId);
    }

    public static String inventoryQuota(long planId, long userId, long inventoryId) {
        return RedisKeyPolicy.require("offer:plan:{" + planId + "}:inventory:" + inventoryId + ":quota:" + userId);
    }

    public static String inventoryTicket(long planId, long inventoryId, long claimTicketId) {
        return inventoryTicketByKey(planId, inventoryId, Long.toString(claimTicketId));
    }

    public static String inventoryTicketByKey(long planId, long inventoryId, String claimKey) {
        return RedisKeyPolicy.require("offer:plan:{" + planId + "}:inventory:" + inventoryId + ":ticket:" + claimKey);
    }
}
