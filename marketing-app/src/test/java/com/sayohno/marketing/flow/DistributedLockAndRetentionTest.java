package com.sayohno.marketing.flow;

import com.sayohno.marketing.campaign.service.DataRetentionService;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.lock.DistributedLock;
import com.sayohno.marketing.common.lock.DistributedLockMapper;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxEventMapper;
import com.sayohno.marketing.common.outbox.OutboxStatus;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import com.sayohno.marketing.identity.dao.mapper.IamUserMapper;
import com.sayohno.marketing.identity.service.IdentityContextLoader;
import com.sayohno.marketing.identity.session.UserSession;
import com.sayohno.marketing.support.MemoryStockConfiguration;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分布式锁互斥、Caffeine 身份上下文缓存失效与分批数据清理集成验证。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class DistributedLockAndRetentionTest {
    @Autowired
    private DistributedLock distributedLock;
    @Autowired
    private DistributedLockMapper lockMapper;
    @Autowired
    private DataRetentionService retentionService;
    @Autowired
    private OutboxEventMapper outboxMapper;
    @Autowired
    private IdentityContextLoader contextLoader;
    @Autowired
    private IamUserMapper userMapper;
    @Autowired
    private MarketingProperties properties;

    @Test
    void databaseDistributedLockPreventsConcurrentExecutionAndAllowsReacquireAfterRelease() {
        String lockName = "test-lock-" + UUID.randomUUID();
        AtomicInteger executed = new AtomicInteger();

        boolean first = distributedLock.tryExecute(lockName, Duration.ofSeconds(30), () -> {
            executed.incrementAndGet();
            // 锁被持有期间，第二次抢占必须返回 false
            boolean nested = distributedLock.tryExecute(lockName, Duration.ofSeconds(30), () -> {
                executed.incrementAndGet();
            });
            assertFalse(nested);
        });
        assertTrue(first);
        assertEquals(1, executed.get());

        // 释放后可立即再次抢占成功
        boolean second = distributedLock.tryExecute(lockName, Duration.ofSeconds(30), () -> {
            executed.incrementAndGet();
        });
        assertTrue(second);
        assertEquals(2, executed.get());
        lockMapper.deleteById(lockName);
    }

    @Test
    void retentionPurgesOverBatchSizeAcrossMultipleBatches() {
        LocalDateTime oldTime = LocalDateTime.now().minusDays(20);
        for (int i = 0; i < 1050; i++) {
            OutboxEvent event = new OutboxEvent();
            event.setEventId("batch-purge-" + UUID.randomUUID());
            event.setEventType("campaign.conversion");
            event.setAggregateId("1");
            event.setPayload("{}");
            event.setStatus(OutboxStatus.SENT);
            event.setRetryCount(0);
            event.setNextRetryAt(oldTime);
            event.setCreateTime(oldTime);
            event.setUpdateTime(oldTime);
            outboxMapper.insert(event);
        }

        Map<String, Integer> summary = retentionService.purgeExpired(LocalDateTime.now());
        assertTrue(summary.get("outboxDeleted") >= 1050);
    }

    @Test
    void identityContextCacheEvictsOnUserOrBroadcastInvalidation() {
        Duration original = properties.getSession().getContextCacheTtl();
        properties.getSession().setContextCacheTtl(Duration.ofSeconds(30));
        try {
            IamUser user = new IamUser();
            user.setUsername("cache_" + UUID.randomUUID().toString().substring(0, 8));
            user.setPasswordHash("hash");
            user.setStatus(IamStatus.ENABLED);
            userMapper.insert(user);

            UserSession session = new UserSession();
            session.setUserId(user.getId());

            assertEquals(user.getUsername(), contextLoader.load(session).getUsername());

            // 数据库停用账号，未失效缓存前仍命中 Caffeine
            user.setStatus(IamStatus.DISABLED);
            userMapper.updateById(user);
            assertEquals(user.getUsername(), contextLoader.load(session).getUsername());

            // 收到广播或主动失效后回源查库，立刻感知账号已停用
            contextLoader.handleInvalidation("u:" + user.getId());
            assertNull(contextLoader.load(session));
        } finally {
            properties.getSession().setContextCacheTtl(original);
        }
    }
}
