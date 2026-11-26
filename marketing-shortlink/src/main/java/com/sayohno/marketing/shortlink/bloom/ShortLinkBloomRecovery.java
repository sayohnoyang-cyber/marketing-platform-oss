package com.sayohno.marketing.shortlink.bloom;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.lock.DistributedLock;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import com.sayohno.marketing.shortlink.redis.ShortLinkRedisKeys;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 短链布隆过滤器启动恢复器：
 * 1. 若 Redis 中已存在布隆过滤器且 BLOOM_READY 标记就绪，直接跳过全表扫描，避免滚动发布或扩容时重复耗时重建；
 * 2. 仅在冷启动/缓存丢失需要恢复时，通过后台异步守护线程 + 分布式锁互斥执行，不阻塞应用启动与健康探针。
 */
@Component
@ConditionalOnProperty(prefix = "marketing.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShortLinkBloomRecovery implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ShortLinkBloomRecovery.class);
    private static final String RECOVERY_LOCK = "shortlink:bloom:recovery";
    private static final Duration RECOVERY_LOCK_TTL = Duration.ofMinutes(10);

    private final RedissonClient redissonClient;
    private final LinkMapper linkMapper;
    private final StringRedisTemplate redis;
    private final MarketingProperties properties;
    private final ObjectProvider<DistributedLock> distributedLock;

    public ShortLinkBloomRecovery(RedissonClient redissonClient,
                                  LinkMapper linkMapper,
                                  StringRedisTemplate redis,
                                  MarketingProperties properties) {
        this(redissonClient, linkMapper, redis, properties, null);
    }

    @Autowired
    public ShortLinkBloomRecovery(RedissonClient redissonClient,
                                  LinkMapper linkMapper,
                                  StringRedisTemplate redis,
                                  MarketingProperties properties,
                                  ObjectProvider<DistributedLock> distributedLock) {
        this.redissonClient = redissonClient;
        this.linkMapper = linkMapper;
        this.redis = redis;
        this.properties = properties;
        this.distributedLock = distributedLock;
    }

    @Override
    public void run(ApplicationArguments args) {
        RBloomFilter<String> bloom = redissonClient.getBloomFilter(ShortLinkRedisKeys.BLOOM_URI);
        boolean alreadyReady = "ready".equals(redis.opsForValue().get(ShortLinkRedisKeys.BLOOM_READY)) && bloom.isExists();
        if (alreadyReady) {
            log.info("短链布隆过滤器已处于就绪状态，跳过启动全表重建");
            return;
        }
        // 异步执行冷启动恢复，避免全表加载阻塞 Spring Boot 启动和探针；未就绪期间由 BloomReadPolicy 回源查库兜底。
        Thread worker = new Thread(this::recoverWithLock, "shortlink-bloom-recovery");
        worker.setDaemon(true);
        worker.start();
    }

    void recoverWithLock() {
        DistributedLock lock = distributedLock == null ? null : distributedLock.getIfAvailable();
        if (lock != null) {
            lock.tryExecute(RECOVERY_LOCK, RECOVERY_LOCK_TTL, this::doRecover);
        } else {
            doRecover();
        }
    }

    private void doRecover() {
        RBloomFilter<String> bloom = redissonClient.getBloomFilter(ShortLinkRedisKeys.BLOOM_URI);
        if ("ready".equals(redis.opsForValue().get(ShortLinkRedisKeys.BLOOM_READY)) && bloom.isExists()) {
            return;
        }
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
        log.info("短链布隆过滤器后台恢复完成");
    }
}
