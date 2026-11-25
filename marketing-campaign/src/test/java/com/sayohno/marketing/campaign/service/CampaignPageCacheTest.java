package com.sayohno.marketing.campaign.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 活动页缓存 Singleflight 防击穿单元测试。
 */
class CampaignPageCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void concurrentCacheMissTriggersLoaderOnlyOnce() throws Exception {
        ObjectProvider<StringRedisTemplate> emptyRedis = mock(ObjectProvider.class);
        when(emptyRedis.getIfAvailable()).thenReturn(null);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        CampaignPageCache cache = new CampaignPageCache(emptyRedis, mapper);

        AtomicInteger loaderCalls = new AtomicInteger();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        LocalDateTime now = LocalDateTime.now();
        CampaignPageCache.PageSnapshot expected = new CampaignPageCache.PageSnapshot(
                100L, 1L, "压测活动", "优惠券推广活动", 1, 1,
                now.minusHours(1), now, now.plusDays(1),
                null, 0, null, null, null, null, null, null, Map.of(), Map.of());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    CampaignPageCache.PageSnapshot actual = cache.getOrLoad(100L, () -> {
                        loaderCalls.incrementAndGet();
                        return expected;
                    });
                    assertEquals(100L, actual.activityId());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        assertEquals(1, loaderCalls.get());
    }
}
