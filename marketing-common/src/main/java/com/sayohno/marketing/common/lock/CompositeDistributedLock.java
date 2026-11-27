package com.sayohno.marketing.common.lock;

import com.sayohno.marketing.common.redis.RedisKeyPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 组合分布式锁实现：
 * 1. 启用且连接可用时优先走 Redis（SET NX PX + Lua 比对持有者 Token 原子释放）；
 * 2. 未启用 Redis 或 Redis 异常时自动降级为数据库 infra_distributed_lock 租约锁，确保单库多实例下定时任务仍互斥安全。
 */
@Component
public class CompositeDistributedLock implements DistributedLock {
    private static final Logger log = LoggerFactory.getLogger(CompositeDistributedLock.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class
    );

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final DistributedLockMapper lockMapper;

    public CompositeDistributedLock(ObjectProvider<StringRedisTemplate> redisProvider, DistributedLockMapper lockMapper) {
        this.redisProvider = redisProvider;
        this.lockMapper = lockMapper;
    }

    @Override
    public boolean tryExecute(String lockName, Duration ttl, Runnable action) {
        return trySupply(lockName, ttl, () -> {
            action.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    @Override
    public <T> Optional<T> trySupply(String lockName, Duration ttl, Supplier<T> action) {
        if (lockName == null || lockName.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("分布式锁名称与租约时长必须合法");
        }
        String ownerId = UUID.randomUUID().toString().replace("-", "");
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis != null) {
            String key = RedisKeyPolicy.require("infra:lock:" + lockName);
            Boolean acquired;
            try {
                acquired = redis.opsForValue().setIfAbsent(key, ownerId, ttl);
            } catch (RuntimeException exception) {
                log.warn("Redis 分布式锁不可用，降级为数据库租约锁。lockName={}", lockName, exception);
                return tryDatabaseLock(lockName, ownerId, ttl, action);
            }
            if (!Boolean.TRUE.equals(acquired)) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(action.get());
            } finally {
                releaseRedis(redis, key, ownerId);
            }
        }
        return tryDatabaseLock(lockName, ownerId, ttl, action);
    }

    private <T> Optional<T> tryDatabaseLock(String lockName, String ownerId, Duration ttl, Supplier<T> action) {
        if (!acquireDatabaseLock(lockName, ownerId, ttl)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(action.get());
        } finally {
            releaseDatabaseLock(lockName, ownerId);
        }
    }

    private void releaseRedis(StringRedisTemplate redis, String key, String ownerId) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key), ownerId);
        } catch (RuntimeException exception) {
            log.warn("释放 Redis 分布式锁失败，等待租约自动过期。key={}", key, exception);
        }
    }

    private boolean acquireDatabaseLock(String lockName, String ownerId, Duration ttl) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plus(ttl);
        if (lockMapper.tryAcquire(lockName, ownerId, leaseUntil, now) == 1) {
            return true;
        }
        try {
            return lockMapper.insertLock(lockName, ownerId, leaseUntil, now) == 1;
        } catch (DataIntegrityViolationException exception) {
            return lockMapper.tryAcquire(lockName, ownerId, leaseUntil, LocalDateTime.now()) == 1;
        }
    }

    private void releaseDatabaseLock(String lockName, String ownerId) {
        try {
            lockMapper.releaseOwned(lockName, ownerId, LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("释放数据库分布式锁失败，等待租约自动过期。lockName={}", lockName, exception);
        }
    }
}
