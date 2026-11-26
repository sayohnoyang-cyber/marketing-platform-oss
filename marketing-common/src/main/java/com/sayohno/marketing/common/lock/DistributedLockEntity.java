package com.sayohno.marketing.common.lock;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库分布式租约锁表实体，供未启用 Redis 或 Redis 不可用时兜底互斥。
 */
@Data
@TableName("infra_distributed_lock")
public class DistributedLockEntity {
    @TableId(type = IdType.INPUT)
    private String lockName;
    private String ownerId;
    private LocalDateTime leaseUntil;
    private LocalDateTime updateTime;
}
