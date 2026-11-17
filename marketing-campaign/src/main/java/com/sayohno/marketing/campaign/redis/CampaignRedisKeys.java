package com.sayohno.marketing.campaign.redis;

import com.sayohno.marketing.common.redis.RedisKeyPolicy;

public final class CampaignRedisKeys {
    public static final String NAMESPACE = RedisKeyPolicy.require("campaign:namespace");

    private CampaignRedisKeys() {
    }
}
