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
        // 按主键游标分页仅恢复未删除的有效短链，防止一次性全表拉取造成启动内存峰值或 OOM。
        long lastId = 0L;
        while (true) {
            List<Link> batch = linkMapper.selectList(Wrappers.<Link>lambdaQuery()
                    .select(Link::getId, Link::getFullShortUrl)
                    .eq(Link::getDelTime, 0L)
                    .gt(Link::getId, lastId)
                    .orderByAsc(Link::getId)
                    .last("LIMIT 1000"));
            if (batch.isEmpty()) {
                break;
            }
            for (Link link : batch) {
                if (link.getFullShortUrl() != null && !link.getFullShortUrl().isBlank()) {
                    bloom.add(link.getFullShortUrl());
                }
                if (link.getId() != null && link.getId() > lastId) {
                    lastId = link.getId();
                }
            }
            if (batch.size() < 1000) {
                break;
            }
        }
        redis.opsForValue().set(ShortLinkRedisKeys.BLOOM_READY, "ready");
    }
}
