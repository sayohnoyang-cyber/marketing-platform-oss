package com.sayohno.marketing.coupon.redis;

import com.sayohno.marketing.common.redis.RedisKeyPolicy;

public final class OfferInventoryKeys {
    private OfferInventoryKeys() {
    }

    public static String planInventory(long planId) {
        return RedisKeyPolicy.require("offer:plan:" + planId + ":inventory");
    }

    public static String planQuota(long userId, long planId) {
        return RedisKeyPolicy.require("offer:plan:" + planId + ":quota:" + userId);
    }

    public static String ticket(long claimTicketId) {
        return RedisKeyPolicy.require("offer:ticket:" + claimTicketId);
    }

    public static String inventory(long inventoryId) {
        return RedisKeyPolicy.require("offer:inventory:" + inventoryId);
    }

    public static String inventoryQuota(long userId, long inventoryId) {
        return RedisKeyPolicy.require("offer:inventory:" + inventoryId + ":quota:" + userId);
    }

    public static String inventoryTicket(long inventoryId, long claimTicketId) {
        return inventoryTicketByKey(inventoryId, Long.toString(claimTicketId));
    }

    public static String inventoryTicketByKey(long inventoryId, String claimKey) {
        return RedisKeyPolicy.require("offer:ticket:inv:" + inventoryId + ":" + claimKey);
    }
}
