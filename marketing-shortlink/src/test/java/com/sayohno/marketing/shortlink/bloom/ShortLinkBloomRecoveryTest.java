package com.sayohno.marketing.shortlink.bloom;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.lock.DistributedLock;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import com.sayohno.marketing.shortlink.redis.ShortLinkRedisKeys;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 布隆过滤器启动按需恢复与分布式锁协调单元测试。
 */
class ShortLinkBloomRecoveryTest {

    @BeforeAll
    static void initMybatisLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Link.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsFullTableScanWhenBloomIsAlreadyReady() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBloomFilter<String> bloom = mock(RBloomFilter.class);
        LinkMapper linkMapper = mock(LinkMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);

        when(redisson.<String>getBloomFilter(ShortLinkRedisKeys.BLOOM_URI)).thenReturn(bloom);
        when(bloom.isExists()).thenReturn(true);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(ShortLinkRedisKeys.BLOOM_READY)).thenReturn("ready");

        ShortLinkBloomRecovery recovery = new ShortLinkBloomRecovery(
                redisson, linkMapper, redis, new MarketingProperties());
        recovery.run(new DefaultApplicationArguments());

        verify(linkMapper, never()).selectList(any());
        verify(ops, never()).set(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acquiresDistributedLockWhenRecovering() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBloomFilter<String> bloom = mock(RBloomFilter.class);
        LinkMapper linkMapper = mock(LinkMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        ObjectProvider<DistributedLock> lockProvider = mock(ObjectProvider.class);

        when(redisson.<String>getBloomFilter(ShortLinkRedisKeys.BLOOM_URI)).thenReturn(bloom);
        when(bloom.isExists()).thenReturn(false);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(ShortLinkRedisKeys.BLOOM_READY)).thenReturn(null);
        when(linkMapper.selectList(any())).thenReturn(List.of());

        AtomicBoolean lockCalled = new AtomicBoolean(false);
        DistributedLock lock = new DistributedLock() {
            @Override
            public boolean tryExecute(String lockName, Duration ttl, Runnable action) {
                lockCalled.set(true);
                action.run();
                return true;
            }

            @Override
            public <T> java.util.Optional<T> trySupply(String lockName, Duration ttl, java.util.function.Supplier<T> action) {
                return java.util.Optional.ofNullable(action.get());
            }
        };
        when(lockProvider.getIfAvailable()).thenReturn(lock);

        ShortLinkBloomRecovery recovery = new ShortLinkBloomRecovery(
                redisson, linkMapper, redis, new MarketingProperties(), lockProvider);
        recovery.recoverWithLock();

        assertTrue(lockCalled.get());
        verify(ops).set(ShortLinkRedisKeys.BLOOM_READY, "ready");
    }
}
