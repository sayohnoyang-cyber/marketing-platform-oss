package com.sayohno.marketing.common.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisKeyPolicyTest {

    @Test
    void acceptsSingleNamespace() {
        assertEquals("identity:session:abc", RedisKeyPolicy.require("identity:session:abc"));
    }

    @Test
    void rejectsDoubledNamespace() {
        assertThrows(IllegalArgumentException.class, () -> RedisKeyPolicy.require("coupon:coupon:template:1"));
    }
}
