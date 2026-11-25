package com.sayohno.marketing.coupon.redis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验优惠券与投放库存 Redis 键名、Hash Tag 一致性及 Lua 脚本契约。
 */
class OfferInventoryKeysTest {

    @Test
    void javaKeysMatchLuaContractAndSharePlanHashTag() throws Exception {
        String planInv = OfferInventoryKeys.planInventory(18);
        String planQuota = OfferInventoryKeys.planQuota(7, 18);
        String planTicket = OfferInventoryKeys.ticket(18, 99);
        assertEquals("offer:plan:{18}:inventory", planInv);
        assertEquals("offer:plan:{18}:quota:7", planQuota);
        assertEquals("offer:plan:{18}:ticket:99", planTicket);

        String inv = OfferInventoryKeys.inventory(18, 301);
        String invQuota = OfferInventoryKeys.inventoryQuota(18, 7, 301);
        String invTicket = OfferInventoryKeys.inventoryTicketByKey(18, 301, "req-1");
        assertEquals("offer:plan:{18}:inventory:301", inv);
        assertEquals("offer:plan:{18}:inventory:301:quota:7", invQuota);
        assertEquals("offer:plan:{18}:inventory:301:ticket:req-1", invTicket);

        // 同一方案的多键 Lua 脚本（直领 3 键 / 投放 4 键）必须共享完全一致的 Redis Cluster Hash Tag {18}，避免 CROSSSLOT
        Set<String> directTags = List.of(planInv, planQuota, planTicket).stream()
                .map(OfferInventoryKeysTest::extractHashTag)
                .collect(Collectors.toSet());
        assertEquals(Set.of("18"), directTags);

        Set<String> allocationTags = List.of(inv, invQuota, planQuota, invTicket).stream()
                .map(OfferInventoryKeysTest::extractHashTag)
                .collect(Collectors.toSet());
        assertEquals(Set.of("18"), allocationTags);

        String lua = new String(OfferInventoryKeysTest.class.getResourceAsStream("/lua/reserve_offer_inventory.lua")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(lua.contains("KEYS[1]"));
        assertTrue(lua.contains("KEYS[2]"));
        assertTrue(lua.contains("EXPIRE"));
        assertTrue(lua.contains("reserved"));
        assertTrue(lua.contains("sold_out"));
        assertTrue(lua.contains("quota_exceeded"));
        assertFalse(lua.contains("coupon:"));

        String increaseLua = new String(OfferInventoryKeysTest.class.getResourceAsStream("/lua/increase_offer_stock.lua")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(increaseLua.contains("EXISTS"));
        assertTrue(increaseLua.contains("INCRBY"));
    }

    private static String extractHashTag(String key) {
        int start = key.indexOf('{');
        int end = key.indexOf('}', start + 1);
        assertTrue(start >= 0 && end > start + 1, "Redis key 缺少合法 Hash Tag: " + key);
        return key.substring(start + 1, end);
    }
}
