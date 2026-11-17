package com.sayohno.marketing.coupon.redis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfferInventoryKeysTest {

    @Test
    void javaKeysMatchLuaContractWithoutExtraPrefix() throws Exception {
        assertEquals("offer:plan:18:inventory", OfferInventoryKeys.planInventory(18));
        assertEquals("offer:plan:18:quota:7", OfferInventoryKeys.planQuota(7, 18));
        String lua = new String(OfferInventoryKeysTest.class.getResourceAsStream("/lua/reserve_offer_inventory.lua")
                .readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(lua.contains("KEYS[1]"));
        assertTrue(lua.contains("KEYS[2]"));
        assertTrue(lua.contains("EXPIRE"));
        assertTrue(lua.contains("reserved"));
        assertTrue(lua.contains("sold_out"));
        assertTrue(lua.contains("quota_exceeded"));
        assertFalse(lua.contains("coupon:"));
    }
}
