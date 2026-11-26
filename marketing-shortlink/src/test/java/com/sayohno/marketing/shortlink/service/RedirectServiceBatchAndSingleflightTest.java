package com.sayohno.marketing.shortlink.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.outbox.OutboxStore;
import com.sayohno.marketing.shortlink.bloom.BloomReadPolicy;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 短链防击穿 Singleflight 与访问统计批量落库/失败重入队列单元测试。
 */
class RedirectServiceBatchAndSingleflightTest {

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Link.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleflightDeduplicatesConcurrentCacheMisses() throws Exception {
        LinkMapper linkMapper = mock(LinkMapper.class);
        MarketingProperties properties = new MarketingProperties();
        properties.getShortlink().setAsyncVisitRecord(false);
        ObjectProvider<RedissonClient> redissonProvider = mock(ObjectProvider.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(ops);

        Map<String, String> cache = new HashMap<>();
        when(ops.get(anyString())).thenAnswer(inv -> cache.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            cache.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any());

        Link link = new Link();
        link.setId(501L);
        link.setShortUri("hot1");
        link.setFullShortUrl("127.0.0.1:9100/s/hot1");
        link.setOriginUrl("https://example.com/hot");
        link.setEnableStatus(0);
        link.setDelTime(0L);
        link.setUpdateTime(LocalDateTime.now());
        AtomicInteger dbCalls = new AtomicInteger();
        when(linkMapper.selectOne(any())).thenAnswer(inv -> {
            dbCalls.incrementAndGet();
            Thread.sleep(20);
            return link;
        });

        RedirectService service = new RedirectService(
                linkMapper, properties, new BloomReadPolicy(),
                redissonProvider, redisProvider, mock(OutboxStore.class),
                new ObjectMapper(), noopTxManager());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    assertEquals("https://example.com/hot", service.resolve("hot1", "v1", false).getOriginUrl());
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        assertEquals(1, dbCalls.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedBatchFlushRequeuesVisitsForNextRetry() {
        LinkMapper linkMapper = mock(LinkMapper.class);
        MarketingProperties properties = new MarketingProperties();
        properties.getShortlink().setAsyncVisitRecord(true);
        ObjectProvider<RedissonClient> redissonProvider = mock(ObjectProvider.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        OutboxStore outboxStore = mock(OutboxStore.class);

        Link link = new Link();
        link.setId(502L);
        link.setShortUri("q1");
        link.setFullShortUrl("127.0.0.1:9100/s/q1");
        link.setOriginUrl("https://example.com/q");
        link.setEnableStatus(0);
        link.setDelTime(0L);
        link.setUpdateTime(LocalDateTime.now());
        when(linkMapper.selectOne(any())).thenReturn(link);

        RedirectService service = new RedirectService(
                linkMapper, properties, new BloomReadPolicy(),
                redissonProvider, redisProvider, outboxStore,
                new ObjectMapper(), noopTxManager());
        try {
            service.resolve("q1", "v1", true, "wx");
            service.resolve("q1", "v2", true, "dy");

            // 第一次刷盘模拟 DB 异常：返回 0 且事件应被放回缓冲队列
            doThrow(new RuntimeException("db glitch")).when(outboxStore).appendBatch(anyList());
            assertEquals(0, service.flushPendingVisits());

            // 恢复后再次刷盘：应完整补写出 2 条事件
            doAnswer(inv -> 2).when(outboxStore).appendBatch(anyList());
            assertEquals(2, service.flushPendingVisits());
            verify(outboxStore, times(2)).appendBatch(anyList());
        } finally {
            service.shutdownVisitQueue();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shutdownDrainsMoreThanOneFlushCycle() {
        MarketingProperties properties = new MarketingProperties();
        // 手动启用入队，避免后台定时器在断言前提前刷盘。
        properties.getShortlink().setAsyncVisitRecord(false);
        LinkMapper links = mock(LinkMapper.class);
        Link link = new Link();
        link.setId(503L);
        link.setShortUri("drain");
        link.setOriginUrl("https://example.com/drain");
        link.setDelTime(0L);
        link.setEnableStatus(0);
        when(links.selectOne(any())).thenReturn(link);
        AtomicInteger written = new AtomicInteger();
        OutboxStore store = mock(OutboxStore.class);
        when(store.appendBatch(anyList())).thenAnswer(call -> {
            int count = call.<java.util.List<?>>getArgument(0).size();
            written.addAndGet(count);
            return count;
        });
        RedirectService service = new RedirectService(links, properties, new BloomReadPolicy(),
                mock(ObjectProvider.class), mock(ObjectProvider.class), store, new ObjectMapper(), noopTxManager());
        properties.getShortlink().setAsyncVisitRecord(true);
        for (int i = 0; i < 2201; i++) {
            service.resolve("drain", "visitor-" + i, true);
        }
        service.shutdownVisitQueue();
        assertEquals(2201, written.get());
    }

    private static PlatformTransactionManager noopTxManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
