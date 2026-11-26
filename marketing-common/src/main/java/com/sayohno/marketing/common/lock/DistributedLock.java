package com.sayohno.marketing.common.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 跨实例分布式互斥锁接口：用于保障定时调度任务、布隆恢复及全局协调在多副本部署下不重复并发执行。
 */
public interface DistributedLock {

    /**
     * 尝试抢占指定名称的租约锁并执行无返回值任务；若锁已被其他实例持有则直接跳过。
     */
    boolean tryExecute(String lockName, Duration ttl, Runnable action);

    /**
     * 尝试抢占指定名称的租约锁并执行带返回值任务；未抢占到锁时返回 Optional.empty()。
     */
    <T> Optional<T> trySupply(String lockName, Duration ttl, Supplier<T> action);
}
