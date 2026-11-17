package com.sayohno.marketing.shortlink.bloom;

import com.sayohno.marketing.shortlink.redis.ShortLinkRedisKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomReadPolicyTest {

    @Test
    void missingBloomDoesNotRejectExistingLink() {
        BloomReadPolicy policy = new BloomReadPolicy();
        assertTrue(policy.continueLookup(false, false));
        assertFalse(policy.continueLookup(true, false));
        assertTrue(policy.continueLookup(true, true));
    }

    @Test
    void redissonBloomNameHasNoSecondPrefix() {
        assertEquals("shortlink:bloom:uri", ShortLinkRedisKeys.BLOOM_URI);
        assertEquals("shortlink:goto:nurl.ink/abc", ShortLinkRedisKeys.gotoCache("nurl.ink/abc"));
    }
}
