package com.sayohno.marketing.shortlink.bloom;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import com.sayohno.marketing.shortlink.redis.ShortLinkRedisKeys;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "marketing.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShortLinkBloomRecovery implements ApplicationRunner {
    private final RedissonClient redissonClient;
    private final LinkMapper linkMapper;
    private final StringRedisTemplate redis;
    private final MarketingProperties properties;

    public ShortLinkBloomRecovery(RedissonClient redissonClient,
                                  LinkMapper linkMapper,
                                  StringRedisTemplate redis,
                                  MarketingProperties properties) {
        this.redissonClient = redissonClient;
        this.linkMapper = linkMapper;
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        RBloomFilter<String> bloom = redissonClient.getBloomFilter(ShortLinkRedisKeys.BLOOM_URI);
        if (!bloom.isExists()) {
            MarketingProperties.Shortlink shortlink = properties.getShortlink();
            bloom.tryInit(shortlink.getBloomExpectedInsertions(), shortlink.getBloomFalseProbability());
        }
        List<Link> links = linkMapper.selectList(Wrappers.<Link>lambdaQuery().select(Link::getFullShortUrl));
        for (Link link : links) {
            if (link.getFullShortUrl() != null && !link.getFullShortUrl().isBlank()) {
                bloom.add(link.getFullShortUrl());
            }
        }
        redis.opsForValue().set(ShortLinkRedisKeys.BLOOM_READY, "ready");
    }
}
